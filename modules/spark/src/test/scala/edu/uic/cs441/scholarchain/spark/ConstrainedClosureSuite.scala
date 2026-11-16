package edu.uic.cs441.scholarchain.spark

import edu.uic.cs441.scholarchain.spark.ConstrainedClosure.{Link, Result, Veto}

import scala.collection.mutable

/**
 * Known-graph tests of the veto-constrained identity closure (Phase 11 repair-01 D1, STRICT rule
 * c). Pure Scala, no Spark: empty, chain, star, clique, bridge, disconnected, plus order invariance
 * over every permutation and properties on seeded random graphs.
 */
class ConstrainedClosureSuite extends munit.FunSuite:

  private def key(a: String, b: String): String =
    val (l, h) = if a.compareTo(b) <= 0 then (a, b) else (b, a)
    s"mention|$l|$h"
  private def link(a: String, b: String, conf: Double = 0.9): Link =
    val (l, h) = if a.compareTo(b) <= 0 then (a, b) else (b, a)
    Link(l, h, conf, key(l, h))
  private def veto(a: String, b: String): Veto =
    val (l, h) = if a.compareTo(b) <= 0 then (a, b) else (b, a)
    Veto(l, h, key(l, h))

  /** Clusters as sets, from the anchor map. */
  private def clusters(r: Result): Set[Set[String]] =
    r.anchorOf.groupMap(_._2)(_._1).values.map(_.toSet).toSet

  /** Reference transitive closure (plain connected components), anchor = smallest member. */
  private def refClosure(ls: Seq[Link]): Map[String, String] =
    val adj = ls.flatMap(l => Seq(l.low -> l.high, l.high -> l.low)).groupMap(_._1)(_._2)
    val out = mutable.Map.empty[String, String]
    adj.keys.toList.sorted.foreach { v =>
      if !out.contains(v) then
        val comp = mutable.Set(v)
        val q    = mutable.Queue(v)
        while q.nonEmpty do adj(q.dequeue()).foreach(n => if comp.add(n) then q.enqueue(n))
        comp.foreach(out(_) = comp.min)
    }
    out.toMap

  test("empty input gives no clusters and no refusals") {
    assertEquals(ConstrainedClosure.solve(Nil, Nil), Result(Map.empty, Nil))
    assertEquals(ConstrainedClosure.solve(Nil, Seq(veto("a", "b"))), Result(Map.empty, Nil))
  }

  test("D1 chain: John - J. - Jane with John-Jane rejected never joins John and Jane") {
    val r = ConstrainedClosure.solve(
      Seq(link("john", "j"), link("j", "jane")),
      Seq(veto("john", "jane"))
    )
    // Equal confidence: key order puts j|jane before j|john, so J. joins Jane first, and the
    // John link is refused by the John-Jane veto.
    assertEquals(clusters(r), Set(Set("j", "jane"), Set("john")))
    assertEquals(r.refused, List(key("j", "john") -> key("jane", "john")))
    assertNotEquals(r.anchorOf("john"), r.anchorOf("jane"))
    // A higher-confidence John link wins instead; John and Jane stay apart either way.
    val r2 = ConstrainedClosure.solve(
      Seq(link("john", "j", 0.95), link("j", "jane", 0.91)),
      Seq(veto("john", "jane"))
    )
    assertEquals(clusters(r2), Set(Set("j", "john"), Set("jane")))
    assertEquals(r2.refused, List(key("j", "jane") -> key("jane", "john")))
  }

  test("long chain with a veto in the middle: the weakest link is the one refused") {
    val chain = Seq("l1", "l2", "l3", "l4", "l5", "l6").sliding(2).map(p => (p(0), p(1))).toList
    // l3-l4 is the weakest link; the veto l2-l5 spans it.
    val weighted = chain.map((a, b) => link(a, b, if a == "l3" then 0.86 else 0.95))
    val r        = ConstrainedClosure.solve(weighted, Seq(veto("l2", "l5")))
    assertEquals(clusters(r), Set(Set("l1", "l2", "l3"), Set("l4", "l5", "l6")))
    assertEquals(r.refused, List(key("l3", "l4") -> key("l2", "l5")))
    assertEquals(r.anchorOf("l6"), "l4")
    // Equal confidence: key order, so l4-l5 is the first union the veto blocks.
    val flat = ConstrainedClosure.solve(chain.map(link(_, _)), Seq(veto("l2", "l5")))
    assertEquals(clusters(flat), Set(Set("l1", "l2", "l3", "l4"), Set("l5", "l6")))
    assertEquals(flat.refused, List(key("l4", "l5") -> key("l2", "l5")))
  }

  test("star: a veto between two leaves refuses exactly one spoke") {
    val r = ConstrainedClosure.solve(
      Seq("p", "q", "r", "s").map(link("h", _)),
      Seq(veto("q", "s"))
    )
    assertEquals(clusters(r), Set(Set("h", "p", "q", "r"), Set("s")))
    assertEquals(r.refused, List(key("h", "s") -> key("q", "s")))
  }

  test("clique K4 minus a vetoed pair: the vetoed vertex is cut off, every refusal cites it") {
    val vs = Seq("a", "b", "c", "d")
    val ls = for x <- vs; y <- vs if x < y && !(x == "a" && y == "d") yield link(x, y)
    val r  = ConstrainedClosure.solve(ls, Seq(veto("a", "d")))
    assertEquals(clusters(r), Set(Set("a", "b", "c"), Set("d")))
    assertEquals(r.refused, List(key("b", "d") -> key("a", "d"), key("c", "d") -> key("a", "d")))
  }

  test("bridge: two triangles stay apart when a veto spans the bridge") {
    val tri = Seq(link("a", "b"), link("b", "c"), link("a", "c"), link("d", "e"), link("e", "f"))
    val ls  = tri ++ Seq(link("d", "f"), link("c", "d", 0.5))
    val r   = ConstrainedClosure.solve(ls, Seq(veto("a", "f")))
    assertEquals(clusters(r), Set(Set("a", "b", "c"), Set("d", "e", "f")))
    assertEquals(r.refused, List(key("c", "d") -> key("a", "f")))
  }

  test("disconnected components and vetoes outside the links change nothing") {
    val ls = Seq(link("a", "b"), link("b", "c"), link("x", "y"))
    val r  = ConstrainedClosure.solve(ls, Seq(veto("a", "x"), veto("c", "zz"), veto("q", "r")))
    assertEquals(r.anchorOf, refClosure(ls))
    assertEquals(r.refused, Nil)
  }

  test("a pair that is both accepted and vetoed is refused (the veto wins)") {
    val r = ConstrainedClosure.solve(Seq(link("a", "b")), Seq(veto("a", "b")))
    assertEquals(clusters(r), Set(Set("a"), Set("b")))
    assertEquals(r.refused, List(key("a", "b") -> key("a", "b")))
  }

  test("several blocking vetoes: the smallest veto key is reported") {
    val r = ConstrainedClosure.solve(
      Seq(link("a", "b", 0.99), link("c", "d", 0.99), link("b", "c", 0.5)),
      Seq(veto("b", "d"), veto("a", "d"), veto("a", "c"))
    )
    assertEquals(clusters(r), Set(Set("a", "b"), Set("c", "d")))
    assertEquals(r.refused, List(key("b", "c") -> key("a", "c")))
  }

  test("duplicate links keep the highest confidence; NaN confidence counts as 0.0") {
    // a-b duplicated at 0.1 and 0.99 sorts first; c-a at NaN sorts last.
    val ls = Seq(
      link("a", "b", 0.1),
      link("b", "c", 0.5),
      link("a", "b", 0.99),
      link("a", "c", Double.NaN)
    )
    val r = ConstrainedClosure.solve(ls, Seq(veto("a", "c")))
    assertEquals(clusters(r), Set(Set("a", "b"), Set("c")))
    assertEquals(r.refused, List(key("a", "c") -> key("a", "c"), key("b", "c") -> key("a", "c")))
  }

  test("no vetoes: equal to the plain transitive closure on seeded random graphs (regression)") {
    List(1, 7, 42, 1009).foreach { seed =>
      val rnd = scala.util.Random(seed)
      val vs  = (0 until 30).map(i => f"m$i%02d")
      val ls  =
        for x <- vs; y <- vs if x < y && rnd.nextDouble() < 0.06 yield link(x, y, rnd.nextDouble())
      val r = ConstrainedClosure.solve(ls, Nil)
      assertEquals(r.anchorOf, refClosure(ls), s"seed $seed")
      assertEquals(r.refused, Nil)
    }
  }

  test("order invariance: every permutation of links and vetoes gives the same result") {
    val ls = Seq(
      link("l1", "l2"),
      link("l2", "l3", 0.7),
      link("l3", "l4"),
      link("l4", "l5", 0.7),
      link("l5", "l1", 0.8)
    )
    val vs       = Seq(veto("l1", "l4"), veto("l2", "l5"))
    val expected = ConstrainedClosure.solve(ls, vs)
    for p <- ls.permutations; q <- vs.permutations do
      assertEquals(ConstrainedClosure.solve(p, q), expected, s"$p $q")
  }

  test("properties on seeded random graphs with random vetoes") {
    List(3, 11, 99, 2024, 31337).foreach { seed =>
      val rnd = scala.util.Random(seed)
      val ms  = (0 until 40).map(i => f"m$i%02d")
      val ls  =
        for x <- ms; y <- ms if x < y && rnd.nextDouble() < 0.08
        yield link(x, y, (rnd.nextInt(5) + 5) / 10.0)
      val vs = for x <- ms; y <- ms if x < y && rnd.nextDouble() < 0.03 yield veto(x, y)
      val r  = ConstrainedClosure.solve(ls, vs)
      val a  = r.anchorOf
      def same(x: String, y: String) = a.contains(x) && a.contains(y) && a(x) == a(y)
      // (1) no cluster holds a veto pair
      vs.foreach(v => assert(!same(v.a, v.b), s"seed $seed veto $v joined"))
      // (2) refused links end apart, and their blocking veto spans the two final clusters
      val refusedKeys = r.refused.map(_._1).toSet
      val byKey       = vs.map(v => v.key -> v).toMap
      r.refused.foreach { (lk, vk) =>
        val l = ls.find(_.key == lk).get
        val v = byKey(vk)
        assert(!same(l.low, l.high), s"seed $seed refused $lk joined")
        assert(
          (a(v.a) == a(l.low) && a(v.b) == a(l.high)) ||
            (a(v.b) == a(l.low) && a(v.a) == a(l.high)),
          s"seed $seed veto $vk does not span refused $lk"
        )
      }
      // (3) every other link is honoured
      ls.filterNot(l => refusedKeys(l.key)).foreach(l => assert(same(l.low, l.high), l.toString))
      // (4) clusters refine the plain closure, anchors are cluster minima
      val ref = refClosure(ls)
      a.foreach((m, anchor) => assertEquals(ref(anchor), ref(m), s"seed $seed $m"))
      clusters(r).foreach(c => c.foreach(m => assertEquals(a(m), c.min)))
      // (5) shuffled input gives the same result
      assertEquals(ConstrainedClosure.solve(rnd.shuffle(ls), rnd.shuffle(vs)), r, s"seed $seed")
    }
  }
