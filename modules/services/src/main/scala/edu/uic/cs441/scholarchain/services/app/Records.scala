package edu.uic.cs441.scholarchain.services.app

import edu.uic.cs441.scholarchain.core.model.EvidenceItem
import edu.uic.cs441.scholarchain.rag.runtime.RagOutcome
import edu.uic.cs441.scholarchain.services.api.{ApiError, NormalizedQuery}
import java.time.Instant
import scala.collection.mutable

/** Lifecycle state of a query's workflow (the job state in the Jobs API). */
enum JobState(val wire: String, val terminal: Boolean):
  case Queued    extends JobState("QUEUED", false)
  case Running   extends JobState("RUNNING", false)
  case Succeeded extends JobState("SUCCEEDED", true)
  case Failed    extends JobState("FAILED", true)

/** How the caller asked the workflow to run. */
enum ExecutionMode(val wire: String):
  case Synchronous  extends ExecutionMode("synchronous")
  case Asynchronous extends ExecutionMode("asynchronous")
  case Auto         extends ExecutionMode("auto")

object ExecutionMode:
  def fromWire(s: String): Option[ExecutionMode] = values.find(_.wire == s)

/**
 * Workflow result kept for the Jobs, Evidence, and Explain APIs. `semanticIndexes` lists the vector
 * indexes this query's semantic stage actually searched (empty when the stage did not run).
 */
final case class WorkflowResult(
    outcome: RagOutcome,
    trace: TraceSummary,
    workflowMillis: Long,
    pipelineCacheHit: Boolean,
    semanticIndexes: List[String]
)

/** Downstream call counters of one workflow (from its [[CallTrace]]). */
final case class TraceSummary(calls: Int, retries: Int, faults: List[String])

/**
 * One accepted query. `jobId` is set when the caller received a job reference (asynchronous mode,
 * or auto mode past the synchronous deadline). `error` is set only in state FAILED.
 */
final case class QueryRecord(
    queryId: String,
    jobId: String,
    principal: String,
    mode: ExecutionMode,
    jobExposed: Boolean,
    request: NormalizedQuery,
    state: JobState,
    stage: String,
    createdAt: Instant,
    updatedAt: Instant,
    result: Option[WorkflowResult],
    error: Option[ApiError]
)

/** An evidence item served in some answer, with the pointers the Evidence API returns. */
final case class EvidenceRecord(
    item: EvidenceItem,
    bundleId: String,
    sourceUri: Option[String],
    graphPath: List[String],
    semantic: Boolean
)

/** The terminal reply of a query, kept with its idempotency record for replay. */
enum StoredReply:
  case Reply(reply: QueryReply)
  case Error(error: ApiError)

/**
 * The idempotency record of one (principal, Idempotency-Key) pair: the request fingerprint, the
 * query it started, and, once the query is terminal, its reply. The record is independent of the
 * query record, so replay and conflict detection hold for the whole idempotency TTL even after the
 * query record has been evicted.
 */
final case class IdempotencyRecord(
    requestHash: String,
    queryId: String,
    createdAt: Instant,
    reply: Option[StoredReply]
)

/** Outcome of claiming a new query. */
enum ClaimResult:
  case Created
  case Existing(record: IdempotencyRecord)
  case Rejected(error: ApiError)

/** Capacity and retention of the in-memory store. */
final case class StoreLimits(
    maxRecords: Int,
    maxRecordsPerPrincipal: Int,
    recordTtlSeconds: Long,
    maxEvidence: Int,
    idempotencyTtlSeconds: Long,
    maxIdempotencyKeys: Int,
    maxIdempotencyKeysPerPrincipal: Int
)

/**
 * Query, job, idempotency, and evidence records. The production composition uses
 * [[InMemoryQueryRecordStore]]: records live in this task only (see RISKS in the phase report: a
 * durable storage-owned table adapter is needed for multi-task deployments).
 */
trait QueryRecordStore:
  /**
   * Atomically claims (principal, key) for `record`. Returns the existing idempotency record when
   * the key was claimed within the idempotency TTL; otherwise stores `record` (and the key) and
   * returns `Created`, or `Rejected` when the principal has no capacity left.
   */
  def claim(
      principal: String,
      key: Option[String],
      requestHash: String,
      record: QueryRecord,
      now: Instant
  ): ClaimResult

  /** Drops the key's claim if it still points at `queryId` (the query never started). */
  def release(principal: String, key: Option[String], queryId: String): Unit

  /** Stores the terminal reply of `queryId` with the idempotency record that started it, if any. */
  def settle(queryId: String, reply: StoredReply): Unit

  def get(queryId: String): Option[QueryRecord]
  def byJob(jobId: String): Option[QueryRecord]

  /** Applies `f` if the record exists and is not terminal; returns the stored result. */
  def advance(queryId: String)(f: QueryRecord => QueryRecord): Option[QueryRecord]

  def indexEvidence(records: Seq[EvidenceRecord]): Unit
  def evidence(evidenceId: String): Option[EvidenceRecord]

/**
 * Bounded in-memory store.
 *
 *   - Query records: terminal records expire after `recordTtlSeconds`. Each principal holds at most
 *     `maxRecordsPerPrincipal`; at the cap, that principal's own oldest terminal record is evicted
 *     (or the claim is rejected with 429 when all of its records are still running). Beyond
 *     `maxRecords` in total, the oldest terminal record of the principal holding the most records
 *     is evicted, so one principal's traffic cannot evict a lighter principal's records.
 *     Non-terminal records are never evicted.
 *   - Idempotency records: kept for `idempotencyTtlSeconds` regardless of query-record eviction.
 *     Each principal holds at most `maxIdempotencyKeysPerPrincipal` keys (its own oldest key is
 *     dropped first); beyond `maxIdempotencyKeys`, the heaviest principal's oldest key is dropped.
 *   - Evidence records: evicted oldest-first beyond `maxEvidence`.
 */
final class InMemoryQueryRecordStore(limits: StoreLimits, now: () => Instant)
    extends QueryRecordStore:
  private type Key = (String, String)
  private val records     = mutable.LinkedHashMap.empty[String, QueryRecord]
  private val jobs        = mutable.HashMap.empty[String, String]
  private val idempotency = mutable.LinkedHashMap.empty[Key, IdempotencyRecord]
  private val keyOf       = mutable.HashMap.empty[String, Key]
  private val evidenceMap = mutable.LinkedHashMap.empty[String, EvidenceRecord]

  private def removeRecord(id: String): Unit =
    records.remove(id).foreach(r => jobs.remove(r.jobId))

  private def removeKey(k: Key): Unit =
    idempotency.remove(k).foreach(r => keyOf.remove(r.queryId))

  /** The principal holding the most entries (ties: the first such principal in `owners`). */
  private def heaviest(owners: Iterable[String]): Option[String] =
    owners.groupBy(identity).toList.sortBy((p, xs) => (-xs.size, p)).headOption.map(_._1)

  private def oldestTerminalOf(principal: String): Option[String] =
    records.values.find(r => r.principal == principal && r.state.terminal).map(_.queryId)

  private def expire(): Unit =
    val t      = now()
    val cutoff = t.minusSeconds(limits.recordTtlSeconds)
    records.values
      .filter(r => r.state.terminal && r.updatedAt.isBefore(cutoff))
      .map(_.queryId)
      .toList
      .foreach(removeRecord)
    val idemCutoff = t.minusSeconds(limits.idempotencyTtlSeconds)
    idempotency.filter((_, r) => r.createdAt.isBefore(idemCutoff)).keys.toList.foreach(removeKey)

  /** Makes room for one more record of `principal`, or explains why there is none. */
  private def makeRoom(principal: String): Option[ApiError] =
    val own = records.values.count(_.principal == principal)
    if own >= limits.maxRecordsPerPrincipal then
      oldestTerminalOf(principal) match
        case Some(id) => removeRecord(id); None
        case None     => Some(ApiError.RateLimited(1))
    else if records.size >= limits.maxRecords then
      val victim = heaviest(records.values.filter(_.state.terminal).map(_.principal))
        .flatMap(oldestTerminalOf)
      victim match
        case Some(id) => removeRecord(id); None
        case None     => Some(ApiError.Overloaded("query-records"))
    else None

  private def addKey(principal: String, k: String, rec: IdempotencyRecord): Unit =
    val own = idempotency.keys.filter(_._1 == principal).toList
    if own.size >= limits.maxIdempotencyKeysPerPrincipal then own.headOption.foreach(removeKey)
    else if idempotency.size >= limits.maxIdempotencyKeys then
      heaviest(idempotency.keys.map(_._1)).flatMap(p => idempotency.keys.find(_._1 == p))
        .foreach(removeKey)
    idempotency((principal, k)) = rec
    keyOf(rec.queryId) = (principal, k)

  def claim(
      principal: String,
      key: Option[String],
      requestHash: String,
      record: QueryRecord,
      at: Instant
  ): ClaimResult = synchronized {
    expire()
    key.flatMap(k => idempotency.get((principal, k))) match
      case Some(existing) => ClaimResult.Existing(existing)
      case None           =>
        makeRoom(principal) match
          case Some(err) => ClaimResult.Rejected(err)
          case None      =>
            key.foreach(k =>
              addKey(principal, k, IdempotencyRecord(requestHash, record.queryId, at, None))
            )
            records(record.queryId) = record
            jobs(record.jobId) = record.queryId
            ClaimResult.Created
  }

  def release(principal: String, key: Option[String], queryId: String): Unit = synchronized {
    key.foreach(k =>
      if idempotency.get((principal, k)).exists(_.queryId == queryId) then removeKey((principal, k))
    )
  }

  def settle(queryId: String, reply: StoredReply): Unit = synchronized {
    keyOf.get(queryId).foreach(k =>
      idempotency.get(k).foreach(r => idempotency(k) = r.copy(reply = Some(reply)))
    )
  }

  def get(queryId: String): Option[QueryRecord] = synchronized(records.get(queryId))

  def byJob(jobId: String): Option[QueryRecord] =
    synchronized(jobs.get(jobId).flatMap(records.get))

  def advance(queryId: String)(f: QueryRecord => QueryRecord): Option[QueryRecord] =
    synchronized {
      records.get(queryId).map { r =>
        if r.state.terminal then r
        else
          val next = f(r)
          records(queryId) = next
          next
      }
    }

  def indexEvidence(rs: Seq[EvidenceRecord]): Unit = synchronized {
    rs.foreach(r =>
      if !evidenceMap.contains(r.item.evidenceId) then evidenceMap(r.item.evidenceId) = r
    )
    val overflow = evidenceMap.size - limits.maxEvidence
    if overflow > 0 then evidenceMap.keys.take(overflow).toList.foreach(evidenceMap.remove)
  }

  def evidence(evidenceId: String): Option[EvidenceRecord] =
    synchronized(evidenceMap.get(evidenceId))
