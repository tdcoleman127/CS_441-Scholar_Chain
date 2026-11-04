package edu.uic.cs441.scholarchain.storage.vectors

import edu.uic.cs441.scholarchain.storage.{AwsErrors, StorageError}
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetVectorsRequest,
  PutInputVector,
  PutVectorsRequest,
  QueryVectorsRequest,
  VectorData
}

/**
 * S3 Vectors adapter for [[VectorStore]]. Bucket and index-name prefix come from configuration;
 * this class never creates, deletes, or reconfigures vector buckets or indexes. Build the client
 * with [[edu.uic.cs441.scholarchain.storage.AwsClients]] so SDK retries are off.
 */
final class S3VectorsStore(client: S3VectorsClient, vectorBucket: String, indexPrefix: String)
    extends VectorStore:

  private def toDocument(m: Map[String, MetaValue]): Document =
    Document.fromMap(m.map {
      case (k, MetaValue.Str(v)) => k -> Document.fromString(v)
      case (k, MetaValue.Num(v)) => k -> Document.fromNumber(v)
    }.asJava)

  private def fromDocument(d: Document): Map[String, MetaValue] =
    if d == null || !d.isMap then Map.empty
    else
      d.asMap.asScala.toMap.collect {
        case (k, v) if v.isString => k -> MetaValue.Str(v.asString)
        case (k, v) if v.isNumber => k -> MetaValue.Num(v.asNumber.longValue)
      }

  private def data(v: Vector[Float]): VectorData =
    VectorData.builder().float32(v.map(java.lang.Float.valueOf).asJava).build()

  def putBatch(index: VectorIndex, vectors: Seq[VectorRecord]): Either[StorageError, Unit] =
    VectorStore.checkBatch(vectors).flatMap { _ =>
      val input = vectors.map(r =>
        PutInputVector.builder().key(r.key).data(data(r.data)).metadata(toDocument(
          r.metadata
        )).build()
      )
      try
        client.putVectors(
          PutVectorsRequest
            .builder()
            .vectorBucketName(vectorBucket)
            .indexName(index.physicalName(indexPrefix))
            .vectors(input.asJava)
            .build()
        )
        Right(())
      catch case e: Exception => Left(AwsErrors.classify("s3vectors:PutVectors", e))
    }

  def query(
      index: VectorIndex,
      vector: Vector[Float],
      topK: Int,
      filter: Map[String, MetaValue]
  ): Either[StorageError, List[VectorHit]] =
    VectorStore.checkQuery(vector, topK, filter).flatMap { _ =>
      val base = QueryVectorsRequest
        .builder()
        .vectorBucketName(vectorBucket)
        .indexName(index.physicalName(indexPrefix))
        .topK(topK)
        .queryVector(data(vector))
        .returnMetadata(true)
        .returnDistance(true)
      // Equality filter: {"key": {"$eq": value}, ...}, combined with $and when there are several.
      val clauses = filter.toList.sortBy(_._1).map { (k, v) =>
        Document.fromMap(Map(k -> Document.fromMap(Map("$eq" -> toValue(v)).asJava)).asJava)
      }
      val request = clauses match
        case Nil        => base.build()
        case one :: Nil => base.filter(one).build()
        case many       =>
          base.filter(Document.fromMap(Map("$and" ->
            Document.fromList(many.asJava)).asJava)).build()
      try
        val resp = client.queryVectors(request)
        Right(
          resp
            .vectors()
            .asScala
            .toList
            .map(v =>
              VectorHit(
                v.key(),
                Option(v.distance()).map(_.doubleValue),
                fromDocument(v.metadata())
              )
            )
        )
      catch case e: Exception => Left(AwsErrors.classify("s3vectors:QueryVectors", e))
    }

  def getMetadata(
      index: VectorIndex,
      keys: Seq[String]
  ): Either[StorageError, Map[String, Map[String, MetaValue]]] =
    VectorStore.checkGet(keys).flatMap { _ =>
      try
        val resp = client.getVectors(
          GetVectorsRequest
            .builder()
            .vectorBucketName(vectorBucket)
            .indexName(index.physicalName(indexPrefix))
            .keys(keys.asJava)
            .returnMetadata(true)
            .returnData(false)
            .build()
        )
        Right(resp.vectors().asScala.toList.map(v => v.key() -> fromDocument(v.metadata())).toMap)
      catch case e: Exception => Left(AwsErrors.classify("s3vectors:GetVectors", e))
    }

  private def toValue(v: MetaValue): Document = v match
    case MetaValue.Str(s) => Document.fromString(s)
    case MetaValue.Num(n) => Document.fromNumber(n)
