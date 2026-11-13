package edu.uic.cs441.scholarchain.services.http

import java.io.ByteArrayOutputStream
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Transport-independent request. `rawPath` and `rawQuery` are still percent-encoded; routes decode
 * them through [[PathCodec]] and [[QueryString]], so a malformed escape is a typed 400, never an
 * exception. Header names are lower-cased; only the first value of each header is kept.
 */
final case class HttpRequest(
    method: String,
    rawPath: String,
    rawQuery: String,
    headers: Map[String, String],
    body: Array[Byte]
):
  def header(name: String): Option[String] =
    headers.get(name.toLowerCase(Locale.ROOT)).map(_.trim).filter(_.nonEmpty)

object HttpRequest:
  def apply(
      method: String,
      target: String,
      headers: Map[String, String] = Map.empty,
      body: String = ""
  ): HttpRequest =
    val (p, q) = target.indexOf('?') match
      case -1 => (target, "")
      case i  => (target.take(i), target.drop(i + 1))
    new HttpRequest(
      method,
      p,
      q,
      headers.map((k, v) => k.toLowerCase(Locale.ROOT) -> v),
      body.getBytes(StandardCharsets.UTF_8)
    )

/** Transport-independent response. The body is always UTF-8 text. */
final case class HttpResponse(status: Int, headers: List[(String, String)], body: String):
  def withHeader(name: String, value: String): HttpResponse =
    copy(headers = headers.filterNot(_._1.equalsIgnoreCase(name)) :+ (name -> value))
  def header(name: String): Option[String] = headers.find(_._1.equalsIgnoreCase(name)).map(_._2)

object HttpResponse:
  val JsonType: String = "application/json; charset=utf-8"
  val TextType: String = "text/plain; version=0.0.4; charset=utf-8"

  def json(status: Int, body: String): HttpResponse =
    HttpResponse(status, List("Content-Type" -> JsonType), body)

  def text(status: Int, body: String, contentType: String = TextType): HttpResponse =
    HttpResponse(status, List("Content-Type" -> contentType), body)

/** Strict UTF-8 decoding: invalid byte sequences are an error, never replaced silently. */
object Utf8:
  def decode(bytes: Array[Byte]): Either[String, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch case _: CharacterCodingException => Left("body is not valid UTF-8")

/** Percent-decoding of one path segment (`+` stays `+`; only `%XX` escapes are decoded). */
object PathCodec:
  private def hex(c: Char): Int = Character.digit(c, 16)

  def decodeSegment(s: String): Either[String, String] =
    val out = ByteArrayOutputStream()
    var i   = 0
    var bad = false
    while i < s.length && !bad do
      val c = s.charAt(i)
      if c == '%' then
        if i + 2 < s.length && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0 then
          out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)))
          i += 3
        else bad = true
      else
        out.writeBytes(c.toString.getBytes(StandardCharsets.UTF_8))
        i += 1
    if bad then Left("malformed percent-escape in path")
    else Utf8.decode(out.toByteArray).left.map(_ => "path is not valid UTF-8")

  /** Splits a raw path into decoded segments. Empty segments (`//`, trailing `/`) are refused. */
  def segments(rawPath: String): Either[String, List[String]] =
    val trimmed = rawPath.stripPrefix("/")
    if trimmed.isEmpty then Right(Nil)
    else
      val parts = trimmed.split("/", -1).toList
      if parts.exists(_.isEmpty) then Left("empty path segment")
      else
        parts.foldRight[Either[String, List[String]]](Right(Nil)) { (p, acc) =>
          acc.flatMap(rest => decodeSegment(p).map(_ :: rest))
        }

/** Query-string parsing (`application/x-www-form-urlencoded` rules), bounded. */
object QueryString:
  val MaxParameters: Int = 32

  private def decode(s: String): Either[String, String] =
    PathCodec.decodeSegment(s.replace('+', ' '))

  def parse(raw: String): Either[String, Map[String, List[String]]] =
    if raw == null || raw.isEmpty then Right(Map.empty)
    else
      val pairs = raw.split("&", -1).toList.filter(_.nonEmpty)
      if pairs.size > MaxParameters then Left(s"more than $MaxParameters query parameters")
      else
        pairs
          .foldLeft[Either[String, List[(String, String)]]](Right(Nil)) { (acc, p) =>
            acc.flatMap { done =>
              val (k, v) = p.indexOf('=') match
                case -1 => (p, "")
                case i  => (p.take(i), p.drop(i + 1))
              for
                dk <- decode(k)
                dv <- decode(v)
              yield (dk, dv) :: done
            }
          }
          .map(_.reverse.groupMap(_._1)(_._2))
