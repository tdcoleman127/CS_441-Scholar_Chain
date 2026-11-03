package edu.uic.cs441.scholarchain.storage.objectstore

import edu.uic.cs441.scholarchain.storage.{AwsErrors, Idempotency, StorageError, WriteOutcome}
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  HeadObjectRequest,
  NoSuchKeyException,
  PutObjectRequest,
  S3Exception
}

/**
 * S3 adapter for [[ObjectStore]]. The bucket name comes from configuration; this class never
 * creates, deletes, or reconfigures buckets. Build the client with
 * [[edu.uic.cs441.scholarchain.storage.AwsClients]] so SDK retries are off.
 *
 * Create-if-absent uses `If-None-Match: *`. On HTTP 412 the existing object's `sc-content-hash`
 * metadata is read and compared (see [[Idempotency.resolveExisting]]).
 */
final class S3ObjectStore(client: S3Client, bucket: String) extends ObjectStore:

  def putIfAbsent(
      key: ObjectKey,
      bytes: Array[Byte],
      contentType: String
  ): Either[StorageError, WriteOutcome] =
    val hash    = ObjectStore.contentHash(bytes)
    val request = PutObjectRequest
      .builder()
      .bucket(bucket)
      .key(key.value)
      .contentType(contentType)
      .ifNoneMatch("*")
      .metadata(Map(ObjectStore.ContentHashMetadata -> hash).asJava)
      .build()
    try
      client.putObject(request, RequestBody.fromBytes(bytes))
      Right(WriteOutcome.Written)
    catch
      case e: S3Exception if e.statusCode() == 412 || e.statusCode() == 409 =>
        existingHash(key, afterConcurrentWrite = e.statusCode() == 409)
          .flatMap(h => Idempotency.resolveExisting(key.value, h, hash))
      case e: Exception => Left(AwsErrors.classify("s3:PutObject", e))

  def get(key: ObjectKey): Either[StorageError, Option[StoredObject]] =
    try
      val resp = client.getObjectAsBytes(
        GetObjectRequest.builder().bucket(bucket).key(key.value).build()
      )
      val bytes = resp.asByteArray()
      val hash  = Option(resp.response().metadata().get(ObjectStore.ContentHashMetadata))
        .getOrElse(ObjectStore.contentHash(bytes))
      Right(Some(StoredObject(key.value, bytes, hash)))
    catch
      case _: NoSuchKeyException => Right(None)
      case e: Exception          => Left(AwsErrors.classify("s3:GetObject", e))

  /**
   * After a 409 (a concurrent conditional write is in progress) the competing object may not be
   * committed yet, so HeadObject can return 404. That is transient: it is surfaced as the retryable
   * `Unavailable`, and the bounded retry re-attempts the idempotent create.
   */
  private def existingHash(
      key: ObjectKey,
      afterConcurrentWrite: Boolean
  ): Either[StorageError, Option[String]] =
    try
      val head =
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key.value).build())
      Right(Option(head.metadata().get(ObjectStore.ContentHashMetadata)))
    catch
      case e: S3Exception if afterConcurrentWrite && e.statusCode() == 404 =>
        Left(StorageError.Unavailable("s3:HeadObject", "object not yet committed after 409"))
      case e: Exception => Left(AwsErrors.classify("s3:HeadObject", e))
