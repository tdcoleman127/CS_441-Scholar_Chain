package edu.uic.cs441.scholarchain.er

import edu.uic.cs441.scholarchain.core.model.IdNamespace
import edu.uic.cs441.scholarchain.er.Identifiers.IdComparison

class FeaturesSuite extends munit.FunSuite:

  test("nameSimilarity: order- and accent-insensitive, None when empty") {
    assertEquals(Features.nameSimilarity("Müller, Hans", "Hans Muller"), Some(1.0))
    val s = Features.nameSimilarity("Hans Muller", "Hans Mueller").get
    assert(s > 0.9 && s < 1.0, s)
    assertEquals(Features.nameSimilarity("", "Hans"), None)
  }

  test("initialsCompatibility: initials vs full names, surname must agree") {
    assertEquals(Features.initialsCompatibility("J. Smith", "John Smith"), Some(1.0))
    assertEquals(Features.initialsCompatibility("John Smith", "Jane Smith"), Some(0.0))
    assertEquals(Features.initialsCompatibility("J. Smith", "K. Smith"), Some(0.0))
    assertEquals(Features.initialsCompatibility("J. Smith", "J. Smyth"), Some(0.0))
    assertEquals(Features.initialsCompatibility("Smith", "J. Smith"), None)
  }

  test("coauthorOverlap, idAgreement, titleSimilarity, yearDistance") {
    assertEquals(
      Features.coauthorOverlap(List("A. Lee", "Bo Chen"), List("Lee, A.", "C. Diaz")),
      Some(1.0 / 3)
    )
    assertEquals(Features.coauthorOverlap(Nil, Nil), None)
    assertEquals(Features.idAgreement(IdComparison(List(IdNamespace.Orcid), Nil)), Some(1.0))
    assertEquals(
      Features.idAgreement(IdComparison(List(IdNamespace.Doi), List(IdNamespace.Orcid))),
      Some(0.0)
    )
    assertEquals(Features.idAgreement(IdComparison.Empty), None)
    assertEquals(Features.titleSimilarity("Graph Mining!", "graph   mining"), Some(1.0))
    assertEquals(Features.titleSimilarity("", "x"), None)
    assertEquals(Features.yearDistance(Some(2001), Some(2005)), Some(4.0))
    assertEquals(Features.yearDistance(None, Some(2005)), None)
  }
