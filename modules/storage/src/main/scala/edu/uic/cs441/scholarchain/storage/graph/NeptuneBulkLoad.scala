package edu.uic.cs441.scholarchain.storage.graph

import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.{Validation, Validator}
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.storage.kv.{RejectRecord, RunLedgerTable}
import edu.uic.cs441.scholarchain.storage.objectstore.{ObjectKey, ObjectStore, S3Prefix}
import edu.uic.cs441.scholarchain.storage.{Retry, RetryPolicy, StorageError, WriteOutcome}
import java.nio.charset.StandardCharsets

/** Inputs of one Neptune bulk load, mapped from core records. */
final case class BulkLoadInput(
    runId: String,
    policyVersion: String,
    researchers: Seq[CanonicalResearcher],
    works: Seq[WorkRecord],
    authorships: Seq[AuthorshipRecord],
    coauthorEdges: Seq[CoauthorEdge]
)

final case class BulkLoadFile(key: ObjectKey, content: String, rows: Int):
  def bytes: Array[Byte]  = content.getBytes(StandardCharsets.UTF_8)
  def contentHash: String = ObjectStore.contentHash(bytes)

final case class BulkLoadReject(scope: String, key: String, reason: String, provenance: Provenance)

/** A generated, deterministic bulk load: CSV files, the evidence file, and the manifest. */
final case class BulkLoadPlan(
    runId: String,
    csvFiles: List[BulkLoadFile],
    evidence: BulkLoadFile,
    manifest: BulkLoadFile,
    rejects: List[BulkLoadReject]
)

/**
 * Generates Neptune bulk-loader input in the openCypher CSV format for Researcher and Work nodes
 * and AUTHORED and COAUTHORED_WITH relationships.
 *
 * Layout (all under `graph/neptune-bulk-load/v1/run=<runId>/`):
 * {{{
 * csv/nodes-researcher.csv  csv/nodes-work.csv  csv/rel-authored.csv  csv/rel-coauthored-with.csv
 * evidence/coauthored-with.jsonl   one JSON line per COAUTHORED_WITH edge: edgeId,
 *                                  supportingWorkIds, provenance
 * load-manifest.json               written last; its presence means the load set is complete
 * }}}
 * Only `csv/` is the bulk loader's source prefix, so evidence and manifest are never loaded as
 * graph data. Every row carries `sourceDataset`, `sourceRecordId`, and `pipelineRunId`.
 * COAUTHORED_WITH rows carry `evidencePointer` = `<evidence key>#<edgeId>`.
 *
 * Determinism: rows are sorted by ID and every value is derived from the records, so the same input
 * yields byte-identical files and replays resolve as `AlreadyPresent`.
 *
 * Rejected (and returned for the RunLedger): records failing the core validator, duplicate IDs with
 * different content, and relationships whose endpoints are not nodes of this load.
 */
object NeptuneBulkLoad:

  val ArtifactName: String = "neptune-bulk-load"

  private def q(s: String): String =
    "\"" + s.replace("\r", " ").replace("\n", " ").replace("\"", "\"\"") + "\""

  private def opt(i: Option[Int]): String = i.fold("")(_.toString)

  private def prov(p: Provenance): List[String] =
    List(q(p.sourceDataset.wire), q(p.sourceRecordId), q(p.pipelineRunId))

  private val ProvHeader = "sourceDataset:String,sourceRecordId:String,pipelineRunId:String"

  val ResearcherHeader: String = s":ID,:LABEL,displayName:String,orcid:String,$ProvHeader"
  val WorkHeader: String       = s":ID,:LABEL,title:String,year:Int,$ProvHeader"
  val AuthoredHeader: String   =
    s":ID,:START_ID,:END_ID,:TYPE,authorPosition:Int,mentionId:String,year:Int,$ProvHeader"
  val CoauthoredHeader: String =
    ":ID,:START_ID,:END_ID,:TYPE,jointPublicationCount:Int,firstYear:Int,lastYear:Int," +
      "windowStart:Int,windowEnd:Int,policyVersion:String,confidence:Double," +
      s"evidencePointer:String,$ProvHeader"

  /** Validates and de-duplicates by ID: identical copies collapse, conflicting copies reject. */
  private def clean[A: Validator](
      scope: String,
      items: Seq[A],
      id: A => String,
      provenance: A => Provenance
  ): (List[A], List[BulkLoadReject]) =
    val (valid, invalid) = items.partition(a => Validation.isValid(a))
    val invalidRejects   = invalid.toList.map { a =>
      val reasons = Validation.validate(a).left.toOption.toList.flatten.map(_.message)
      BulkLoadReject(scope, id(a), reasons.mkString("; "), provenance(a))
    }
    val grouped           = valid.groupBy(id).toList.sortBy(_._1)
    val (ok, conflicting) = grouped.partition((_, as) => as.distinct.size == 1)
    val conflictRejects   = conflicting.flatMap((k, as) =>
      as.distinct.map(a => BulkLoadReject(scope, k, "conflicting duplicate ID", provenance(a)))
    )
    (ok.map((_, as) => as.head), invalidRejects ++ conflictRejects)

  def plan(input: BulkLoadInput): Either[StorageError, BulkLoadPlan] =
    for
      runDir <- ObjectKey.runDirectory(S3Prefix.Graph, ArtifactName, 1, input.runId)
      csvDir <- ObjectKey.child(runDir, "csv")
      evDir  <- ObjectKey.child(runDir, "evidence")
      rKey   <- ObjectKey.child(csvDir, "nodes-researcher.csv")
      wKey   <- ObjectKey.child(csvDir, "nodes-work.csv")
      aKey   <- ObjectKey.child(csvDir, "rel-authored.csv")
      cKey   <- ObjectKey.child(csvDir, "rel-coauthored-with.csv")
      evKey  <- ObjectKey.child(evDir, "coauthored-with.jsonl")
      mKey   <- ObjectKey.child(runDir, "load-manifest.json")
      _      <-
        if input.policyVersion.trim.nonEmpty then Right(())
        else Left(StorageError.InvalidInput("policyVersion", "must not be blank"))
    yield
      val (researchers, rRej) =
        clean("graph:Researcher", input.researchers, _.researcherId, _.provenance)
      val (works, wRej) = clean("graph:Work", input.works, _.workId, _.provenance)
      val researcherIds = researchers.map(_.researcherId).toSet
      val workIds       = works.map(_.workId).toSet

      def authoredId(a: AuthorshipRecord) =
        StableIds.graphEdgeId(
          GraphEdgeType.Authored,
          a.researcherId,
          a.workId,
          input.policyVersion,
          None
        )
      val (authoredAll, aRej) = clean("graph:AUTHORED", input.authorships, authoredId, _.provenance)
      val (authored, aEndpointRej) = authoredAll.partition(a =>
        researcherIds(a.researcherId) && workIds(a.workId)
      )
      val (coauthorAll, cRej) =
        clean("graph:COAUTHORED_WITH", input.coauthorEdges, _.edgeId, _.provenance)
      val (coauthor, cEndpointRej) = coauthorAll.partition(e =>
        researcherIds(e.researcherLow) && researcherIds(e.researcherHigh)
      )
      val endpointRejects =
        aEndpointRej.map(a =>
          BulkLoadReject("graph:AUTHORED", authoredId(a), "endpoint not in load", a.provenance)
        ) ++ cEndpointRej.map(e =>
          BulkLoadReject("graph:COAUTHORED_WITH", e.edgeId, "endpoint not in load", e.provenance)
        )

      def csv(header: String, rows: List[String]) = (header :: rows).mkString("", "\n", "\n")

      val rRows = researchers.map(r =>
        (List(q(r.researcherId), q("Researcher"), q(r.displayName), q(r.orcid.getOrElse(""))) ++
          prov(r.provenance)).mkString(",")
      )
      val wRows = works.map(w =>
        (List(q(w.workId), q("Work"), q(w.title), opt(w.year)) ++ prov(w.provenance)).mkString(",")
      )
      val aRows = authored.sortBy(authoredId).map(a =>
        (List(
          q(authoredId(a)),
          q(a.researcherId),
          q(a.workId),
          q("AUTHORED"),
          a.authorPosition.toString,
          q(a.mentionId),
          opt(a.year)
        ) ++ prov(a.provenance)).mkString(",")
      )
      val cRows = coauthor.map(e =>
        (List(
          q(e.edgeId),
          q(e.researcherLow),
          q(e.researcherHigh),
          q("COAUTHORED_WITH"),
          e.coauthoredWorkCount.toString,
          e.firstYear.toString,
          e.lastYear.toString,
          e.window.startYear.toString,
          e.window.endYear.toString,
          q(e.policyVersion),
          e.confidence.toString,
          q(s"${evKey.value}#${e.edgeId}")
        ) ++ prov(e.provenance)).mkString(",")
      )
      val evLines = coauthor.map(e =>
        ujson
          .Obj(
            "edgeId"            -> e.edgeId,
            "supportingWorkIds" -> ujson.Arr.from(e.supportingWorkIds.map(ujson.Str(_))),
            "provenance"        -> ujson.Obj(
              "sourceDataset"  -> e.provenance.sourceDataset.wire,
              "sourceRecordId" -> e.provenance.sourceRecordId,
              "pipelineRunId"  -> e.provenance.pipelineRunId
            )
          )
          .render()
      )
      val csvFiles = List(
        BulkLoadFile(rKey, csv(ResearcherHeader, rRows), rRows.size),
        BulkLoadFile(wKey, csv(WorkHeader, wRows), wRows.size),
        BulkLoadFile(aKey, csv(AuthoredHeader, aRows), aRows.size),
        BulkLoadFile(cKey, csv(CoauthoredHeader, cRows), cRows.size)
      )
      val evidence = BulkLoadFile(evKey, evLines.map(_ + "\n").mkString, evLines.size)
      val rejects  = rRej ++ wRej ++ aRej ++ cRej ++ endpointRejects
      def fileJson(f: BulkLoadFile) =
        ujson.Obj("key" -> f.key.value, "rows" -> f.rows, "contentHash" -> f.contentHash)
      val manifestJson = ujson
        .Obj(
          "schemaVersion" -> 1,
          "runId"         -> input.runId,
          "format"        -> "opencypher",
          "policyVersion" -> input.policyVersion,
          "loadSource"    -> (csvDir.value + "/"),
          "files"         -> ujson.Arr.from(csvFiles.map(fileJson)),
          "evidence"      -> fileJson(evidence),
          "rejectCount"   -> rejects.size
        )
        .render(indent = 2) + "\n"
      BulkLoadPlan(input.runId, csvFiles, evidence, BulkLoadFile(mKey, manifestJson, 0), rejects)

/** Writes a plan: rejects to the RunLedger, then CSV and evidence files, then the manifest. */
final class NeptuneBulkLoadWriter(
    store: ObjectStore,
    ledger: RunLedgerTable,
    policy: RetryPolicy = RetryPolicy.Default
):
  def write(plan: BulkLoadPlan): Either[StorageError, List[(ObjectKey, WriteOutcome)]] =
    val files = plan.csvFiles :+ plan.evidence :+ plan.manifest
    for
      _ <- plan.rejects.foldLeft[Either[StorageError, Unit]](Right(())) { (acc, r) =>
        acc.flatMap(_ =>
          ledger
            .recordReject(RejectRecord(plan.runId, r.scope, r.key, r.reason, r.provenance))
            .map(_ => ())
        )
      }
      outcomes <- files.foldLeft[Either[StorageError, List[(ObjectKey, WriteOutcome)]]](
        Right(Nil)
      ) { (acc, f) =>
        acc.flatMap { done =>
          val contentType = if f.key.value.endsWith(".csv") then "text/csv" else "application/json"
          Retry.run(policy)(store.putIfAbsent(f.key, f.bytes, contentType)).map(o =>
            done :+ (f.key -> o)
          )
        }
      }
    yield outcomes
