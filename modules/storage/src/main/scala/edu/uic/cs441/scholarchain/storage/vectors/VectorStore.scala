package edu.uic.cs441.scholarchain.storage.vectors

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind}
import edu.uic.cs441.scholarchain.core.model.{DocumentType, Provenance, SourceType}
import edu.uic.cs441.scholarchain.storage.StorageError
import edu.uic.cs441.scholarchain.storage.objectstore.{ObjectKey, S3Prefix}

/**
 * Logical vector indexes. The physical index name is `<configured vectorIndex>-<wire>`, inside the
 * configured vector bucket; indexes are created by Terraform, never here.
 */
enum VectorIndex(val wire: String):
  case ResearcherProfile       extends VectorIndex("researcher-profile-v1")
  case CollaborationCommunity  extends VectorIndex("collaboration-community-v1")
  case PaperAbstract           extends VectorIndex("paper-abstract-v1")
  case CollaborationPath       extends VectorIndex("collaboration-path-v1")
  case TopicInstitutionSummary extends VectorIndex("topic-institution-summary-v1")

  def physicalName(configuredPrefix: String): String = s"$configuredPrefix-$wire"

/** A metadata value: S3 Vectors metadata here is limited to strings and integers. */
enum MetaValue:
  case Str(value: String)
  case Num(value: Long)

/**
 * Compact vector metadata. Filterable keys: entityId, docType, windowStart, windowEnd,
 * sourceDataset, sourceRecordId, pipelineRunId, modelVersion, textHash. Non-filterable: textRef
 * (the S3 key under `semantic-context/` holding the long text, which is never stored in the index).
 */
final case class VectorMetadata(
    entityId: String,
    docType: DocumentType,
    window: Option[(Int, Int)],
    provenance: Provenance,
    modelVersion: String,
    textHash: String,
    textRef: ObjectKey
):
  def toMap: Map[String, MetaValue] =
    Map(
      "entityId"       -> MetaValue.Str(entityId),
      "docType"        -> MetaValue.Str(docType.wire),
      "sourceDataset"  -> MetaValue.Str(provenance.sourceDataset.wire),
      "sourceRecordId" -> MetaValue.Str(provenance.sourceRecordId),
      "pipelineRunId"  -> MetaValue.Str(provenance.pipelineRunId),
      "modelVersion"   -> MetaValue.Str(modelVersion),
      "textHash"       -> MetaValue.Str(textHash),
      "textRef"        -> MetaValue.Str(textRef.value)
    ) ++ window.toList.flatMap((s, e) =>
      List("windowStart" -> MetaValue.Num(s.toLong), "windowEnd" -> MetaValue.Num(e.toLong))
    )

object VectorMetadata:
  val Filterable: Set[String] = Set(
    "entityId",
    "docType",
    "windowStart",
    "windowEnd",
    "sourceDataset",
    "sourceRecordId",
    "pipelineRunId",
    "modelVersion",
    "textHash"
  )
  val NonFilterable: Set[String] = Set("textRef")
  val Required: Set[String]      = (Filterable -- Set("windowStart", "windowEnd")) ++ NonFilterable

  /** Longest allowed string value; longer text belongs in S3 behind `textRef`. */
  val MaxValueChars: Int = 256

  /** Budget for the UTF-8 size of all filterable metadata of one vector. */
  val MaxFilterableBytes: Int = 2048

  /** Validates raw metadata; returns every problem found. */
  def validate(m: Map[String, MetaValue]): List[String] =
    def str(k: String): Option[String] = m.get(k).collect { case MetaValue.Str(v) => v }
    def num(k: String): Option[Long]   = m.get(k).collect { case MetaValue.Num(v) => v }
    val unknown = (m.keySet -- Filterable -- NonFilterable).toList.sorted.map(k =>
      s"unknown key $k"
    )
    val missing = (Required -- m.keySet).toList.sorted.map(k => s"missing key $k")
    val tooLong = m.toList.sortBy(_._1).collect {
      case (k, MetaValue.Str(v)) if v.length > MaxValueChars =>
        s"$k exceeds $MaxValueChars characters (store long text in S3 via textRef)"
    }
    val filterableBytes = m.collect {
      case (k, MetaValue.Str(v)) if Filterable(k) => k.length + v.getBytes("UTF-8").length
      case (k, MetaValue.Num(_)) if Filterable(k) => k.length + 8
    }.sum
    val size =
      if filterableBytes > MaxFilterableBytes then
        List(s"filterable metadata is $filterableBytes bytes (max $MaxFilterableBytes)")
      else Nil
    val types = List("windowStart", "windowEnd").collect {
      case k if m.get(k).exists(_.isInstanceOf[MetaValue.Str]) => s"$k must be an integer"
    } ++ Required.toList.sorted.collect {
      case k if m.get(k).exists(_.isInstanceOf[MetaValue.Num]) => s"$k must be a string"
    }
    val values = List(
      str("docType").filter(d => !DocumentType.values.exists(_.wire == d)).map(d =>
        s"unknown docType $d"
      ),
      str("sourceDataset").filter(SourceType.fromWire(_).isEmpty).map(d =>
        s"unknown sourceDataset $d"
      ),
      str("pipelineRunId").filterNot(IdFormat.isValid(IdKind.Run, _)).map(_ =>
        "pipelineRunId is not a run:v1 ID"
      ),
      str("textHash").filterNot(IdFormat.isValid(IdKind.ContentHash, _)).map(_ =>
        "textHash is not sha256:<hex>"
      ),
      str("textRef").filterNot(r =>
        r.startsWith(S3Prefix.SemanticContext.wire + "/") && ObjectKey.parse(r).isRight
      ).map(_ => "textRef must be an S3 key under semantic-context/"),
      str("entityId").filterNot(IdFormat.isAnyKnown).map(_ => "entityId is not a stable ID"),
      str("sourceRecordId").filter(_.trim.isEmpty).map(_ => "sourceRecordId is blank"),
      (num("windowStart"), num("windowEnd")) match
        case (Some(s), Some(e)) if s > e       => Some("windowStart exceeds windowEnd")
        case (Some(_), None) | (None, Some(_)) =>
          Some("windowStart and windowEnd go together")
        case _ => None
    ).flatten
    unknown ++ missing ++ tooLong ++ size ++ types ++ values

  /** Provenance read back from metadata, when it is well formed. */
  def provenanceOf(m: Map[String, MetaValue]): Option[Provenance] =
    def str(k: String) = m.get(k).collect { case MetaValue.Str(v) => v }
    for
      ds  <- str("sourceDataset").flatMap(SourceType.fromWire)
      rec <- str("sourceRecordId")
      run <- str("pipelineRunId")
    yield Provenance(ds, rec, run)

/** One vector to store. `key` is the core `chunk:v1:<hex>` ID of the embedded text. */
final case class VectorRecord(key: String, data: Vector[Float], metadata: Map[String, MetaValue])

final case class VectorHit(key: String, distance: Option[Double], metadata: Map[String, MetaValue])

/**
 * Vector storage port. `putBatch` is an upsert keyed by the stable vector key, so replays are
 * idempotent. `query` returns at most `topK` hits matching the equality `filter` (filterable keys
 * only).
 */
trait VectorStore:
  def putBatch(index: VectorIndex, vectors: Seq[VectorRecord]): Either[StorageError, Unit]
  def query(
      index: VectorIndex,
      vector: Vector[Float],
      topK: Int,
      filter: Map[String, MetaValue]
  ): Either[StorageError, List[VectorHit]]

  /**
   * Stored metadata of the given keys (GetVectors with metadata, without data). Keys that are not
   * stored are absent from the result. At most [[VectorStore.MaxGetKeys]] keys per call. A read, so
   * safe to retry.
   */
  def getMetadata(
      index: VectorIndex,
      keys: Seq[String]
  ): Either[StorageError, Map[String, Map[String, MetaValue]]]

object VectorStore:
  /** Upper bound on vectors in one PutVectors call accepted by this layer. */
  val MaxBatchSize: Int = 500
  val MaxTopK: Int      = 30

  /** Upper bound on keys in one GetVectors call accepted by this layer. */
  val MaxGetKeys: Int = 100

  def checkGet(keys: Seq[String]): Either[StorageError, Unit] =
    if keys.isEmpty || keys.size > MaxGetKeys then
      Left(StorageError.InvalidInput("keys", s"must hold 1..$MaxGetKeys keys"))
    else if keys.distinct.size != keys.size then
      Left(StorageError.InvalidInput("keys", "duplicate keys in one lookup"))
    else Right(())

  def checkQuery(
      vector: Vector[Float],
      topK: Int,
      filter: Map[String, MetaValue]
  ): Either[StorageError, Unit] =
    if topK < 1 || topK > MaxTopK then
      Left(StorageError.InvalidInput("topK", s"must be in 1..$MaxTopK"))
    else if vector.isEmpty || vector.exists(f => f.isNaN || f.isInfinite) then
      Left(StorageError.InvalidInput("vector", "must be non-empty and finite"))
    else if !filter.keySet.subsetOf(VectorMetadata.Filterable) then
      Left(StorageError.InvalidInput("filter", "only filterable keys may be used"))
    else Right(())

  def checkBatch(vectors: Seq[VectorRecord]): Either[StorageError, Unit] =
    if vectors.size > MaxBatchSize then
      Left(StorageError.InvalidInput("vectors", s"batch exceeds $MaxBatchSize"))
    else if vectors.map(_.key).distinct.size != vectors.size then
      Left(StorageError.InvalidInput("vectors", "duplicate keys in one batch"))
    else Right(())
