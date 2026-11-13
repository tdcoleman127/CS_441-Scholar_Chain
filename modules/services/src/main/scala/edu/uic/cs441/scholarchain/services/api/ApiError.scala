package edu.uic.cs441.scholarchain.services.api

import upickle.default.ReadWriter

/** The error body of every non-2xx response (REST API contract, "Error model"). */
final case class ErrorBody(error: String, message: String, traceId: String, retryable: Boolean)
    derives ReadWriter

/**
 * Typed API errors. Each case fixes its HTTP status, its stable machine-readable `code`, and
 * whether a client may retry the same request. Messages never carry secrets, credentials, raw
 * request bodies, or downstream exception text: only field names, bounds, and stable reasons.
 */
enum ApiError(val status: Int, val code: String, val retryable: Boolean):
  // 400
  case MalformedJson(detail: String)               extends ApiError(400, "MALFORMED_JSON", false)
  case InvalidField(field: String, reason: String) extends ApiError(400, "INVALID_FIELD", false)
  case InvalidParameter(name: String, reason: String)
      extends ApiError(400, "INVALID_PARAMETER", false)
  case InvalidCursor(reason: String)               extends ApiError(400, "INVALID_CURSOR", false)
  case InvalidHeader(name: String, reason: String) extends ApiError(400, "INVALID_HEADER", false)
  // 401 and 403
  case Unauthenticated(reason: String) extends ApiError(401, "UNAUTHENTICATED", false)
  case Forbidden(scope: String)        extends ApiError(403, "FORBIDDEN", false)
  // 404 and 405
  case RouteNotFound extends ApiError(404, "ROUTE_NOT_FOUND", false)
  case NotFound(resource: String, id: String)
      extends ApiError(404, s"${resource.toUpperCase(java.util.Locale.ROOT)}_NOT_FOUND", false)
  case MethodNotAllowed(allowed: List[String])
      extends ApiError(405, "METHOD_NOT_ALLOWED", false)
  // 409
  case IdempotencyConflict           extends ApiError(409, "IDEMPOTENCY_CONFLICT", false)
  case IdempotencyInProgress         extends ApiError(409, "IDEMPOTENCY_IN_PROGRESS", true)
  case JobNotComplete(state: String) extends ApiError(409, "JOB_NOT_COMPLETE", true)
  case JobFailed(cause: String)      extends ApiError(409, "JOB_FAILED", false)
  // 413 and 415
  case PayloadTooLarge(limitBytes: Int) extends ApiError(413, "PAYLOAD_TOO_LARGE", false)
  case UnsupportedMediaType(expected: String)
      extends ApiError(415, "UNSUPPORTED_MEDIA_TYPE", false)
  // 422
  case UnsupportedConstraint(field: String, reason: String)
      extends ApiError(422, "UNSUPPORTED_CONSTRAINT", false)
  case UnsupportedRetrievalPlan(field: String, reason: String)
      extends ApiError(422, "UNSUPPORTED_RETRIEVAL_PLAN", false)
  case UnsupportedQuery(reason: String)   extends ApiError(422, "UNSUPPORTED_QUERY", false)
  case ConstraintConflict(reason: String) extends ApiError(422, "CONSTRAINT_CONFLICT", false)
  case UnsupportedNodeType(nodeType: String)
      extends ApiError(422, "UNSUPPORTED_NODE_TYPE", false)
  case UnsupportedEdgeType(edgeType: String)
      extends ApiError(422, "UNSUPPORTED_EDGE_TYPE", false)
  // 429
  case RateLimited(retryAfterSeconds: Int) extends ApiError(429, "RATE_LIMITED", true)
  // 5xx
  case Internal                              extends ApiError(500, "INTERNAL_ERROR", false)
  case DownstreamFailure(dependency: String) extends ApiError(502, "DOWNSTREAM_FAILURE", true)
  case Overloaded(pool: String)              extends ApiError(503, "OVERLOADED", true)
  case DependencyUnavailable(dependency: String)
      extends ApiError(503, "DEPENDENCY_UNAVAILABLE", true)
  case NotReady                              extends ApiError(503, "NOT_READY", true)
  case WorkflowTimeout(deadlineMillis: Long) extends ApiError(504, "WORKFLOW_TIMEOUT", true)
  case DownstreamTimeout(dependency: String) extends ApiError(504, "DOWNSTREAM_TIMEOUT", true)

  def message: String = this match
    case MalformedJson(d)       => s"Request body is not valid JSON: $d."
    case InvalidField(f, r)     => s"Field '$f' is invalid: $r."
    case InvalidParameter(n, r) => s"Parameter '$n' is invalid: $r."
    case InvalidCursor(r)       => s"Cursor is invalid: $r."
    case InvalidHeader(n, r)    => s"Header '$n' is invalid: $r."
    case Unauthenticated(r)     => s"Authentication failed: $r."
    case Forbidden(s)           => s"The caller lacks the '$s' permission for this resource."
    case RouteNotFound          => "No route matches this path."
    case NotFound(r, id)        => s"Unknown $r ID '${ApiError.bounded(id)}'."
    case MethodNotAllowed(a)    => s"Method not allowed; allowed: ${a.mkString(", ")}."
    case IdempotencyConflict    =>
      "This Idempotency-Key was already used with a different request body."
    case IdempotencyInProgress =>
      "A request with this Idempotency-Key is still being processed; retry later."
    case JobNotComplete(s)       => s"The job has not completed (state $s); poll the status URL."
    case JobFailed(c)            => s"The job failed ($c); see the job status for details."
    case PayloadTooLarge(l)      => s"Request body exceeds $l bytes."
    case UnsupportedMediaType(e) => s"Content-Type must be $e."
    case UnsupportedConstraint(f, r)    => s"Constraint '$f' is not supported: $r."
    case UnsupportedRetrievalPlan(f, r) => s"Retrieval setting '$f' is not supported: $r."
    case UnsupportedQuery(r)            => s"The question cannot be planned: $r."
    case ConstraintConflict(r)          => s"Request constraints conflict: $r."
    case UnsupportedNodeType(t)         =>
      s"Node type '$t' has no read port in this deployment; only Researcher nodes are served."
    case UnsupportedEdgeType(t) =>
      s"Edge type '$t' has no neighbor port in this deployment; only COAUTHORED_WITH is served."
    case RateLimited(s)           => s"Rate limit exceeded; retry after $s second(s)."
    case Internal                 => "Unexpected internal failure; report the trace ID."
    case DownstreamFailure(d)     => s"Downstream dependency '$d' failed."
    case Overloaded(p)            => s"The $p worker pool is at capacity; retry later."
    case DependencyUnavailable(d) => s"Dependency '$d' is temporarily unavailable (circuit open)."
    case NotReady                 => "The service is not ready."
    case WorkflowTimeout(ms)      => s"The Graph-RAG workflow exceeded its $ms ms deadline."
    case DownstreamTimeout(d)     => s"Downstream dependency '$d' timed out."

  /** Extra headers for this error (Retry-After, Allow, WWW-Authenticate). */
  def headers: List[(String, String)] = this match
    case MethodNotAllowed(a) => List("Allow" -> a.mkString(", "))
    case Unauthenticated(_)  => List("WWW-Authenticate" -> "Bearer realm=\"scholarchain\"")
    case RateLimited(s)      => List("Retry-After" -> s.toString)
    case _ if status == 503  => List("Retry-After" -> "1")
    case _                   => Nil

  def body(traceId: String): ErrorBody = ErrorBody(code, message, traceId, retryable)

object ApiError:
  /** Echoed identifiers are cut to 128 characters and stripped of control characters. */
  def bounded(s: String): String = s.filterNot(_.isControl).take(128)
