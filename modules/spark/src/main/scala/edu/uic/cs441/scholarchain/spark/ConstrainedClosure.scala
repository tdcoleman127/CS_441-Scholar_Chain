package edu.uic.cs441.scholarchain.spark

import org.apache.spark.sql.{functions as F, Row}
import org.apache.spark.sql.api.java.UDF2
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.*

import scala.collection.mutable

/**
 * Veto-constrained identity closure (`identity-closure-v2`, Phase 11 repair-01 D1; human decision
 * STRICT, 2026-11-14, rule c).
 *
 * Accepted ER links are unioned one at a time in the canonical [[LinkOrder]], and a union is
 * refused when the two clusters it would join contain the two members of any veto pair. A veto is
 * an ER decision for that exact mention pair whose status is not `accepted` (rejected, unresolved,
 * or conflict; ER `Outputs.MergeVetoFiles` and `ResolutionRecord.blocksMerge`).
 *
 * Pairs that were never ER candidates have no decision, so they carry no veto: two mentions that no
 * blocking key ever compared may still be joined transitively through accepted links. Only an
 * explicit non-accepted decision for the pair blocks the join.
 *
 * Link order (the tie-break): `confidence` descending (ER confidence: 1.0 for exact-ID and
 * deterministic accepts, the lower score bound for probabilistic accepts; NaN or null counts as
 * 0.0), then the link key `mention|<low>|<high>` ascending (String.compareTo). Keys are unique per
 * pair after de-duplication (a duplicated link keeps its highest confidence), so the order is
 * total.
 *
 * Determinism: the result is a function of the SET of links (key, confidence) and the SET of veto
 * pairs only. Both are de-duplicated and the links are sorted by a total order before any union, so
 * input order, partitioning, `collect_list` order, and parallelism cannot change it. Cluster
 * anchors are the smallest mention ID of each final cluster, independent of union-find root choice.
 * When several veto pairs block one union, the reported blocking veto is the one with the smallest
 * veto key.
 *
 * Guarantees, by construction: (1) no final cluster contains both members of a veto pair (every
 * cluster starts veto-free and a union happens only when no veto crosses it); (2) every refused
 * link's endpoints end in different clusters (clusters only grow, so the blocking veto stays across
 * them); (3) every non-refused link's endpoints share a cluster; (4) with no vetoes the result
 * equals the plain transitive closure (connected components of the links).
 *
 * Cost per component with n mentions, l links, and v vetoes: O(l log l) for the sort, and for each
 * attempted union a scan of the smaller cluster's members' veto partners, so O(l * min-cluster-size
 * * veto-degree) in the worst case; memory O(n + l + v), all inside one task.
 */
object ConstrainedClosure:

  val Version: String = "identity-closure-v2"

  final case class Link(low: String, high: String, confidence: Double, key: String)
  final case class Veto(a: String, b: String, key: String)

  /**
   * `anchorOf`: every link endpoint mapped to its cluster anchor (smallest mention ID of its final
   * cluster). `refused`: (link key, blocking veto key), sorted by link key.
   */
  final case class Result(anchorOf: Map[String, String], refused: List[(String, String)])

  private def conf(d: Double): Double = if d.isNaN then 0.0 else d

  /** Confidence descending, then link key ascending. */
  val LinkOrder: Ordering[Link] =
    Ordering
      .Tuple2(Ordering.Double.TotalOrdering, Ordering.String)
      .on[Link](l => (-conf(l.confidence), l.key))

  def solve(links: Seq[Link], vetoes: Seq[Veto]): Result =
    val uniqueLinks = links
      .filter(l => l.low != l.high)
      .groupBy(_.key)
      .values
      .map(_.max(Ordering.by[Link, Double](l => conf(l.confidence))(Ordering.Double.TotalOrdering)))
      .toVector
      .sorted(LinkOrder)
    val parent  = mutable.HashMap.empty[String, String]
    val members = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
    uniqueLinks.foreach { l =>
      Seq(l.low, l.high).foreach { m =>
        if !parent.contains(m) then
          parent(m) = m
          members(m) = mutable.ArrayBuffer(m)
      }
    }
    // Static veto adjacency: mention -> (partner, veto key). A partner that is no link endpoint is
    // a singleton outside every cluster here, so it can never block a union.
    val vetoAdj = mutable.HashMap.empty[String, mutable.ArrayBuffer[(String, String)]]
    vetoes.filter(v => v.a != v.b).distinctBy(_.key).foreach { v =>
      vetoAdj.getOrElseUpdate(v.a, mutable.ArrayBuffer.empty) += ((v.b, v.key))
      vetoAdj.getOrElseUpdate(v.b, mutable.ArrayBuffer.empty) += ((v.a, v.key))
    }
    def find(x: String): String =
      var r = x
      while parent(r) != r do r = parent(r)
      var y = x
      while parent(y) != r do
        val next = parent(y)
        parent(y) = r
        y = next
      r
    val refused = mutable.ArrayBuffer.empty[(String, String)]
    uniqueLinks.foreach { l =>
      val ru = find(l.low)
      val rv = find(l.high)
      if ru != rv then
        val (small, large) = if members(ru).size <= members(rv).size then (ru, rv) else (rv, ru)
        val blocking       = members(small).iterator.flatMap { m =>
          vetoAdj.getOrElse(m, Nil).iterator.collect {
            case (p, k) if parent.contains(p) && find(p) == large => k
          }
        }
        if blocking.hasNext then refused += ((l.key, blocking.min))
        else
          parent(small) = large
          members(large) ++= members(small)
          members.remove(small)
    }
    val anchorOf = members.valuesIterator.flatMap { ms =>
      val anchor = ms.min
      ms.iterator.map(_ -> anchor)
    }.toMap
    Result(anchorOf, refused.sortBy(_._1).toList)

  // ---- Spark adapter -------------------------------------------------------------------------------

  /** `links: array<struct<low, high, confidence: double, key>>` as collected per component. */
  val LinkStruct: StructType = StructType(
    Seq(
      StructField("low", StringType),
      StructField("high", StringType),
      StructField("confidence", DoubleType),
      StructField("key", StringType)
    )
  )

  /** `vetoes: array<struct<a, b, key>>` as collected per component. */
  val VetoStruct: StructType = StructType(
    Seq(StructField("a", StringType), StructField("b", StringType), StructField("key", StringType))
  )

  val ResultType: StructType = StructType(
    Seq(
      StructField(
        "assign",
        ArrayType(
          StructType(
            Seq(StructField("mentionId", StringType), StructField("anchorMentionId", StringType))
          )
        )
      ),
      StructField(
        "refused",
        ArrayType(
          StructType(Seq(StructField("linkKey", StringType), StructField("vetoKey", StringType)))
        )
      )
    )
  )

  private def str(r: Row, i: Int): String = if r.isNullAt(i) then null else r.getString(i)

  /**
   * Solves one component: `(links, vetoes) -> struct<assign, refused>`. Spark passes arrays as
   * `scala.collection.Seq` (a mutable ArraySeq), so the parameters are typed that way.
   */
  val solveUdf: UserDefinedFunction =
    F.udf(
      new UDF2[scala.collection.Seq[Row], scala.collection.Seq[Row], Row]:
        def call(ls: scala.collection.Seq[Row], vs: scala.collection.Seq[Row]): Row =
          val links = Option(ls).toSeq.flatten.collect {
            case r if r != null && !r.isNullAt(0) && !r.isNullAt(1) && !r.isNullAt(3) =>
              Link(str(r, 0), str(r, 1), if r.isNullAt(2) then 0.0 else r.getDouble(2), str(r, 3))
          }
          val vetoes = Option(vs).toSeq.flatten.collect {
            case r if r != null && !r.isNullAt(0) && !r.isNullAt(1) && !r.isNullAt(2) =>
              Veto(str(r, 0), str(r, 1), str(r, 2))
          }
          val res = solve(links, vetoes)
          Row(
            res.anchorOf.toSeq.sorted.map((m, a) => Row(m, a)),
            res.refused.map((l, v) => Row(l, v))
          )
      ,
      ResultType
    )
