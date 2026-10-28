package edu.uic.cs441.scholarchain.hadoop

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import upickle.default.{read as decode, write}

/**
 * RejectRecord v2 (human decision 2026-10-27): every reject names its input file, so the per-file
 * offset is unambiguous when one job reads several files. Runs on every platform (no job runner).
 */
class RejectRecordSuite extends munit.FunSuite:
  import Fixtures.*

  private def split(uri: String) = new FileSplit(new Path(uri), 0L, 10L, Array.empty[String])

  test("inputFile is the base name of the split's file, independent of where it is stored") {
    val local = "file:///C:/tmp/a/datasets/samples/dblp/dblp-sample.xml"
    assertEquals(DblpExtractMapper.inputFileName(split(local)), "dblp-sample.xml")
    assertEquals(
      DblpExtractMapper.inputFileName(split("file:///other/dblp-sample.xml")),
      "dblp-sample.xml"
    )
    assertEquals(
      DblpExtractMapper.inputFileName(split("s3://bucket/raw/dblp.xml.gz")),
      "dblp.xml.gz"
    )
    assertEquals(DblpExtractMapper.inputFileName(split("hdfs:///in/dblp.xml.gz")), "dblp.xml.gz")
  }

  test("reject records are schema 2, carry inputFile, and are written under rejects/v2") {
    val rej = Rejection("missing-key", None, "article")
    val rr  = DblpExtractMapper.rejectRecord(rej, "dblp-sample.xml", "snap", runId, 3859L)
    assertEquals(rr.rejectSchemaVersion, 2)
    assertEquals(rr.inputFile, "dblp-sample.xml")
    assertEquals(rr.inputOffset, 3859L)
    assertEquals(DblpExtractMapper.RejectBase, "rejects/v2/part")
    val json = write(rr)
    assert(json.contains("\"inputFile\":\"dblp-sample.xml\""), json)
    assert(json.contains("\"rejectSchemaVersion\":2"), json)
    assertEquals(decode[RejectRecord](json), rr)
  }

  test("the six sample rejects get their own file names and per-file offsets") {
    val rejects =
      for
        f        <- Vector("dblp-sample.xml", "dblp-malformed.xml")
        (off, r) <- scanAll(read(f), 1 << 20)
        rej      <- DblpRecordParser.parse(r, entities, runId).left.toOption
      yield DblpExtractMapper.rejectRecord(rej, f, "sample", runId, off)
    assertEquals(
      rejects.map(r => (r.inputFile, r.inputOffset, r.reason)),
      Vector(
        ("dblp-sample.xml", 3859L, "missing-key"),
        ("dblp-sample.xml", 4031L, "unsupported-record-type"),
        ("dblp-sample.xml", 4285L, "unsupported-record-type"),
        ("dblp-sample.xml", 4454L, "unsupported-record-type"),
        ("dblp-malformed.xml", 643L, "undeclared-entity"),
        ("dblp-malformed.xml", 812L, "truncated-record")
      )
    )
  }
