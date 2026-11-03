package edu.uic.cs441.scholarchain.storage

/**
 * Typed storage failures. Adapters translate every SDK exception into one of these; nothing above
 * the adapter layer sees an AWS exception type.
 *
 * Retryable (see [[StorageError.retryable]]): `Throttled`, `Timeout`, `Unavailable`. Everything
 * else is surfaced to the caller unchanged.
 */
enum StorageError:
  /** The service throttled the request (HTTP 429, S3 503 SlowDown, DynamoDB throughput). */
  case Throttled(operation: String, detail: String)

  /** A client-side call or attempt timeout expired. */
  case Timeout(operation: String, detail: String)

  /** A 5xx response or a transport failure. */
  case Unavailable(operation: String, detail: String)

  /**
   * A conditional write found an existing item or object whose content hash differs from the one
   * being written: a replay with different content, never silently overwritten.
   */
  case ConditionalConflict(key: String, existingHash: Option[String], attemptedHash: String)

  /** An optimistic-lock update found a different version than expected. */
  case VersionConflict(key: String, expectedVersion: Long)

  /** The input violates a storage contract (bad key, metadata, dimension, or record). */
  case InvalidInput(field: String, reason: String)

  /** A non-retryable service error (access denied, validation, missing resource). */
  case Failed(operation: String, detail: String)

  def retryable: Boolean = this match
    case Throttled(_, _) | Timeout(_, _) | Unavailable(_, _) => true
    case _                                                   => false

  def message: String = this match
    case Throttled(op, d)             => s"$op throttled: $d"
    case Timeout(op, d)               => s"$op timed out: $d"
    case Unavailable(op, d)           => s"$op unavailable: $d"
    case ConditionalConflict(k, e, a) =>
      s"$k already holds different content (existing ${e.getOrElse("unknown")}, attempted $a)"
    case VersionConflict(k, v) => s"$k is not at expected version $v"
    case InvalidInput(f, r)    => s"$f: $r"
    case Failed(op, d)         => s"$op failed: $d"

/** Result of an idempotent create. */
enum WriteOutcome:
  /** The item or object did not exist and was written. */
  case Written

  /** An identical item or object (same content hash) already existed: a safe replay. */
  case AlreadyPresent

object Idempotency:

  /**
   * Decides a failed create-if-absent. Same content hash means the earlier write (possibly our own
   * timed-out attempt) already stored this content; anything else is a conflict.
   */
  def resolveExisting(
      key: String,
      existingHash: Option[String],
      attemptedHash: String
  ): Either[StorageError, WriteOutcome] =
    if existingHash.contains(attemptedHash) then Right(WriteOutcome.AlreadyPresent)
    else Left(StorageError.ConditionalConflict(key, existingHash, attemptedHash))
