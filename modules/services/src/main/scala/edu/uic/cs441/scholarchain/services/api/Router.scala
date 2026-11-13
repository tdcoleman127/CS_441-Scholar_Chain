package edu.uic.cs441.scholarchain.services.api

import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.services.app.*
import edu.uic.cs441.scholarchain.services.http.*
import edu.uic.cs441.scholarchain.services.ops.{Metrics, Readiness, StructuredLog}
import edu.uic.cs441.scholarchain.services.security.*
import java.security.SecureRandom
import scala.util.control.NonFatal
import upickle.default.{write, Writer}

/** Request and trace identifiers of one request. */
final class RequestContext(val requestId: String, val traceId: String):
  var principal: Option[String] = None

object RequestContext:
  private val random              = SecureRandom()
  private val Token               = """^[A-Za-z0-9._-]{1,64}$""".r
  private val AmznRoot            = """(?:^|;)\s*Root=(1-[0-9a-f]{8}-[0-9a-f]{24})\s*(?:;|$)""".r
  private val TraceParent         = """^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$""".r
  private def hex(n: Int): String =
    val b = new Array[Byte](n)
    random.nextBytes(b)
    b.map(x => f"${x & 0xff}%02x").mkString

  /**
   * Request ID: a well-formed `X-Request-Id`, else generated. Trace ID, first match wins: the ALB
   * `X-Amzn-Trace-Id` Root, a W3C `traceparent` trace ID, a well-formed `X-Trace-Id`, else
   * generated. Malformed incoming values are replaced, never echoed.
   */
  def from(req: HttpRequest): RequestContext =
    val requestId = req.header("x-request-id").filter(Token.matches).getOrElse(s"req-${hex(12)}")
    val traceId   = req
      .header("x-amzn-trace-id")
      .flatMap(h => AmznRoot.findFirstMatchIn(h).map(_.group(1)))
      .orElse(req.header("traceparent").collect {
        case TraceParent(t) if t != "0" * 32 => t
      })
      .orElse(req.header("x-trace-id").filter(Token.matches))
      .getOrElse(s"trace-${hex(16)}")
    RequestContext(requestId, traceId)

/**
 * The route table. Routes are thin: authenticate, check the scope, extract and validate path and
 * query parameters, call one application service, and render. No retrieval, analytics, or storage
 * logic lives here.
 */
final class Router(
    queries: QueryService,
    evidence: EvidenceService,
    graph: GraphService,
    readiness: Readiness,
    auth: ApiKeyAuthenticator,
    limiter: RateLimiter,
    metrics: Metrics,
    log: StructuredLog,
    maxBodyBytes: Int,
    metricsEnabled: Boolean,
    defaultWindow: YearWindow,
    nanoTime: () => Long = () => System.nanoTime()
):
  private type Handler = RequestContext => Either[ApiError, HttpResponse]
  final private case class Route(template: String, method: String, handler: Handler)

  private val IdempotencyKey = """^[A-Za-z0-9._:-]{1,128}$""".r

  private def json[A: Writer](status: Int, a: A): HttpResponse = HttpResponse.json(status, write(a))

  private def errorResponse(e: ApiError, traceId: String): HttpResponse =
    e.headers.foldLeft(json(e.status, e.body(traceId)))((r, h) => r.withHeader(h._1, h._2))

  private def authorize(req: HttpRequest, ctx: RequestContext, scope: Scope)
      : Either[ApiError, Principal] =
    auth.authenticate(req.header("authorization")).flatMap { p =>
      ctx.principal = Some(p.id)
      Either.cond(p.has(scope), p, ApiError.Forbidden(scope.wire))
    }

  private def checkId(name: String, id: String, ok: String => Boolean): Either[ApiError, String] =
    Either.cond(ok(id), id, ApiError.InvalidParameter(name, "malformed identifier"))

  private def noQuery(req: HttpRequest): Either[ApiError, Unit] =
    QueryString.parse(req.rawQuery) match
      case Left(r)                => Left(ApiError.InvalidParameter("query", r))
      case Right(m) if m.nonEmpty =>
        Left(ApiError.InvalidParameter(ApiError.bounded(m.keys.min), "unknown parameter"))
      case Right(_) => Right(())

  private def postQuery(req: HttpRequest)(ctx: RequestContext): Either[ApiError, HttpResponse] =
    for
      p <- authorize(req, ctx, Scope.QueryWrite)
      _ <- limiter.acquire(p.id)
      _ <- noQuery(req)
      _ <- Either.cond(
        req.header(
          "content-type"
        ).exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")),
        (),
        ApiError.UnsupportedMediaType("application/json")
      )
      _ <- Either.cond(req.body.length <= maxBodyBytes, (), ApiError.PayloadTooLarge(maxBodyBytes))
      key <- req.header("idempotency-key") match
        case None                                 => Right(None)
        case Some(k) if IdempotencyKey.matches(k) => Right(Some(k))
        case Some(_)                              =>
          Left(ApiError.InvalidHeader("Idempotency-Key", "must be 1..128 of [A-Za-z0-9._:-]"))
      text      <- Utf8.decode(req.body).left.map(ApiError.MalformedJson(_))
      q         <- QueryRequestDecoder.decode(text)
      submitted <- queries.submit(p, key, q, ctx.traceId)
    yield
      val (reply, replayed) = submitted
      val base              = reply match
        case QueryReply.Answer(b)   => json(200, b)
        case QueryReply.Accepted(b) => json(202, b).withHeader("Location", b.statusUrl)
      if replayed then base.withHeader("Idempotent-Replayed", "true") else base

  private def read[A: Writer](
      req: HttpRequest,
      scope: Scope
  )(body: Principal => Either[ApiError, A])
      : Handler =
    ctx =>
      for
        p <- authorize(req, ctx, scope)
        _ <- noQuery(req)
        a <- body(p)
      yield json(200, a)

  private def ready(ctx: RequestContext): Either[ApiError, HttpResponse] =
    val (ok, checks) = readiness.check()
    Right(
      json(
        if ok then 200 else 503,
        ReadinessView(if ok then "ready" else "not-ready", checks, ctx.traceId)
      )
    )

  private def route(req: HttpRequest, segments: List[String]): Option[Route] =
    segments match
      case List("healthz") =>
        Some(Route(
          "/healthz",
          "GET",
          _ => Right(json(200, HealthView("ok", "scholarchain-services")))
        ))
      case List("readyz")                    => Some(Route("/readyz", "GET", ready))
      case List("metrics") if metricsEnabled =>
        Some(Route(
          "/metrics",
          "GET",
          ctx =>
            authorize(req, ctx, Scope.OpsMetrics).map(_ => HttpResponse.text(200, metrics.render))
        ))
      case List("v1", "query") => Some(Route("/v1/query", "POST", postQuery(req)))
      case List("v1", "queries", qid, "evidence") =>
        Some(Route(
          "/v1/queries/{queryId}/evidence",
          "GET",
          read(req, Scope.EvidenceRead)(p =>
            checkId("queryId", qid, RestIds.isQueryId).flatMap(queries.queryEvidence(p, _))
          )
        ))
      case List("v1", "jobs", jid) =>
        Some(Route(
          "/v1/jobs/{jobId}",
          "GET",
          read(req, Scope.QueryRead)(p =>
            checkId("jobId", jid, RestIds.isJobId).flatMap(queries.job(p, _))
          )
        ))
      case List("v1", "jobs", jid, "result") =>
        Some(Route(
          "/v1/jobs/{jobId}/result",
          "GET",
          read(req, Scope.QueryRead)(p =>
            checkId("jobId", jid, RestIds.isJobId).flatMap(queries.jobResult(p, _))
          )
        ))
      case List("v1", "evidence", eid) =>
        Some(Route(
          "/v1/evidence/{evidenceId}",
          "GET",
          read(req, Scope.EvidenceRead)(_ =>
            evidence.evidence(eid)
          )
        ))
      case List("v1", "graph", "nodes", nid) =>
        Some(Route(
          "/v1/graph/nodes/{nodeId}",
          "GET",
          read(req, Scope.GraphRead)(_ =>
            graph.node(nid)
          )
        ))
      case List("v1", "graph", "nodes", nid, "neighbors") =>
        Some(Route(
          "/v1/graph/nodes/{nodeId}/neighbors",
          "GET",
          ctx =>
            for
              _      <- authorize(req, ctx, Scope.GraphRead)
              params <-
                QueryString.parse(req.rawQuery).left.map(ApiError.InvalidParameter("query", _))
              q    <- NeighborQuery.parse(nid, params, defaultWindow)
              view <- graph.neighbors(q)
            yield json(200, view)
        ))
      case List("v1", "explain", qid) =>
        Some(Route(
          "/v1/explain/{queryId}",
          "GET",
          read(req, Scope.QueryRead)(p =>
            checkId("queryId", qid, RestIds.isQueryId).flatMap(queries.explain(p, _))
          )
        ))
      case _ => None

  def handle(req: HttpRequest): HttpResponse =
    val started                                                       = nanoTime()
    val ctx                                                           = RequestContext.from(req)
    val (template, outcome): (String, Either[ApiError, HttpResponse]) =
      try
        PathCodec.segments(req.rawPath) match
          case Left(r)         => ("invalid-path", Left(ApiError.InvalidParameter("path", r)))
          case Right(segments) =>
            route(req, segments) match
              case None                              => ("unmatched", Left(ApiError.RouteNotFound))
              case Some(r) if r.method != req.method =>
                (r.template, Left(ApiError.MethodNotAllowed(List(r.method))))
              case Some(r) => (r.template, r.handler(ctx))
      catch
        case NonFatal(e) =>
          log.error(
            "http.unhandled",
            "traceId"   -> ctx.traceId,
            "requestId" -> ctx.requestId,
            "exception" -> e.getClass.getName
          )
          ("unhandled", Left(ApiError.Internal))
    val response = (outcome match
      case Right(r) => r
      case Left(e)  => errorResponse(e, ctx.traceId)
    )
      .withHeader("X-Request-Id", ctx.requestId)
      .withHeader("X-Trace-Id", ctx.traceId)
      .withHeader("Cache-Control", "no-store")
      .withHeader("X-Content-Type-Options", "nosniff")
    val millis = (nanoTime() - started) / 1000000L
    // Every label value comes from a bounded set: method is mapped to a fixed list, route is a
    // constant template, and status is one of the statuses this router emits.
    val method = Router.methodLabel(req.method)
    val labels = List("method" -> method, "route" -> template)
    metrics.inc(
      "scholarchain_http_requests_total",
      (labels :+ ("status" -> response.status.toString))*
    )
    metrics.add("scholarchain_http_request_duration_ms_sum", millis, labels*)
    metrics.inc("scholarchain_http_request_duration_ms_count", labels*)
    log.info(
      "http.request",
      "requestId" -> ctx.requestId,
      "traceId"   -> ctx.traceId,
      "method"    -> method,
      "route"     -> template,
      "status"    -> response.status,
      "latencyMs" -> millis.toDouble,
      "principal" -> ctx.principal.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "error"     -> outcome.left.toOption.fold[ujson.Value](ujson.Null)(e => ujson.Str(e.code))
    )
    response

object Router:
  val KnownMethods: Set[String] = Set("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS")

  /** Client-supplied method tokens are unbounded; only known methods are kept as labels. */
  def methodLabel(method: String): String = if KnownMethods(method) then method else "OTHER"
