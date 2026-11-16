package edu.uic.cs441.scholarchain.er

import com.typesafe.config.ConfigFactory
import edu.uic.cs441.scholarchain.core.model.{IdNamespace, Provenance, SourceType}
import edu.uic.cs441.scholarchain.er.Identifiers.IdComparison
import upickle.default.{read, write}

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 11 repair-01, er-policy-v3 (human decision STRICT, 2026-11-14): (a) missing evidence never
 * raises a score; (b) name plus initials alone never reach accepted; (c) every non-accepted
 * decision is a merge veto that the identity closure can read. Items: D3, D3-floor, M1, and D1
 * support (decision outputs). All inputs are synthetic.
 */
class StrictPolicySuite extends munit.FunSuite:

  val runId  = "run:v1:" + "d" * 64
  val policy = ScoringPolicy.loadDefault().fold(e => fail(e), identity)

  def prov(id: String) = Provenance(SourceType.Dblp, s"src/$id", runId)

  def pair(f: FeatureVector, scope: String = "mention") =
    PairInput(
      scope,
      "m|smith|j",
      "m1",
      "m2",
      Some(false),
      f,
      IdComparison.Empty,
      Nil,
      prov("m1"),
      prov("m2")
    )

  def fv(
      n: Option[Double],
      i: Option[Double],
      c: Option[Double],
      t: Option[Double],
      y: Option[Double]
  ) = FeatureVector(n, i, c, None, t, y)

  def view(id: String, name: String, coauthors: List[String], title: String, year: Option[Int]) =
    EntityView(id, s"work:dblp:t/$id", name, title, year, coauthors, Map.empty, prov(id))

  /** An adjudicator that always accepts and counts its calls. */
  final class AlwaysAccept extends Adjudicator:
    val calls                                = AtomicInteger(0)
    val version                              = "always-accept-test"
    def adjudicate(req: AdjudicationRequest) =
      calls.incrementAndGet()
      AdjudicationResult(
        AdjudicationOutcome.Accept,
        Some(req.candidateMemberIds.head),
        version,
        "x"
      )

  // ---- The combination space: every feature missing or at grid values --------------------------

  val names     = List(None, Some(0.0), Some(0.5), Some(0.9), Some(1.0))
  val initials  = List(None, Some(0.0), Some(1.0))
  val coauthors = List(None, Some(0.0), Some(0.0499), Some(0.05), Some(0.5), Some(1.0))
  val titles    = List(None, Some(0.0), Some(0.4999), Some(0.5), Some(1.0))
  val years     = List(None, Some(0.0), Some(5.0), Some(20.0))

  val space: List[FeatureVector] =
    for n <- names; i <- initials; c <- coauthors; t <- titles; y <- years
    yield fv(n, i, c, t, y)

  /** The five weighted slots of a vector, to fill one missing slot at a time. */
  def slots(f: FeatureVector): List[Option[Double]] =
    List(
      f.nameSimilarity,
      f.initialsCompatibility,
      f.coauthorOverlap,
      f.titleSimilarity,
      f.yearDistance
    )

  def withSlot(f: FeatureVector, k: Int, v: Option[Double]): FeatureVector = k match
    case 0 => f.copy(nameSimilarity = v)
    case 1 => f.copy(initialsCompatibility = v)
    case 2 => f.copy(coauthorOverlap = v)
    case 3 => f.copy(titleSimilarity = v)
    case _ => f.copy(yearDistance = v)

  val fills: List[List[Option[Double]]] =
    List(names, initials, coauthors, titles, years).map(_.flatten.map(Some(_)))
  val best: List[Option[Double]]  = List(Some(1.0), Some(1.0), Some(1.0), Some(1.0), Some(0.0))
  val worst: List[Option[Double]] = List(Some(0.0), Some(0.0), Some(0.0), Some(0.0), Some(40.0))

  test("policy er-policy-v5 loads; corroboration minimums come from config and fail closed") {
    assertEquals(policy.policyVersion, "er-policy-v5")
    assertEquals(policy.corroboration, Corroboration(0.05, 0.5))
    assertEquals(policy.minEvidenceWeight, 0.15)
    val base = ConfigFactory.parseResources(getClass.getClassLoader, ScoringPolicy.DefaultResource)
    def withValue(p: String, v: String) =
      ConfigFactory.parseString(s"${ScoringPolicy.ConfigPath}.$p = $v").withFallback(base)
    List("0", "0.0", "-0.1", "1.01").foreach { v =>
      assert(ScoringPolicy.fromConfig(withValue("corroboration.coauthor-overlap-min", v)).isLeft, v)
      assert(ScoringPolicy.fromConfig(withValue("corroboration.title-similarity-min", v)).isLeft, v)
    }
    assert(ScoringPolicy.fromConfig(withValue("corroboration.title-similarity-min", "1.0")).isRight)
    List("corroboration.coauthor-overlap-min", "corroboration.title-similarity-min").foreach(p =>
      assert(
        ScoringPolicy.fromConfig(base.withoutPath(s"${ScoringPolicy.ConfigPath}.$p")).isLeft,
        p
      )
    )
  }

  // ---- D3 -----------------------------------------------------------------------------------------

  test("D3: two single-author 'J. Smith' mentions, different titles, same year: not accepted") {
    val a = view("m1", "J. Smith", Nil, "Distributed hash tables for peer networks", Some(2020))
    val b = view("m2", "J. Smith", Nil, "Protein folding on cubic lattices", Some(2020))
    val r = Resolver.decide(policy, PairInput.fromViews("mention", "m|smith|j", a, b), runId)
    // er-policy-v2 renormalized over the computable features: 0.70 / 0.75 = 0.9333, accepted.
    // er-policy-v3: lower = upper = 0.40 + 0.15 + 0.25*0 + 0.05*0 + 0.15*1 = 0.70.
    assertEquals(r.status, ResolutionStatus.Unresolved, r)
    assertEquals(r.reasonCodes, List("score-band:unresolved", "uncorroborated"))
    assertEqualsDouble(r.score.get, 0.70, 1e-9)
    assertEqualsDouble(r.scoreUpper.get, 0.70, 1e-9)
    assertEquals(r.corroborated, Some(false))
    // An adjudicator that would accept anything is never consulted for it.
    val adj = AlwaysAccept()
    val ra  = Resolver.decide(policy, PairInput.fromViews("mention", "m|smith|j", a, b), runId, adj)
    assertEquals((ra.status, adj.calls.get), (ResolutionStatus.Unresolved, 0))
  }

  test("(a) over the full combination space: filling a missing feature never lowers the score") {
    var checked = 0
    for
      scope  <- List("mention", "work")
      f      <- space
      (v, k) <- slots(f).zipWithIndex if v.isEmpty
      fill   <- fills(k)
    do
      val missing = Resolver.score(policy, scope, f).get
      val filled  = Resolver.score(policy, scope, withSlot(f, k, fill)).get
      assert(
        filled.lower >= missing.lower - 1e-12,
        s"$scope $f slot $k=$fill: lower rose by absence"
      )
      assert(
        filled.upper <= missing.upper + 1e-12,
        s"$scope $f slot $k=$fill: upper fell by absence"
      )
      checked += 1
    assert(checked > 10000, checked)
  }

  test("(a) decisions: absence never causes an accept, and never causes a reject") {
    for
      f      <- space
      (v, k) <- slots(f).zipWithIndex if v.isEmpty
    do
      val st = Resolver.decide(policy, pair(f), runId).status
      if st == ResolutionStatus.Accepted then
        val up = Resolver.decide(policy, pair(withSlot(f, k, best(k))), runId)
        assertEquals(up.status, ResolutionStatus.Accepted, s"$f accepted, not when slot $k is best")
      if st == ResolutionStatus.Rejected then
        val dn = Resolver.decide(policy, pair(withSlot(f, k, worst(k))), runId)
        assertEquals(
          dn.status,
          ResolutionStatus.Rejected,
          s"$f rejected, not when slot $k is worst"
        )
  }

  // ---- D3-floor -----------------------------------------------------------------------------------

  test(
    "(b) over the full combination space, any thresholds and any deterministic minimums, both " +
      "scopes: uncorroborated never accepts"
  ) {
    // Phase 11 repair-03 (review-02 F3): the deterministic minimums vary too. The policies are
    // built with `copy`, so they bypass ScoringPolicy.validated on purpose: the Resolver itself
    // must not accept an uncorroborated pair even under a policy validation would reject.
    val d             = policy.deterministic
    val deterministic = List(
      d,
      d.copy(mentionCoauthorMin = 0.0), // F3: empty coauthors observe 0.0 (M1)
      d.copy(workTitleMin = 0.0),
      d.copy(mentionCoauthorMin = 0.0499, workTitleMin = 0.4999), // just below corroboration
      d.copy(
        mentionNameMin = 0.0,
        mentionCoauthorMin = 0.0,
        mentionMaxYearGap = 1000.0,
        workTitleMin = 0.0,
        workMaxYearGap = 1000.0
      )
    )
    val thresholds = List(
      policy,
      policy.copy(minEvidenceWeight = 0.0),
      policy.copy(accept = 0.5, reject = 0.3),
      policy.copy(accept = 0.0, reject = 0.0, minEvidenceWeight = 0.0)
    )
    val policies       = for t <- thresholds; dr <- deterministic yield t.copy(deterministic = dr)
    val uncorroborated = space.filterNot(Resolver.corroborated(policy, _))
    assert(uncorroborated.size > 500, uncorroborated.size)
    var checked = 0
    for p <- policies; scope <- List("mention", "work"); f <- uncorroborated do
      val adj = AlwaysAccept()
      val r   = Resolver.decide(p, pair(f, scope), runId, adj)
      assertNotEquals(
        r.status,
        ResolutionStatus.Accepted,
        s"accept=${p.accept} ${p.deterministic} $scope $f: ${r.path.wire} ${r.reasonCodes}"
      )
      assertEquals(adj.calls.get, 0, s"adjudicator consulted for uncorroborated $f")
      checked += 1
    assertEquals(checked, policies.size * 2 * uncorroborated.size)
    // Name plus initials at their best, every other feature missing: insufficient evidence.
    val nameOnly = Resolver.decide(policy, pair(fv(Some(1.0), Some(1.0), None, None, None)), runId)
    assertEquals(
      (nameOnly.status, nameOnly.reasonCodes),
      (ResolutionStatus.Unresolved, List("insufficient-evidence"))
    )
  }

  test("D3-floor: min-evidence-weight counts only name-independent features (boundaries)") {
    val nameYear = fv(Some(1.0), Some(1.0), None, None, Some(0.0)) // independent weight 0.15
    assertEquals(Resolver.score(policy, "mention", nameYear).get.independentWeight, 0.15)
    // Exactly at the floor (inclusive): passes it, then stops at corroboration.
    assertEquals(
      Resolver.decide(policy, pair(nameYear), runId).reasonCodes,
      List("score-band:unresolved", "uncorroborated")
    )
    val above = policy.copy(minEvidenceWeight = 0.1500001)
    assertEquals(
      Resolver.decide(above, pair(nameYear), runId).reasonCodes,
      List("insufficient-evidence")
    )
    // A title alone (0.05) is below the floor even though name + initials + title weigh 0.60.
    val nameTitle = fv(Some(1.0), Some(1.0), None, Some(1.0), None)
    assertEquals(
      Resolver.decide(policy, pair(nameTitle), runId).reasonCodes,
      List("insufficient-evidence")
    )
  }

  test("corroboration minimums are inclusive; just below them the pair stays unresolved") {
    val p                                       = policy.copy(accept = 0.70)
    def r(c: Option[Double], t: Option[Double]) =
      Resolver.decide(p, pair(fv(Some(1.0), Some(1.0), c, t, Some(0.0))), runId)
    // lower = 0.70 + 0.25c (+ 0.05t): all of these clear accept 0.70 on the score alone.
    assertEquals(r(Some(0.05), None).status, ResolutionStatus.Accepted)
    assertEquals(r(Some(0.0499), None).reasonCodes, List("score-band:unresolved", "uncorroborated"))
    assertEquals(r(Some(0.0), Some(0.5)).status, ResolutionStatus.Accepted)
    assertEquals(
      r(Some(0.0), Some(0.4999)).reasonCodes,
      List("score-band:unresolved", "uncorroborated")
    )
  }

  test("bound thresholds: accept on lower (inclusive), reject on upper (exclusive)") {
    val w = policy.copy(workWeights = FeatureWeights(0, 0, 0.5, 0.5, 0), minEvidenceWeight = 0.0)
    def work(t: Double) = pair(fv(None, None, None, Some(t), None), "work") // coauthor missing
    // title 0.9: lower 0.45, upper 0.95.
    assertEquals(
      Resolver.decide(w.copy(accept = 0.45, reject = 0.3), work(0.9), runId).status,
      ResolutionStatus.Accepted
    )
    assertEquals(
      Resolver.decide(w.copy(accept = 0.46, reject = 0.3), work(0.9), runId).status,
      ResolutionStatus.Unresolved
    )
    // title 0.0: lower 0.0, upper 0.5. The missing coauthor feature blocks a reject at 0.5.
    assertEquals(
      Resolver.decide(w.copy(accept = 0.9, reject = 0.5), work(0.0), runId).status,
      ResolutionStatus.Unresolved
    )
    assertEquals(
      Resolver.decide(w.copy(accept = 0.9, reject = 0.5000001), work(0.0), runId).status,
      ResolutionStatus.Rejected
    )
    assertEquals(
      Resolver.band(0.85, 0.9, corroborated = false, 0.85, 0.55),
      ResolutionStatus.Unresolved
    )
    assertEquals(
      Resolver.band(0.2, 0.5499, corroborated = true, 0.85, 0.55),
      ResolutionStatus.Rejected
    )
  }

  test("adjudicator: consulted for corroborated band pairs only") {
    val adj  = AlwaysAccept()
    val band = fv(Some(1.0), Some(1.0), Some(1.0 / 3), Some(0.0), Some(3.0)) // lower 0.7608
    val r    = Resolver.decide(policy, pair(band), runId, adj)
    assertEquals(
      (r.status, r.path, adj.calls.get),
      (
        ResolutionStatus.Accepted,
        MatchPath.LlmAdjudicated,
        1
      )
    )
    val noCo = Resolver.decide(policy, pair(band.copy(coauthorOverlap = Some(0.0))), runId, adj)
    assertEquals((noCo.status, adj.calls.get), (ResolutionStatus.Unresolved, 1))
  }

  test("evaluator sweep: an uncorroborated pair never re-bands to accepted at a lower threshold") {
    val a    = view("m1", "J. Smith", Nil, "Distributed hash tables", Some(2020))
    val b    = view("m2", "J. Smith", Nil, "Protein folding", Some(2020))
    val item = Outputs.toReviewItem(
      Resolver.decide(policy, PairInput.fromViews("mention", "k", a, b), runId)
    )
    assertEqualsDouble(item.scoreUpper.get, 0.70, 1e-9)
    assertEquals(item.corroborated, Some(false))
    // er-policy-v2 re-banding on the score alone would accept 0.70 at a = 0.60.
    assertEquals(Evaluator.restatus(item, 0.60, 0.55), ResolutionStatus.Unresolved)
    assertEquals(Evaluator.restatus(item, 0.80, 0.71), ResolutionStatus.Rejected) // upper < 0.71
    val corroborated = item.copy(corroborated = Some(true))
    assertEquals(Evaluator.restatus(corroborated, 0.60, 0.55), ResolutionStatus.Accepted)
  }

  // ---- M1: one pair through both input paths ------------------------------------------------------

  /** The Job 5 line Hadoop Blocking.features emits for the pair (Blocking.scala:168-182). */
  def hadoopLine(lo: String, hi: String, feats: List[(String, String)]): String =
    def p(s: String) =
      s"""{"sourceDataset":"dblp","sourceRecordId":"src/$s","pipelineRunId":"$runId"}"""
    val fs = feats.map((n, v) => s"""{"name":"$n","value":$v}""").mkString(",")
    s"""{"scope":"mention","blockKey":"m|smith|j","chunk":0,"memberLow":"$lo","memberHigh":"$hi",""" +
      s""""workLow":"work:dblp:t/$lo","workHigh":"work:dblp:t/$hi","features":[$fs],""" +
      s""""provenanceLow":${p(lo)},"provenanceHigh":${p(hi)}}"""

  /** Features both paths compute; the Hadoop record carries no name string or mention title. */
  def shared(f: FeatureVector) =
    (f.nameSimilarity, f.coauthorOverlap, f.idAgreement, f.yearDistance)

  test("M1: one pair through the entity-view path and the Hadoop path gives the same features") {
    // D3 pair: single-author papers (both coauthor sets empty), same name and year.
    val a = view("m1", "J. Smith", Nil, "Distributed hash tables for peer networks", Some(2020))
    val b = view("m2", "J. Smith", Nil, "Protein folding on cubic lattices", Some(2020))
    val viewIn = PairInput.fromViews("mention", "m|smith|j", a, b)
    // Hadoop: normalizedName "j smith" both sides, context (other authors) empty on both sides.
    val hadoopIn = HadoopCandidates.toPairInput(HadoopCandidates.parseLine(hadoopLine(
      "m1",
      "m2",
      List(
        "labelExact"        -> "1",
        "labelTokenJaccard" -> "1",
        "sameWork"          -> "0",
        "sameSource"        -> "1",
        "yearGap"           -> "0",
        "sameVenue"         -> "null",
        "sharedExactKeys"   -> "0",
        "sharedContext"     -> "0",
        "contextJaccard"    -> "0",
        "orcidEqual"        -> "null"
      )
    )).toOption.get)
    assertEquals(shared(viewIn.features), shared(hadoopIn.features))
    assertEquals(viewIn.features.coauthorOverlap, Some(0.0))
    assertEquals(
      (viewIn.memberLow, viewIn.memberHigh, viewIn.sameWork, viewIn.ids),
      (hadoopIn.memberLow, hadoopIn.memberHigh, hadoopIn.sameWork, hadoopIn.ids)
    )
    val rv = Resolver.decide(policy, viewIn, runId)
    val rh = Resolver.decide(policy, hadoopIn, runId)
    assertEquals((rv.status, rv.path, rv.corroborated), (rh.status, rh.path, rh.corroborated))
    assertEquals(rv.status, ResolutionStatus.Unresolved)
    assert(rv.reasonCodes.contains("uncorroborated") && rh.reasonCodes.contains("uncorroborated"))
    // The view path also has initials and title; under v3 extra features can only tighten the
    // bounds, so the Hadoop bounds contain the view bounds.
    assert(rh.score.get <= rv.score.get + 1e-12 && rv.scoreUpper.get <= rh.scoreUpper.get + 1e-12)
  }

  test("M1: a pair with one shared coauthor decides the same on both paths") {
    val a        = view("m1", "Ana Lee", List("Bo Chen", "C. Diaz"), "Graph mining", Some(2010))
    val b        = view("m2", "Ana Lee", List("Bo Chen", "F. Gil"), "Protein folding", Some(2013))
    val viewIn   = PairInput.fromViews("mention", "m|lee|a", a, b)
    val hadoopIn = HadoopCandidates.toPairInput(HadoopCandidates.parseLine(hadoopLine(
      "m1",
      "m2",
      List(
        "labelExact"        -> "1",
        "labelTokenJaccard" -> "1",
        "sameWork"          -> "0",
        "yearGap"           -> "3",
        "sharedContext"     -> "1",
        "contextJaccard"    -> (1.0 / 3).toString,
        "orcidEqual"        -> "null"
      )
    ))
      .toOption.get)
    assertEquals(shared(viewIn.features), shared(hadoopIn.features))
    val rv = Resolver.decide(policy, viewIn, runId)
    val rh = Resolver.decide(policy, hadoopIn, runId)
    assertEquals((rv.status, rv.corroborated), (rh.status, rh.corroborated))
    assertEquals((rv.status, rv.corroborated), (ResolutionStatus.Unresolved, Some(true)))
  }

  // ---- D1 support: decision outputs are merge vetoes Spark can read -------------------------------

  val V2Keys: List[String] = List(
    "schemaVersion",
    "decisionId",
    "pairId",
    "scope",
    "blockKey",
    "memberLow",
    "memberHigh",
    "status",
    "path",
    "outcome",
    "decider",
    "score",
    "confidence",
    "policyVersion",
    "featureSpecVersion",
    "adjudicatorVersion",
    "features",
    "reasonCodes",
    "provenance",
    "provenanceLow",
    "provenanceHigh",
    "scoreUpper",
    "corroborated"
  )

  test("outputs: every rejected, unresolved, and conflict pair is a readable merge veto") {
    val john  = view("m-a", "John Smith", List("Alan Turing"), "Graph partitioning", Some(2020))
    val j     = view("m-b", "J. Smith", List("Alan Turing"), "Graph partitioning", Some(2020))
    val jane  = view("m-c", "Jane Smith", List("Alan Turing"), "Graph partitioning", Some(2020))
    val solo1 = view("m-d", "J. Smith", Nil, "Distributed hash tables", Some(2020))
    val orcid = (id: String, o: String) =>
      view(id, "Wei Wang", Nil, "T", Some(2020)).copy(ids =
        Map(edu.uic.cs441.scholarchain.core.model.IdNamespace.Orcid -> List(o))
      )
    def d(x: EntityView, y: EntityView) =
      Resolver.decide(policy, PairInput.fromViews("mention", "k", x, y), runId)
    val recs = List(
      d(john, j),
      d(j, jane),
      d(john, jane),
      d(j, solo1),
      d(orcid("m-e", "0000-0002-1825-0097"), orcid("m-f", "0000-0001-5109-3700"))
    )
    assertEquals(
      recs.map(_.status),
      List(
        ResolutionStatus.Accepted,
        ResolutionStatus.Accepted,
        ResolutionStatus.Rejected,
        ResolutionStatus.Unresolved,
        ResolutionStatus.Conflict
      )
    )
    val dir = Files.createTempDirectory("er-veto")
    Outputs.writeAll(dir, recs, 10)
    assertEquals(
      Outputs.MergeVetoFiles,
      List("rejected.jsonl", "unresolved.jsonl", "conflict.jsonl")
    )
    val vetoes = Outputs.MergeVetoFiles.flatMap { f =>
      val (rs, bad) = Outputs.readRecords(dir.resolve(f))
      assertEquals(bad, Nil, f)
      rs
    }
    assertEquals(vetoes.map(_.pairId).sorted, recs.filter(_.blocksMerge).map(_.pairId).sorted)
    assertEquals(
      vetoes.map(r => (r.memberLow, r.memberHigh)).toSet,
      Set(("m-a", "m-c"), ("m-b", "m-d"), ("m-e", "m-f"))
    )
    vetoes.foreach { r =>
      assert(r.blocksMerge && r.memberLow < r.memberHigh && r.scope == "mention", r)
      assert(r.provenanceLow.sourceRecordId.nonEmpty && r.provenanceHigh.sourceRecordId.nonEmpty)
      assertEquals((r.schemaVersion, r.policyVersion), (2, "er-policy-v5"))
      assertEquals(r.provenance.pipelineRunId, runId)
    }
    val (acc, _) = Outputs.readRecords(dir.resolve("accepted.jsonl"))
    assert(acc.nonEmpty && acc.forall(!_.blocksMerge))
    // Schema v2 key set, written in full even when the new fields are null.
    Files.readAllLines(dir.resolve("conflict.jsonl")).forEach { line =>
      val keys = ujson.read(line).obj.keys.toList
      assertEquals(keys, V2Keys)
      assertEquals(ujson.read(line)("corroborated"), ujson.Null)
    }
  }

  test("outputs: a schema-v1 line (no scoreUpper, no corroborated) still reads") {
    val r = Resolver.decide(
      policy,
      pair(fv(Some(1.0), Some(1.0), Some(1.0), None, Some(0.0))),
      runId
    )
    val js = ujson.read(write(r))
    js.obj.remove("scoreUpper"); js.obj.remove("corroborated"); js("schemaVersion") = 1
    val back = read[ResolutionRecord](js.render())
    assertEquals((back.scoreUpper, back.corroborated), (None, None))
    assertEquals(
      back.copy(
        schemaVersion = 2,
        scoreUpper = r.scoreUpper,
        corroborated = r.corroborated
      ),
      r
    )
  }

  // ---- Phase 11 repair-02, er-policy-v4: no missing value moves a decision to Accepted -----------

  /** The weighted slots that are features of a scope (name and initials are mention-only). */
  def scopeSlots(scope: String): List[Int] =
    if scope == "work" then List(2, 3, 4) else List(0, 1, 2, 3, 4)

  val permissive: ScoringPolicy = policy.copy(accept = 0.5, reject = 0.3, minEvidenceWeight = 0.0)

  test("(a) decisions against EVERY value, the blocking ones included: removal never accepts") {
    // "(a) decisions" above compares a missing value with the best value only, which is why it
    // missed RR3. Here: for every observed value v of every feature (year 20, initials 0, coauthor
    // 0, ...), if the pair with v is not accepted, the pair with that feature removed is not either.
    // Two policies, an abstaining and an accept-everything adjudicator, both scopes.
    var checked  = 0
    var accepted = 0
    for
      p     <- List(policy, permissive)
      mk    <- List[() => Adjudicator](() => AbstainAdjudicator, () => AlwaysAccept())
      scope <- List("mention", "work")
      f     <- space
      k     <- scopeSlots(scope) if slots(f)(k).isDefined
    do
      val full = Resolver.decide(p, pair(f, scope), runId, mk()).status
      if full == ResolutionStatus.Accepted then accepted += 1
      else
        val reduced = Resolver.decide(p, pair(withSlot(f, k, None), scope), runId, mk())
        assertNotEquals(
          reduced.status,
          ResolutionStatus.Accepted,
          s"accept=${p.accept} $scope: slot $k=${slots(f)(k)} gives ${full.wire}, removed gives " +
            s"accepted ${reduced.path.wire} ${reduced.reasonCodes}; $f"
        )
        checked += 1
    // Not vacuous: both sides of the implication are exercised many times.
    assert(checked > 20000 && accepted > 2000, (checked, accepted))
  }

  test("RR3: the work-order pair is not accepted when one side's year is removed") {
    val a = view(
      "m1",
      "Smith, J.",
      List("A. Turing", "Wei Wang 0002"),
      "Protein folding on cubic lattices",
      Some(2000)
    )
    val b = view("m2", "J. Smith", List("A. Turing", "Wei Wang 0002", "Cy Ho"), "!!!", Some(2020))
    val full = Resolver.decide(policy, PairInput.fromViews("mention", "m|smith|j", a, b), runId)
    assertEquals(full.status, ResolutionStatus.Unresolved, full)
    val noYear = Resolver.decide(
      policy,
      PairInput.fromViews("mention", "m|smith|j", a.copy(year = None), b),
      runId
    )
    // v3: accepted, deterministic name-exact+coauthor-overlap, confidence 1.0.
    assertEquals(
      (noYear.status, noYear.path),
      (ResolutionStatus.Unresolved, MatchPath.Probabilistic)
    )
    assert(!noYear.reasonCodes.contains("name-exact+coauthor-overlap"), noYear.reasonCodes)
  }

  test("RR3 boundaries: deterministic mention accept needs an observed year gap <= the maximum") {
    val gap                  = policy.deterministic.mentionMaxYearGap
    def r(y: Option[Double]) =
      Resolver.decide(policy, pair(fv(Some(1.0), Some(1.0), Some(1.0), None, y)), runId)
    val atGap = r(Some(gap))
    assertEquals(
      (atGap.status, atGap.path, atGap.reasonCodes),
      (ResolutionStatus.Accepted, MatchPath.Deterministic, List("name-exact+coauthor-overlap"))
    )
    // Just over the gap: probabilistic, lower = 0.80 + 0.15 * (1 - 16/20) = 0.83 < 0.85.
    val over = r(Some(gap + 1))
    assertEquals((over.status, over.path), (ResolutionStatus.Unresolved, MatchPath.Probabilistic))
    assertEqualsDouble(over.score.get, 0.83, 1e-9)
    // Missing: never deterministic; the year counts 0 on the lower bound (0.80), 1 on the upper.
    val missing = r(None)
    assertEquals(
      (missing.status, missing.path, missing.reasonCodes),
      (
        ResolutionStatus.Unresolved,
        MatchPath.Probabilistic,
        List("score-band:unresolved", "adjudicator-abstain")
      )
    )
    assertEqualsDouble(missing.score.get, 0.80, 1e-9)
    assertEqualsDouble(missing.scoreUpper.get, 1.0, 1e-9)
    // Work scope (unchanged, already needed a present year): title-exact needs a year too.
    def w(y: Option[Double]) =
      Resolver.decide(policy, pair(fv(None, None, None, Some(1.0), y), "work"), runId)
    assertEquals(w(Some(0.0)).reasonCodes, List("title-exact+year"))
    assertNotEquals(w(None).path, MatchPath.Deterministic)
  }

  test("v4 audit: an unknown sameWork never reaches Accepted on a mention pair, ID or not") {
    val agree = IdComparison(List(IdNamespace.Orcid), Nil)
    def in(f: FeatureVector, sw: Option[Boolean], ids: IdComparison) =
      pair(f.copy(idAgreement = Features.idAgreement(ids))).copy(sameWork = sw, ids = ids)
    var knownAccepted = 0
    for f <- space; ids <- List(IdComparison.Empty, agree) do
      val adj = AlwaysAccept()
      if Resolver.decide(policy, in(f, Some(false), ids), runId, adj).status ==
          ResolutionStatus.Accepted
      then knownAccepted += 1
      // The vetoing value never accepts, so neither may the missing one.
      assertNotEquals(
        Resolver.decide(policy, in(f, Some(true), ids), runId, adj).status,
        ResolutionStatus.Accepted
      )
      val unknown = Resolver.decide(policy, in(f, None, ids), runId, adj)
      assertNotEquals(unknown.status, ResolutionStatus.Accepted, s"$f $ids: ${unknown.reasonCodes}")
    assert(knownAccepted > 1000, knownAccepted)
    val strong = fv(Some(1.0), Some(1.0), Some(1.0), Some(1.0), Some(0.0))
    val e      = Resolver.decide(policy, in(strong, None, agree), runId)
    assertEquals(
      (e.status, e.path, e.reasonCodes),
      (
        ResolutionStatus.Unresolved,
        MatchPath.ExactId,
        List("accept-blocked:same-work-unknown", "id-agree:orcid")
      )
    )
    assertEquals(
      Resolver.decide(policy, in(strong, Some(false), agree), runId).status,
      ResolutionStatus.Accepted
    )
    assertEquals(
      Resolver.decide(policy, in(strong, Some(true), agree), runId).status,
      ResolutionStatus.Conflict
    )
    val n = Resolver.decide(policy, in(strong, None, IdComparison.Empty), runId)
    assertEquals(
      (n.status, n.reasonCodes),
      (
        ResolutionStatus.Unresolved,
        List("score-band:unresolved", "accept-blocked:same-work-unknown")
      )
    )
    // With reject-same-work off, sameWork never vetoes a non-ID pair, so its absence blocks nothing.
    val off = policy.copy(deterministic = policy.deterministic.copy(rejectSameWork = false))
    assertEquals(
      Resolver.decide(off, in(strong, None, IdComparison.Empty), runId).status,
      ResolutionStatus.Accepted
    )
  }

  test("v4 audit: unknown initials block every non-ID accept of a mention pair (vs initials = 0)") {
    val co  = List("Bo Chen", "C. Diaz", "D. Eng")
    val a   = view("m1", "Li", co, "Graph partitioning at scale", Some(2020))
    val b   = view("m2", "Li", co, "Graph partitioning at scale", Some(2020))
    val adj = AlwaysAccept()
    val r   = Resolver.decide(policy, PairInput.fromViews("mention", "m|li|", a, b), runId, adj)
    assertEquals(r.features.find(_.name == Features.InitialsCompatibility).get.value, None)
    // v3: lower = 0.40 + 0.25 + 0.05 + 0.15 = 0.85 (missing initials count 0), corroborated.
    assertEqualsDouble(r.score.get, 0.85, 1e-9)
    assertEquals(
      (r.status, r.reasonCodes),
      (
        ResolutionStatus.Unresolved,
        List("score-band:unresolved", "accept-blocked:initials-unknown")
      )
    )
    assertEquals(adj.calls.get, 0, "a blocked pair reached the adjudicator")
    // The observed vetoing value rejects the same evidence.
    val zero = Resolver.decide(
      policy,
      pair(fv(Some(1.0), Some(0.0), Some(1.0), Some(1.0), Some(0.0))),
      runId
    )
    assertEquals(
      (zero.status, zero.reasonCodes),
      (ResolutionStatus.Rejected, List("initials-incompatible"))
    )
    // The block follows the veto rule: with reject-incompatible-initials off, initials never veto.
    val off =
      permissive.copy(deterministic = policy.deterministic.copy(rejectIncompatibleInitials = false))
    assertEquals(
      Resolver.decide(off, PairInput.fromViews("mention", "m|li|", a, b), runId).status,
      ResolutionStatus.Accepted
    )
    // A blocked pair is still rejected when its upper bound is below reject (0.15 < 0.55).
    val low =
      Resolver.decide(policy, pair(fv(Some(0.0), None, Some(0.0), Some(0.0), Some(40.0))), runId)
    assertEquals(
      (low.status, low.reasonCodes),
      (ResolutionStatus.Rejected, List("score-band:rejected"))
    )
    // Hadoop path (initials never computable): only ORCID agreement accepts, at any thresholds.
    def h(orcid: String) = HadoopCandidates.toPairInput(HadoopCandidates.parseLine(hadoopLine(
      "m1",
      "m2",
      List(
        "labelTokenJaccard" -> "1",
        "sameWork"          -> "0",
        "yearGap"           -> "0",
        "contextJaccard"    -> "1",
        "orcidEqual"        -> orcid
      )
    )).toOption.get)
    val hAdj = AlwaysAccept()
    val hr   = Resolver.decide(permissive, h("null"), runId, hAdj)
    assertEquals((hr.status, hAdj.calls.get), (ResolutionStatus.Unresolved, 0))
    assert(hr.reasonCodes.contains("accept-blocked:initials-unknown"), hr.reasonCodes)
    val hId = Resolver.decide(policy, h("1"), runId)
    assertEquals((hId.status, hId.path), (ResolutionStatus.Accepted, MatchPath.ExactId))
  }

  test(
    "v4 audit: the adjudicator never sees a pair that only missing features keep from a reject"
  ) {
    // name 0.9, initials 1, coauthor 0.05, title 0: observed part 0.5225.
    val adj                  = AlwaysAccept()
    def r(y: Option[Double]) =
      Resolver.decide(policy, pair(fv(Some(0.9), Some(1.0), Some(0.05), Some(0.0), y)), runId, adj)
    val gap20 = r(Some(20.0)) // lower = upper = 0.5225 < reject 0.55
    assertEquals(gap20.status, ResolutionStatus.Rejected)
    val missing = r(None) // lower 0.5225 < 0.55 <= upper 0.6725
    assertEquals(
      (missing.status, missing.reasonCodes),
      (ResolutionStatus.Unresolved, List("score-band:unresolved", "bounds-straddle-reject"))
    )
    assertEquals(adj.calls.get, 0)
    // Observed year 0: lower 0.6725 >= reject, so the pair is in the band and is adjudicated.
    assertEquals((r(Some(0.0)).path, adj.calls.get), (MatchPath.LlmAdjudicated, 1))
    // Boundary: lower exactly at reject is adjudicated (reject is exclusive).
    val atReject = policy.copy(reject =
      Resolver.score(
        policy,
        "mention",
        fv(Some(0.9), Some(1.0), Some(0.05), Some(0.0), None)
      ).get.lower
    )
    assertEquals(
      Resolver.decide(
        atReject,
        pair(fv(Some(0.9), Some(1.0), Some(0.05), Some(0.0), None)),
        runId,
        adj
      ).path,
      MatchPath.LlmAdjudicated
    )
  }

  test("RR4 parity: DBLP homonym-numbered coauthors are different people on both paths") {
    val a = view("m1", "J. Smith", List("Wei Wang 0001"), "Distributed hash tables", Some(2020))
    val b = view("m2", "J. Smith", List("Wei Wang 0002"), "Protein folding on lattices", Some(2020))
    val viewIn = PairInput.fromViews("mention", "m|smith|j", a, b)
    // Hadoop: contexts {"wei wang 0001"} and {"wei wang 0002"}: contextJaccard 0.
    def hadoop(cj: String) = HadoopCandidates.toPairInput(HadoopCandidates.parseLine(hadoopLine(
      "m1",
      "m2",
      List(
        "labelExact"        -> "1",
        "labelTokenJaccard" -> "1",
        "sameWork"          -> "0",
        "yearGap"           -> "0",
        "sharedContext"     -> (if cj == "0" then "0" else "1"),
        "contextJaccard"    -> cj,
        "orcidEqual"        -> "null"
      )
    )).toOption.get)
    val hadoopIn = hadoop("0")
    assertEquals(viewIn.features.coauthorOverlap, Some(0.0))
    assertEquals(shared(viewIn.features), shared(hadoopIn.features))
    val rv = Resolver.decide(policy, viewIn, runId)
    val rh = Resolver.decide(policy, hadoopIn, runId)
    // v3: the view path saw overlap 1.0 and accepted (name-exact+coauthor-overlap).
    assertEquals((rv.status, rv.corroborated), (rh.status, rh.corroborated))
    assertNotEquals(rv.status, ResolutionStatus.Accepted, rv)
    // The same number on both sides is the same coauthor on both paths.
    val same =
      PairInput.fromViews("mention", "m|smith|j", a, b.copy(coauthors = List("Wei Wang 0001")))
    assertEquals(shared(same.features), shared(hadoop("1").features))
  }

  test("v4 audit: the sweep never moves an item to Accepted on a missing value") {
    val base = Outputs.toReviewItem(
      Resolver.decide(
        policy,
        pair(fv(Some(1.0), Some(1.0), Some(1.0 / 3), Some(0.0), Some(3.0))),
        runId
      )
    )
    // lower = upper = 0.7608, corroborated, adjudicator abstained.
    assertEquals((base.status, base.corroborated), (ResolutionStatus.Unresolved, Some(true)))
    assertEquals(Evaluator.restatus(base, 0.60, 0.55), ResolutionStatus.Accepted)
    // A pre-v3 item has no `corroborated`. v3 treated it as corroborated, so 0.60 accepted it.
    val v1 = base.copy(scoreUpper = None, corroborated = None)
    assertEquals(Evaluator.restatus(v1, 0.60, 0.55), ResolutionStatus.Unresolved)
    // An item its own policy accepted stays accepted where its score allows; it is never raised.
    val v1Accepted = v1.copy(status = ResolutionStatus.Accepted)
    assertEquals(Evaluator.restatus(v1Accepted, 0.60, 0.55), ResolutionStatus.Accepted)
    assertEquals(Evaluator.restatus(v1Accepted, 0.80, 0.55), ResolutionStatus.Unresolved)
    // An accept-blocked item never re-bands to accepted, at any threshold; it can still reject.
    val blocked =
      base.copy(reasonCodes = List("score-band:unresolved", "accept-blocked:initials-unknown"))
    assertEquals(Evaluator.restatus(blocked, 0.0, 0.0), ResolutionStatus.Unresolved)
    assertEquals(Evaluator.restatus(blocked, 0.99, 0.95), ResolutionStatus.Rejected)
  }

  // ---- Phase 11 repair-03, er-policy-v5 (review-02 F3) -------------------------------------------

  val defaultConfig =
    ConfigFactory.parseResources(getClass.getClassLoader, ScoringPolicy.DefaultResource)

  def withValues(kv: (String, String)*) =
    kv.foldLeft(defaultConfig)((c, e) =>
      ConfigFactory.parseString(s"${ScoringPolicy.ConfigPath}.${e._1} = ${e._2}").withFallback(c)
    )

  def loads(kv: (String, String)*): Boolean = ScoringPolicy.fromConfig(withValues(kv*)).isRight

  test("F3: two single-author 'J. Smith' mentions are never accepted, even with coauthor-min 0") {
    val a  = view("m1", "J. Smith", Nil, "Distributed hash tables", Some(2020))
    val b  = view("m2", "J. Smith", Nil, "Protein folding on cubic lattices", Some(2020))
    val in = PairInput.fromViews("mention", "m|smith|j", a, b)
    assertEquals(in.features.coauthorOverlap, Some(0.0)) // M1: observed, not missing
    // The review-02 F3 policy: before v5 it loaded, and the pair was Accepted (deterministic,
    // name-exact+coauthor-overlap, confidence 1.0) on name, initials, and year alone.
    assert(!loads("deterministic.mention-coauthor-min" -> "0.0"))
    val zero = policy.copy(deterministic = policy.deterministic.copy(mentionCoauthorMin = 0.0))
    val adj  = AlwaysAccept()
    val r    = Resolver.decide(zero, in, runId, adj)
    assertEquals(
      (r.status, r.path, r.reasonCodes, adj.calls.get),
      (
        ResolutionStatus.Unresolved,
        MatchPath.Probabilistic,
        List("score-band:unresolved", "uncorroborated"),
        0
      )
    )
    // The work analogue: work-title-min 0 accepted two different same-year works.
    assert(!loads("deterministic.work-title-min" -> "0.0"))
    val zeroT = policy.copy(deterministic = policy.deterministic.copy(workTitleMin = 0.0))
    val w     =
      Resolver.decide(zeroT, pair(fv(None, None, Some(0.0), Some(0.0), Some(0.0)), "work"), runId)
    assertNotEquals(w.status, ResolutionStatus.Accepted, w)
    assert(!w.reasonCodes.contains("title-exact+year"), w.reasonCodes)
  }

  test("F3 boundaries: deterministic minimums in (0, 1] and >= the corroboration minimums") {
    assert(loads())
    // mention-coauthor-min against corroboration.coauthor-overlap-min = 0.05 (inclusive).
    List("0", "0.0", "-0.1", "0.0499", "1.01").foreach(v =>
      assert(!loads("deterministic.mention-coauthor-min" -> v), s"mention-coauthor-min $v")
    )
    List("0.05", "1.0").foreach(v =>
      assert(loads("deterministic.mention-coauthor-min" -> v), s"mention-coauthor-min $v")
    )
    // work-title-min against corroboration.title-similarity-min = 0.5 (inclusive).
    List("0", "-0.1", "0.4999", "1.01").foreach(v =>
      assert(!loads("deterministic.work-title-min" -> v), s"work-title-min $v")
    )
    List("0.5", "1.0").foreach(v =>
      assert(loads("deterministic.work-title-min" -> v), s"work-title-min $v")
    )
    // The ordering holds in both directions: raising a corroboration minimum above the
    // deterministic one is rejected too.
    assert(!loads("corroboration.coauthor-overlap-min" -> "0.5001"))
    assert(loads("corroboration.coauthor-overlap-min" -> "0.5"))
    assert(!loads(
      "corroboration.coauthor-overlap-min" -> "0.6",
      "deterministic.mention-coauthor-min" -> "0.55"
    ))
    // mention-name-min in (0, 1].
    List("0", "-1", "1.01").foreach(v =>
      assert(!loads("deterministic.mention-name-min" -> v), s"mention-name-min $v")
    )
    assert(loads("deterministic.mention-name-min" -> "0.01"))
    // Maximum year gaps >= 0.
    List("deterministic.mention-max-year-gap", "deterministic.work-max-year-gap").foreach { k =>
      assert(!loads(k -> "-1"), k)
      assert(!loads(k -> "-0.001"), k)
      assert(loads(k -> "0"), k)
    }
    // At the inclusive boundary the deterministic accept still fires (corroboration holds).
    val atMin = ScoringPolicy.fromConfig(
      withValues("deterministic.mention-coauthor-min" -> "0.05")
    ).fold(e => fail(e), identity)
    def det(c: Double) =
      Resolver.decide(atMin, pair(fv(Some(1.0), Some(1.0), Some(c), None, Some(0.0))), runId)
    assertEquals(
      (det(0.05).status, det(0.05).path),
      (ResolutionStatus.Accepted, MatchPath.Deterministic)
    )
    assertNotEquals(det(0.0499).path, MatchPath.Deterministic)
  }

  test("F3 audit: thresholds, weights, sweep values, and finiteness are validated") {
    // Ordering 0 <= reject <= accept <= 1 (equal allowed: the band is then empty), accept > 0.
    assert(!loads("thresholds.accept" -> "0", "thresholds.reject" -> "0"))
    assert(loads("thresholds.accept" -> "0.01", "thresholds.reject" -> "0"))
    assert(loads("thresholds.accept" -> "0.6", "thresholds.reject" -> "0.6"))
    assert(!loads("thresholds.accept" -> "0.6", "thresholds.reject" -> "0.6001"))
    // Weights >= 0.
    assert(!loads("weights.mention.coauthor-overlap" -> "-0.1"))
    assert(!loads("weights.work.title-similarity" -> "-0.0001"))
    assert(loads("weights.work.name-similarity" -> "0"))
    // Sweep values in [0, 1].
    assert(!loads("sweep.accept" -> "[0.5, 1.5]"))
    assert(!loads("sweep.reject" -> "[-0.1]"))
    // Finite: NaN would compare false and silently disable a rule.
    val d = policy.deterministic
    List(
      policy.copy(yearScale = Double.NaN),
      policy.copy(accept = Double.NaN),
      policy.copy(minEvidenceWeight = Double.PositiveInfinity),
      policy.copy(deterministic = d.copy(mentionCoauthorMin = Double.NaN)),
      policy.copy(deterministic = d.copy(mentionMaxYearGap = Double.PositiveInfinity)),
      policy.copy(mentionWeights = policy.mentionWeights.copy(yearDistance = Double.NaN)),
      policy.copy(sweepAccept = List(Double.NaN))
    ).foreach(p => assert(ScoringPolicy.validated(p).isLeft, p))
    assertEquals(ScoringPolicy.validated(policy), Right(policy))
    // The core override path validates the same rules.
    assert(policy.withCoreSettings(
      edu.uic.cs441.scholarchain.core.config.EntityResolutionSettings("er-policy-x", 0.0, 0.0)
    ).isLeft)
  }
