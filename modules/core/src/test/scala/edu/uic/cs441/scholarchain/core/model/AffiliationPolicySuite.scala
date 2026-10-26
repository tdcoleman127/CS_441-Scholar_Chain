package edu.uic.cs441.scholarchain.core.model

import edu.uic.cs441.scholarchain.core.{Fixtures, Golden, GoldenCatalog}
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.validation.{ValidationError, Validator}
import edu.uic.cs441.scholarchain.core.validation.ValidationError.InvalidField
import edu.uic.cs441.scholarchain.core.validation.Validators.given

/**
 * Human decision (phase 03): affiliations reach runtime at institution level only. Raw affiliation
 * text may exist in batch records (AuthorMention.affiliationText) but in no other shared record,
 * and runtime affiliation facts are resolved (ROR or OpenAlex) institution links with years.
 */
class AffiliationPolicySuite extends munit.FunSuite:
  import Fixtures.*

  private def errors[A: Validator](a: A): List[ValidationError] = summon[Validator[A]].validate(a)

  private def keys(v: ujson.Value): Set[String] = v match
    case o: ujson.Obj => o.value.keySet.toSet ++ o.value.values.flatMap(keys)
    case a: ujson.Arr => a.value.flatMap(keys).toSet
    case _            => Set.empty

  private def wireKeys[A](g: Golden[A]): Set[String] =
    given upickle.default.ReadWriter[A] = g.rw
    keys(Wire.toJson(g.value))

  private def isAffiliationName(name: String): Boolean =
    name.toLowerCase(java.util.Locale.ROOT).contains("affiliation")

  private val sc1Institution =
    StableIds.fallbackNamespacedId(IdKind.Institution, SourceType.OpenAlex, "Example University")

  private val openAlexInstitution = "institution:openalex:I9990000001"

  private def affiliatedEdge(toId: String, window: Option[YearWindow]): GraphEdge =
    val draft = graphEdge.copy(
      edgeType = GraphEdgeType.AffiliatedWith,
      fromId = researcherA,
      toId = toId,
      window = window,
      weight = None
    )
    draft.copy(edgeId = StableIds.graphEdgeId(draft))

  test("only AuthorMention carries a raw-affiliation field on the wire") {
    val carriers = GoldenCatalog.all.filter(g => wireKeys(g).exists(isAffiliationName)).map(_.name)
    assertEquals(carriers, List("author-mention"))
    assertEquals(
      Fixtures.mention.productElementNames.filter(isAffiliationName).toList,
      List("affiliationText")
    )
  }

  test("runtime-facing records declare no affiliation field") {
    val runtime: List[Product] = List(
      graphNode,
      graphEdge,
      sourceEvidence,
      bundle,
      queryPlan,
      claim,
      answer,
      researcher,
      institution,
      coauthorEdge,
      metrics
    )
    runtime.foreach { r =>
      assertEquals(r.productElementNames.filter(isAffiliationName).toList, Nil, r.productPrefix)
    }
  }

  test("resolved institution IDs are ROR or OpenAlex, never the sc1 fallback") {
    assert(IdFormat.isResolvedInstitutionId(institutionId))
    assert(IdFormat.isResolvedInstitutionId(openAlexInstitution))
    assert(IdFormat.isValid(IdKind.Institution, sc1Institution))
    assert(!IdFormat.isResolvedInstitutionId(sc1Institution))
    assert(!IdFormat.isResolvedInstitutionId("Example University"))
    assert(!IdFormat.isResolvedInstitutionId(null))
  }

  test("an AFFILIATED_WITH edge to a resolved institution with years is valid") {
    assertEquals(errors(affiliatedEdge(institutionId, Some(window))), Nil)
    assertEquals(errors(affiliatedEdge(openAlexInstitution, Some(YearWindow(2024, 2024)))), Nil)
  }

  test("an AFFILIATED_WITH edge to an unresolved (sc1) institution is rejected") {
    assert(
      errors(affiliatedEdge(sc1Institution, Some(window))).exists {
        case InvalidField("toId", _) => true
        case _                       => false
      }
    )
  }

  test("an AFFILIATED_WITH edge without years is rejected") {
    assert(
      errors(affiliatedEdge(institutionId, None)).exists {
        case InvalidField("window", _) => true
        case _                         => false
      }
    )
  }

  test("other edge types may omit the window") {
    val draft = graphEdge.copy(window = None)
    assertEquals(errors(draft.copy(edgeId = StableIds.graphEdgeId(draft))), Nil)
  }

  test("an Institution graph node must be a resolved institution") {
    val node = graphNode.copy(
      nodeId = institutionId,
      nodeType = GraphNodeType.Institution,
      label = "Sample University",
      properties = Nil
    )
    assertEquals(errors(node), Nil)
    assert(errors(node.copy(nodeId = sc1Institution)).exists {
      case InvalidField("nodeId", _) => true
      case _                         => false
    })
  }

  test("graph node properties may not carry affiliations, in any case") {
    List("affiliation", "rawAffiliation", "AFFILIATION_TEXT").foreach { name =>
      val node = graphNode.copy(properties = graphNode.properties :+ Property(name, "Example U"))
      assert(
        errors(node).exists {
          case InvalidField("properties[1].name", _) => true
          case _                                     => false
        },
        name
      )
    }
  }

  test("a canonical researcher may list only resolved institutions") {
    assertEquals(errors(researcher), Nil)
    assert(
      errors(researcher.copy(institutionIds = List(institutionId, sc1Institution))).exists {
        case InvalidField("institutionIds[1]", _) => true
        case _                                    => false
      }
    )
    assert(errors(researcher.copy(institutionIds = List("Example University"))).nonEmpty)
  }
