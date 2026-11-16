package edu.uic.cs441.scholarchain.er

import com.typesafe.config.ConfigFactory
import edu.uic.cs441.scholarchain.core.config.EntityResolutionSettings
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.{IdNamespace, MatchOutcome, Provenance, SourceType}
import upickle.default.read

import java.nio.file.Files

class ResolverSuite extends munit.FunSuite:

  val runId  = "run:v1:" + "b" * 64
  val policy = ScoringPolicy.loadDefault().fold(e => fail(e), identity)

  def view(
      id: String,
      name: String,
      ids: Map[IdNamespace, List[String]] = Map.empty,
      coauthors: List[String] = Nil,
      work: String = "",
      year: Option[Int] = Some(2010),
      title: String = ""
  ) =
    EntityView(
      id,
      if work.isEmpty then s"w-$id" else work,
      name,
      title,
      year,
      coauthors,
      ids,
      Provenance(SourceType.Dblp, s"src/$id", runId)
    )

  def mention(a: EntityView, b: EntityView) = PairInput.fromViews("mention", "m|k|x", a, b)

  test("policy comes from configuration and fails closed on bad thresholds") {
    assert(IdFormat.isValid(IdKind.Run, runId))
    assertEquals(policy.policyVersion, "er-policy-v5")
    assert(policy.reject <= policy.accept)
    val swapped = ConfigFactory.parseString(
      "scholarchain.entity-resolution.policy.thresholds { accept = 0.4, reject = 0.6 }"
    ).withFallback(ConfigFactory.parseResources(ScoringPolicy.DefaultResource))
    assert(ScoringPolicy.fromConfig(swapped).isLeft)
    assert(ScoringPolicy.fromConfig(ConfigFactory.empty()).isLeft)
    val core =
      policy.withCoreSettings(EntityResolutionSettings("er-policy-test", 0.9, 0.5)).toOption.get
    assertEquals((core.policyVersion, core.accept, core.reject), ("er-policy-test", 0.9, 0.5))
  }

  test("threshold boundaries: accept is inclusive, reject is exclusive") {
    val p = policy.copy(accept = 0.8, reject = 0.5)
    assertEquals(Resolver.band(0.8, p.accept, p.reject), ResolutionStatus.Accepted)
    assertEquals(Resolver.band(0.7999999, p.accept, p.reject), ResolutionStatus.Unresolved)
    assertEquals(Resolver.band(0.5, p.accept, p.reject), ResolutionStatus.Unresolved)
    assertEquals(Resolver.band(0.4999999, p.accept, p.reject), ResolutionStatus.Rejected)
    // End to end: a work pair whose only weighted feature is titleSimilarity scores exactly it.
    val titleOnly = p.copy(workWeights = FeatureWeights(0, 0, 0, 1.0, 0), minEvidenceWeight = 0)
    def work(t: Double) = PairInput(
      "work",
      "w|k",
      "work:a",
      "work:b",
      Some(false),
      FeatureVector(None, None, None, None, Some(t), None),
      Identifiers.IdComparison.Empty,
      Nil,
      view("a", "").provenance,
      view("b", "").provenance
    )
    assertEquals(Resolver.decide(titleOnly, work(0.8), runId).status, ResolutionStatus.Accepted)
    assertEquals(Resolver.decide(titleOnly, work(0.79), runId).status, ResolutionStatus.Unresolved)
    assertEquals(Resolver.decide(titleOnly, work(0.5), runId).status, ResolutionStatus.Unresolved)
    assertEquals(Resolver.decide(titleOnly, work(0.49), runId).status, ResolutionStatus.Rejected)
  }

  test("conflicting authoritative IDs never merge, even with another ID agreeing") {
    val a = view(
      "m1",
      "John Smith",
      Map(
        IdNamespace.Orcid    -> List("0000-0002-1825-0097"),
        IdNamespace.OpenAlex -> List("A1")
      )
    )
    val b = view(
      "m2",
      "John Smith",
      Map(
        IdNamespace.Orcid    -> List("0000-0001-5109-3700"),
        IdNamespace.OpenAlex -> List("https://openalex.org/A1")
      )
    )
    val r = Resolver.decide(policy, mention(a, b), runId)
    assertEquals(r.status, ResolutionStatus.Conflict)
    assertEquals(r.outcome, MatchOutcome.NeedsReview)
    assert(r.reasonCodes.contains("id-conflict:orcid"), r.reasonCodes)
    val c = view(
      "m3",
      "J. Smith",
      Map(IdNamespace.Orcid -> List("https://orcid.org/0000-0002-1825-0097"))
    )
    val e = Resolver.decide(policy, mention(a, c), runId)
    assertEquals((e.status, e.path), (ResolutionStatus.Accepted, MatchPath.ExactId))
  }

  test(
    "deterministic rules: same work rejects, incompatible initials reject, name+coauthors accept"
  ) {
    val sameWork = Resolver.decide(
      policy,
      mention(
        view("m1", "A. Lee", work = "w9"),
        view("m2", "A. Lee", work = "w9")
      ),
      runId
    )
    assertEquals(
      (sameWork.status, sameWork.path),
      (ResolutionStatus.Rejected, MatchPath.Deterministic)
    )
    val initials =
      Resolver.decide(policy, mention(view("m1", "John Smith"), view("m2", "Jane Smith")), runId)
    assertEquals(initials.reasonCodes.head, "initials-incompatible")
    val co  = List("Bo Chen", "C. Diaz")
    val acc = Resolver.decide(
      policy,
      mention(
        view("m1", "Ana Lee", coauthors = co),
        view("m2", "Lee, Ana", coauthors = co)
      ),
      runId
    )
    assertEquals((acc.status, acc.path), (ResolutionStatus.Accepted, MatchPath.Deterministic))
  }

  // ---- Phase 05 repair-01, F1: work-level IDs never decide a mention pair ----------------------

  test("F1 DOI variant: same-named coauthors on one paper sharing the work DOI are rejected") {
    val doi = Map(IdNamespace.Doi -> List("10.1/x"))
    val a   = view("m1", "Wei Wang", doi, work = "work:dblp:x")
    val b   = view("m2", "Wei Wang", doi, work = "work:dblp:x")
    val in  = PairInput.fromViews("mention", "m|wang|w", a, b)
    assertEquals(in.ids, Identifiers.IdComparison.Empty)
    val r = Resolver.decide(policy, in, runId)
    assertEquals(
      (r.status, r.path, r.outcome, r.reasonCodes),
      (ResolutionStatus.Rejected, MatchPath.Deterministic, MatchOutcome.NonMatch, List("same-work"))
    )
    assert(!r.reasonCodes.exists(_.startsWith("id-agree")), r.reasonCodes)
  }

  test("F1 OpenAlex variant: shared work ID W1 is ignored; author IDs A1 vs A2 conflict") {
    val a = view("m1", "Wei Wang", Map(IdNamespace.OpenAlex -> List("W1", "A1")))
    val b = view("m2", "Wei Wang", Map(IdNamespace.OpenAlex -> List("W1", "A2")))
    val r = Resolver.decide(policy, PairInput.fromViews("mention", "m|wang|w", a, b), runId)
    assertEquals((r.status, r.path), (ResolutionStatus.Conflict, MatchPath.ExactId))
    assertEquals(r.reasonCodes, List("id-conflict:openalex"))
    // Same paper as well: still a conflict output, never a merge.
    val c = view("m3", "Wei Wang", Map(IdNamespace.OpenAlex -> List("W1", "A1")), work = "w9")
    val d = view("m4", "Wei Wang", Map(IdNamespace.OpenAlex -> List("W1", "A2")), work = "w9")
    val s = Resolver.decide(policy, PairInput.fromViews("mention", "m|wang|w", c, d), runId)
    assertEquals(s.status, ResolutionStatus.Conflict)
    // Only the shared work ID: no ID evidence at all for a mention pair.
    val e = view("m5", "Wei Wang", Map(IdNamespace.OpenAlex -> List("W1")))
    val f = view("m6", "Wei Wang", Map(IdNamespace.OpenAlex -> List("W1")))
    val g = Resolver.decide(policy, PairInput.fromViews("mention", "m|wang|w", e, f), runId)
    assertNotEquals(g.path, MatchPath.ExactId)
    assert(!g.reasonCodes.exists(_.startsWith("id-")), g.reasonCodes)
  }

  test("F1 rule: same-work mention pair with an agreeing researcher ID is a conflict output") {
    val orcid = Map(IdNamespace.Orcid -> List("0000-0002-1825-0097"))
    val a     = view("m1", "Wei Wang", orcid, work = "w9")
    val b     = view("m2", "Wei Wang", orcid, work = "w9")
    val r     = Resolver.decide(policy, PairInput.fromViews("mention", "m|wang|w", a, b), runId)
    assertEquals((r.status, r.path), (ResolutionStatus.Conflict, MatchPath.ExactId))
    assertEquals(r.reasonCodes, List("same-work+id-agree", "id-agree:orcid"))
    // Different works with the same ORCID still take the exact-ID accept.
    val c = view("m3", "W. Wang", orcid, work = "w8")
    val e = Resolver.decide(policy, PairInput.fromViews("mention", "m|wang|w", a, c), runId)
    assertEquals((e.status, e.path), (ResolutionStatus.Accepted, MatchPath.ExactId))
    // Hadoop path: orcidEqual = 1 on one work is the same conflict.
    val h = HadoopCandidates.toPairInput(HadoopCandidates.parseLine(
      hadoopLine(
        "m|wang|w",
        List("labelTokenJaccard" -> "1", "sameWork" -> "1", "orcidEqual" -> "1")
      )
    ).toOption.get)
    assertEquals(Resolver.decide(policy, h, runId).status, ResolutionStatus.Conflict)
  }

  // ---- Phase 05 repair-01, F2: no deterministic "name-exact" accept from a token-set proxy ----

  private def hadoopLine(key: String, feats: List[(String, String)]): String =
    val prov =
      (s: String) => s"""{"sourceDataset":"dblp","sourceRecordId":"$s","pipelineRunId":"$runId"}"""
    val fs = feats.map((n, v) => s"""{"name":"$n","value":$v}""").mkString(",")
    s"""{"scope":"mention","blockKey":"$key","chunk":0,"memberLow":"lo","memberHigh":"hi","workLow":"w1","workHigh":"w2","features":[$fs],"provenanceLow":${prov(
        "lo"
      )},"provenanceHigh":${prov("hi")}}"""

  test("F2 Berg example: token-set label match on the Hadoop path is not a deterministic accept") {
    // "anna maria lisa berg" vs "anna lisa maria berg": token sets equal, labels not equal.
    val line = hadoopLine(
      "m|berg|a",
      List(
        "labelExact"        -> "0",
        "labelTokenJaccard" -> "1",
        "sameWork"          -> "0",
        "sameSource"        -> "1",
        "yearGap"           -> "3",
        "sameVenue"         -> "null",
        "sharedExactKeys"   -> "0",
        "sharedContext"     -> "1",
        "contextJaccard"    -> "0.5",
        "orcidEqual"        -> "null"
      )
    )
    val in = HadoopCandidates.toPairInput(HadoopCandidates.parseLine(line).toOption.get)
    val r  = Resolver.decide(policy, in, runId)
    assertNotEquals(r.path, MatchPath.Deterministic)
    assert(!r.reasonCodes.contains("name-exact+coauthor-overlap"), r.reasonCodes)
    // Routed to the probabilistic path. er-policy-v3 bounds over all mention weights (sum 1.0):
    // lower = 0.40*1 + 0.25*0.5 + 0.15*0.85 = 0.6525 (initials and title missing count 0);
    // upper = 0.6525 + 0.15 + 0.05 = 0.8525 (missing count 1). Both < accept: unresolved.
    assertEquals((r.status, r.path), (ResolutionStatus.Unresolved, MatchPath.Probabilistic))
    assertEqualsDouble(r.score.get, 0.6525, 1e-9)
    assertEqualsDouble(r.scoreUpper.get, 0.8525, 1e-9)
    assertEquals(r.corroborated, Some(true))
  }

  test("F2 rule: deterministic mention accept needs initialsCompatibility == 1 (boundaries)") {
    val co = List("Bo Chen", "C. Diaz", "D. Eng")
    // Mononyms: nameSimilarity 1.0 but no given names, so initials are not computable.
    val mono = Resolver.decide(
      policy,
      mention(view("m1", "Li", coauthors = co), view("m2", "Li", coauthors = co)),
      runId
    )
    assertEquals(mono.features.find(_.name == Features.InitialsCompatibility).get.value, None)
    assertEquals(mono.path, MatchPath.Probabilistic)
    assert(!mono.reasonCodes.contains("name-exact+coauthor-overlap"), mono.reasonCodes)
    // Initials present and compatible, coauthorOverlap exactly at mention-coauthor-min (0.5).
    val half = Resolver.decide(
      policy,
      mention(
        view("m1", "Ana Lee", coauthors = co),
        view("m2", "Ana Lee", coauthors = List("Bo Chen", "C. Diaz", "F. Gil"))
      ),
      runId
    )
    assertEquals((half.status, half.path), (ResolutionStatus.Accepted, MatchPath.Deterministic))
    // Just below the coauthor minimum (1/3): not deterministic.
    val third = Resolver.decide(
      policy,
      mention(
        view("m1", "Ana Lee", coauthors = List("Bo Chen", "C. Diaz")),
        view("m2", "Ana Lee", coauthors = List("Bo Chen", "F. Gil"))
      ),
      runId
    )
    assertNotEquals(third.path, MatchPath.Deterministic)
  }

  test("adjudicator: stub abstains in the band; an invented ID is refused") {
    val band = policy.copy(accept = 0.99, reject = 0.0)
    // One shared coauthor (overlap 1/3) corroborates, so the pair reaches the adjudicator band
    // (er-policy-v3: uncorroborated pairs never do).
    val in = mention(
      view("m1", "A. Lee", coauthors = List("Bo Chen", "C. Diaz")),
      view("m2", "Ana Lee", coauthors = List("Bo Chen", "F. Gil"))
    )
    val r = Resolver.decide(band, in, runId)
    assertEquals(r.status, ResolutionStatus.Unresolved)
    assertEquals(r.adjudicatorVersion, Some(AbstainAdjudicator.version))
    val rogue = new Adjudicator:
      val version                            = "rogue"
      def adjudicate(q: AdjudicationRequest) =
        AdjudicationResult(AdjudicationOutcome.Accept, Some("mention:v1:invented"), version, "x")
    val x = Resolver.decide(band, in, runId, rogue)
    assertEquals(x.status, ResolutionStatus.Unresolved)
    assert(x.reasonCodes.contains("adjudicator-invalid-id"))
  }

  test(
    "all four outputs and the review queue are written with provenance; Hadoop wire format reads"
  ) {
    // Line shaped exactly like Hadoop Blocking.candidate output (Blocking.scala:53-64, 168-182).
    val prov =
      (s: String) => s"""{"sourceDataset":"dblp","sourceRecordId":"$s","pipelineRunId":"$runId"}"""
    def line(lo: String, hi: String, feats: String) =
      s"""{"scope":"mention","blockKey":"m|lee|a","chunk":0,"memberLow":"$lo","memberHigh":"$hi","workLow":"w1","workHigh":"w2","features":[$feats],"provenanceLow":${prov(
          lo
        )},"provenanceHigh":${prov(hi)}}"""
    val f     = (n: String, v: String) => s"""{"name":"$n","value":$v}"""
    val lines = List(
      line(
        "a1",
        "a2",
        List(f("labelTokenJaccard", "1"), f("sameWork", "0"), f("orcidEqual", "1")).mkString(",")
      ),
      line(
        "b1",
        "b2",
        List(f("labelTokenJaccard", "1"), f("sameWork", "0"), f("orcidEqual", "0")).mkString(",")
      ),
      line(
        "c1",
        "c2",
        List(
          f("labelTokenJaccard", "0"),
          f("sameWork", "0"),
          f("contextJaccard", "0"),
          f("yearGap", "30"),
          f("orcidEqual", "null")
        ).mkString(",")
      ),
      line(
        "d1",
        "d2",
        List(
          f("labelTokenJaccard", "0.5"),
          f("sameWork", "0"),
          f("contextJaccard", "0.5"),
          f("yearGap", "2")
        ).mkString(",")
      )
    )
    val recs = lines.map(l =>
      Resolver.decide(
        policy,
        HadoopCandidates.toPairInput(HadoopCandidates.parseLine(l).toOption.get),
        runId
      )
    )
    assertEquals(
      recs.map(_.status),
      List(
        ResolutionStatus.Accepted,
        ResolutionStatus.Conflict,
        ResolutionStatus.Rejected,
        ResolutionStatus.Unresolved
      )
    )
    recs.foreach { r =>
      assertEquals(r.provenance.pipelineRunId, runId)
      assertEquals(r.provenance.sourceDataset, SourceType.ScholarChainPipeline)
      assert(r.provenanceLow.sourceRecordId.nonEmpty && r.provenanceHigh.sourceRecordId.nonEmpty)
    }
    assert(HadoopCandidates.parseLine("{not json").isLeft)
    val dir    = Files.createTempDirectory("er-out")
    val counts = Outputs.writeAll(dir, recs, 10)
    assertEquals(
      counts("accepted") + counts("rejected") + counts("unresolved") + counts("conflict"),
      4
    )
    ResolutionStatus.values.foreach(s => assert(Files.exists(dir.resolve(Outputs.fileFor(s)))))
    val queue = Files.readAllLines(dir.resolve(Outputs.ReviewQueueFile))
    assertEquals(queue.size, 4)
    val first = read[ReviewItem](queue.get(0))
    assertEquals(first.status, ResolutionStatus.Conflict)
    assertEquals(first.label, None)
  }
