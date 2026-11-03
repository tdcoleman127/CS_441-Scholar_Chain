package edu.uic.cs441.scholarchain.storage.objectstore

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{CanonicalHash, IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.json.Wire
import edu.uic.cs441.scholarchain.core.model.RunManifest
import edu.uic.cs441.scholarchain.storage.{Retry, RetryPolicy, StorageError, WriteOutcome}
import java.nio.charset.StandardCharsets

/** A stored object with its `sc-content-hash` metadata. */
final case class StoredObject(key: String, bytes: Array[Byte], contentHash: String):
  def text: String = String(bytes, StandardCharsets.UTF_8)

/**
 * Object storage port. Writes are create-if-absent: replaying identical bytes returns
 * `AlreadyPresent`, different bytes under an existing key return `ConditionalConflict`. There is no
 * overwrite and no delete: versioned keys make new content a new key.
 */
trait ObjectStore:
  def putIfAbsent(
      key: ObjectKey,
      bytes: Array[Byte],
      contentType: String
  ): Either[StorageError, WriteOutcome]

  def get(key: ObjectKey): Either[StorageError, Option[StoredObject]]

object ObjectStore:
  /** `sha256:<hex>` over the raw bytes; stored as the `sc-content-hash` user metadata. */
  def contentHash(bytes: Array[Byte]): String = "sha256:" + CanonicalHash.sha256Hex(bytes)

  val ContentHashMetadata: String = "sc-content-hash"

/** Top-level S3 prefixes. */
enum S3Prefix(val wire: String):
  case Raw             extends S3Prefix("raw")
  case Curated         extends S3Prefix("curated")
  case Graph           extends S3Prefix("graph")
  case SemanticContext extends S3Prefix("semantic-context")
  case Reports         extends S3Prefix("reports")
  case Logs            extends S3Prefix("logs")

/** A validated S3 object key. Build it through [[ObjectKey]] helpers only. */
final case class ObjectKey private (value: String)

object ObjectKey:
  private val Segment = """^[A-Za-z0-9][A-Za-z0-9._=:-]{0,199}$""".r

  def segment(field: String, s: String): Either[StorageError, String] =
    if Segment.matches(s) && !s.contains("..") then Right(s)
    else Left(StorageError.InvalidInput(field, s"'$s' is not a safe key segment"))

  /** `<prefix>/<artifact>/v<schema>/run=<runId>/<name>`; `runId` must be a `run:v1:` ID. */
  def versioned(
      prefix: S3Prefix,
      artifact: String,
      schemaVersion: Int,
      runId: String,
      name: String
  ): Either[StorageError, ObjectKey] =
    for
      a <- segment("artifact", artifact)
      _ <-
        if schemaVersion >= 1 then Right(())
        else Left(StorageError.InvalidInput("schemaVersion", "must be >= 1"))
      r <- runIdSegment(runId)
      n <- segment("name", name)
    yield ObjectKey(s"${prefix.wire}/$a/v$schemaVersion/run=$r/$n")

  /** Joins validated segments under an already validated key directory. */
  def child(parent: ObjectKey, name: String): Either[StorageError, ObjectKey] =
    segment("name", name).map(n => ObjectKey(s"${parent.value}/$n"))

  /** Directory key (no trailing slash) for a versioned artifact run. */
  def runDirectory(
      prefix: S3Prefix,
      artifact: String,
      schemaVersion: Int,
      runId: String
  ): Either[StorageError, ObjectKey] =
    for
      a <- segment("artifact", artifact)
      r <- runIdSegment(runId)
    yield ObjectKey(s"${prefix.wire}/$a/v$schemaVersion/run=$r")

  /** `<runManifestPrefix>/<runId>/run-manifest.json`. The prefix comes from configuration. */
  def runManifest(runManifestPrefix: String, runId: String): Either[StorageError, ObjectKey] =
    val parts = runManifestPrefix.stripSuffix("/").split('/').toList
    for
      ps <- parts.foldRight[Either[StorageError, List[String]]](Right(Nil)) { (p, acc) =>
        acc.flatMap(rest => segment("runManifestPrefix", p).map(_ :: rest))
      }
      r <- runIdSegment(runId)
    yield ObjectKey((ps :+ r :+ "run-manifest.json").mkString("/"))

  /** Accepts a key read back from a trusted store pointer (validated segment by segment). */
  def parse(raw: String): Either[StorageError, ObjectKey] =
    val parts = raw.split('/').toList
    if parts.isEmpty then Left(StorageError.InvalidInput("key", "empty"))
    else
      parts
        .foldRight[Either[StorageError, List[String]]](Right(Nil)) { (p, acc) =>
          acc.flatMap(rest => segment("key", p).map(_ :: rest))
        }
        .map(ps => ObjectKey(ps.mkString("/")))

  private def runIdSegment(runId: String): Either[StorageError, String] =
    if IdFormat.isValid(IdKind.Run, runId) then Right(runId)
    else Left(StorageError.InvalidInput("runId", s"'$runId' is not a run:v1 ID"))

/** Writes immutable run manifests (the provenance root) with create-if-absent semantics. */
final class RunManifestWriter(
    store: ObjectStore,
    runManifestPrefix: String,
    policy: RetryPolicy = RetryPolicy.Default
):
  def write(manifest: RunManifest): Either[StorageError, (ObjectKey, WriteOutcome)] =
    for
      _ <-
        if !SchemaVersion.isSupported(manifest.schemaVersion) then
          Left(StorageError.InvalidInput("schemaVersion", s"${manifest.schemaVersion}"))
        else if StableIds.runId(manifest) != manifest.runId then
          Left(StorageError.InvalidInput("runId", "does not match its formula"))
        else Right(())
      key <- ObjectKey.runManifest(runManifestPrefix, manifest.runId)
      bytes = Wire.encode(manifest).getBytes(StandardCharsets.UTF_8)
      out <- Retry.run(policy)(store.putIfAbsent(key, bytes, "application/json"))
    yield (key, out)
