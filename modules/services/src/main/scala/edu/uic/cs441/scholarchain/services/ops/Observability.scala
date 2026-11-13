package edu.uic.cs441.scholarchain.services.ops

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Destination of structured log lines (one JSON object per line). */
trait LogSink:
  def emit(level: String, line: String): Unit

/** Production sink: SLF4J (logback, configured to print the message only). */
final class Slf4jLogSink extends LogSink:
  private val logger                          = LoggerFactory.getLogger("scholarchain.services")
  def emit(level: String, line: String): Unit = level match
    case "ERROR" => logger.error(line)
    case "WARN"  => logger.warn(line)
    case _       => logger.info(line)

/** Test sink: keeps lines in memory. */
final class MemoryLogSink extends LogSink:
  private val buf                             = mutable.ArrayBuffer.empty[String]
  def emit(level: String, line: String): Unit = synchronized(buf += line)
  def lines: List[String]                     = synchronized(buf.toList)

/**
 * Structured JSON logger. Callers pass only allowlisted, non-sensitive fields: IDs, route
 * templates, statuses, durations, counts, and error codes. Request bodies, questions, header
 * values, credentials, and idempotency keys are never passed here; idempotency keys are logged only
 * as a short digest.
 */
final class StructuredLog(sink: LogSink, clock: Clock):
  def log(level: String, event: String, fields: (String, ujson.Value)*): Unit =
    val obj = ujson.Obj(
      "ts"    -> clock.instant().toString,
      "level" -> level,
      "event" -> event
    )
    fields.foreach((k, v) => obj(k) = v)
    sink.emit(level, ujson.write(obj))

  def info(event: String, fields: (String, ujson.Value)*): Unit  = log("INFO", event, fields*)
  def warn(event: String, fields: (String, ujson.Value)*): Unit  = log("WARN", event, fields*)
  def error(event: String, fields: (String, ujson.Value)*): Unit = log("ERROR", event, fields*)

/**
 * Process-local counters, exposed in Prometheus text format by `GET /metrics`. Callers pass only
 * bounded label values; as a backstop, at most `maxSeries` counter series exist, and increments for
 * any further series go to `scholarchain_metrics_dropped_series_total`, so memory stays bounded
 * even if a caller passes an unbounded label.
 */
final class Metrics(maxSeries: Int = Metrics.DefaultMaxSeries):
  private val counters = ConcurrentHashMap[String, LongAdder]()
  private val gauges   = ConcurrentHashMap[String, () => Long]()
  private val dropped  = LongAdder()

  /** Number of counter series currently held (the bounded quantity). */
  def seriesCount: Int = counters.size

  private def key(name: String, labels: Seq[(String, String)]): String =
    if labels.isEmpty then name
    else
      labels
        .map((k, v) => s"""$k="${v.replace("\\", "\\\\").replace("\"", "\\\"")}"""")
        .mkString(s"$name{", ",", "}")

  def inc(name: String, labels: (String, String)*): Unit = add(name, 1L, labels*)

  def add(name: String, n: Long, labels: (String, String)*): Unit =
    val k = key(name, labels)
    Option(counters.get(k)) match
      case Some(c)                            => c.add(n)
      case None if counters.size >= maxSeries => dropped.increment()
      case None => counters.computeIfAbsent(k, _ => LongAdder()).add(n)

  def gauge(name: String, labels: (String, String)*)(read: () => Long): Unit =
    gauges.put(key(name, labels), read)
    ()

  def counter(name: String, labels: (String, String)*): Long =
    Option(counters.get(key(name, labels))).fold(0L)(_.sum())

  def render: String =
    val c = counters.asScala.toList.map((k, v) => s"$k ${v.sum()}")
    val g = gauges.asScala.toList.map((k, f) => s"$k ${f()}")
    val d = s"scholarchain_metrics_dropped_series_total ${dropped.sum()}"
    (d :: c ++ g).sorted.mkString("", "\n", "\n")

object Metrics:
  val DefaultMaxSeries: Int = 2000
