package edu.uic.cs441.scholarchain.storage.kv

import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import edu.uic.cs441.scholarchain.storage.{Retry, RetryPolicy, StorageError, WriteOutcome}
import upickle.default.{read, write, ReadWriter}

/**
 * Key schema (all tables: string `pk`, string `sk`):
 *
 * {{{
 * RunLedger         pk RUN#<runId>                 sk MANIFEST | STEP#<step>
 *                                                     | REJECT#<scope>#<key>#<digest>
 * EntityLinkCache   pk MENTION#<mentionId>         sk POLICY#<policyVersion>
 * QuerySession      pk SESSION#<sessionId>         sk TURN#<6-digit turn>          (+ ttl)
 * EvidenceBundle    pk BUNDLE#<bundleId>           sk V1
 * ArtifactRegistry  pk ARTIFACT#<type>#<name>      sk RUN#<runId>
 * }}}
 *
 * Key components never contain `#`. A reject key that is not a safe component is replaced by its
 * `sha256:` content hash so a malformed producer key cannot forge another item's key.
 *
 * Reject `<digest>` is [[RejectRecord.digest]]: the first 16 hex characters of SHA-256 over the
 * length-prefixed (scope, key, reason, sourceDataset, sourceRecordId, pipelineRunId). Several
 * rejects under one entity ID (different reason or provenance) therefore get distinct items, while
 * a replay of the same reject maps to the same item and resolves as `AlreadyPresent`, in any input
 * order.
 */
object Keys:
  private val Safe = """^[A-Za-z0-9][A-Za-z0-9._:/=-]{0,255}$""".r

  def component(field: String, s: String): Either[StorageError, String] =
    if Safe.matches(s) then Right(s)
    else Left(StorageError.InvalidInput(field, s"'$s' is not a safe key component"))

  def safeOrHash(s: String): String = if Safe.matches(s) then s else CanonicalHash.contentHash(s)

  def run(runId: String): Either[StorageError, String] =
    if IdFormat.isValid(IdKind.Run, runId) then Right(s"RUN#$runId")
    else Left(StorageError.InvalidInput("runId", s"'$runId' is not a run:v1 ID"))

  val Manifest: String   = "MANIFEST"
  def step(step: String) = component("step", step).map(s => s"STEP#$s")
  def reject(scope: String, key: String, digest: String) =
    for
      s <- component("scope", scope)
      d <- component("digest", digest)
    yield s"REJECT#$s#${safeOrHash(key)}#$d"
  def mention(id: String) = s"MENTION#$id"
  def policy(v: String)   = component("policyVersion", v).map(p => s"POLICY#$p")
  def session(id: String) = component("sessionId", id).map(s => s"SESSION#$s")
  def turn(n: Int): Either[StorageError, String] =
    if n >= 0 && n <= 999999 then Right(f"TURN#$n%06d")
    else Left(StorageError.InvalidInput("turn", "must be in 0..999999"))
  def bundle(id: String)                = s"BUNDLE#$id"
  val BundleSk: String                  = "V1"
  def artifact(t: String, name: String) =
    for
      a <- component("artifactType", t)
      n <- component("name", name)
    yield s"ARTIFACT#$a#$n"

private def invalid(errors: List[ValidationError]): StorageError =
  StorageError.InvalidInput("record", errors.map(_.message).mkString("; "))

private def runProvenance(runId: String): Provenance =
  Provenance(SourceType.ScholarChainPipeline, runId, runId)

/** A record a loader refused, kept in the RunLedger so rejects are never silently dropped. */
final case class RejectRecord(
    runId: String,
    scope: String,
    key: String,
    reason: String,
    provenance: Provenance
) derives ReadWriter

object RejectRecord:
  val DigestHexLength: Int = 16

  /** Deterministic per-reject digest used in the sort key (see [[Keys]]). */
  def digest(r: RejectRecord): String =
    CanonicalHash
      .hashFields(Seq(
        r.scope,
        r.key,
        r.reason,
        r.provenance.sourceDataset.wire,
        r.provenance.sourceRecordId,
        r.provenance.pipelineRunId
      ))
      .take(DigestHexLength)

/** One step status in a run. */
final case class RunStep(runId: String, step: String, detail: String) derives ReadWriter

/**
 * Stored payload of a STEP item. The status is part of the hashed payload, so replaying the same
 * step with a different status is a `ConditionalConflict`, never a silent `AlreadyPresent`.
 */
final case class RunStepPayload(step: RunStep, status: String) derives ReadWriter

final class RunLedgerTable(store: KeyValueStore, policy: RetryPolicy = RetryPolicy.Default):

  def putManifest(manifest: RunManifest): Either[StorageError, WriteOutcome] =
    for
      _ <-
        if StableIds.runId(manifest) == manifest.runId then Right(())
        else Left(StorageError.InvalidInput("runId", "does not match its formula"))
      pk <- Keys.run(manifest.runId)
      item = TableItem.create(
        pk,
        Keys.Manifest,
        Wire.encode(manifest),
        runProvenance(manifest.runId),
        status = Some("started")
      )
      out <- Retry.run(policy)(store.putIfAbsent(Table.RunLedger, item))
    yield out

  def putStep(step: RunStep, status: String): Either[StorageError, WriteOutcome] =
    for
      pk <- Keys.run(step.runId)
      sk <- Keys.step(step.step)
      item = TableItem.create(
        pk,
        sk,
        write(RunStepPayload(step, status)),
        runProvenance(step.runId),
        Some(status)
      )
      out <- Retry.run(policy)(store.putIfAbsent(Table.RunLedger, item))
    yield out

  def recordReject(reject: RejectRecord): Either[StorageError, WriteOutcome] =
    for
      pk <- Keys.run(reject.runId)
      sk <- Keys.reject(reject.scope, reject.key, RejectRecord.digest(reject))
      item = TableItem.create(pk, sk, write(reject), reject.provenance)
      out <- Retry.run(policy)(store.putIfAbsent(Table.RunLedger, item))
    yield out

  /** Optimistic status transition of the MANIFEST item. Not retried (not idempotent). */
  def transition(runId: String, from: String, to: String): Either[StorageError, Unit] =
    for
      pk      <- Keys.run(runId)
      current <- store.get(Table.RunLedger, pk, Keys.Manifest)
      item    <- current.toRight(StorageError.InvalidInput("runId", s"no manifest for $runId"))
      _       <-
        if item.status.contains(from) then Right(())
        else Left(StorageError.VersionConflict(item.key, item.version))
      _ <- store.updateIfVersion(
        Table.RunLedger,
        item.copy(status = Some(to), version = item.version + 1),
        item.version
      )
    yield ()

  def rejects(runId: String, limit: Int): Either[StorageError, List[RejectRecord]] =
    for
      pk    <- Keys.run(runId)
      items <- Retry.run(policy)(store.queryPartition(Table.RunLedger, pk, limit))
    yield items.filter(_.sk.startsWith("REJECT#")).map(i => read[RejectRecord](i.payload))

final class EntityLinkCacheTable(store: KeyValueStore, policy: RetryPolicy = RetryPolicy.Default):

  def put(decision: MatchDecision): Either[StorageError, WriteOutcome] =
    for
      payload <- Wire.encodeValidated(decision).left.map(invalid)
      sk      <- Keys.policy(decision.policyVersion)
      item = TableItem.create(Keys.mention(decision.mentionId), sk, payload, decision.provenance)
      out <- Retry.run(policy)(store.putIfAbsent(Table.EntityLinkCache, item))
    yield out

  def get(mentionId: String, policyVersion: String): Either[StorageError, Option[MatchDecision]] =
    for
      sk   <- Keys.policy(policyVersion)
      item <- Retry.run(policy)(store.get(Table.EntityLinkCache, Keys.mention(mentionId), sk))
      out  <- item match
        case None    => Right(None)
        case Some(i) =>
          Wire
            .decode[MatchDecision](i.payload)
            .left
            .map(e => StorageError.Failed("decode", e.toString))
            .map(Some(_))
    yield out

/** One turn of a runtime query session. Sessions expire through the `ttl` attribute. */
final case class SessionTurn(
    sessionId: String,
    turn: Int,
    planId: String,
    answerId: Option[String],
    ttlEpochSeconds: Long,
    provenance: Provenance
) derives ReadWriter

final class QuerySessionTable(store: KeyValueStore, policy: RetryPolicy = RetryPolicy.Default):

  def put(turn: SessionTurn): Either[StorageError, WriteOutcome] =
    for
      pk <- Keys.session(turn.sessionId)
      sk <- Keys.turn(turn.turn)
      _  <-
        if IdFormat.isValid(IdKind.QueryPlan, turn.planId) then Right(())
        else Left(StorageError.InvalidInput("planId", "not a plan:v1 ID"))
      item = TableItem.create(
        pk,
        sk,
        write(turn),
        turn.provenance,
        ttlEpochSeconds = Some(turn.ttlEpochSeconds)
      )
      out <- Retry.run(policy)(store.putIfAbsent(Table.QuerySession, item))
    yield out

  def turns(sessionId: String, limit: Int): Either[StorageError, List[SessionTurn]] =
    for
      pk    <- Keys.session(sessionId)
      items <- Retry.run(policy)(store.queryPartition(Table.QuerySession, pk, limit))
    yield items.map(i => read[SessionTurn](i.payload))

final class EvidenceBundleTable(store: KeyValueStore, policy: RetryPolicy = RetryPolicy.Default):

  def put(bundle: EvidenceBundle): Either[StorageError, WriteOutcome] =
    for
      payload <- Wire.encodeValidated(bundle).left.map(invalid)
      item =
        TableItem.create(Keys.bundle(bundle.bundleId), Keys.BundleSk, payload, bundle.provenance)
      out <- Retry.run(policy)(store.putIfAbsent(Table.EvidenceBundle, item))
    yield out

  def get(bundleId: String): Either[StorageError, Option[EvidenceBundle]] =
    Retry.run(policy)(store.get(
      Table.EvidenceBundle,
      Keys.bundle(bundleId),
      Keys.BundleSk
    )).flatMap {
      case None    => Right(None)
      case Some(i) =>
        Wire
          .decode[EvidenceBundle](i.payload)
          .left
          .map(e => StorageError.Failed("decode", e.toString))
          .map(Some(_))
    }

/** A registered S3 artifact produced by a run. */
final case class ArtifactRecord(
    artifactType: String,
    name: String,
    runId: String,
    objectKey: String,
    contentHash: String,
    provenance: Provenance
) derives ReadWriter

final class ArtifactRegistryTable(store: KeyValueStore, policy: RetryPolicy = RetryPolicy.Default):

  def register(a: ArtifactRecord): Either[StorageError, WriteOutcome] =
    for
      pk <- Keys.artifact(a.artifactType, a.name)
      sk <- Keys.run(a.runId)
      _  <-
        if IdFormat.isValid(IdKind.ContentHash, a.contentHash) then Right(())
        else Left(StorageError.InvalidInput("contentHash", "not sha256:<hex>"))
      _ <-
        if a.provenance.pipelineRunId == a.runId then Right(())
        else Left(StorageError.InvalidInput("provenance.pipelineRunId", "must equal runId"))
      item = TableItem.create(pk, sk, write(a), a.provenance)
      out <- Retry.run(policy)(store.putIfAbsent(Table.ArtifactRegistry, item))
    yield out
