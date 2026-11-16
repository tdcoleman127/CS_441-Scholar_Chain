package edu.uic.cs441.scholarchain.er

import java.nio.file.Path

/**
 * Evaluator on a tiny SYNTHETIC fixture (src/test/resources/er/labeled-fixture.jsonl). The fixture
 * tests the arithmetic only; its labels are not gold labels and its metrics are not results.
 */
class EvaluatorSuite extends munit.FunSuite:

  val policy =
    ScoringPolicy.loadDefault().fold(e => fail(e), identity).copy(accept = 0.85, reject = 0.55)
  val fixture = Path.of(getClass.getClassLoader.getResource("er/labeled-fixture.jsonl").toURI)

  test("evaluator: confusion by path, metrics, coverage, unresolved rate, and threshold sweep") {
    val load = Evaluator.load(fixture)
    // 12 lines: 9 traceable labels, 1 unlabeled, 1 label without labeler (untraceable), 1 unsure.
    assertEquals(
      (load.labeled.size, load.skippedUnlabeled, load.skippedUnsure, load.rejectedLines),
      (9, 2, 1, Nil)
    )
    val e    = Evaluator.evaluate(policy, load)
    val path = e.byPath.toMap
    assertEquals(path(MatchPath.ExactId), Confusion(1, 0, 0, 0, 0, 1))
    assertEquals(path(MatchPath.Deterministic), Confusion(1, 0, 1, 0, 0, 0))
    val prob = path(MatchPath.Probabilistic)
    assertEquals(prob, Confusion(1, 1, 1, 1, 1, 0))
    assertEquals(prob.precision, Some(0.5))
    assertEquals(prob.recall, Some(1.0 / 3))
    assertEqualsDouble(prob.f1.get, 0.4, 1e-9)
    assertEquals(prob.coverage, Some(0.8))
    assertEquals(path(MatchPath.LlmAdjudicated), Confusion.Zero)
    assertEquals(e.overall, Confusion(3, 1, 2, 1, 1, 1))
    assertEquals(e.overall.unresolvedRate, Some(2.0 / 9))
    val sweep = e.sweepAccept.map(r => r.accept -> r.overall).toMap
    assertEquals((sweep(0.95).tp, sweep(0.95).fp), (2, 0)) // 0.90 and 0.86 move to unresolved
    // er-policy-v4: the fixture lines predate v3 (no `corroborated`), so the unresolved 0.70 pair
    // stays unresolved at 0.60: a missing corroboration never moves an item to accepted (v3: (4, 1)).
    assertEquals((sweep(0.60).tp, sweep(0.60).fp), (3, 1))
    val rej = e.sweepReject.map(r => r.reject -> r.overall).toMap
    assertEquals((rej(0.30).fn, rej(0.30).undecidedPos), (0, 2)) // 0.40 moves to unresolved
    val (lo, hi) = Evaluator.wilson(1, 2).get
    assert(lo < 0.5 && hi > 0.5)
    val report = Evaluator.report(e)
    assert(report.contains("probabilistic"))
    println(report) // the evaluator run on the only labeled file in the repository (synthetic)
  }
