package edu.uic.cs441.scholarchain.core.validation

import edu.uic.cs441.scholarchain.core.Fixtures
import edu.uic.cs441.scholarchain.core.ids.StableIds
import edu.uic.cs441.scholarchain.core.json.{Wire, WireError}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError.*
import edu.uic.cs441.scholarchain.core.validation.Validators.given

class ValidationSuite extends munit.FunSuite:
  import Fixtures.*

  private def errors[A: Validator](a: A): List[ValidationError] = summon[Validator[A]].validate(a)

  private def assertRejects[A: Validator](a: A)(pf: PartialFunction[ValidationError, Boolean])(using
      munit.Location
  ): Unit =
    val errs = errors(a)
    assert(
      errs.exists(e => pf.applyOrElse(e, (_: ValidationError) => false)),
      s"expected rejection, got $errs"
    )

  // ---- Missing provenance -------------------------------------------------------------------

  test("wire: a record without provenance is rejected") {
    val j = ujson.read(Wire.encode(work))
    j.obj.remove("provenance")
    assertEquals(
      Wire.decode[WorkRecord](ujson.write(j)),
      Left(WireError.Invalid(List(MissingProvenance("provenance", "absent or null"))))
    )
  }

  test("wire: a null provenance is rejected") {
    val j = ujson.read(Wire.encode(coauthorEdge))
    j("provenance") = ujson.Null
    assert(Wire.decode[CoauthorEdge](ujson.write(j)).isLeft)
  }

  test("wire: provenance missing a field is rejected") {
    val j = ujson.read(Wire.encode(topic))
    j("provenance").obj.remove("pipelineRunId")
    assert(Wire.decode[Topic](ujson.write(j)).isLeft)
  }

  test("blank source record ID, blank run ID, or malformed run ID is rejected") {
    assertRejects(work.copy(provenance = work.provenance.copy(sourceRecordId = " "))) {
      case MissingProvenance("provenance.sourceRecordId", _) => true
    }
    assertRejects(work.copy(provenance = work.provenance.copy(pipelineRunId = ""))) {
      case MissingProvenance("provenance.pipelineRunId", _) => true
    }
    assertRejects(work.copy(provenance =
      work.provenance.copy(pipelineRunId = "run-2026-10-18T12:00")
    )) {
      case MalformedId("provenance.pipelineRunId", "run", _) => true
    }
  }

  test("programmatic null provenance is rejected, not thrown") {
    assertRejects(mention.copy(provenance = null)) { case MissingProvenance("provenance", _) =>
      true
    }
  }

  test("nested records need provenance too") {
    val bad = answer.copy(claims = answer.claims.map(_.copy(provenance = null)))
    assertRejects(bad) { case MissingProvenance("claims[0].provenance", _) => true }
  }

  // ---- Confidence ---------------------------------------------------------------------------

  test("confidence outside [0, 1] or NaN is rejected; the bounds are accepted") {
    List(-0.01, 1.0001, Double.NaN, Double.PositiveInfinity).foreach { c =>
      assertRejects(coauthorEdge.copy(confidence = c)) {
        case ConfidenceOutOfRange("confidence", _) => true
      }
      assertRejects(matchDecision.copy(confidence = c)) {
        case ConfidenceOutOfRange("confidence", _) => true
      }
      assertRejects(graphEdge.copy(confidence = c)) { case ConfidenceOutOfRange("confidence", _) =>
        true
      }
    }
    assertEquals(errors(coauthorEdge.copy(confidence = 0.0)), Nil)
    assertEquals(errors(coauthorEdge.copy(confidence = 1.0)), Nil)
  }

  test("claim confidence is checked at its nested path") {
    val bad = answer.copy(claims = answer.claims.map(_.copy(confidence = 2.0)))
    assertRejects(bad) { case ConfidenceOutOfRange("claims[0].confidence", 2.0) => true }
  }

  // ---- Schema version -----------------------------------------------------------------------

  test("unsupported schema versions are rejected programmatically") {
    List(0, 2, -1).foreach { v =>
      assertRejects(topic.copy(schemaVersion = v)) {
        case UnsupportedSchemaVersion("schemaVersion", `v`, List(1)) => true
      }
      assertRejects(manifest.copy(schemaVersion = v)) {
        case UnsupportedSchemaVersion("schemaVersion", `v`, _) => true
      }
    }
  }

  // ---- Malformed IDs and formula mismatches -------------------------------------------------

  test("malformed stable IDs are rejected") {
    assertRejects(work.copy(workId = "conf/kdd/CarberrySmith24")) {
      case MalformedId("workId", "work", _) => true
    }
    assertRejects(team.copy(researcherIds = List(researcherA, "jane smith"))) {
      case MalformedId("researcherIds[1]", "researcher", _) => true
    }
    assertRejects(graphNode.copy(nodeId = workId)) { case MalformedId("nodeId", "researcher", _) =>
      true
    }
    assertRejects(authorship.copy(mentionId = "mention:v1:xyz")) {
      case MalformedId("mentionId", "mention", _) => true
    }
    assertRejects(sourceEvidence.copy(contentHash = "abc")) {
      case MalformedId("contentHash", "sha256", _) => true
    }
  }

  test("an ID that does not match its formula is rejected") {
    assertRejects(coauthorEdge.copy(policyVersion = "graph-policy-v2")) {
      case IdMismatch("edgeId", _, _) => true
    }
    assertRejects(mention.copy(authorPosition = 3)) { case IdMismatch("mentionId", _, _) => true }
    assertRejects(graphEdge.copy(policyVersion = "other")) { case IdMismatch("edgeId", _, _) =>
      true
    }
    assertRejects(manifest.copy(attempt = 2)) { case IdMismatch("runId", _, _) => true }
    assertRejects(claim.copy(text = "changed")) { case IdMismatch("claimId", _, _) => true }
    assertRejects(queryPlan.copy(question = "changed?")) { case IdMismatch("planId", _, _) => true }
  }

  test("an external ID that is not normalized is rejected") {
    assertRejects(work.copy(externalIds =
      List(ExternalId(IdNamespace.Doi, "10.5555/SAMPLE.0001"))
    )) {
      case InvalidField("externalIds[0].value", _) => true
    }
  }

  // ---- Undirected pair ordering -------------------------------------------------------------

  test("an inverted coauthor pair is rejected") {
    assertRejects(pairCandidate.copy(researcherLow = researcherB, researcherHigh = researcherA)) {
      case InvalidPairOrdering("researcherLow", `researcherB`, `researcherA`) => true
    }
    assertRejects(coauthorEdge.copy(researcherLow = researcherB, researcherHigh = researcherA)) {
      case InvalidPairOrdering("researcherLow", _, _) => true
    }
  }

  test("a coauthor self-pair is rejected") {
    assertRejects(pairCandidate.copy(researcherHigh = researcherA)) {
      case SelfPair("researcherLow", `researcherA`) => true
    }
  }

  test("an undirected graph edge must store canonical endpoints; any self-loop is rejected") {
    val inverted = graphEdge.copy(fromId = graphEdge.toId, toId = graphEdge.fromId)
    assertRejects(inverted) { case InvalidPairOrdering("fromId", _, _) => true }
    assertRejects(graphEdge.copy(toId = graphEdge.fromId)) { case SelfPair("fromId", _) => true }
  }

  test("a directed graph edge may point from a larger to a smaller ID") {
    val (from, to) = (workId2, workId)
    assert(from > to)
    val cites = graphEdge.copy(
      edgeType = GraphEdgeType.Cites,
      fromId = from,
      toId = to,
      edgeId = StableIds.graphEdgeId(GraphEdgeType.Cites, from, to, graphPolicy, Some(window))
    )
    assertEquals(errors(cites), Nil)
  }

  test("a publication team with a duplicated researcher is rejected") {
    assertRejects(team.copy(researcherIds = List(researcherA, researcherA))) {
      case InvalidField("researcherIds", _) => true
    }
  }

  // ---- Year windows -------------------------------------------------------------------------

  test("a window whose start year exceeds its end year is rejected everywhere") {
    val w = YearWindow(2025, 2020)
    assertEquals(errors(w), List(InvalidYearWindow("", 2025, 2020)))
    assertRejects(coauthorEdge.copy(window = w)) { case InvalidYearWindow("window", 2025, 2020) =>
      true
    }
    assertRejects(metrics.copy(window = w)) { case InvalidYearWindow("window", 2025, 2020) => true }
    assertRejects(graphEdge.copy(window = Some(w))) {
      case InvalidYearWindow("window", 2025, 2020) => true
    }
    assertRejects(queryPlan.copy(window = Some(w))) {
      case InvalidYearWindow("window", 2025, 2020) => true
    }
    assertEquals(errors(YearWindow(2024, 2024)), Nil)
  }

  test("coauthor edge first and last years must be ordered and inside the window") {
    assertRejects(coauthorEdge.copy(firstYear = 2024, lastYear = 2022)) {
      case InvalidYearWindow("firstYear", 2024, 2022) => true
    }
    assertRejects(coauthorEdge.copy(firstYear = 2019)) { case InvalidField("firstYear", _) => true }
  }

  test("coauthor edge work count must equal its sorted, distinct supporting works") {
    assertRejects(coauthorEdge.copy(coauthoredWorkCount = 3)) {
      case InvalidField("coauthoredWorkCount", _) => true
    }
    assertRejects(coauthorEdge.copy(supportingWorkIds = coauthorEdge.supportingWorkIds.reverse)) {
      case InvalidField("supportingWorkIds", _) => true
    }
  }

  // ---- Evidence and claims ------------------------------------------------------------------

  test("a graph edge without evidence is rejected") {
    assertRejects(graphEdge.copy(evidenceIds = Nil)) { case InvalidField("evidenceIds", _) => true }
  }

  test("a claim without evidence must be marked unsupported") {
    val noEvidence = claim.copy(evidenceIds = Nil, claimId = StableIds.claimId(claim.text, Nil))
    assertRejects(noEvidence) { case InvalidField("verificationStatus", _) => true }
    assertEquals(errors(noEvidence.copy(verificationStatus = VerificationStatus.Unsupported)), Nil)
  }

  test("an answer may cite only evidence present in its bundle") {
    val bundleWithoutEdge = bundle.copy(
      items = List(sourceEvidence),
      bundleId = StableIds.evidenceBundleId(List(sourceEvidence.evidenceId))
    )
    val bad = answer.copy(
      evidence = bundleWithoutEdge,
      responseId =
        StableIds.answerId(answer.planId, bundleWithoutEdge.bundleId, answer.claims.map(_.claimId))
    )
    assertRejects(bad) { case InvalidField("claims[0].evidenceIds", _) => true }
  }

  test("an overall supported answer requires every claim to be supported") {
    val partial = claim.copy(verificationStatus = VerificationStatus.PartiallySupported)
    val bad     = answer.copy(
      claims = List(partial),
      responseId =
        StableIds.answerId(answer.planId, answer.evidence.bundleId, List(partial.claimId))
    )
    assertRejects(bad) { case InvalidField("overallStatus", _) => true }
  }

  // An answer with no claims and an empty evidence bundle, with a consistent responseId.
  private def claimlessAnswer(status: VerificationStatus): AnswerResponse =
    val emptyBundle = bundle.copy(items = Nil, bundleId = StableIds.evidenceBundleId(Nil))
    answer.copy(
      answerText = "A and B coauthored 12 papers",
      claims = Nil,
      evidence = emptyBundle,
      overallStatus = status,
      responseId = StableIds.answerId(answer.planId, emptyBundle.bundleId, Nil)
    )

  // Answer with the given claims over the fixture bundle, with a consistent responseId.
  private def answerWith(status: VerificationStatus, cs: List[AnswerClaim]): AnswerResponse =
    answer.copy(
      claims = cs,
      overallStatus = status,
      responseId = StableIds.answerId(answer.planId, answer.evidence.bundleId, cs.map(_.claimId))
    )

  test("an overall supported answer with no claims and no evidence is rejected (law 3)") {
    val bad = claimlessAnswer(VerificationStatus.Supported)
    assertRejects(bad) { case InvalidField("overallStatus", _) => true }
    assert(Wire.encodeValidated(bad).isLeft, "encodeValidated must refuse to emit it")
  }

  test("an overall partially-supported answer with no claims is rejected") {
    val bad = claimlessAnswer(VerificationStatus.PartiallySupported)
    assertRejects(bad) { case InvalidField("overallStatus", _) => true }
    assert(Wire.encodeValidated(bad).isLeft)
  }

  test("an overall contradicted answer with no claims is rejected") {
    assertRejects(claimlessAnswer(VerificationStatus.Contradicted)) {
      case InvalidField("overallStatus", _) => true
    }
  }

  test("an answer with no claims may be unsupported or not-verified") {
    assertEquals(errors(claimlessAnswer(VerificationStatus.Unsupported)), Nil)
    assertEquals(errors(claimlessAnswer(VerificationStatus.NotVerified)), Nil)
  }

  test("partially-supported requires at least one supported or partially-supported claim") {
    val unsupported = claim.copy(verificationStatus = VerificationStatus.Unsupported)
    val bad         = answerWith(VerificationStatus.PartiallySupported, List(unsupported))
    assertRejects(bad) { case InvalidField("overallStatus", _) => true }
    val mixed = List(claim, unsupported.copy(text = "An unverified side remark."))
      .map(c => c.copy(claimId = StableIds.claimId(c.text, c.evidenceIds)))
    assertEquals(errors(answerWith(VerificationStatus.PartiallySupported, mixed)), Nil)
  }

  test("contradicted requires at least one contradicted claim") {
    val bad = answerWith(VerificationStatus.Contradicted, List(claim))
    assertRejects(bad) { case InvalidField("overallStatus", _) => true }
    val contradicted = claim.copy(verificationStatus = VerificationStatus.Contradicted)
    assertEquals(errors(answerWith(VerificationStatus.Contradicted, List(contradicted))), Nil)
  }

  test("a match outcome needs a candidate researcher") {
    val bad = matchDecision.copy(
      candidateResearcherId = None,
      decisionId = StableIds.matchDecisionId(matchDecision.mentionId, None, erPolicy)
    )
    assertRejects(bad) { case InvalidField("candidateResearcherId", _) => true }
    assertEquals(errors(bad.copy(outcome = MatchOutcome.NonMatch)), Nil)
  }

  test("validation reports every violation, not just the first") {
    val bad =
      coauthorEdge.copy(schemaVersion = 9, confidence = 5.0, window = YearWindow(2030, 2000))
    val errs = errors(bad)
    assert(errs.exists(_.isInstanceOf[UnsupportedSchemaVersion]))
    assert(errs.exists(_.isInstanceOf[ConfidenceOutOfRange]))
    assert(errs.exists(_.isInstanceOf[InvalidYearWindow]))
    errs.foreach(e => assert(e.message.nonEmpty))
  }

  test("Validation.validate returns Right for a valid record and Left with errors otherwise") {
    assertEquals(Validation.validate(work), Right(work))
    assert(Validation.validate(work.copy(title = "")).isLeft)
  }
