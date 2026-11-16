package edu.uic.cs441.scholarchain.tests.adversarial

import edu.uic.cs441.scholarchain.services.security.ApiKeys
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.tests.support.ApiKit.*

/**
 * Log redaction: structured logs and metrics never carry credentials, key digests, idempotency
 * keys, questions, request bodies, exception messages, or storage error text.
 */
class LoggingRedactionSuite extends munit.FunSuite:

  private val wrongToken = "phase11-wrong-token-SECRET-0123456789abcdef"
  private val idemKey    = "idem-SECRET-KEY-0123"
  private val marker     = "Qzxmarker"

  private def assertWellFormed(lines: List[String])(using munit.Location): List[ujson.Value] =
    assert(lines.nonEmpty)
    lines.map { l =>
      val v = ujson.read(l)
      assert(Set("ts", "level", "event").subsetOf(v.obj.keySet), l)
      v
    }

  test("no credential, digest, idempotency key, question, or body text reaches the log") {
    withEnv(env()) { e =>
      e.post(Coauthors, Map("Idempotency-Key" -> idemKey))
      e.post(Coauthors, Map("Idempotency-Key" -> idemKey))
      e.post(Coauthors, token = Some(wrongToken))
      e.post(Coauthors, token = Some(BobToken))
      e.post(s"""{"question": "Who are the coauthors of $marker Nobody?"}""")
      e.post(s"""{"question": "$marker""")
      e.post(s"""{"question": "x", "$marker": 1}""")
      e.call(
        "GET",
        "/v1/jobs/job-" + "0" * 32,
        headers = Map("X-Request-Id" -> s"$marker\ninjected")
      )
      e.get("/metrics")
      val text      = e.logs.lines.mkString("\n")
      val forbidden = List(
        AliceToken,
        BobToken,
        wrongToken,
        ApiKeys.digest(AliceToken),
        ApiKeys.digest(BobToken),
        "Bearer",
        idemKey,
        marker,
        "Alice Adams",
        "coauthors of"
      )
      forbidden.foreach(f => assert(!text.contains(f), s"log contains '$f'"))
      val parsed = assertWellFormed(e.logs.lines)
      val reqs   = parsed.filter(_("event").str == "http.request")
      assertEquals(reqs.size, 9)
      assert(reqs.forall(r => r("requestId").str.matches("req-[0-9a-f]{24}")), reqs)
      val unauth = reqs.filter(r => r("status").num.toInt == 401)
      assertEquals(unauth.map(_("principal")), List(ujson.Null))
      assert(reqs.filter(_("status").num.toInt == 200).forall(r => r("principal").strOpt.isDefined))
      // Metrics labels are route templates and bounded codes, never raw IDs or credentials.
      val metrics = e.app.metrics.render
      assert(!metrics.contains("job-" + "0" * 32), metrics)
      forbidden.foreach(f => assert(!metrics.contains(f), s"metrics contain '$f'"))
    }
  }

  test("an unexpected exception logs its class only; the response carries the trace ID only") {
    withEnv(env(wrap = _ => ThrowingWorkflow())) { e =>
      val r = e.post(Coauthors)
      assertEquals(r.status, 500)
      val trace = json(r)("traceId").str
      assert(!r.body.contains("phase11-secret-detail"), r.body)
      val text = e.logs.lines.mkString("\n")
      assert(!text.contains("phase11-secret-detail"), text)
      assert(!text.contains("boom"), text)
      val failed = assertWellFormed(e.logs.lines).filter(_("event").str == "workflow.failed")
      assertEquals(failed.map(_("exception").str), List("java.lang.IllegalStateException"))
      assertEquals(failed.map(_("traceId").str), List(trace))
    }
  }

  test("storage error text stays out of logs, readiness, and responses") {
    withEnv(env()) { e =>
      val secret = "arn:aws:dynamodb:us-east-1:123456789012:table/SECRET-TABLE AccessDenied"
      e.fixture.keyValue.failNext(StorageError.Failed("dynamodb:GetItem", secret))
      val ready = e.get("/readyz", None)
      assertEquals(ready.status, 503)
      assert(!ready.body.contains("SECRET-TABLE") && !ready.body.contains("arn:"), ready.body)
      e.fixture.graph.failNext(List.fill(3)(StorageError.Unavailable("neptune", secret))*)
      val q = e.post(Coauthors)
      assertEquals(q.status, 502)
      assert(!q.body.contains("SECRET-TABLE"), q.body)
      val text = e.logs.lines.mkString("\n")
      assert(!text.contains("SECRET-TABLE") && !text.contains("123456789012"), text)
      val details = assertWellFormed(e.logs.lines)
        .filter(_("event").str == "readiness.check-failed").map(_("detail").str)
      assert(details.nonEmpty && details.forall(_.length <= 64), details)
    }
  }

  test("API key configuration errors name the entry and field, never the configured value") {
    val digest = ApiKeys.digest(AliceToken)
    val inputs = List(
      s"""[{"principal": "Bad Principal!", "sha256": "$digest", "scopes": []}]""",
      s"""[{"principal": "ok", "sha256": "${digest.toUpperCase}", "scopes": []}]""",
      s"""[{"principal": "ok", "sha256": "$digest", "scopes": ["root"]}]""",
      s"""[{"principal": "ok", "sha256": "$digest", "scopes": [], "token": "$AliceToken"}]""",
      s"""[{"principal": "a", "sha256": "$digest", "scopes": []}, {"principal": "b", "sha256": "$digest", "scopes": []}]"""
    )
    inputs.foreach { in =>
      ApiKeys.parse(in) match
        case Left(msg) =>
          assert(!msg.contains(digest) && !msg.toLowerCase.contains(digest), msg)
          assert(!msg.contains(AliceToken) && !msg.contains("Bad Principal"), msg)
        case Right(v) => fail(s"accepted: $v")
    }
  }
