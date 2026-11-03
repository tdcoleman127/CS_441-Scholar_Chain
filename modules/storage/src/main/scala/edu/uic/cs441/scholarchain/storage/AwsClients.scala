package edu.uic.cs441.scholarchain.storage

import java.time.Duration
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.{
  ApiCallAttemptTimeoutException,
  ApiCallTimeoutException,
  SdkClientException
}

/**
 * Client construction and error classification shared by every AWS adapter. Region and resource
 * names come from configuration; credentials come from the default provider chain (no secrets in
 * code or configuration).
 */
object AwsClients:

  /** SDK retries off (our [[Retry]] policy is the only retry layer) with bounded call timeouts. */
  def overrideConfiguration(
      apiCallTimeout: Duration = Duration.ofSeconds(10),
      apiCallAttemptTimeout: Duration = Duration.ofSeconds(5)
  ): ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .retryStrategy(AwsRetryStrategy.doNotRetry())
      .apiCallTimeout(apiCallTimeout)
      .apiCallAttemptTimeout(apiCallAttemptTimeout)
      .build()

object AwsErrors:

  /** Maps an SDK exception to a typed [[StorageError]]. */
  def classify(operation: String, e: Throwable): StorageError = e match
    case t: ApiCallAttemptTimeoutException => StorageError.Timeout(operation, t.getMessage)
    case t: ApiCallTimeoutException        => StorageError.Timeout(operation, t.getMessage)
    case s: AwsServiceException            =>
      if s.isThrottlingException || s.statusCode() == 429 then
        StorageError.Throttled(operation, s.getMessage)
      else if s.statusCode() >= 500 then StorageError.Unavailable(operation, s.getMessage)
      else StorageError.Failed(operation, s.getMessage)
    case c: SdkClientException => StorageError.Unavailable(operation, c.getMessage)
    case other                 => StorageError.Failed(operation, String.valueOf(other.getMessage))
