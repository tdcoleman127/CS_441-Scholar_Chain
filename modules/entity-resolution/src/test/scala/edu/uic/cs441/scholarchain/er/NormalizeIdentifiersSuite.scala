package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.model.IdNamespace

class NormalizeIdentifiersSuite extends munit.FunSuite:

  test("normalization: Unicode, punctuation, whitespace, initials, name order") {
    assertEquals(Normalize.text("  Jürgen   O'Brien-Müller\t"), "jurgen o brien muller")
    val a = Normalize.personName("Smith, J.R.")
    val b = Normalize.personName("J. R. Smith")
    assertEquals(a, b)
    assertEquals(a.givenNames, Vector("j", "r"))
    assertEquals(a.surname, "smith")
    assert(a.hasOnlyInitials)
    // DBLP homonym suffix is not part of the name.
    assertEquals(Normalize.personName("Wei Wang 0003").canonical, "wei wang")
  }

  test("exact ID rules: DOI prefixes, arXiv versions, ORCID, OpenAlex, Semantic Scholar") {
    assertEquals(
      Identifiers.normalize(IdNamespace.Doi, "https://doi.org/10.1145/ABC.1"),
      Some("10.1145/abc.1")
    )
    assertEquals(Identifiers.normalize(IdNamespace.Doi, "doi:10.1145/abc.1"), Some("10.1145/abc.1"))
    assertEquals(Identifiers.normalize(IdNamespace.Doi, "not-a-doi"), None)
    assertEquals(Identifiers.normalize(IdNamespace.Arxiv, "arXiv:2101.00001v3"), Some("2101.00001"))
    assertEquals(
      Identifiers.normalize(IdNamespace.Arxiv, "https://arxiv.org/abs/2101.00001"),
      Some("2101.00001")
    )
    assertEquals(
      Identifiers.normalize(IdNamespace.Orcid, "https://orcid.org/0000-0002-1825-0097"),
      Some("0000-0002-1825-0097")
    )
    assertEquals(
      Identifiers.normalize(IdNamespace.Orcid, "0000-0002-1825-0098"),
      None
    ) // bad checksum
    assertEquals(
      Identifiers.normalize(IdNamespace.OpenAlex, "https://openalex.org/A5023888391"),
      Some("A5023888391")
    )
    assertEquals(Identifiers.normalize(IdNamespace.SemanticScholar, " 1741101 "), Some("1741101"))
  }

  test("ID comparison: agreement, conflict, and conflict alongside agreement") {
    val l = Identifiers.normalizeAll(Map(
      IdNamespace.Doi      -> List("https://doi.org/10.1/x"),
      IdNamespace.Arxiv    -> List("2101.00001"),
      IdNamespace.Orcid    -> List("0000-0002-1825-0097"),
      IdNamespace.OpenAlex -> List("A1")
    ))
    val sameWorkIdsOtherOrcid = Identifiers.normalizeAll(Map(
      IdNamespace.Doi      -> List("10.1/X"),
      IdNamespace.Arxiv    -> List("2101.00001v2"),
      IdNamespace.Orcid    -> List("0000-0001-5109-3700"),
      IdNamespace.OpenAlex -> List("https://openalex.org/A1")
    ))
    val c = Identifiers.compare("mention", l, sameWorkIdsOtherOrcid)
    assertEquals(c.agreements, List(IdNamespace.OpenAlex))
    assertEquals(c.conflicts, List(IdNamespace.Orcid))
    assertEquals(Identifiers.compare("mention", l, Map.empty), Identifiers.IdComparison.Empty)
  }

  test("ID scope (F1): mentions use ORCID and OpenAlex A IDs; works use DOI, arXiv, s2, W IDs") {
    val all = Identifiers.normalizeAll(Map(
      IdNamespace.Doi             -> List("10.1/x"),
      IdNamespace.Arxiv           -> List("2101.00001"),
      IdNamespace.Orcid           -> List("0000-0002-1825-0097"),
      IdNamespace.OpenAlex        -> List("W1", "A1", "I7"),
      IdNamespace.SemanticScholar -> List("1741101")
    ))
    assertEquals(
      Identifiers.forScope("mention", all),
      Map(IdNamespace.Orcid -> Set("0000-0002-1825-0097"), IdNamespace.OpenAlex -> Set("A1"))
    )
    assertEquals(
      Identifiers.forScope("work", all),
      Map(
        IdNamespace.Doi             -> Set("10.1/x"),
        IdNamespace.Arxiv           -> Set("2101.00001"),
        IdNamespace.OpenAlex        -> Set("W1"),
        IdNamespace.SemanticScholar -> Set("1741101")
      )
    )
    // Same DOI, different ORCID: a work pair agrees on DOI; a mention pair conflicts on ORCID.
    val other = Identifiers.normalizeAll(Map(
      IdNamespace.Doi   -> List("10.1/x"),
      IdNamespace.Orcid -> List("0000-0001-5109-3700")
    ))
    assertEquals(
      Identifiers.compare("work", all, other),
      Identifiers.IdComparison(List(IdNamespace.Doi), Nil)
    )
    assertEquals(
      Identifiers.compare("mention", all, other),
      Identifiers.IdComparison(Nil, List(IdNamespace.Orcid))
    )
    intercept[IllegalArgumentException](Identifiers.forScope("venue", all))
  }
