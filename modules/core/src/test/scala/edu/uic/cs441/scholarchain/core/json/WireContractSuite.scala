package edu.uic.cs441.scholarchain.core.json

import edu.uic.cs441.scholarchain.core.{Fixtures, Golden, GoldenCatalog, SchemaVersion}
import edu.uic.cs441.scholarchain.core.model.*
import edu.uic.cs441.scholarchain.core.validation.ValidationError
import edu.uic.cs441.scholarchain.core.validation.Validators.given
import java.nio.charset.StandardCharsets
import scala.io.Source

/**
 * Frozen wire contract. Each file in src/test/resources/wire/ is the exact two-space-indented
 * uPickle encoding of a fixture record. A failing test here means the wire format changed: that is
 * a schema-version change and needs human approval and a migration note.
 */
class WireContractSuite extends munit.FunSuite:

  private def resource(name: String): String =
    val stream = getClass.getResourceAsStream(s"/wire/$name.json")
    assert(stream != null, s"missing golden fixture wire/$name.json")
    val src = Source.fromInputStream(stream, StandardCharsets.UTF_8.name)
    try src.mkString.replace("\r\n", "\n").stripSuffix("\n")
    finally src.close()

  private def checkGolden[A](g: Golden[A]): Unit =
    given upickle.default.ReadWriter[A]                           = g.rw
    given edu.uic.cs441.scholarchain.core.validation.Validator[A] = g.validator
    test(s"wire/${g.name}.json: byte-for-byte frozen, round-trips, and validates") {
      val golden = resource(g.name)
      assertEquals(Wire.encodePretty(g.value), golden, s"encoding of ${g.name} changed")
      assertEquals(ujson.read(golden), Wire.toJson(g.value), "structural mismatch")
      val decoded = Wire.decode[A](golden)
      assertEquals(decoded, Right(g.value))
      assertEquals(decoded.map(Wire.encodePretty(_)), Right(golden))
      assertEquals(Wire.encodeValidated(g.value).map(ujson.read(_)), Right(ujson.read(golden)))
      assertEquals(ujson.read(golden)("schemaVersion").num.toInt, SchemaVersion.Current)
    }

  private def isMalformed(r: Either[WireError, ?]): Boolean = r match
    case Left(WireError.Malformed(_)) => true
    case _                            => false

  private def isInvalid(r: Either[WireError, ?]): Boolean = r match
    case Left(WireError.Invalid(_)) => true
    case _                          => false

  GoldenCatalog.all.foreach(g => checkGolden(g))

  test("the golden catalog covers every shared record type exactly once") {
    val names = GoldenCatalog.all.map(_.name)
    assertEquals(names.distinct.size, names.size)
    assertEquals(names.size, 20)
  }

  // ---- Nullability and shape conventions ----------------------------------------------------

  test("None is written as an explicit JSON null, never omitted") {
    val json = ujson.read(Wire.encode(Fixtures.mention))
    assertEquals(json("affiliationText"), ujson.Null)
  }

  test("a missing Option field is rejected rather than read as None") {
    val json = ujson.read(Wire.encode(Fixtures.mention))
    json.obj.remove("affiliationText")
    assert(Wire.decode[AuthorMention](ujson.write(json)).isLeft)
  }

  test("a missing required list field is rejected") {
    val json = ujson.read(Wire.encode(Fixtures.work))
    json.obj.remove("externalIds")
    assert(clue(Wire.decode[WorkRecord](ujson.write(json))).isLeft)
  }

  test("an unknown enum wire value is rejected as malformed") {
    val json = ujson.read(Wire.encode(Fixtures.venue))
    json("venueKind") = "symposium"
    assert(isMalformed(Wire.decode[Venue](ujson.write(json))))
  }

  test("Scala case names are not accepted in place of wire values") {
    val json = ujson.read(Wire.encode(Fixtures.topic))
    json("provenance")("sourceDataset") = "OpenAlex"
    assert(Wire.decode[Topic](ujson.write(json)).isLeft)
  }

  test("non-JSON and non-object payloads are malformed") {
    assert(isMalformed(Wire.decode[Topic]("not json")))
    assert(isMalformed(Wire.decode[Topic]("[1,2]")))
  }

  test("a whole-number Double is written without a fraction and read back as a Double") {
    val json = ujson.read(Wire.encode(Fixtures.graphEdge))
    assertEquals(ujson.write(json("weight")), "2")
    assertEquals(Wire.decode[GraphEdge](ujson.write(json)).map(_.weight), Right(Some(2.0)))
  }

  // ---- Schema-version gate ------------------------------------------------------------------

  private def withVersion(json: String, v: ujson.Value): String =
    val j = ujson.read(json)
    j("schemaVersion") = v
    ujson.write(j)

  test("an unsupported schema version is rejected before the shape is decoded") {
    val future = ujson.Obj("schemaVersion" -> 2, "entirelyNewShape" -> true)
    assertEquals(
      Wire.decode[Topic](ujson.write(future)),
      Left(WireError.Invalid(List(ValidationError.UnsupportedSchemaVersion(
        "schemaVersion",
        2,
        List(1)
      ))))
    )
    assertEquals(
      Wire.decode[CoauthorEdge](withVersion(Wire.encode(Fixtures.coauthorEdge), 0)),
      Left(WireError.Invalid(List(ValidationError.UnsupportedSchemaVersion(
        "schemaVersion",
        0,
        List(1)
      ))))
    )
  }

  test("a missing or non-integer schema version is rejected") {
    val j = ujson.read(Wire.encode(Fixtures.topic))
    j.obj.remove("schemaVersion")
    assert(isInvalid(Wire.decode[Topic](ujson.write(j))))
    assert(Wire.decode[Topic](withVersion(Wire.encode(Fixtures.topic), "1")).isLeft)
    assert(Wire.decode[Topic](withVersion(Wire.encode(Fixtures.topic), 1.5)).isLeft)
  }

  test("a nested record with an unsupported schema version is rejected with its path") {
    val bad = Fixtures.answer.copy(claims = Fixtures.answer.claims.map(_.copy(schemaVersion = 7)))
    val result = Wire.decode[AnswerResponse](Wire.encode(bad))
    assert(
      clue(result).left.exists {
        case WireError.Invalid(errs) =>
          errs.contains(ValidationError.UnsupportedSchemaVersion(
            "claims[0].schemaVersion",
            7,
            List(1)
          ))
        case _ => false
      }
    )
  }

  test("encodeValidated refuses to emit an invalid record") {
    assert(Wire.encodeValidated(Fixtures.topic.copy(schemaVersion = 2)).isLeft)
  }

  test("schema version constants: the current version is supported") {
    assert(SchemaVersion.isSupported(SchemaVersion.Current))
    assertEquals(SchemaVersion.Current, 1)
    assertEquals(SchemaVersion.ConfigCurrent, 1)
    assert(SchemaVersion.ConfigSupported.contains(SchemaVersion.ConfigCurrent))
    assert(
      SchemaVersion.ResearcherIdentitySupported.contains(SchemaVersion.ResearcherIdentityCurrent)
    )
  }
