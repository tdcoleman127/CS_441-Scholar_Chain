package edu.uic.cs441.scholarchain.storage.kv

import edu.uic.cs441.scholarchain.core.model.{Provenance, SourceType}
import edu.uic.cs441.scholarchain.storage.{AwsErrors, Idempotency, StorageError, WriteOutcome}
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  ConditionalCheckFailedException,
  GetItemRequest,
  PutItemRequest,
  QueryRequest,
  ReturnValuesOnConditionCheckFailure
}

/**
 * DynamoDB adapter for [[KeyValueStore]]. Table names are `<tablePrefix>-<logical>` with the prefix
 * from configuration; this class never creates, deletes, or reconfigures tables. Build the client
 * with [[edu.uic.cs441.scholarchain.storage.AwsClients]] so SDK retries are off.
 *
 * Attributes: `pk` (S), `sk` (S), `payload` (S), `contentHash` (S), `sourceDataset` (S),
 * `sourceRecordId` (S), `pipelineRunId` (S), `version` (N), optional `status` (S), optional `ttl`
 * (N, epoch seconds).
 */
final class DynamoDbKeyValueStore(client: DynamoDbClient, tablePrefix: String)
    extends KeyValueStore:

  private def s(v: String)   = AttributeValue.builder().s(v).build()
  private def n(v: Long)     = AttributeValue.builder().n(v.toString).build()
  private def name(t: Table) = t.physicalName(tablePrefix)

  private def toAttributes(i: TableItem): java.util.Map[String, AttributeValue] =
    val base = Map(
      "pk"             -> s(i.pk),
      "sk"             -> s(i.sk),
      "payload"        -> s(i.payload),
      "contentHash"    -> s(i.contentHash),
      "sourceDataset"  -> s(i.provenance.sourceDataset.wire),
      "sourceRecordId" -> s(i.provenance.sourceRecordId),
      "pipelineRunId"  -> s(i.provenance.pipelineRunId),
      "version"        -> n(i.version)
    )
    (base ++ i.status.map("status" -> s(_)) ++ i.ttlEpochSeconds.map("ttl" -> n(_))).asJava

  private def fromAttributes(
      m: java.util.Map[String, AttributeValue]
  ): Either[StorageError, TableItem] =
    val a              = m.asScala
    def str(k: String) = a.get(k).flatMap(v => Option(v.s()))
    def num(k: String) = a.get(k).flatMap(v => Option(v.n())).flatMap(_.toLongOption)
    def req(k: String) = str(k).toRight(StorageError.Failed("decode", s"missing $k"))
    for
      pk   <- req("pk")
      sk   <- req("sk")
      pay  <- req("payload")
      hash <- req("contentHash")
      ds   <- req("sourceDataset").flatMap(w =>
        SourceType.fromWire(w).toRight(StorageError.Failed("decode", s"unknown dataset $w"))
      )
      rec <- req("sourceRecordId")
      run <- req("pipelineRunId")
      ver <- num("version").toRight(StorageError.Failed("decode", "missing version"))
    yield TableItem(pk, sk, pay, hash, Provenance(ds, rec, run), ver, str("status"), num("ttl"))

  private def keyOf(pk: String, sk: String) = Map("pk" -> s(pk), "sk" -> s(sk)).asJava

  def putIfAbsent(table: Table, item: TableItem): Either[StorageError, WriteOutcome] =
    val req = PutItemRequest
      .builder()
      .tableName(name(table))
      .item(toAttributes(item))
      .conditionExpression("attribute_not_exists(pk)")
      .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
      .build()
    try
      client.putItem(req)
      Right(WriteOutcome.Written)
    catch
      case e: ConditionalCheckFailedException =>
        val existing =
          if e.hasItem then Right(Option(e.item().get("contentHash")).flatMap(v => Option(v.s())))
          else get(table, item.pk, item.sk).map(_.map(_.contentHash))
        existing.flatMap(h => Idempotency.resolveExisting(item.key, h, item.contentHash))
      case e: Exception => Left(AwsErrors.classify("dynamodb:PutItem", e))

  def get(table: Table, pk: String, sk: String): Either[StorageError, Option[TableItem]] =
    try
      val resp = client.getItem(
        GetItemRequest.builder().tableName(name(table)).key(keyOf(
          pk,
          sk
        )).consistentRead(true).build()
      )
      if !resp.hasItem || resp.item().isEmpty then Right(None)
      else fromAttributes(resp.item()).map(Some(_))
    catch case e: Exception => Left(AwsErrors.classify("dynamodb:GetItem", e))

  def updateIfVersion(
      table: Table,
      item: TableItem,
      expectedVersion: Long
  ): Either[StorageError, Unit] =
    KeyValueStore.checkUpdate(item, expectedVersion).flatMap { _ =>
      val req = PutItemRequest
        .builder()
        .tableName(name(table))
        .item(toAttributes(item))
        .conditionExpression("#v = :expected")
        .expressionAttributeNames(Map("#v" -> "version").asJava)
        .expressionAttributeValues(Map(":expected" -> n(expectedVersion)).asJava)
        .build()
      try
        client.putItem(req)
        Right(())
      catch
        case _: ConditionalCheckFailedException =>
          Left(StorageError.VersionConflict(item.key, expectedVersion))
        case e: Exception => Left(AwsErrors.classify("dynamodb:PutItem", e))
    }

  def queryPartition(table: Table, pk: String, limit: Int): Either[StorageError, List[TableItem]] =
    KeyValueStore.checkLimit(limit).flatMap { _ =>
      try
        val resp = client.query(
          QueryRequest
            .builder()
            .tableName(name(table))
            .keyConditionExpression("pk = :pk")
            .expressionAttributeValues(Map(":pk" -> s(pk)).asJava)
            .limit(limit)
            .consistentRead(true)
            .build()
        )
        resp.items().asScala.toList.foldRight[Either[StorageError, List[TableItem]]](Right(Nil)) {
          (m, acc) => acc.flatMap(rest => fromAttributes(m).map(_ :: rest))
        }
      catch case e: Exception => Left(AwsErrors.classify("dynamodb:Query", e))
    }
