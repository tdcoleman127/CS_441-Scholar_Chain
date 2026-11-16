package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.er.*
import edu.uic.cs441.scholarchain.hadoop.{Blocking, MatchCandidateRecord}
import edu.uic.cs441.scholarchain.tests.hadoop.AdvFixtures

/**
 * Phase 11 repair-01 rerun, target 2: entity resolution under the human STRICT decision, across
 * modules.
 *
 *   - Over many generated entity-view pairs (names, coauthor lists, titles, years, ORCIDs), no
 *     non-ID decision is Accepted without corroboration (coauthorOverlap >= 0.05 or titleSimilarity
 *     >= 0.5), with the bundled abstaining adjudicator and with one that accepts everything.
 *   - Removing evidence (a year, a title, coauthors) never turns a decision into Accepted.
 *   - Path parity (M1): the same pair through the real Hadoop Job 4/5 code (DBLP parser, Blocking
 *     members and candidate, JSON wire) and through the entity-view path.
 */
class ErStrictCrossPathSuite extends munit.FunSuite:

  private val runId  = "run:v1:" + "f" * 64
  private val policy = ScoringPolicy.loadDefault().fold(e => fail(e), identity)

  /** An adjudicator that accepts every pair it is shown. */
  private object AlwaysAccept extends Adjudicator:
    val version                              = "phase11-rerun-always-accept"
    def adjudicate(req: AdjudicationRequest) =
      AdjudicationResult(
        AdjudicationOutcome.Accept,
        Some(req.candidateMemberIds.head),
        version,
        "x"
      )

  // ---- Generator -------------------------------------------------------------------------------

  private val namePool = Vector(
    "J. Smith",
    "John Smith",
    "Smith, J.",
    "J. R. Smith",
    "Jane Smith",
    "John Smith 0001",
    "John Smith 0002",
    "Jo Smith",
    "José Smith",
    "Ｊ． Ｓｍｉｔｈ",
    "Smith",
    "Wei Wang",
    "W. Wang",
    "Wen Wang"
  )
  private val coauthorPool = Vector(
    "Alan Turing",
    "A. Turing",
    "Turing, Alan",
    "Ada Byron",
    "Bo Wu",
    "Cy Ho",
    "Di Fox",
    "Wei Wang 0001",
    "Wei Wang 0002"
  )
  private val titlePool = Vector(
    "Graph partitioning at scale",
    "Graph partitioning at large scale",
    "Scale free graph partitioning",
    "Protein folding on cubic lattices",
    "Distributed hash tables",
    "",
    "!!!"
  )
  private val orcids = Vector("0000-0002-1825-0097", "0000-0001-5109-3700")

  final private case class Side(
      name: String,
      co: List[String],
      title: String,
      year: Option[Int],
      orcid: Option[String]
  )

  private def side(rnd: scala.util.Random): Side =
    Side(
      namePool(rnd.nextInt(namePool.size)),
      rnd.shuffle(coauthorPool.toList).take(rnd.nextInt(4)),
      titlePool(rnd.nextInt(titlePool.size)),
      if rnd.nextInt(4) == 0 then None else Some(2000 + rnd.nextInt(25)),
      if rnd.nextInt(10) == 0 then Some(orcids(rnd.nextInt(2))) else None
    )

  private def view(id: String, s: Side): EntityView =
    EntityView(
      id,
      s"work:dblp:t/$id",
      s.name,
      s.title,
      s.year,
      s.co,
      s.orcid.fold(Map.empty[IdNamespace, List[String]])(o => Map(IdNamespace.Orcid -> List(o))),
      Provenance(SourceType.Dblp, s"t/$id", runId)
    )

  private def decide(a: Side, b: Side, adj: Adjudicator = AbstainAdjudicator): ResolutionRecord =
    Resolver.decide(
      policy,
      PairInput.fromViews("mention", "bk", view("m1", a), view("m2", b)),
      runId,
      adj
    )

  private lazy val pairs: List[(Side, Side)] =
    val rnd = scala.util.Random(441)
    List.fill(4000)((side(rnd), side(rnd)))

  private def feature(r: ResolutionRecord, name: String): Option[Double] =
    r.features.find(_.name == name).flatMap(_.value)

  // ---- Tests -----------------------------------------------------------------------------------

  test(
    "STRICT over 4000 generated pairs: no non-ID accept without corroboration, any adjudicator"
  ) {
    var probabilisticAccepts = 0
    var deterministic        = 0
    var exactId              = 0
    val bad                  = List.newBuilder[String]
    for (a, b) <- pairs; adj <- List(AbstainAdjudicator, AlwaysAccept) do
      val r = decide(a, b, adj)
      if r.status == ResolutionStatus.Accepted then
        r.path match
          case MatchPath.ExactId =>
            exactId += 1
            if !r.reasonCodes.exists(_.startsWith("id-agree:")) then
              bad += s"exact-id accept $a $b $r"
          case p =>
            if p == MatchPath.Deterministic then deterministic += 1 else probabilisticAccepts += 1
            val co = feature(r, Features.CoauthorOverlap).getOrElse(0.0)
            val ti = feature(r, Features.TitleSimilarity).getOrElse(0.0)
            if !(co >= 0.05 || ti >= 0.5) then
              bad += s"uncorroborated $p accept (coauthor=$co title=$ti): $a / $b"
    val found = bad.result()
    assertEquals(found.take(5), Nil, s"${found.size} violations")
    // Not vacuous: every accept path was exercised.
    assert(
      probabilisticAccepts > 0 && deterministic > 0 && exactId > 0,
      (probabilisticAccepts, deterministic, exactId)
    )
  }

  test("name plus initials alone never reach Accepted (no coauthors, unrelated titles, no IDs)") {
    val solos =
      for
        n1 <- namePool
        n2 <- namePool
        y1 <- List(None, Some(2020))
        y2 <- List(None, Some(2020), Some(2021))
      yield (
        Side(n1, Nil, "Distributed hash tables", y1, None),
        Side(n2, Nil, "Protein folding on cubic lattices", y2, None)
      )
    val accepted = solos.flatMap((a, b) =>
      List(AbstainAdjudicator, AlwaysAccept).map(decide(a, b, _)).filter(_.status ==
        ResolutionStatus.Accepted).map(r => (a.name, b.name, r.path))
    )
    assertEquals(accepted.toList, Nil)
    assert(solos.size > 1000, solos.size)
  }

  test("missing evidence never turns a decision into Accepted (year, title, coauthors removed)") {
    // er-policy-v3 (human decision STRICT): missing evidence never raises a score, and the policy
    // documents that absence can never cause an accept. Each generated pair is decided as is and
    // with one side's year, title, or coauthors removed; the removal must never produce Accepted
    // where the full evidence did not.
    val removals: List[(String, Side => Side)] = List(
      "year"      -> (_.copy(year = None)),
      "title"     -> (_.copy(title = "")),
      "coauthors" -> (_.copy(co = Nil))
    )
    val bad =
      for
        (a, b) <- pairs
        full = decide(a, b)
        if full.status != ResolutionStatus.Accepted
        (what, drop) <- removals
        (ra, rb)     <- List((drop(a), b), (a, drop(b)))
        r = decide(ra, rb)
        if r.status == ResolutionStatus.Accepted
      yield s"removing $what: ${full.status.wire} ${full.reasonCodes} -> accepted ${r.path.wire} " +
        s"${r.reasonCodes}; full=($a, $b) reduced=($ra, $rb)"
    assertEquals(
      bad.distinct.take(3),
      Nil,
      s"${bad.distinct.size} pairs became Accepted by absence"
    )
  }

  // ---- Path parity through the real Hadoop Job 4/5 code -----------------------------------------

  /** Parses DBLP records with the real Hadoop parser and returns (mention view, Hadoop member). */
  private def parsed(key: String, authors: Seq[String], title: String, year: String) =
    val p      = AdvFixtures.parseOk(AdvFixtures.dblpRecord(key, authors, title, year))
    val env    = AdvFixtures.envelope(p)
    val me     = p.mentions.find(_.authorPosition == 0).get
    val member = Blocking.members(env).collectFirst {
      case (Some(_), m) if m.scope == "mention" && m.memberId == me.mentionId => m
    }.get
    val v = EntityView(
      me.mentionId,
      p.work.workId,
      me.rawName,
      p.work.title,
      p.work.year,
      p.mentions.filterNot(_.mentionId == me.mentionId).map(_.rawName),
      Map.empty,
      me.provenance
    )
    (v, member)

  /** (view-path input, Hadoop-path input) for the first authors of two DBLP records. */
  private def bothPaths(
      a: (String, Seq[String], String, String),
      b: (String, Seq[String], String, String)
  ): (PairInput, PairInput) =
    val (va, ma) = parsed(a._1, a._2, a._3, a._4)
    val (vb, mb) = parsed(b._1, b._2, b._3, b._4)
    val key      = Blocking.mentionKey(ma.label).get
    val wire     = upickle.default.write[MatchCandidateRecord](Blocking.candidate(key, 0, ma, mb))
    val hadoop   =
      HadoopCandidates.toPairInput(HadoopCandidates.parseLine(wire).fold(e => fail(e), identity))
    (PairInput.fromViews("mention", key, va, vb), hadoop)

  private def shared(f: FeatureVector) = (f.coauthorOverlap, f.idAgreement, f.yearDistance)

  test("M1 parity, real Hadoop path: two single-author 'J. Smith' records (empty coauthor sets)") {
    val (v, h) = bothPaths(
      ("journals/tst/P1", Seq("J. Smith"), "Distributed hash tables for peer networks", "2020"),
      ("journals/tst/P2", Seq("J. Smith"), "Protein folding on cubic lattices", "2020")
    )
    assertEquals((v.memberLow, v.memberHigh), (h.memberLow, h.memberHigh))
    assertEquals(shared(v.features), shared(h.features))
    assertEquals(v.features.coauthorOverlap, Some(0.0))
    assertEquals(h.features.nameSimilarity, Some(1.0))
    val rv = Resolver.decide(policy, v, runId)
    val rh = Resolver.decide(policy, h, runId)
    assertEquals((rv.status, rv.corroborated), (rh.status, rh.corroborated))
    assertEquals(rv.status, ResolutionStatus.Unresolved)
    // Hadoop lacks initials and the mention title; its bounds must contain the view bounds.
    assert(rh.score.get <= rv.score.get + 1e-12 && rv.scoreUpper.get <= rh.scoreUpper.get + 1e-12)
  }

  test("M1 parity, real Hadoop path: one shared coauthor and one coauthor each side only") {
    val (v, h) = bothPaths(
      ("journals/tst/P3", Seq("Ana Lee", "Bo Chen", "Cy Diaz"), "Graph mining", "2010"),
      ("journals/tst/P4", Seq("Ana Lee", "Bo Chen", "Fe Gil"), "Protein folding", "2013")
    )
    assertEquals(shared(v.features), shared(h.features))
    assertEqualsDouble(v.features.coauthorOverlap.get, 1.0 / 3, 1e-12)
  }

  test("parity: DBLP homonym-numbered coauthors ('Wei Wang 0001' vs '0002') are different people") {
    // DBLP marks distinct people who share a name with a numeric suffix. The Hadoop path keeps the
    // suffix in its coauthor context (DblpRecordParser.normalizeName keeps digits), so the two
    // coauthor sets below do not overlap. The entity-view path computes coauthorOverlap over
    // Normalize.personName(..).canonical (er Features.scala:125-128), which drops purely numeric
    // trailing tokens (er Normalize.scala personName), so both become "wei wang" and overlap 1.0.
    // M1 promises the same coauthorOverlap on both paths (er HadoopCandidates.scala:56-62).
    val (v, h) = bothPaths(
      ("journals/tst/H1", Seq("J. Smith", "Wei Wang 0001"), "Distributed hash tables", "2020"),
      ("journals/tst/H2", Seq("J. Smith", "Wei Wang 0002"), "Protein folding on lattices", "2020")
    )
    assertEquals(h.features.coauthorOverlap, Some(0.0), "precondition: Hadoop sees no overlap")
    val rv = Resolver.decide(policy, v, runId)
    val rh = Resolver.decide(policy, h, runId)
    assertEquals(
      v.features.coauthorOverlap,
      h.features.coauthorOverlap,
      s"view path status ${rv.status.wire} ${rv.reasonCodes}; Hadoop path ${rh.status.wire} ${rh.reasonCodes}"
    )
    assertNotEquals(rv.status, ResolutionStatus.Accepted, rv)
  }
