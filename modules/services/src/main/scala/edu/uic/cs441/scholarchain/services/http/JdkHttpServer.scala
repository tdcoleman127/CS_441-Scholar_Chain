package edu.uic.cs441.scholarchain.services.http

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.io.{ByteArrayOutputStream, InputStream}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.{
  ArrayBlockingQueue,
  RejectedExecutionHandler,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Adapter from the JDK's built-in HTTP server (module `jdk.httpserver`, no third-party dependency)
 * to the transport-independent `HttpRequest => HttpResponse` router. The adapter only moves bytes:
 * it bounds the request body, builds the request, and writes the response.
 *
 * Bounds: the body is read up to `maxBodyBytes + 1` bytes (a declared or actual larger body gets a
 * 413 without being buffered). Handler threads are a fixed pool with a bounded queue. When the
 * queue is full, the exchange goes to a small dedicated shed pool that never routes: it answers
 * `GET /healthz` with 200 (liveness does not depend on request load) and everything else with 503
 * OVERLOADED, without reading the body. Router work never runs on the JDK dispatcher thread. Only
 * if the shed pool's own queue is also full (an extreme flood) does the dispatcher send that same
 * constant reply itself.
 */
final class JdkHttpServer private (
    server: HttpServer,
    executor: ThreadPoolExecutor,
    shed: ThreadPoolExecutor
):
  def port: Int       = server.getAddress.getPort
  def queueDepth: Int = executor.getQueue.size

  def stop(graceSeconds: Int): Unit =
    server.stop(graceSeconds)
    List(executor, shed).foreach { e =>
      e.shutdown()
      e.awaitTermination(graceSeconds.toLong, TimeUnit.SECONDS)
    }

object JdkHttpServer:
  val ShedThreads: Int = 2
  val ShedQueue: Int   = 1024

  private val TooLargeBody =
    """{"error":"PAYLOAD_TOO_LARGE","message":"Request body exceeds the limit.","traceId":"none","retryable":false}"""
  private val OverloadedBody =
    """{"error":"OVERLOADED","message":"The http worker pool is at capacity; retry later.","traceId":"none","retryable":true}"""
  private val HealthBody = """{"status":"ok","service":"scholarchain-services"}"""

  /** True on a thread that is running an overflow exchange (shed reply only, no routing). */
  private val shedding = ThreadLocal.withInitial[java.lang.Boolean](() => java.lang.Boolean.FALSE)

  private def runShed(r: Runnable): Unit =
    shedding.set(java.lang.Boolean.TRUE)
    try r.run()
    finally shedding.set(java.lang.Boolean.FALSE)

  /** Reads at most `limit + 1` bytes; `None` means the body is larger than `limit`. */
  private def readBounded(in: InputStream, limit: Int): Option[Array[Byte]] =
    val out   = ByteArrayOutputStream()
    val buf   = new Array[Byte](4096)
    var total = 0
    var n     = in.read(buf)
    while n >= 0 && total <= limit do
      out.write(buf, 0, n)
      total += n
      if total <= limit then n = in.read(buf)
    if total > limit then None else Some(out.toByteArray)

  private def send(ex: HttpExchange, r: HttpResponse): Unit =
    val bytes = r.body.getBytes(StandardCharsets.UTF_8)
    r.headers.foreach((k, v) => ex.getResponseHeaders.set(k, v))
    val noBody = ex.getRequestMethod.equalsIgnoreCase("HEAD") || r.status == 204
    ex.sendResponseHeaders(r.status, if noBody then -1L else bytes.length.toLong)
    if !noBody then ex.getResponseBody.write(bytes)

  /** The constant overflow reply. */
  private def shedReply(ex: HttpExchange): HttpResponse =
    val path = Option(ex.getRequestURI.getRawPath).getOrElse("")
    if ex.getRequestMethod.equalsIgnoreCase("GET") && path == "/healthz" then
      HttpResponse.json(200, HealthBody).withHeader("Cache-Control", "no-store")
    else
      HttpResponse
        .json(503, OverloadedBody)
        .withHeader("Retry-After", "1")
        .withHeader("Connection", "close")

  private def handle(ex: HttpExchange, router: HttpRequest => HttpResponse, maxBody: Int): Unit =
    try
      if shedding.get().booleanValue then send(ex, shedReply(ex))
      else
        val headers = ex.getRequestHeaders.asScala.toMap.collect {
          case (k, vs) if k != null && !vs.isEmpty => k.toLowerCase(Locale.ROOT) -> vs.get(0)
        }
        val declared = headers.get("content-length").flatMap(_.trim.toLongOption)
        val body     =
          if declared.exists(_ > maxBody) then None
          else readBounded(ex.getRequestBody, maxBody)
        body match
          case None =>
            send(ex, HttpResponse.json(413, TooLargeBody).withHeader("Connection", "close"))
          case Some(bytes) =>
            val uri = ex.getRequestURI
            val req = HttpRequest(
              ex.getRequestMethod.toUpperCase(Locale.ROOT),
              Option(uri.getRawPath).getOrElse("/"),
              Option(uri.getRawQuery).getOrElse(""),
              headers,
              bytes
            )
            send(ex, router(req))
    catch case NonFatal(_) => () // the client went away mid-exchange; nothing more to send
    finally ex.close()

  private def daemons(prefix: String): ThreadFactory =
    val counter = AtomicInteger(0)
    (r: Runnable) =>
      val t = Thread(r, s"$prefix-${counter.incrementAndGet()}")
      t.setDaemon(true)
      t

  /**
   * Starts the server. `sun.net.httpserver.maxReqTime` and `maxRspTime` (seconds) bound slow
   * clients; they are read once by the JDK, so they are set here before the server is created.
   */
  def start(
      port: Int,
      threads: Int,
      queueCapacity: Int,
      maxBodyBytes: Int,
      maxResponseSeconds: Int,
      router: HttpRequest => HttpResponse
  ): JdkHttpServer =
    System.setProperty("sun.net.httpserver.maxReqTime", "30")
    System.setProperty("sun.net.httpserver.maxRspTime", maxResponseSeconds.toString)
    val lastResort: RejectedExecutionHandler = (r: Runnable, _: ThreadPoolExecutor) => r.run()
    val shed                                 = ThreadPoolExecutor(
      ShedThreads,
      ShedThreads,
      0L,
      TimeUnit.MILLISECONDS,
      ArrayBlockingQueue[Runnable](ShedQueue),
      daemons("scholarchain-http-shed"),
      lastResort
    )
    val overflow: RejectedExecutionHandler =
      (r: Runnable, _: ThreadPoolExecutor) => shed.execute(() => runShed(r))
    val executor = ThreadPoolExecutor(
      threads,
      threads,
      0L,
      TimeUnit.MILLISECONDS,
      ArrayBlockingQueue[Runnable](queueCapacity),
      daemons("scholarchain-http"),
      overflow
    )
    val server = HttpServer.create(InetSocketAddress(port), 128)
    server.createContext("/", (ex: HttpExchange) => handle(ex, router, maxBodyBytes))
    server.setExecutor(executor)
    server.start()
    new JdkHttpServer(server, executor, shed)
