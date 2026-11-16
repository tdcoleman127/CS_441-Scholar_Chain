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
    // er-features-v3 (M1): two empty coauthor sets give 0.0, as the Hadoop contextJaccard does.
    assertEquals(Features.coauthorOverlap(Nil, Nil), Some(0.0))
    assertEquals(Features.coauthorOverlap(List("A. Lee"), Nil), Some(0.0))
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

  // ---- Phase 11 repair-02, RR4 (er-features-v4): coauthor identity keeps the homonym number -----

  test("RR4 coauthorKey: canonical name plus the DBLP homonym number, as the Hadoop context") {
    // The Hadoop context string of each name (DblpRecordParser.normalizeName keeps digits).
    assertEquals(Features.coauthorKey("Wei Wang 0001"), "wei wang 0001")
    assertEquals(Features.coauthorKey("Wei Wang 0002"), "wei wang 0002")
    assertEquals(Features.coauthorKey("Wei Wang"), "wei wang")
    assertEquals(Features.coauthorKey("Collab Member 02"), "collab member 02")
    // Name order and punctuation stay folded, as in er-features-v3.
    assertEquals(Features.coauthorKey("Hopper, Grace"), "grace hopper")
    assertEquals(Features.coauthorKey("Wang 0001, Wei"), "wei wang 0001")
    assertEquals(Features.coauthorKey(""), "")
    // The name features still drop the suffix (unchanged): the number is not part of the name.
    assertEquals(Normalize.personName("Wei Wang 0001").canonical, "wei wang")
    assertEquals(Normalize.homonymNumber("Wei Wang 0001"), Vector("0001"))
    assertEquals(Normalize.homonymNumber("John Smith"), Vector.empty)
    assertEquals(Features.nameSimilarity("John Smith 0001", "John Smith"), Some(1.0))
  }

  test("RR4 coauthorOverlap: 'Wei Wang 0001' and 'Wei Wang 0002' are different coauthors") {
    assertEquals(Features.coauthorOverlap(List("Wei Wang 0001"), List("Wei Wang 0002")), Some(0.0))
    assertEquals(Features.coauthorOverlap(List("Wei Wang 0001"), List("Wei Wang 0001")), Some(1.0))
    // Numbered vs unnumbered: different strings on the Hadoop path, so different here too.
    assertEquals(Features.coauthorOverlap(List("Wei Wang 0001"), List("Wei Wang")), Some(0.0))
    assertEquals(
      Features.coauthorOverlap(
        List("A. Turing", "Wei Wang 0002"),
        List("A. Turing", "Wei Wang 0002", "Cy Ho")
      ),
      Some(2.0 / 3)
    )
    assertEquals(
      Features.coauthorOverlap(
        List("A. Turing", "Wei Wang 0001"),
        List("A. Turing", "Wei Wang 0002")
      ),
      Some(1.0 / 3)
    )
    // Distinct numbered members are distinct set elements (v3 collapsed them into one).
    assertEquals(
      Features.coauthorOverlap(
        List("Collab Member 01", "Collab Member 02"),
        List("Collab Member 01")
      ),
      Some(0.5)
    )
  }
