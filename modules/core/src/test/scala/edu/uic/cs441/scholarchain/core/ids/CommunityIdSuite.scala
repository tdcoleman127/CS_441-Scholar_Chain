package edu.uic.cs441.scholarchain.core.ids

import edu.uic.cs441.scholarchain.core.Fixtures
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError
import edu.uic.cs441.scholarchain.core.validation.Validators.given

/**
 * Contract for the coauthorship community ID (`cocomm:v1:<hex>`, WO-08x, closes R-08-3 in core).
 * The golden value below was computed outside Scala from the documented formula. A failing golden
 * test means the formula changed: that is a schema-version change and needs human approval.
 */
class CommunityIdSuite extends munit.FunSuite:
  import Fixtures.*

  private val alg = "label-propagation-v1"
  private val r1  = "researcher:orcid:0000-0002-1825-0097"
  private val r2  = "researcher:orcid:0000-0001-5109-3700"

  private def ok(e: Either[ValidationError, String]): String =
    e.fold(err => fail(s"unexpected rejection: $err"), identity)

  test("golden: the formula is frozen") {
    // H("scholarchain:coauthor-community:v1", alg, "2020", "2024", "2", sorted members...)
    assertEquals(
      StableIds.communityId(alg, YearWindow(2020, 2024), List(r1, r2)),
      Right("cocomm:v1:7411f02dc385490f0494033054c5f52aad9405fb8dcc46cbaa3a03ebad754431")
    )
  }

  test("formula: length-prefixed fields with a sorted, distinct member list") {
    val members  = List(researcherB, researcherA)
    val expected = "cocomm:v1:" + CanonicalHash.hashFields(
      Seq("scholarchain:coauthor-community:v1", alg, "2020", "2024") ++
        CanonicalHash.list(members.distinct.sorted)
    )
    assertEquals(StableIds.communityId(alg, window, members), Right(expected))
  }

  test("member order and repeated members do not change the ID") {
    val base = ok(StableIds.communityId(alg, window, List(researcherA, researcherB, r2)))
    assertEquals(
      StableIds.communityId(alg, window, List(r2, researcherB, researcherA, r2, researcherA)),
      Right(base)
    )
  }

  test("membership, window, and algorithm version each determine identity") {
    val base     = ok(StableIds.communityId(alg, window, List(researcherA, researcherB)))
    val variants = List(
      StableIds.communityId(alg, window, List(researcherA)),
      StableIds.communityId(alg, window, List(researcherA, researcherB, r2)),
      StableIds.communityId(alg, YearWindow(2020, 2025), List(researcherA, researcherB)),
      StableIds.communityId(alg, YearWindow(2021, 2024), List(researcherA, researcherB)),
      StableIds.communityId("label-propagation-v2", window, List(researcherA, researcherB))
    ).map(ok)
    variants.foreach(v => assertNotEquals(v, base))
    assertEquals(variants.distinct.size, variants.size)
  }

  test("a singleton community is allowed") {
    assert(StableIds.communityId(alg, window, List(researcherA)).isRight)
  }

  test("round-trip: a minted ID is a well-formed Community ID, and only that kind") {
    val id = ok(StableIds.communityId(alg, window, List(researcherA, researcherB)))
    assert(id.startsWith("cocomm:v1:"))
    assert(IdFormat.isValid(IdKind.Community, id))
    assert(IdFormat.isAnyKnown(id))
    IdKind.values.filterNot(_ == IdKind.Community).foreach { k =>
      assert(!IdFormat.isValid(k, id), s"$k accepted $id")
    }
    assertEquals(IdKind.Community.prefix, "cocomm")
    assert(!IdKind.Community.isNamespaced)
  }

  test("constructor rejects blank version, inverted window, empty or malformed membership") {
    assertEquals(
      StableIds.communityId("  ", window, List(researcherA)),
      Left(ValidationError.InvalidField("algorithmVersion", "must not be blank"))
    )
    assertEquals(
      StableIds.communityId(alg, YearWindow(2025, 2020), List(researcherA)),
      Left(ValidationError.InvalidYearWindow("window", 2025, 2020))
    )
    assertEquals(
      StableIds.communityId(alg, window, Nil),
      Left(ValidationError.InvalidField("memberIds", "must not be empty"))
    )
    assertEquals(
      StableIds.communityId(alg, window, List(researcherA, "Josiah Carberry")),
      Left(ValidationError.MalformedId("memberIds[1]", "researcher", "Josiah Carberry"))
    )
    assert(StableIds.communityId(alg, window, List(workId)).isLeft)
    assert(StableIds.communityId(alg, window, List("researcher:orcid:0000-0002-1825-0098")).isLeft)
  }

  test("malformed community IDs are rejected") {
    val hex = "a" * 64
    val bad = List(
      s"cocomm:v2:$hex",
      s"cocomm:sc1:$hex",
      s"cocomm:v1:${"A" * 64}",
      s"cocomm:v1:${"a" * 63}",
      s"cocomm:v1:$hex:extra",
      s"cocomm:v1:",
      s"COCOMM:v1:$hex",
      s"coedge:v1:$hex",
      "cocomm",
      ""
    )
    bad.foreach(v => assert(!IdFormat.isValid(IdKind.Community, v), s"accepted '$v'"))
  }

  test("the Phase 08 rag-local community:v1 shape is not a core ID (no collision with rag)") {
    val ragLocal = "community:v1:" + CanonicalHash.hashFields(
      Seq("scholarchain:community:v1", alg, "2020", "2024", "1", researcherA)
    )
    assert(!IdFormat.isAnyKnown(ragLocal))
    assert(!IdFormat.isValid(IdKind.Community, ragLocal))
  }

  test("serialization: evidence about a community round-trips and validates") {
    val communityId = ok(StableIds.communityId(alg, window, List(researcherA, researcherB)))
    val hash        = CanonicalHash.contentHash("community summary text")
    val p           = prov(SourceType.ScholarChainPipeline, "communities/0001")
    val item        = EvidenceItem(
      schemaVersion = 1,
      evidenceId = StableIds.evidenceId(
        EvidenceType.SourceRecord,
        communityId,
        hash,
        p.sourceDataset,
        p.sourceRecordId
      ),
      evidenceType = EvidenceType.SourceRecord,
      subjectId = communityId,
      contentHash = hash,
      excerpt = None,
      provenance = p
    )
    val json = Wire.encodeValidated(item)
    assert(json.isRight, json.toString)
    assertEquals(Wire.decode[EvidenceItem](json.toOption.get), Right(item))
    assert(ujson.read(json.toOption.get)("subjectId").str.startsWith("cocomm:v1:"))
  }
