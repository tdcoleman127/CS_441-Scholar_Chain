package edu.uic.cs441.scholarchain.services.security

import edu.uic.cs441.scholarchain.core.ids.CanonicalHash
import edu.uic.cs441.scholarchain.services.api.ApiError
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.mutable
import scala.util.Try

/** Permissions a route can require. */
enum Scope(val wire: String):
  case QueryWrite   extends Scope("query:write")
  case QueryRead    extends Scope("query:read")
  case EvidenceRead extends Scope("evidence:read")
  case GraphRead    extends Scope("graph:read")
  case OpsMetrics   extends Scope("ops:metrics")

object Scope:
  def fromWire(s: String): Option[Scope] = values.find(_.wire == s)

/** An authenticated caller. `id` is a non-secret label; it is logged, the credential never is. */
final case class Principal(id: String, scopes: Set[Scope]):
  def has(scope: Scope): Boolean = scopes.contains(scope)

/** One configured API key: the SHA-256 hex of the token, never the token itself. */
final case class ApiKeyEntry(principal: String, sha256: String, scopes: Set[Scope])

/**
 * Bearer API-key authentication (course deployment).
 *
 * Keys are configured as SHA-256 digests in `SCHOLARCHAIN_API_KEYS` (a JSON array injected into the
 * ECS task from AWS Secrets Manager by ARN). The service never holds a raw key at rest: a presented
 * `Authorization: Bearer <token>` is hashed and compared against every configured digest with a
 * constant-time comparison, scanning all entries so the match position is not observable.
 */
final class ApiKeyAuthenticator(entries: List[ApiKeyEntry]):
  private val digests: List[(Array[Byte], Principal)] =
    entries.map(e =>
      (e.sha256.getBytes(StandardCharsets.US_ASCII), Principal(e.principal, e.scopes))
    )

  def size: Int = entries.size

  def authenticate(authorization: Option[String]): Either[ApiError, Principal] =
    authorization match
      case None    => Left(ApiError.Unauthenticated("missing Authorization header"))
      case Some(h) =>
        val parts = h.trim.split("\\s+", 2)
        if parts.length != 2 || !parts(0).equalsIgnoreCase("Bearer") then
          Left(ApiError.Unauthenticated("expected the Bearer scheme"))
        else
          val token = parts(1).trim
          if !ApiKeys.TokenShape.matches(token) then
            Left(ApiError.Unauthenticated("malformed bearer token"))
          else
            val presented = ApiKeys.digest(token).getBytes(StandardCharsets.US_ASCII)
            var found     = Option.empty[Principal]
            digests.foreach { (d, p) =>
              if MessageDigest.isEqual(d, presented) && found.isEmpty then found = Some(p)
            }
            found.toRight(ApiError.Unauthenticated("unknown credential"))

object ApiKeys:
  val EnvVar: String         = "SCHOLARCHAIN_API_KEYS"
  val MaxEntries: Int        = 100
  val TokenShape             = """^[A-Za-z0-9._~-]{32,256}$""".r
  private val PrincipalShape = """^[a-z0-9][a-z0-9-]{0,63}$""".r
  private val Hex64          = """^[0-9a-f]{64}$""".r

  /** Lower-case SHA-256 hex of the token's UTF-8 bytes. */
  def digest(token: String): String =
    CanonicalHash.sha256Hex(token.getBytes(StandardCharsets.UTF_8))

  /**
   * Parses `[{"principal": "...", "sha256": "<64 hex>", "scopes": ["query:write", ...]}, ...]`.
   * Error messages name the entry index and field only; they never echo configured values.
   */
  def parse(json: String): Either[String, List[ApiKeyEntry]] =
    Try(ujson.read(json)).toOption match
      case None                                                => Left(s"$EnvVar is not valid JSON")
      case Some(arr: ujson.Arr) if arr.value.isEmpty           => Left(s"$EnvVar has no entries")
      case Some(arr: ujson.Arr) if arr.value.size > MaxEntries =>
        Left(s"$EnvVar has more than $MaxEntries entries")
      case Some(arr: ujson.Arr) =>
        val parsed = arr.value.toList.zipWithIndex.map((v, i) => entry(v, i))
        parsed.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None    =>
            val ok = parsed.collect { case Right(e) => e }
            if ok.map(_.principal).distinct.size != ok.size then
              Left(s"$EnvVar has duplicate principals")
            else if ok.map(_.sha256).distinct.size != ok.size then
              Left(s"$EnvVar has duplicate key digests")
            else Right(ok)
      case Some(_) => Left(s"$EnvVar must be a JSON array")

  private def entry(v: ujson.Value, i: Int): Either[String, ApiKeyEntry] =
    def str(name: String): Option[String] = v.objOpt.flatMap(_.get(name)).flatMap(_.strOpt)
    val scopes = v.objOpt.flatMap(_.get("scopes")).flatMap(_.arrOpt).map(_.toList)
    for
      p  <- str("principal").filter(PrincipalShape.matches).toRight(s"entry $i: invalid principal")
      h  <- str("sha256").filter(Hex64.matches).toRight(s"entry $i: sha256 must be 64 hex chars")
      ss <- scopes.toRight(s"entry $i: scopes must be an array")
      sc <- ss
        .map(_.strOpt.flatMap(Scope.fromWire))
        .foldRight[Either[String, List[Scope]]](Right(Nil)) {
          case (Some(s), Right(acc)) => Right(s :: acc)
          case (None, _)             => Left(s"entry $i: unknown scope")
          case (_, l)                => l
        }
      _ <- Either.cond(
        v.objOpt.exists(_.keySet.subsetOf(Set("principal", "sha256", "scopes"))),
        (),
        s"entry $i: unknown field"
      )
    yield ApiKeyEntry(p, h, sc.toSet)

/**
 * Per-principal token bucket. `ratePerSecond` tokens are added continuously up to `burst`; each
 * request takes one. The map is bounded by the number of configured principals.
 */
final class RateLimiter(ratePerSecond: Double, burst: Int, nanoTime: () => Long):
  final private case class Bucket(tokens: Double, at: Long)
  private val buckets = mutable.Map.empty[String, Bucket]

  def acquire(principal: String): Either[ApiError, Unit] = synchronized {
    val now     = nanoTime()
    val b       = buckets.getOrElse(principal, Bucket(burst.toDouble, now))
    val elapsed = (now - b.at).max(0L) / 1e9
    val tokens  = (b.tokens + elapsed * ratePerSecond).min(burst.toDouble)
    if tokens >= 1.0 then
      buckets(principal) = Bucket(tokens - 1.0, now)
      Right(())
    else
      buckets(principal) = Bucket(tokens, now)
      val wait = math.ceil((1.0 - tokens) / ratePerSecond).toInt.max(1)
      Left(ApiError.RateLimited(wait))
  }
