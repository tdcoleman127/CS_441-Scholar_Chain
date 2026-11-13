package edu.uic.cs441.scholarchain.services.app

import edu.uic.cs441.scholarchain.core.model.YearWindow
import edu.uic.cs441.scholarchain.rag.runtime.{Abstention, AuditRecord, RagOutcome}
import edu.uic.cs441.scholarchain.services.api.*
import edu.uic.cs441.scholarchain.services.ops.{Metrics, StructuredLog}
import edu.uic.cs441.scholarchain.services.security.Principal
import java.security.SecureRandom
import java.time.Clock
import java.util.concurrent.*
import scala.util.{Failure, Success, Try}

/** A successful `POST /v1/query` reply. */
enum QueryReply:
  case Answer(body: QueryResponse)
  case Accepted(body: AsyncQueryResponse)

/** Service-local REST identifiers (`qry-` and `job-` plus 32 random hex characters). */
object RestIds:
  private val random      = SecureRandom()
  private val QueryId     = """^qry-[0-9a-f]{32}$""".r
  private val JobId       = """^job-[0-9a-f]{32}$""".r
  private def hex: String =
    val b = new Array[Byte](16)
    random.nextBytes(b)
    b.map(x => f"${x & 0xff}%02x").mkString
  def newQueryId(): String          = s"qry-$hex"
  def newJobId(): String            = s"job-$hex"
  def isQueryId(s: String): Boolean = QueryId.matches(s)
  def isJobId(s: String): Boolean   = JobId.matches(s)

/** Runs a task once after a delay (the job deadline). Injectable so tests control time. */
trait DeadlineScheduler:
  def schedule(delayMillis: Long)(task: () => Unit): Unit
  def shutdown(): Unit

/** Production scheduler: one daemon thread. */
final class ExecutorDeadlineScheduler extends DeadlineScheduler:
  private val executor =
    val s = ScheduledThreadPoolExecutor(
      1,
      (r: Runnable) =>
        val t = Thread(r, "scholarchain-job-deadlines")
        t.setDaemon(true)
        t
    )
    s.setRemoveOnCancelPolicy(true)
    s

  def schedule(delayMillis: Long)(task: () => Unit): Unit =
    executor.schedule((() => task()): Runnable, delayMillis, TimeUnit.MILLISECONDS)
    ()

  def shutdown(): Unit =
    executor.shutdownNow()
    ()

/**
 * Query and job use cases. Every query runs as a job on the bounded workflow pool under the job
 * deadline. Synchronous mode waits up to the synchronous deadline and cancels on expiry (504); auto
 * mode waits the same time and then hands back the job reference (202); asynchronous mode returns
 * the job reference at once (202).
 */
final class QueryService(
    workflow: GraphRagWorkflow,
    store: QueryRecordStore,
    pool: WorkerPool,
    syncDeadlineMillis: Long,
    jobDeadlineMillis: Long,
    clock: Clock,
    log: StructuredLog,
    metrics: Metrics,
    deadlines: DeadlineScheduler
):
  def shutdown(): Unit = deadlines.shutdown()

  private def modeWire(r: QueryRecord): String =
    if r.jobExposed then ExecutionMode.Asynchronous.wire else ExecutionMode.Synchronous.wire

  private def accepted(r: QueryRecord): QueryReply =
    QueryReply.Accepted(
      AsyncQueryResponse(r.queryId, "asynchronous", r.jobId, r.state.wire, s"/v1/jobs/${r.jobId}")
    )

  /** The replay of a terminal record (none while it is still running). */
  private def replyOf(r: QueryRecord): Option[StoredReply] =
    if !r.state.terminal then None
    else if r.jobExposed then Some(StoredReply.Reply(accepted(r)))
    else if r.state == JobState.Failed then
      // A failed query may still carry a workflow result (for example an abstention caused by a
      // downstream failure); its reply is the error, never that result.
      Some(StoredReply.Error(r.error.getOrElse(ApiError.Internal)))
    else
      render(r) match
        case Right(reply) => Some(StoredReply.Reply(reply))
        case Left(err)    => Some(StoredReply.Error(err))

  /** Keeps the terminal reply with the idempotency record, so replay outlives the query record. */
  private def settle(queryId: String): Unit =
    store.get(queryId).flatMap(replyOf).foreach(store.settle(queryId, _))

  /** Marks a non-terminal query failed. A query that is already terminal keeps its outcome. */
  private def fail(queryId: String, error: ApiError): Unit =
    val before = store.get(queryId).map(_.state)
    store.advance(queryId)(r =>
      r.copy(
        state = JobState.Failed,
        stage = "FAILED",
        error = Some(error),
        updatedAt = clock.instant()
      )
    )
    if before.exists(!_.terminal) then
      metrics.inc("scholarchain_workflow_outcomes_total", "outcome" -> error.code)
      settle(queryId)

  /** Validated submission with optional idempotency key (already format-checked). */
  def submit(
      principal: Principal,
      idempotencyKey: Option[String],
      q: NormalizedQuery,
      traceId: String
  ): Either[ApiError, (QueryReply, Boolean)] =
    val now    = clock.instant()
    val record = QueryRecord(
      RestIds.newQueryId(),
      RestIds.newJobId(),
      principal.id,
      q.mode,
      q.mode == ExecutionMode.Asynchronous,
      q,
      JobState.Queued,
      "QUEUED",
      now,
      now,
      None,
      None
    )
    store.claim(principal.id, idempotencyKey, q.requestHash, record, now) match
      case ClaimResult.Existing(existing) if existing.requestHash != q.requestHash =>
        Left(ApiError.IdempotencyConflict)
      case ClaimResult.Existing(existing) => replay(existing).map(_ -> true)
      case ClaimResult.Rejected(err)      => Left(err)
      case ClaimResult.Created            => start(record, idempotencyKey, traceId).map(_ -> false)

  /** Replays from the live record when it exists, else from the reply kept with the key. */
  private def replay(existing: IdempotencyRecord): Either[ApiError, QueryReply] =
    store.get(existing.queryId) match
      case Some(r) if r.jobExposed => Right(accepted(r))
      case Some(r)                 =>
        r.state match
          case JobState.Succeeded => render(r)
          case JobState.Failed    => Left(r.error.getOrElse(ApiError.Internal))
          case _                  => Left(ApiError.IdempotencyInProgress)
      case None =>
        existing.reply match
          case Some(StoredReply.Reply(reply)) => Right(reply)
          case Some(StoredReply.Error(err))   => Left(err)
          case None                           => Left(ApiError.IdempotencyInProgress)

  private def render(r: QueryRecord): Either[ApiError, QueryReply] =
    r.result match
      case Some(w) => Right(QueryReply.Answer(Presenter.queryResponse(
          r.queryId,
          modeWire(r),
          w.outcome,
          r.request.output
        )))
      case None => Left(r.error.getOrElse(ApiError.Internal))

  private def outcomeOf(qid: String): Either[ApiError, QueryReply] =
    store.get(qid).toRight(ApiError.Internal).flatMap(r =>
      if r.state == JobState.Failed then Left(r.error.getOrElse(ApiError.Internal)) else render(r)
    )

  private def start(
      record: QueryRecord,
      key: Option[String],
      traceId: String
  ): Either[ApiError, QueryReply] =
    val qid = record.queryId
    pool.submit(() => run(qid, traceId)) match
      case Left(err) =>
        // Never started: release the key so a retry can run, and record the rejection.
        store.release(record.principal, key, qid)
        fail(qid, err)
        Left(err)
      case Right(future) =>
        // Record the timeout first, then interrupt, so the stored error is always the timeout.
        deadlines.schedule(jobDeadlineMillis) { () =>
          if !future.isDone then
            fail(qid, ApiError.WorkflowTimeout(jobDeadlineMillis))
            future.cancel(true)
            ()
        }
        record.mode match
          case ExecutionMode.Asynchronous => Right(accepted(record))
          case mode                       =>
            try
              future.get(syncDeadlineMillis, TimeUnit.MILLISECONDS)
              outcomeOf(qid)
            catch
              case _: TimeoutException if mode == ExecutionMode.Auto =>
                store.advance(qid)(_.copy(jobExposed = true))
                store.get(qid).map(accepted).toRight(ApiError.Internal)
              case _: TimeoutException =>
                fail(qid, ApiError.WorkflowTimeout(syncDeadlineMillis))
                future.cancel(true)
                outcomeOf(qid) // the timeout, unless the workflow finished in the meantime
              case _: CancellationException =>
                Left(store.get(qid).flatMap(_.error).getOrElse(ApiError.Internal))
              case e: ExecutionException =>
                log.error(
                  "workflow.crashed",
                  "traceId"   -> traceId,
                  "queryId"   -> qid,
                  "exception" -> Option(e.getCause).fold("unknown")(_.getClass.getName)
                )
                fail(qid, ApiError.Internal)
                Left(ApiError.Internal)

  /**
   * Runs on a workflow thread. A deadline records its timeout before interrupting this thread; an
   * InterruptedException is not caught by `Try` (it is fatal to `NonFatal`), so it ends the task
   * and the recorded timeout stands.
   */
  private def run(queryId: String, traceId: String): Unit =
    store.advance(queryId)(
      _.copy(state = JobState.Running, stage = "GRAPH_RAG_WORKFLOW", updatedAt = clock.instant())
    )
    store.get(queryId).filterNot(_.state.terminal).foreach { r =>
      val started          = System.nanoTime()
      val (attempt, trace) =
        CallTrace.run(Try(workflow.answer(r.request.question, r.request.workflow)))
      val millis = (System.nanoTime() - started) / 1000000L
      attempt match
        case Failure(e) =>
          log.error(
            "workflow.failed",
            "traceId"   -> traceId,
            "queryId"   -> queryId,
            "exception" -> e.getClass.getName
          )
          fail(queryId, ApiError.Internal)
        case Success(answer) =>
          val summary = TraceSummary(
            trace.calls,
            trace.retries,
            trace.faults.toList.map(f =>
              s"${f.dependency}:${f.productPrefix}"
            )
          )
          val result = WorkflowResult(
            answer.outcome,
            summary,
            millis,
            answer.pipelineCacheHit,
            answer.semanticIndexes.map(_.wire)
          )
          classify(r.request, answer.outcome, trace) match
            case Some(err) =>
              store.advance(queryId)(_.copy(
                state = JobState.Failed,
                stage = "FAILED",
                result = Some(result),
                error = Some(err),
                updatedAt = clock.instant()
              ))
              metrics.inc("scholarchain_workflow_outcomes_total", "outcome" -> err.code)
            case None =>
              store.indexEvidence(Presenter.evidenceRecords(answer.outcome))
              store.advance(queryId)(_.copy(
                state = JobState.Succeeded,
                stage = "COMPLETE",
                result = Some(result),
                updatedAt = clock.instant()
              ))
              metrics.inc(
                "scholarchain_workflow_outcomes_total",
                "outcome" -> answer.outcome.audit.outcome
              )
          settle(queryId)
          log.info(
            "workflow.finished",
            "traceId"           -> traceId,
            "queryId"           -> queryId,
            "outcome"           -> answer.outcome.audit.outcome,
            "workflowMillis"    -> millis.toDouble,
            "downstreamCalls"   -> trace.calls,
            "downstreamRetries" -> trace.retries
          )
    }

  private def window(w: YearWindow) = s"${w.startYear}-${w.endYear}"

  /** Maps outcomes that are failures for the caller to API errors; answers and abstentions pass. */
  private def classify(q: NormalizedQuery, o: RagOutcome, trace: CallTrace): Option[ApiError] =
    o match
      case RagOutcome.Refused(reason, _) => Some(ApiError.UnsupportedQuery(reason.wire))
      case RagOutcome.Abstained(Abstention.RetrievalFailed(_), _) =>
        Some(trace.faults.lastOption.fold(ApiError.DownstreamFailure("graph"))(_.toApiError))
      case RagOutcome.Abstained(Abstention.SynthesisFailed(_), _) =>
        Some(ApiError.DownstreamFailure("synthesis"))
      case other => conflict(q, other.audit)

  private def conflict(q: NormalizedQuery, a: AuditRecord): Option[ApiError] =
    val windowConflict =
      for
        want <- q.workflow.window
        got  <- a.window
        if want != got
      yield ApiError.ConstraintConflict(
        s"the question names window ${window(got)} but constraints request ${window(want)}"
      )
    def off(flag: Boolean, name: String) =
      Option.when(!flag)(ApiError.ConstraintConflict(s"the question needs $name, which is false"))
    windowConflict.orElse(a.operation match
      case Some("shortest-path") => off(q.output.includePaths, "analysis.includePaths")
      case Some("community")     => off(q.output.includeCommunities, "analysis.includeCommunities")
      case Some("bridges") => off(q.output.includeBridgeMetrics, "analysis.includeBridgeMetrics")
      case _               => None)

  // ---- Jobs, query evidence, and explain (owner-scoped reads) ---------------------------------

  /** Records of other principals are reported as unknown (404), so IDs cannot be probed. */
  private def owned(r: Option[QueryRecord], p: Principal, kind: String, id: String) =
    r.filter(_.principal == p.id).toRight(ApiError.NotFound(kind, id))

  def job(p: Principal, jobId: String): Either[ApiError, JobView] =
    owned(store.byJob(jobId), p, "job", jobId).map(Presenter.jobView)

  def jobResult(p: Principal, jobId: String): Either[ApiError, QueryResponse] =
    owned(store.byJob(jobId), p, "job", jobId).flatMap { r =>
      r.state match
        case JobState.Succeeded =>
          r.result.toRight(ApiError.Internal).map(w =>
            Presenter.queryResponse(r.queryId, modeWire(r), w.outcome, r.request.output)
          )
        case JobState.Failed => Left(ApiError.JobFailed(r.error.fold("UNKNOWN")(_.code)))
        case s               => Left(ApiError.JobNotComplete(s.wire))
    }

  def queryEvidence(p: Principal, queryId: String): Either[ApiError, QueryEvidenceView] =
    owned(store.get(queryId), p, "query", queryId).flatMap { r =>
      r.state match
        case JobState.Succeeded =>
          val items = r.result.fold(Nil)(w => Presenter.evidenceRecords(w.outcome)).map(e =>
            EvidenceSummaryView(
              e.item.evidenceId,
              e.item.evidenceType.wire,
              e.item.subjectId,
              e.item.contentHash,
              Presenter.evidenceHref(e.item.evidenceId)
            )
          )
          Right(QueryEvidenceView(queryId, items))
        case JobState.Failed => Left(ApiError.JobFailed(r.error.fold("UNKNOWN")(_.code)))
        case s               => Left(ApiError.JobNotComplete(s.wire))
    }

  def explain(p: Principal, queryId: String): Either[ApiError, ExplainView] =
    owned(store.get(queryId), p, "query", queryId).map(Presenter.explainView)
