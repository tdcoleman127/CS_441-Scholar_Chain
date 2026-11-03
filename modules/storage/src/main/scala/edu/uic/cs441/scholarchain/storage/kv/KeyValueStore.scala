package edu.uic.cs441.scholarchain.storage.kv

import edu.uic.cs441.scholarchain.core.ids.CanonicalHash
import edu.uic.cs441.scholarchain.core.model.Provenance
import edu.uic.cs441.scholarchain.storage.{StorageError, WriteOutcome}

/** Logical DynamoDB tables. Physical name: `<dynamoDbTablePrefix>-<logical>` (from config). */
enum Table(val logical: String):
  case RunLedger        extends Table("run-ledger")
  case EntityLinkCache  extends Table("entity-link-cache")
  case QuerySession     extends Table("query-session")
  case EvidenceBundle   extends Table("evidence-bundle")
  case ArtifactRegistry extends Table("artifact-registry")

  def physicalName(prefix: String): String = s"$prefix-$logical"

/**
 * One stored item. Every table uses the generic string key attributes `pk` (partition) and `sk`
 * (sort). `payload` is the canonical JSON of the record; `contentHash` is `sha256:<hex>` of the
 * payload and decides replay versus conflict. Provenance is stored as three top-level attributes
 * (`sourceDataset`, `sourceRecordId`, `pipelineRunId`). `version` starts at 1 and is the
 * optimistic-lock counter for status transitions. `ttl` (epoch seconds) is set only on QuerySession
 * items.
 */
final case class TableItem(
    pk: String,
    sk: String,
    payload: String,
    contentHash: String,
    provenance: Provenance,
    version: Long,
    status: Option[String],
    ttlEpochSeconds: Option[Long]
):
  def key: String = s"$pk|$sk"

object TableItem:
  def payloadHash(payload: String): String = CanonicalHash.contentHash(payload)

  def create(
      pk: String,
      sk: String,
      payload: String,
      provenance: Provenance,
      status: Option[String] = None,
      ttlEpochSeconds: Option[Long] = None
  ): TableItem =
    TableItem(pk, sk, payload, payloadHash(payload), provenance, 1L, status, ttlEpochSeconds)

/**
 * Key-value table port.
 *
 *   - `putIfAbsent`: create-only (`attribute_not_exists(pk)`). Identical replay is
 *     `AlreadyPresent`; a different payload under the same key is `ConditionalConflict`.
 *   - `updateIfVersion`: optimistic lock; writes `item` (whose `version` must be
 *     `expectedVersion + 1`) only if the stored version equals `expectedVersion`. Not idempotent,
 *     so never retried automatically.
 *   - `queryPartition`: at most `limit` items of one partition, in sort-key order.
 */
trait KeyValueStore:
  def putIfAbsent(table: Table, item: TableItem): Either[StorageError, WriteOutcome]
  def get(table: Table, pk: String, sk: String): Either[StorageError, Option[TableItem]]
  def updateIfVersion(
      table: Table,
      item: TableItem,
      expectedVersion: Long
  ): Either[StorageError, Unit]
  def queryPartition(table: Table, pk: String, limit: Int): Either[StorageError, List[TableItem]]

object KeyValueStore:
  val MaxQueryLimit: Int = 100

  def checkUpdate(item: TableItem, expectedVersion: Long): Either[StorageError, Unit] =
    if item.version != expectedVersion + 1 then
      Left(StorageError.InvalidInput("version", "new version must be expectedVersion + 1"))
    else Right(())

  def checkLimit(limit: Int): Either[StorageError, Unit] =
    if limit < 1 || limit > MaxQueryLimit then
      Left(StorageError.InvalidInput("limit", s"must be in 1..$MaxQueryLimit"))
    else Right(())
