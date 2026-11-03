package edu.uic.cs441.scholarchain.storage.fakes

import edu.uic.cs441.scholarchain.storage.graph.*
import edu.uic.cs441.scholarchain.storage.kv.{KeyValueStore, Table, TableItem}
import edu.uic.cs441.scholarchain.storage.objectstore.{ObjectKey, ObjectStore, StoredObject}
import edu.uic.cs441.scholarchain.storage.vectors.*
import edu.uic.cs441.scholarchain.storage.{Idempotency, StorageError, WriteOutcome}
import edu.uic.cs441.scholarchain.core.model.CoauthorEdge
import scala.collection.mutable

/**
 * Failure injection shared by the fakes: queued errors are returned, one per call, before the fake
 * does any work. `calls` counts every call, including failed ones.
 */
trait FailureInjection:
  private val queued       = mutable.Queue.empty[StorageError]
  @volatile var calls: Int = 0

  def failNext(errors: StorageError*): Unit = synchronized(queued.enqueueAll(errors))

  protected def guarded[A](body: => Either[StorageError, A]): Either[StorageError, A] =
    synchronized {
      calls += 1
      if queued.nonEmpty then Left(queued.dequeue()) else body
    }

/** In-memory [[ObjectStore]] with the same create-if-absent semantics as the S3 adapter. */
final class InMemoryObjectStore extends ObjectStore with FailureInjection:
  private val objects = mutable.Map.empty[String, StoredObject]

  def putIfAbsent(
      key: ObjectKey,
      bytes: Array[Byte],
      contentType: String
  ): Either[StorageError, WriteOutcome] = guarded {
    val hash = ObjectStore.contentHash(bytes)
    objects.get(key.value) match
      case Some(existing) =>
        Idempotency.resolveExisting(key.value, Some(existing.contentHash), hash)
      case None =>
        objects(key.value) = StoredObject(key.value, bytes.clone(), hash)
        Right(WriteOutcome.Written)
  }

  def get(key: ObjectKey): Either[StorageError, Option[StoredObject]] =
    guarded(Right(objects.get(key.value)))

  def keys: List[String] = synchronized(objects.keys.toList.sorted)

/** In-memory [[KeyValueStore]] with the same conditional-write semantics as DynamoDB. */
final class InMemoryKeyValueStore extends KeyValueStore with FailureInjection:
  private val items = mutable.Map.empty[(Table, String, String), TableItem]

  def putIfAbsent(table: Table, item: TableItem): Either[StorageError, WriteOutcome] = guarded {
    items.get((table, item.pk, item.sk)) match
      case Some(existing) =>
        Idempotency.resolveExisting(item.key, Some(existing.contentHash), item.contentHash)
      case None =>
        items((table, item.pk, item.sk)) = item
        Right(WriteOutcome.Written)
  }

  def get(table: Table, pk: String, sk: String): Either[StorageError, Option[TableItem]] =
    guarded(Right(items.get((table, pk, sk))))

  def updateIfVersion(
      table: Table,
      item: TableItem,
      expectedVersion: Long
  ): Either[StorageError, Unit] = guarded {
    KeyValueStore.checkUpdate(item, expectedVersion).flatMap { _ =>
      items.get((table, item.pk, item.sk)) match
        case Some(existing) if existing.version == expectedVersion =>
          items((table, item.pk, item.sk)) = item
          Right(())
        case _ => Left(StorageError.VersionConflict(item.key, expectedVersion))
    }
  }

  def queryPartition(table: Table, pk: String, limit: Int): Either[StorageError, List[TableItem]] =
    guarded {
      KeyValueStore.checkLimit(limit).map { _ =>
        items.collect { case ((t, p, _), i) if t == table && p == pk => i }.toList
          .sortBy(_.sk)
          .take(limit)
      }
    }

  def size(table: Table): Int = synchronized(items.keys.count(_._1 == table))

/** In-memory [[VectorStore]]: upsert by key, cosine-distance query with equality filters. */
final class InMemoryVectorStore extends VectorStore with FailureInjection:
  private val indexes = mutable.Map.empty[VectorIndex, mutable.Map[String, VectorRecord]]
  var putCalls: Int   = 0

  def putBatch(index: VectorIndex, vectors: Seq[VectorRecord]): Either[StorageError, Unit] =
    guarded {
      VectorStore.checkBatch(vectors).map { _ =>
        putCalls += 1
        val idx = indexes.getOrElseUpdate(index, mutable.Map.empty)
        vectors.foreach(v => idx(v.key) = v)
      }
    }

  def query(
      index: VectorIndex,
      vector: Vector[Float],
      topK: Int,
      filter: Map[String, MetaValue]
  ): Either[StorageError, List[VectorHit]] = guarded {
    VectorStore.checkQuery(vector, topK, filter).map { _ =>
      val idx = indexes.getOrElse(index, mutable.Map.empty)
      idx.values.toList
        .filter(v => filter.forall((k, value) => v.metadata.get(k).contains(value)))
        .map(v => VectorHit(v.key, Some(cosineDistance(vector, v.data)), v.metadata))
        .sortBy(h => (h.distance.getOrElse(Double.MaxValue), h.key))
        .take(topK)
    }
  }

  def count(index: VectorIndex): Int = synchronized(indexes.get(index).fold(0)(_.size))

  def stored(index: VectorIndex, key: String): Option[VectorRecord] =
    synchronized(indexes.get(index).flatMap(_.get(key)))

  private def cosineDistance(a: Vector[Float], b: Vector[Float]): Double =
    if a.size != b.size then Double.MaxValue
    else
      val dot = a.zip(b).map((x, y) => x.toDouble * y).sum
      val na  = math.sqrt(a.map(x => x.toDouble * x).sum)
      val nb  = math.sqrt(b.map(x => x.toDouble * x).sum)
      if na == 0 || nb == 0 then 1.0 else 1.0 - dot / (na * nb)

/**
 * In-memory [[GraphStore]] over COAUTHORED_WITH edges, executing the same semantics as the
 * openCypher templates (window filter, ordering, depth and result limits).
 */
final class InMemoryGraphStore(edges: Seq[CoauthorEdge], evidenceKey: String)
    extends GraphStore
    with FailureInjection:

  private def inWindow(e: CoauthorEdge, ws: Int, we: Int) =
    e.window.startYear >= ws && e.window.endYear <= we

  def directCoauthors(q: DirectCoauthorsQuery): Either[StorageError, List[CoauthorRow]] =
    guarded {
      Right(
        edges
          .filter(e => inWindow(e, q.window.startYear, q.window.endYear))
          .flatMap { e =>
            val other =
              if e.researcherLow == q.researcherId then Some(e.researcherHigh)
              else if e.researcherHigh == q.researcherId then Some(e.researcherLow)
              else None
            other.map(c =>
              CoauthorRow(
                c,
                e.edgeId,
                e.coauthoredWorkCount,
                e.firstYear,
                e.lastYear,
                s"$evidenceKey#${e.edgeId}"
              )
            )
          }
          .sortBy(r => (-r.jointPublicationCount, r.coauthorId))
          .take(q.limit)
          .toList
      )
    }

  def boundedPaths(q: BoundedPathQuery): Either[StorageError, List[PathRow]] = guarded {
    val usable = edges.filter(e => inWindow(e, q.window.startYear, q.window.endYear))
    val adj    = usable
      .flatMap(e =>
        Seq(
          e.researcherLow  -> (e.researcherHigh, e.edgeId),
          e.researcherHigh ->
            (e.researcherLow, e.edgeId)
        )
      )
      .groupMap(_._1)(_._2)
    val found = mutable.ListBuffer.empty[PathRow]
    def walk(node: String, nodes: List[String], edgeIds: List[String]): Unit =
      if node == q.toId then found += PathRow(nodes.reverse, edgeIds.reverse)
      else if edgeIds.size < q.maxDepth then
        adj.getOrElse(node, Nil).sortBy(_._1).foreach { (next, eid) =>
          if !nodes.contains(next) then walk(next, next :: nodes, eid :: edgeIds)
        }
    walk(q.fromId, List(q.fromId), Nil)
    Right(found.toList.sortBy(p => (p.edgeIds.size, p.nodeIds.mkString(","))).take(q.limit))
  }
