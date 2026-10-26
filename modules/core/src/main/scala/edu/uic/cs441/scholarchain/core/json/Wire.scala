package edu.uic.cs441.scholarchain.core.json

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.validation.{Validation, ValidationError, Validator}
import scala.util.control.NonFatal
import upickle.default.{read, write, writeJs, ReadWriter}

/** Why a wire payload was rejected. */
enum WireError:
  /** Not JSON, or JSON that does not have the record's shape. */
  case Malformed(message: String)

  /** Well-shaped JSON that violates the contract. */
  case Invalid(errors: List[ValidationError])

/**
 * The single entry point for reading and writing shared records as JSON (uPickle).
 *
 * Decoding order (frozen): parse JSON; require a JSON object; gate on `schemaVersion` (missing,
 * non-integer, or unsupported versions are rejected before the shape is decoded, so a future shape
 * never reaches an old decoder); require a non-null top-level `provenance` object for records that
 * carry provenance; decode the shape; run the record's [[Validator]].
 */
object Wire:

  def encode[A: ReadWriter](a: A): String = write(a)

  /** Two-space indented form used by the golden fixtures. */
  def encodePretty[A: ReadWriter](a: A): String = write(a, indent = 2)

  def toJson[A: ReadWriter](a: A): ujson.Value = writeJs(a)

  /** Encodes only a valid record, so a producer cannot emit a contract violation. */
  def encodeValidated[A: ReadWriter: Validator](a: A): Either[List[ValidationError], String] =
    Validation.validate(a).map(encode(_))

  def decode[A: ReadWriter: Validator](json: String): Either[WireError, A] =
    parse(json).flatMap(decodeValue[A])

  def decodeValue[A](value: ujson.Value)(using
      rw: ReadWriter[A],
      v: Validator[A]
  ): Either[WireError, A] =
    for
      obj <- value.objOpt.toRight(WireError.Malformed("expected a JSON object"))
      _   <- versionGate(obj)
      _   <- provenanceGate(obj, v.requiresProvenance)
      a   <-
        try Right(read[A](value))
        catch case NonFatal(e) => Left(WireError.Malformed(String.valueOf(e.getMessage)))
      ok <- Validation.validate(a).left.map(WireError.Invalid(_))
    yield ok

  private def parse(json: String): Either[WireError, ujson.Value] =
    try Right(ujson.read(json))
    catch case NonFatal(e) => Left(WireError.Malformed(s"not JSON: ${e.getMessage}"))

  private def versionGate(obj: collection.Map[String, ujson.Value]): Either[WireError, Unit] =
    obj.get("schemaVersion") match
      case None =>
        Left(WireError.Invalid(List(ValidationError.InvalidField("schemaVersion", "missing"))))
      case Some(ujson.Num(n)) if n.isWhole =>
        if SchemaVersion.isSupported(n.toInt) then Right(())
        else
          Left(
            WireError.Invalid(
              List(
                ValidationError.UnsupportedSchemaVersion(
                  "schemaVersion",
                  n.toInt,
                  SchemaVersion.Supported.toList.sorted
                )
              )
            )
          )
      case Some(other) =>
        Left(
          WireError.Invalid(
            List(ValidationError.InvalidField("schemaVersion", s"must be an integer, got $other"))
          )
        )

  private def provenanceGate(
      obj: collection.Map[String, ujson.Value],
      required: Boolean
  ): Either[WireError, Unit] =
    if !required then Right(())
    else
      obj.get("provenance") match
        case Some(_: ujson.Obj)      => Right(())
        case Some(ujson.Null) | None =>
          Left(
            WireError.Invalid(
              List(ValidationError.MissingProvenance("provenance", "absent or null"))
            )
          )
        case Some(other) =>
          Left(
            WireError.Invalid(
              List(ValidationError.MissingProvenance("provenance", s"not an object: $other"))
            )
          )
