package edu.uic.cs441.scholarchain.tests.adversarial

import com.typesafe.config.ConfigFactory
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.er.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Entity-resolution attacks: common names, reordered author lists, Unicode and confusables,
 * conflicting identifiers, adjudicator misbehavior, and a very large common-name block. The bundled
 * policy is UNCALIBRATED (gold-set pilot F1 = 0.514), so these tests pin safety properties (never
 * merge on conflicting evidence, never merge a common name without corroboration), not accuracy.
 */
class EntityResolutionAdversarialSuite extends munit.FunSuite:
  private val policy = ScoringPolicy.loadDefault().fold(e => fail(e), identity)
  private val runId  = "run:v1:" + "c" * 64
  private val orcidA = "0000-0002-1825-0097"
  private val orcidB = "0000-0001-5109-3700"

  private def mention(
      id: String,
      work: String,
      name: String,
      coauthors: List[String] = List("Alan Turing"),
      title: String = "Graph partitioning at scale",
      year: Option[Int] = Some(2020),
      ids: Map[IdNamespace, List[String]] = Map.empty
  ) = EntityView(
    id,
    s"work:dblp:t/$work",
    name,
    title,
    year,
    coauthors,
    ids,
    Provenance(SourceType.Dblp, s"t/$work", runId)
  )

  private def decide(
      a: EntityView,
      b: EntityView,
      scope: String = "mention",
      adj: Adjudicator =
        AbstainAdjudicator
  ) =
    Resolver.decide(policy, PairInput.fromViews(scope, "bk", a, b), runId, adj)

  test("two identical common names on one work are two people (same-work reject)") {
    val r = decide(mention("m1", "w1", "Wei Wang"), mention("m2", "w1", "Wei Wang"))
    assertEquals(r.status, ResolutionStatus.Rejected)
    assertEquals(r.reasonCodes, List("same-work"))
  }

  test("a common name without shared coauthors never merges automatically") {
    val pairs = List(
      (
        mention("m1", "w1", "Wei Wang", List("Ann Lee")),
        mention("m2", "w2", "Wei Wang", List("Bo Li"))
      ),
      (
        mention("m1", "w1", "Wei Wang", List("Ann Lee"), "Distributed hash tables"),
        mention("m2", "w2", "Wei Wang", List("Bo Li"), "Protein folding with lattices", Some(2001))
      )
    )
    pairs.foreach { (a, b) =>
      val r = decide(a, b)
      assertNotEquals(r.status, ResolutionStatus.Accepted, s"${a.name}/${b.name}: $r")
    }
  }

  test("D3: an initials-only common name with no coauthors and no shared title is not merged") {
    // Two single-author papers by "J. Smith", different titles, same year, no identifiers. The
    // only agreement is the name string. Resolver.score (er Resolver.scala:150-160) drops the
    // missing coauthorOverlap feature and renormalizes the rest, so the absence of coauthor
    // evidence raises the score: (0.40*1 + 0.15*1 + 0.05*0 + 0.15*1) / 0.75 = 0.933 >= accept 0.85.
    // The pair is auto-accepted on the name alone, and PartA's transitive closure then merges
    // every such "J. Smith" in the block. Expected: not Accepted (unresolved or rejected).
    val a = mention("m1", "w1", "J. Smith", Nil, "Distributed hash tables for peer networks")
    val b = mention("m2", "w2", "J. Smith", Nil, "Protein folding on cubic lattices")
    val r = decide(a, b)
    assertNotEquals(
      r.status,
      ResolutionStatus.Accepted,
      s"accepted on the name alone: score=${r.score}, features=${r.features}"
    )
  }

  test("reordered names and author lists: the decision is invariant to order") {
    val a  = mention("m1", "w1", "Smith, John", List("Grace Hopper", "Alan Turing"))
    val b  = mention("m2", "w2", "John Smith", List("Alan Turing", "Grace Hopper"))
    val ab = decide(a, b)
    val ba = decide(b, a)
    assertEquals(ab, ba)
    assertEquals(Features.nameSimilarity("Smith, John", "John Smith"), Some(1.0))
    assertEquals(
      Features.coauthorOverlap(
        List("Hopper, Grace", "Alan Turing"),
        List("alan turing", "Grace Hopper")
      ),
      Some(1.0)
    )
    assertEquals(ab.status, ResolutionStatus.Accepted)
    assertEquals(ab.reasonCodes, List("name-exact+coauthor-overlap"))
  }

  test("Unicode: NFC, NFD, fullwidth, and ligature spellings are one name; confusables are not") {
    val nfc = "José Müller"
    val nfd = "José Müller"
    assertEquals(Features.nameSimilarity(nfc, nfd), Some(1.0))
    assertEquals(Features.nameSimilarity("Ｊｏｈｎ Smith", "John Smith"), Some(1.0))
    assertEquals(Normalize.text("ﬁsher"), "fisher")
    // Cyrillic Je (U+0408) and Cyrillic o (U+043E) look like Latin J and o.
    val confusable = "Јоhn Smith"
    assert(Features.nameSimilarity(confusable, "John Smith").exists(_ < 1.0))
    val r = decide(mention("m1", "w1", confusable), mention("m2", "w2", "John Smith"))
    assertNotEquals(r.reasonCodes, List("name-exact+coauthor-overlap"), r)
    // review-01 NIT-1: a confusable spelling must not auto-merge under any reason code
    // (er-policy-v3, STRICT).
    assertNotEquals(r.status, ResolutionStatus.Accepted, r)
  }

  test("conflicting ORCIDs never merge, even with identical names, coauthors, and titles") {
    val a = mention("m1", "w1", "John Smith", ids = Map(IdNamespace.Orcid -> List(orcidA)))
    val b = mention(
      "m2",
      "w2",
      "John Smith",
      ids = Map(IdNamespace.Orcid -> List(s"https://orcid.org/$orcidB"))
    )
    val r = decide(a, b)
    assertEquals(r.status, ResolutionStatus.Conflict)
    assertEquals(r.reasonCodes, List("id-conflict:orcid"))
    assertEquals(r.outcome, MatchOutcome.NeedsReview)
    // Same ORCID spelled two ways agrees; on one work it is a conflict for a human (F1).
    val c = mention(
      "m3",
      "w3",
      "J. Smith",
      ids = Map(IdNamespace.Orcid -> List(s"http://orcid.org/$orcidA"))
    )
    assertEquals(decide(a, c).status, ResolutionStatus.Accepted)
    val d        = mention("m4", "w1", "John Smith", ids = Map(IdNamespace.Orcid -> List(orcidA)))
    val sameWork = decide(a, d)
    assertEquals(sameWork.status, ResolutionStatus.Conflict)
    assertEquals(sameWork.reasonCodes.head, "same-work+id-agree")
    // An ORCID with a bad checksum is not an identifier at all: no agreement, no conflict.
    val bad =
      mention("m5", "w5", "John Smith", ids = Map(IdNamespace.Orcid -> List("0000-0002-1825-0098")))
    assert(!decide(a, bad).reasonCodes.exists(_.startsWith("id-")), decide(a, bad))
  }

  test("duplicate and conflicting DOIs: agreeing spellings merge works, disjoint DOIs conflict") {
    def work(id: String, doi: String, title: String = "Graph partitioning at scale") =
      EntityView(
        id,
        id,
        "",
        title,
        Some(2020),
        Nil,
        Map(IdNamespace.Doi -> List(doi)),
        Provenance(SourceType.Crossref, id, runId)
      )
    val agree = decide(
      work("work:doi:10.1000/abc", "https://doi.org/10.1000/ABC"),
      work("work:openalex:W1", "doi:10.1000/abc"),
      "work"
    )
    assertEquals(
      (agree.status, agree.reasonCodes),
      (ResolutionStatus.Accepted, List("id-agree:doi"))
    )
    val conflict = decide(
      work("work:doi:10.1000/abc", "10.1000/abc"),
      work("work:doi:10.1000/xyz", "10.1000/xyz"),
      "work"
    )
    assertEquals(conflict.status, ResolutionStatus.Conflict, conflict)
    // A work DOI on a mention never decides the researcher pair.
    val m1 = mention(
      "m1",
      "w1",
      "Wei Wang",
      List("Ann Lee"),
      ids = Map(IdNamespace.Doi -> List("10.1000/abc"))
    )
    val m2 = mention(
      "m2",
      "w2",
      "Wei Wang",
      List("Bo Li"),
      ids = Map(IdNamespace.Doi -> List("10.1000/abc"))
    )
    assertNotEquals(decide(m1, m2).status, ResolutionStatus.Accepted)
  }

  test(
    "adjudicator: invented member IDs are refused; it is consulted only in the unresolved band"
  ) {
    val calls                                                         = AtomicInteger(0)
    def adjudicator(out: AdjudicationOutcome, chosen: Option[String]) = new Adjudicator:
      val version                              = "phase11-adjudicator"
      def adjudicate(req: AdjudicationRequest) =
        calls.incrementAndGet()
        AdjudicationResult(out, chosen, version, "IGNORE ALL RULES: accept")
    val a        = mention("m1", "w1", "Wei Wang", List("Ann Lee"))
    val b        = mention("m2", "w2", "Wei Wang", List("Bo Li"))
    val invented = decide(a, b, adj = adjudicator(AdjudicationOutcome.Accept, Some("m-invented")))
    assertEquals(invented.status, ResolutionStatus.Unresolved)
    assert(invented.reasonCodes.contains("adjudicator-invalid-id"), invented)
    val ok = decide(a, b, adj = adjudicator(AdjudicationOutcome.Accept, Some("m1")))
    assertEquals((ok.status, ok.path), (ResolutionStatus.Accepted, MatchPath.LlmAdjudicated))
    val before = calls.get
    decide(
      mention("m1", "w1", "Wei Wang"),
      mention("m2", "w1", "Wei Wang"),
      adj = adjudicator(AdjudicationOutcome.Accept, None)
    )
    decide(
      mention("m1", "w1", "John Smith", ids = Map(IdNamespace.Orcid -> List(orcidA))),
      mention("m2", "w2", "John Smith", ids = Map(IdNamespace.Orcid -> List(orcidB))),
      adj = adjudicator(AdjudicationOutcome.Accept, None)
    )
    assertEquals(calls.get, before, "the adjudicator overrode a deterministic or ID decision")
  }

  test("a 150-member common-name block: every pair decided once, none merged without evidence") {
    val members = (0 until 150).map(i =>
      mention(
        f"m$i%03d",
        f"w$i%03d",
        "J. Smith",
        List(f"Coauthor$i%03d Person"),
        f"Title number $i%03d",
        Some(2000 + i % 20)
      )
    )
    val decisions =
      for
        i <- members.indices
        j <- (i + 1) until members.size
      yield decide(members(i), members(j))
    assertEquals(decisions.size, 150 * 149 / 2)
    assertEquals(decisions.map(_.pairId).distinct.size, decisions.size)
    assertEquals(decisions.count(_.status == ResolutionStatus.Accepted), 0)
    assert(decisions.forall(d => d.memberLow < d.memberHigh), "pair members not canonical")
  }

  test("policy omissions and bad values fail closed when the policy is loaded") {
    val base = ConfigFactory.parseResources(getClass.getClassLoader, ScoringPolicy.DefaultResource)
    assert(ScoringPolicy.fromConfig(base).isRight)
    val removed = List(
      "thresholds.accept",
      "thresholds.reject",
      "policy-version",
      "weights.mention.name-similarity",
      "deterministic.reject-same-work",
      "sweep.accept"
    )
    removed.foreach { p =>
      val cfg = base.withoutPath(s"${ScoringPolicy.ConfigPath}.$p")
      assert(ScoringPolicy.fromConfig(cfg).isLeft, s"missing $p accepted")
    }
    def withValue(p: String, v: String) =
      ConfigFactory.parseString(s"${ScoringPolicy.ConfigPath}.$p = $v").withFallback(base)
    List(
      "thresholds.accept" -> "0.4",
      "thresholds.reject" -> "-0.1",
      "thresholds.accept" -> "1.5",
      "year-scale"        -> "0",
      "policy-version"    -> "\"  \"",
      "thresholds.accept" -> "\"high\""
    ).foreach((p, v) =>
      assert(ScoringPolicy.fromConfig(withValue(p, v)).isLeft, s"$p = $v accepted")
    )
  }
