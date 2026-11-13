package edu.uic.cs441.scholarchain.services

import edu.uic.cs441.scholarchain.services.TestKit.*
import edu.uic.cs441.scholarchain.services.http.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JRequest, HttpResponse as JResponse}
import java.time.Duration
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** End to end over a real socket: the JDK server adapter in front of the full composition. */
class HttpTransportSuite extends munit.FunSuite:

  private val client = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  private def withServer(body: Int => Unit): Unit =
    val e      = env(s = settings.copy(maxBodyBytes = 1024))
    val server = JdkHttpServer.start(0, 4, 16, 1024, 20, e.app.router.handle)
    try body(server.port)
    finally
      server.stop(0)
      e.app.close()

  private def get(port: Int, path: String): JRequest =
    JRequest.newBuilder(
      URI.create(s"http://127.0.0.1:$port$path")
    ).timeout(Duration.ofSeconds(10)).GET().build()

  test("a full handler queue sheds with 503 OVERLOADED off the dispatcher; /healthz stays 200") {
    val entered                             = CountDownLatch(1)
    val hold                                = CountDownLatch(1)
    val router: HttpRequest => HttpResponse = req =>
      if req.rawPath == "/block" then
        entered.countDown()
        hold.await(10, TimeUnit.SECONDS)
        HttpResponse.json(200, "{}")
      else HttpResponse.json(200, """{"routed":true}""")
    val server = JdkHttpServer.start(0, 1, 1, 1024, 20, router)
    try
      val running = client.sendAsync(get(server.port, "/block"), JResponse.BodyHandlers.ofString())
      assert(entered.await(5, TimeUnit.SECONDS), "the only handler thread is busy")
      val queued = client.sendAsync(get(server.port, "/block"), JResponse.BodyHandlers.ofString())
      val until  = System.nanoTime() + 5000L * 1000000L
      while server.queueDepth < 1 && System.nanoTime() < until do Thread.sleep(5)
      assertEquals(server.queueDepth, 1, "the handler queue is full")
      val shed = client.send(get(server.port, "/v1/anything"), JResponse.BodyHandlers.ofString())
      assertEquals(shed.statusCode, 503, shed.body)
      assertEquals(ujson.read(shed.body)("error").str, "OVERLOADED")
      assertEquals(shed.headers.firstValue("Retry-After").orElse(""), "1")
      assert(!shed.body.contains("routed"), "overflow is never routed")
      val health = client.send(get(server.port, "/healthz"), JResponse.BodyHandlers.ofString())
      assertEquals(health.statusCode, 200)
      assertEquals(ujson.read(health.body)("status").str, "ok")
      hold.countDown()
      assertEquals(running.get(10, TimeUnit.SECONDS).statusCode, 200)
      assertEquals(queued.get(10, TimeUnit.SECONDS).statusCode, 200)
    finally
      hold.countDown()
      server.stop(0)
  }

  test("a query over HTTP returns an answer and propagates the W3C trace ID") {
    withServer { port =>
      val trace = "0af7651916cd43dd8448eb211c80319c"
      val req   = JRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port/v1/query"))
        .header("Authorization", s"Bearer $AliceToken")
        .header("Content-Type", "application/json")
        .header("traceparent", s"00-$trace-b7ad6b7169203331-01")
        .timeout(Duration.ofSeconds(10))
        .POST(JRequest.BodyPublishers.ofString(CoauthorsOfAlice))
        .build()
      val r = client.send(req, JResponse.BodyHandlers.ofString())
      assertEquals(r.statusCode, 200, r.body)
      assertEquals(r.headers.firstValue("X-Trace-Id").orElse(""), trace)
      assert(r.headers.firstValue("Content-Type").orElse("").startsWith("application/json"))
      assertEquals(ujson.read(r.body)("status").str, "ANSWERED")
      val health = client.send(
        JRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/healthz")).GET().build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(health.statusCode, 200)
    }
  }

  test("an oversized body is refused with 413 before it is buffered") {
    withServer { port =>
      val req = JRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port/v1/query"))
        .header("Authorization", s"Bearer $AliceToken")
        .header("Content-Type", "application/json")
        .POST(JRequest.BodyPublishers.ofString("x" * 5000))
        .build()
      val r = client.send(req, JResponse.BodyHandlers.ofString())
      assertEquals(r.statusCode, 413)
      assertEquals(ujson.read(r.body)("error").str, "PAYLOAD_TOO_LARGE")
    }
  }

  test("path and query decoding is strict") {
    assertEquals(
      PathCodec.segments("/v1/graph/nodes/work%3Adoi%3A10.1%2Fx"),
      Right(List("v1", "graph", "nodes", "work:doi:10.1/x"))
    )
    assert(PathCodec.segments("/v1//jobs").isLeft)
    assert(PathCodec.segments("/v1/%zz").isLeft)
    assert(PathCodec.segments("/v1/%ff").isLeft)
    assertEquals(
      QueryString.parse("a=1&b=x+y&a=2"),
      Right(Map("a" -> List("1", "2"), "b" -> List("x y")))
    )
    assert(QueryString.parse(List.fill(40)("k=v").mkString("&")).isLeft)
  }
