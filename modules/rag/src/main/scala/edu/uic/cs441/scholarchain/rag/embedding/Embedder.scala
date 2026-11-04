package edu.uic.cs441.scholarchain.rag.embedding

import edu.uic.cs441.scholarchain.storage.vectors.EmbeddingSupplier
import java.text.Normalizer
import scala.util.hashing.MurmurHash3

/**
 * Embedding model versions, recorded on every vector (`modelVersion` metadata) and every answer.
 * The fake has its own version so fake vectors can never be mistaken for, or retrieved with,
 * production ones: retrieval always filters on the embedder's `modelVersion`.
 */
object EmbeddingModels:
  /**
   * Production model: Amazon Titan Text Embeddings V2 on Bedrock, 1024 dimensions. Not called in
   * this phase. The ID could not be checked against AWS documentation this session (risk).
   */
  val ProductionModelId: String = "amazon.titan-embed-text-v2:0"
  val ProductionDimension: Int  = 1024
  val FakeModelVersion: String  = "scholarchain-fake-hashed-bow-v1"
  val FakeDimension: Int        = 256
  val MaxTextsPerCall: Int      = 100

/** Embedder port. `embed` returns one vector of `dimension` floats per text, in order. */
trait Embedder:
  def modelVersion: String
  def dimension: Int
  def embed(texts: Seq[String]): Either[String, Seq[Vector[Float]]]

object Embedder:
  /** Adapts the port to storage's ingestion function (storage holds no model client). */
  def toSupplier(e: Embedder): EmbeddingSupplier =
    EmbeddingSupplier(e.modelVersion, e.dimension, texts => e.embed(texts))

/**
 * Deterministic fake: a signed, hashed bag of tokens, L2-normalized. Texts that share tokens get
 * similar vectors, so nearest-neighbor tests are meaningful. Tokens are NFC-lowercased runs of
 * letters and digits; the bucket and sign come from MurmurHash3 with fixed seeds (stable across
 * JVMs). A text with no token is refused (Left), never embedded as a zero vector.
 */
final class HashedBagOfTokensEmbedder(
    val dimension: Int = EmbeddingModels.FakeDimension,
    val modelVersion: String = EmbeddingModels.FakeModelVersion
) extends Embedder:
  require(dimension >= 8, "dimension must be at least 8")

  def tokens(text: String): List[String] =
    Normalizer
      .normalize(text, Normalizer.Form.NFC)
      .toLowerCase(java.util.Locale.ROOT)
      .split("[^\\p{L}\\p{N}]+")
      .toList
      .filter(_.nonEmpty)

  private def one(text: String): Either[String, Vector[Float]] =
    val ts = tokens(text)
    if ts.isEmpty then Left("text has no tokens")
    else
      val acc = new Array[Double](dimension)
      ts.foreach { t =>
        val bucket = Math.floorMod(MurmurHash3.stringHash(t, 0x5c1a1), dimension)
        val sign   = if (MurmurHash3.stringHash(t, 0x0b5e7) & 1) == 0 then 1.0 else -1.0
        acc(bucket) += sign
      }
      val norm = math.sqrt(acc.map(x => x * x).sum)
      if norm == 0 then Left("text tokens cancel out")
      else Right(acc.toVector.map(x => (x / norm).toFloat))

  def embed(texts: Seq[String]): Either[String, Seq[Vector[Float]]] =
    if texts.size > EmbeddingModels.MaxTextsPerCall then
      Left(s"at most ${EmbeddingModels.MaxTextsPerCall} texts per call")
    else
      texts.zipWithIndex.foldLeft[Either[String, Vector[Vector[Float]]]](Right(Vector.empty)) {
        case (Left(e), _)         => Left(e)
        case (Right(acc), (t, i)) => one(t).left.map(e => s"text $i: $e").map(acc :+ _)
      }
