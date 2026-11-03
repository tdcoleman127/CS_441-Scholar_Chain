package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{ResearcherPair, StableIds}
import edu.uic.cs441.scholarchain.core.model.*

/** Deterministic, valid core records for storage tests. */
object Fixtures:

  def runIdFor(attempt: Int): String =
    StableIds.runId(
      "storage-test",
      "code-1",
      "sha256:" + "0" * 64,
      List(RunInput(SourceType.Dblp, "dblp-sample", "sha256:" + "1" * 64)),
      attempt
    )

  val manifest: RunManifest =
    val m = RunManifest(
      SchemaVersion.Current,
      "",
      "storage-test",
      "code-1",
      "sha256:" + "0" * 64,
      List(RunInput(SourceType.Dblp, "dblp-sample", "sha256:" + "1" * 64)),
      1
    )
    m.copy(runId = StableIds.runId(m))

  val runId: String = manifest.runId

  def prov(record: String): Provenance = Provenance(SourceType.Dblp, record, runId)

  def researcherId(anchor: String): String =
    StableIds.scholarChainResearcherId(1, SourceType.Dblp, anchor).toOption.get

  def workId(key: String): String = StableIds.workId(IdNamespace.Dblp, key).toOption.get

  val alice: String = researcherId("homepages/alice")
  val bob: String   = researcherId("homepages/bob")
  val carol: String = researcherId("homepages/carol")
  val dave: String  = researcherId("homepages/dave")

  def researcher(id: String, name: String): CanonicalResearcher =
    CanonicalResearcher(SchemaVersion.Current, id, name, None, List(name), Nil, prov(s"r/$name"))

  def work(key: String, title: String, year: Int): WorkRecord =
    WorkRecord(SchemaVersion.Current, workId(key), title, Some(year), None, Nil, Nil, prov(key))

  def authorship(r: String, key: String, pos: Int, year: Int): AuthorshipRecord =
    val w = workId(key)
    AuthorshipRecord(
      SchemaVersion.Current,
      w,
      r,
      StableIds.mentionId(w, pos, SourceType.Dblp, key),
      pos,
      Some(year),
      prov(key)
    )

  val window: YearWindow = YearWindow(2000, 2020)
  val policy: String     = "graph-v1"

  def coauthorEdge(a: String, b: String, works: List[String], first: Int, last: Int): CoauthorEdge =
    val pair = ResearcherPair.of(a, b).toOption.get
    val ws   = works.map(workId).distinct.sorted
    CoauthorEdge(
      SchemaVersion.Current,
      StableIds.coauthorEdgeId(pair, window, policy),
      pair.low,
      pair.high,
      window,
      policy,
      ws.size,
      first,
      last,
      ws,
      0.9,
      Provenance(SourceType.ScholarChainPipeline, s"edge/${pair.low}/${pair.high}", runId)
    )
