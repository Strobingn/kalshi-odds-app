package com.dirk.kalshiodds.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display only: epoch timestamps and market calculations remain UTC. */
object EasternTime {
    private val zone = ZoneId.of("America/New_York")
    private val short = DateTimeFormatter.ofPattern("h:mm a z", Locale.US).withZone(zone)
    private val seconds = DateTimeFormatter.ofPattern("h:mm:ss a z", Locale.US).withZone(zone)
    private val date = DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US).withZone(zone)

    fun short(epochMs: Long): String = short.format(Instant.ofEpochMilli(epochMs))
    fun seconds(epochMs: Long): String = seconds.format(Instant.ofEpochMilli(epochMs))
    fun date(epochMs: Long): String = date.format(Instant.ofEpochMilli(epochMs))
}
