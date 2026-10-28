package edu.uic.cs441.scholarchain.hadoop

import edu.uic.cs441.scholarchain.core.SchemaVersion
import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind, StableIds}
import edu.uic.cs441.scholarchain.core.model.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object Fixtures:
  val root: java.nio.file.Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("datasets/samples/dblp/dblp-sample.xml")))
      .get
  val dblpDir                    = root.resolve("datasets/samples/dblp")
  def read(name: String): String =
    new String(Files.readAllBytes(dblpDir.resolve(name)), StandardCharsets.ISO_8859_1)
  lazy val entities: Map[String, String] = DblpEntities.fromDtd(read("dblp.dtd"))
  val runId: String                      =
    StableIds.runId("hadoop-test", "test", "cfg", Seq(RunInput(SourceType.Dblp, "sample", "x")), 1)

  def scanAll(xml: String, splitSize: Int): Vector[(Long, String)] =
    val bytes = xml.getBytes(StandardCharsets.ISO_8859_1)
    (0 until bytes.length by splitSize).toVector.flatMap { start =>
      val in = new ByteArrayInputStream(bytes); in.skip(start.toLong)
      val s  = new DblpRecordScanner(
        in,
        start.toLong,
        math.min(bytes.length, start + splitSize).toLong,
        DblpRecordParser.AllRecordTypes
      )
      Iterator.continually(s.next()).takeWhile(_.isDefined).flatten.toVector
    }

class DblpParserSuite extends munit.FunSuite:
  import Fixtures.*

  test("record boundaries are identical for every split size (no record split or duplicated)") {
    val xml   = read("dblp-sample.xml")
    val whole = scanAll(xml, Int.MaxValue / 2)
    assertEquals(whole.size, 10)
    for size <- Seq(1, 7, 64, 100, 333, 1000, 4096) do
      assertEquals(scanAll(xml, size), whole, s"split=$size")
  }

  test("parses the first sample record into valid contracts with entities resolved") {
    val (_, rec) = scanAll(read("dblp-sample.xml"), 1 << 20).head
    val pw       = DblpRecordParser.parse(rec, entities, runId).toOption.get
    assertEquals(pw.work.workId, "work:dblp:conf/scsample/CarberryMS24")
    assertEquals(pw.work.year, Some(2024))
    assertEquals(pw.work.externalIds, List(ExternalId(IdNamespace.Doi, "10.5555/sc441.0001")))
    assertEquals(
      pw.mentions.map(_.rawName),
      List("Josiah Carberry", "Jürgen Müller", "Ada Sample 0001")
    )
    assertEquals(pw.mentions.map(_.authorPosition), List(0, 1, 2))
    assertEquals(pw.mentions.head.orcid, Some("0000-0002-1825-0097"))
    assertEquals(pw.mentions(1).normalizedName, "jurgen muller")
    assertEquals(
      pw.work.provenance,
      Provenance(SourceType.Dblp, "conf/scsample/CarberryMS24", runId)
    )
    assertEquals(pw.work.schemaVersion, SchemaVersion.Current)
    assert(IdFormat.isValid(IdKind.Mention, pw.mentions.head.mentionId))
  }

  test("www person records and keyless records are rejected, not dropped") {
    val recs = scanAll(read("dblp-sample.xml"), 1 << 20).map(_._2)
    val out  = recs.map(DblpRecordParser.parse(_, entities, runId))
    assertEquals(out.count(_.isRight), 6) // 3 www + 1 keyless article rejected
    assertEquals(
      out.collect { case Left(r) => r.reason }.toSet,
      Set("unsupported-record-type", "missing-key")
    )
  }

  test("malformed sample: undeclared entity and truncated record are rejected with reasons") {
    val recs = scanAll(read("dblp-malformed.xml"), 1 << 20).map(_._2)
    val out  = recs.map(DblpRecordParser.parse(_, entities, runId))
    assertEquals(out.size, 3)
    assert(out.head.isRight)
    assertEquals(out(1).left.toOption.map(_.reason), Some("undeclared-entity"))
    assertEquals(out(2).left.toOption.map(_.reason), Some("truncated-record"))
    assertEquals(out(2).left.toOption.flatMap(_.sourceRecordId), Some("conf/scsample/Truncated24"))
  }

  test("missing key and missing title are rejected") {
    val noKey = "<article mdate=\"x\"><author>A B</author><title>T.</title></article>"
    val noTit = "<article key=\"journals/x/Y1\"><author>A B</author></article>"
    assertEquals(
      DblpRecordParser.parse(noKey, entities, runId).left.map(_.reason),
      Left("missing-key")
    )
    assertEquals(
      DblpRecordParser.parse(noTit, entities, runId).left.map(_.reason),
      Left("missing-title")
    )
  }

  test("key functions: venue key, name normalization, partitioner") {
    assertEquals(DblpRecordParser.venueKey("conf/scsample/CarberryMS24"), Some("conf/scsample"))
    assertEquals(DblpRecordParser.venueKey("www/x"), None)
    assertEquals(DblpRecordParser.normalizeName("  Zoë  Ibáñez "), "zoe ibanez")
    val p = WorkIdPartitioner.partition("work:dblp:conf/scsample/CarberryMS24", 4)
    assert(p >= 0 && p < 4)
    assertEquals(WorkIdPartitioner.partition("work:dblp:conf/scsample/CarberryMS24", 4), p)
    assertEquals(Pipeline.runSegment("run:v1:ab"), "run-v1-ab")
    assertEquals(
      Pipeline.outputPath("/o", runId, "snap", "j").toString,
      s"/o/run=${runId.replace(':', '-')}/snapshot=snap/j"
    )
  }
