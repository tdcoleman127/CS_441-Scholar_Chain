package edu.uic.cs441.scholarchain.core.model

import edu.uic.cs441.scholarchain.core.ids.{IdFormat, IdKind, StableIds}
import upickle.default.{read, write}

/** Topic and institution summary document types (R-08-2 / R-07-3): additive, append-only. */
class DocumentTypeSuite extends munit.FunSuite:

  private val entity = "researcher:dblp:homepages/00/0000"
  private val hash   = StableIds.textHash("summary text")

  test("topic and institution summary wire values") {
    assertEquals(DocumentType.TopicSummary.wire, "topic-summary")
    assertEquals(DocumentType.InstitutionSummary.wire, "institution-summary")
  }

  test("new cases are appended: existing ordinals are unchanged") {
    assertEquals(
      DocumentType.values.toList.map(d => d.wire -> d.ordinal),
      List(
        "researcher-profile"    -> 0,
        "work-abstract"         -> 1,
        "work-metadata"         -> 2,
        "collaboration-summary" -> 3,
        "evidence-path"         -> 4,
        "topic-summary"         -> 5,
        "institution-summary"   -> 6
      )
    )
  }

  test("topic and institution summary round-trip through upickle") {
    assertEquals(write(DocumentType.TopicSummary), "\"topic-summary\"")
    assertEquals(write(DocumentType.InstitutionSummary), "\"institution-summary\"")
    assertEquals(read[DocumentType]("\"topic-summary\""), DocumentType.TopicSummary)
    assertEquals(read[DocumentType]("\"institution-summary\""), DocumentType.InstitutionSummary)
    val both = Seq(DocumentType.TopicSummary, DocumentType.InstitutionSummary)
    assertEquals(read[Seq[DocumentType]](write(both)), both)
  }

  test("unknown and near-miss document type values are rejected") {
    Seq(
      "\"topic_summary\"",
      "\"Topic-Summary\"",
      "\"topic-summaries\"",
      "\"institution\"",
      "\"TopicSummary\"",
      "\"\""
    ).foreach(json => intercept[Exception](read[DocumentType](json)))
  }

  test("every document type yields a distinct, well-formed chunk ID for the same inputs") {
    val ids = DocumentType.values.toList.map(d => StableIds.chunkId(entity, "v1", d, None, hash))
    assertEquals(ids.distinct.size, DocumentType.values.length)
    ids.foreach(id => assert(IdFormat.isValid(IdKind.Chunk, id), id))
    assertNotEquals(
      StableIds.chunkId(entity, "v1", DocumentType.TopicSummary, None, hash),
      StableIds.chunkId(entity, "v1", DocumentType.InstitutionSummary, None, hash)
    )
    assertEquals(
      StableIds.chunkId(entity, "v1", DocumentType.TopicSummary, None, hash),
      StableIds.chunkId(entity, "v1", DocumentType.TopicSummary, None, hash)
    )
  }
