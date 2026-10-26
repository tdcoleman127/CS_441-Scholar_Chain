package edu.uic.cs441.scholarchain.core.model

import upickle.default.{readwriter, ReadWriter}

/**
 * Builds a JSON codec for an enum from explicit wire names, so that renaming a Scala case never
 * changes the wire format. An unknown wire name is a decode failure.
 */
private[core] object WireEnum:

  def readWriter[E](kind: String, values: Seq[E], wire: E => String): ReadWriter[E] =
    val byWire = values.map(v => wire(v) -> v).toMap
    require(byWire.size == values.size, s"duplicate wire names in $kind")
    readwriter[String].bimap[E](
      wire,
      s => byWire.getOrElse(s, throw IllegalArgumentException(s"unknown $kind wire value '$s'"))
    )
