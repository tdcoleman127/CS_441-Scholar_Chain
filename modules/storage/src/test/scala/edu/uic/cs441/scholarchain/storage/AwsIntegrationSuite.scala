package edu.uic.cs441.scholarchain.storage

import edu.uic.cs441.scholarchain.storage.kv.{DynamoDbKeyValueStore, RunLedgerTable}
import edu.uic.cs441.scholarchain.storage.objectstore.{RunManifestWriter, S3ObjectStore}
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client

/**
 * Live-AWS contract tests. Skipped unless ALL of these environment variables are set:
 * `SCHOLARCHAIN_STORAGE_IT_PROFILE` (the approved AWS profile), `AWS_REGION`,
 * `SCHOLARCHAIN_IT_CURATED_BUCKET`, `SCHOLARCHAIN_IT_TABLE_PREFIX`. They only create-if-absent (no
 * deletes, no overwrites) against resources Terraform already created. Not run in `sbt check`
 * environments, which set none of these.
 */
class AwsIntegrationSuite extends munit.FunSuite:

  private def env(k: String): Option[String] = sys.env.get(k).filter(_.trim.nonEmpty)

  private val settings =
    for
      profile <- env("SCHOLARCHAIN_STORAGE_IT_PROFILE")
      region  <- env("AWS_REGION")
      bucket  <- env("SCHOLARCHAIN_IT_CURATED_BUCKET")
      prefix  <- env("SCHOLARCHAIN_IT_TABLE_PREFIX")
    yield (profile, region, bucket, prefix)

  test("live S3: run manifest create-if-absent is idempotent") {
    assume(settings.isDefined, "live AWS integration tests are disabled")
    val (profile, region, bucket, _) = settings.get
    val client                       = S3Client.builder()
      .region(Region.of(region))
      .credentialsProvider(ProfileCredentialsProvider.create(profile))
      .overrideConfiguration(AwsClients.overrideConfiguration())
      .build()
    try
      val writer = RunManifestWriter(S3ObjectStore(client, bucket), "it/run-manifests")
      assert(writer.write(Fixtures.manifest).isRight)
      assertEquals(writer.write(Fixtures.manifest).map(_._2), Right(WriteOutcome.AlreadyPresent))
    finally client.close()
  }

  test("live DynamoDB: RunLedger manifest create-if-absent is idempotent") {
    assume(settings.isDefined, "live AWS integration tests are disabled")
    val (profile, region, _, prefix) = settings.get
    val client                       = DynamoDbClient.builder()
      .region(Region.of(region))
      .credentialsProvider(ProfileCredentialsProvider.create(profile))
      .overrideConfiguration(AwsClients.overrideConfiguration())
      .build()
    try
      val ledger = RunLedgerTable(DynamoDbKeyValueStore(client, prefix))
      assert(ledger.putManifest(Fixtures.manifest).isRight)
      assertEquals(ledger.putManifest(Fixtures.manifest), Right(WriteOutcome.AlreadyPresent))
    finally client.close()
  }
