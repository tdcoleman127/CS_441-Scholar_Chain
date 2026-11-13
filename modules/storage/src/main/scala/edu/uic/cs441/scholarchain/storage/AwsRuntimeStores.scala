package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.core.config.ScholarChainConfig
import edu.uic.cs441.scholarchain.storage.kv.{
  DynamoDbKeyValueStore,
  KeyValueStore,
  Table,
  TableItem
}
import edu.uic.cs441.scholarchain.storage.objectstore.{
  ObjectKey,
  ObjectStore,
  S3ObjectStore,
  StoredObject
}
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client

/**
 * Plain-value settings for the runtime (AWS mode) stores. No AWS SDK type appears here, so a caller
 * outside `storage` can build the stores without depending on the SDK.
 *
 * Credentials are not a setting: the clients use the SDK default provider chain (the ECS task role
 * in Fargate). Nothing here is secret.
 *
 * @param region
 *   AWS Region code, for example `us-east-1`
 * @param curatedBucket
 *   S3 bucket holding the release exports (`storage.curated-bucket`)
 * @param dynamoDbTablePrefix
 *   physical table names are `<prefix>-<logical>` (`storage.dynamodb-table-prefix`)
 * @param apiCallTimeout
 *   total time for one SDK call, in `(0, 60 s]`
 * @param apiCallAttemptTimeout
 *   time for one HTTP attempt, in `(0, apiCallTimeout]`
 */
final case class AwsRuntimeSettings(
    region: String,
    curatedBucket: String,
    dynamoDbTablePrefix: String,
    apiCallTimeout: Duration = AwsRuntimeSettings.DefaultApiCallTimeout,
    apiCallAttemptTimeout: Duration = AwsRuntimeSettings.DefaultApiCallAttemptTimeout
)

object AwsRuntimeSettings:
  val DefaultApiCallTimeout: Duration        = Duration.ofSeconds(10)
  val DefaultApiCallAttemptTimeout: Duration = Duration.ofSeconds(5)
  val MaxApiCallTimeout: Duration            = Duration.ofSeconds(60)

  /**
   * Region code shape: `us-east-1`, `us-gov-west-1`, `eu-isoe-west-1`. Not `aws-global` or an AZ.
   */
  private val RegionShape = """^[a-z]{2}(-[a-z]+)+-[0-9]{1,2}$""".r

  private val BucketShape     = """^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$""".r
  private val IpShape         = """^[0-9]{1,3}(\.[0-9]{1,3}){3}$""".r
  private val BucketPrefixes  = List("xn--", "sthree-", "amzn-s3-demo-")
  private val BucketSuffixes  = List("-s3alias", "--ol-s3", ".mrap", "--x-s3", "--table-s3")
  private val TableNameShape  = """^[A-Za-z0-9_.-]{3,255}$""".r
  private val TablePrefixChar = """^[A-Za-z0-9_.-]+$""".r

  /** Settings from the validated core configuration, with the default bounded timeouts. */
  def fromConfig(cfg: ScholarChainConfig): AwsRuntimeSettings =
    AwsRuntimeSettings(cfg.aws.region, cfg.storage.curatedBucket, cfg.storage.dynamoDbTablePrefix)

  /**
   * Checks every setting before any client is built. Rules: S3 general purpose bucket naming rules
   * and DynamoDB table naming rules (3..255 of `[A-Za-z0-9_.-]`, applied to every physical table
   * name `<prefix>-<logical>`).
   */
  def validate(s: AwsRuntimeSettings): Either[StorageError, AwsRuntimeSettings] =
    for
      _ <- check(RegionShape.matches(s.region), "aws.region", s"'${s.region}' is not a Region code")
      _ <- validateBucket(s.curatedBucket)
      _ <- validateTablePrefix(s.dynamoDbTablePrefix)
      _ <- validateTimeouts(s.apiCallTimeout, s.apiCallAttemptTimeout)
    yield s

  private def check(ok: Boolean, field: String, reason: => String): Either[StorageError, Unit] =
    if ok then Right(()) else Left(StorageError.InvalidInput(field, reason))

  private def validateBucket(b: String): Either[StorageError, Unit] =
    val field = "storage.curated-bucket"
    for
      _ <- check(BucketShape.matches(b), field, s"'$b' violates S3 bucket naming rules")
      _ <- check(!b.contains(".."), field, s"'$b' contains adjacent periods")
      _ <- check(!IpShape.matches(b), field, s"'$b' is formatted as an IP address")
      _ <- check(!BucketPrefixes.exists(b.startsWith), field, s"'$b' uses a reserved prefix")
      _ <- check(!BucketSuffixes.exists(b.endsWith), field, s"'$b' uses a reserved suffix")
    yield ()

  private def validateTablePrefix(p: String): Either[StorageError, Unit] =
    val field = "storage.dynamodb-table-prefix"
    for
      _ <- check(TablePrefixChar.matches(p), field, s"'$p' must be non-empty [A-Za-z0-9_.-]")
      _ <- Table.values.toList.find(t => !TableNameShape.matches(t.physicalName(p))) match
        case Some(t) =>
          Left(StorageError.InvalidInput(
            field,
            s"'${t.physicalName(p)}' is not a valid table name"
          ))
        case None => Right(())
    yield ()

  private def validateTimeouts(call: Duration, attempt: Duration): Either[StorageError, Unit] =
    for
      _ <- check(
        call != null && !call.isNegative && !call.isZero && call.compareTo(MaxApiCallTimeout) <= 0,
        "apiCallTimeout",
        s"must be in (0, $MaxApiCallTimeout]"
      )
      _ <- check(
        attempt != null && !attempt.isNegative && !attempt.isZero && attempt.compareTo(call) <= 0,
        "apiCallAttemptTimeout",
        "must be in (0, apiCallTimeout]"
      )
    yield ()

/**
 * The stores the runtime uses in AWS mode, with the SDK clients behind them. Exposes only storage
 * port types; the SDK clients are private to `storage`.
 *
 * `close()` releases every SDK client. It is idempotent and thread safe, and never throws (it is
 * meant for a shutdown hook). After `close()`, every store call fails fast with the non-retryable
 * `StorageError.Failed("<operation>", "store closed")` and never reaches the SDK.
 */
final class AwsRuntimeStores private (
    objectsInner: ObjectStore,
    keyValueInner: KeyValueStore,
    resources: List[AutoCloseable]
) extends AutoCloseable:
  private val closed = AtomicBoolean(false)

  private def whileOpen[A](operation: String)(f: => Either[StorageError, A])
      : Either[StorageError, A] =
    if closed.get then Left(StorageError.Failed(operation, "store closed")) else f

  /** S3 object store over the curated bucket. */
  val objects: ObjectStore = new ObjectStore:
    def putIfAbsent(
        key: ObjectKey,
        bytes: Array[Byte],
        contentType: String
    ): Either[StorageError, WriteOutcome] =
      whileOpen("s3:PutObject")(objectsInner.putIfAbsent(key, bytes, contentType))
    def get(key: ObjectKey): Either[StorageError, Option[StoredObject]] =
      whileOpen("s3:GetObject")(objectsInner.get(key))

  /** DynamoDB key-value store over `<dynamoDbTablePrefix>-<logical>` tables. */
  val keyValue: KeyValueStore = new KeyValueStore:
    def putIfAbsent(table: Table, item: TableItem): Either[StorageError, WriteOutcome] =
      whileOpen("dynamodb:PutItem")(keyValueInner.putIfAbsent(table, item))
    def get(table: Table, pk: String, sk: String): Either[StorageError, Option[TableItem]] =
      whileOpen("dynamodb:GetItem")(keyValueInner.get(table, pk, sk))
    def updateIfVersion(
        table: Table,
        item: TableItem,
        expectedVersion: Long
    ): Either[StorageError, Unit] =
      whileOpen("dynamodb:PutItem")(keyValueInner.updateIfVersion(table, item, expectedVersion))
    def queryPartition(
        table: Table,
        pk: String,
        limit: Int
    ): Either[StorageError, List[TableItem]] =
      whileOpen("dynamodb:Query")(keyValueInner.queryPartition(table, pk, limit))

  def isClosed: Boolean = closed.get

  def close(): Unit =
    if closed.compareAndSet(false, true) then resources.foreach(AwsRuntimeStores.closeQuietly)

object AwsRuntimeStores:

  /**
   * Validates `settings`, then builds the S3 and DynamoDB clients (region from settings, SDK
   * retries off, bounded call and attempt timeouts, default credentials provider chain). Builds no
   * cloud resource and makes no network call. On any failure, clients already built are closed.
   */
  def open(settings: AwsRuntimeSettings): Either[StorageError, AwsRuntimeStores] =
    AwsRuntimeSettings.validate(settings).flatMap { s =>
      var built = List.empty[AutoCloseable]
      try
        val s3 = buildS3(s)
        built = s3 :: built
        val ddb = buildDynamoDb(s)
        built = ddb :: built
        Right(
          AwsRuntimeStores(
            S3ObjectStore(s3, s.curatedBucket),
            DynamoDbKeyValueStore(ddb, s.dynamoDbTablePrefix),
            List(s3, ddb)
          )
        )
      catch
        case NonFatal(e) =>
          built.foreach(closeQuietly)
          Left(StorageError.Failed("aws:client-build", e.getClass.getName))
    }

  /** [[open]] with settings taken from the validated core configuration. */
  def fromConfig(cfg: ScholarChainConfig): Either[StorageError, AwsRuntimeStores] =
    open(AwsRuntimeSettings.fromConfig(cfg))

  // No credentialsProvider is set: the SDK default provider chain resolves credentials lazily, on
  // the first call (the ECS task role in Fargate). Nothing is read from code or a committed file.
  private[storage] def buildS3(s: AwsRuntimeSettings): S3Client =
    S3Client
      .builder()
      .region(Region.of(s.region))
      .overrideConfiguration(
        AwsClients.overrideConfiguration(s.apiCallTimeout, s.apiCallAttemptTimeout)
      )
      .build()

  private[storage] def buildDynamoDb(s: AwsRuntimeSettings): DynamoDbClient =
    DynamoDbClient
      .builder()
      .region(Region.of(s.region))
      .overrideConfiguration(
        AwsClients.overrideConfiguration(s.apiCallTimeout, s.apiCallAttemptTimeout)
      )
      .build()

  /** Test seam: wraps given stores and resources without building SDK clients. */
  private[storage] def assemble(
      objects: ObjectStore,
      keyValue: KeyValueStore,
      resources: List[AutoCloseable]
  ): AwsRuntimeStores =
    AwsRuntimeStores(objects, keyValue, resources)

  private[storage] def closeQuietly(r: AutoCloseable): Unit =
    try r.close()
    catch case NonFatal(_) => ()
