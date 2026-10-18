package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EventsResponse(val cursor: String? = null, val events: List<EventDto> = emptyList())

@Serializable
data class EventDto(@SerialName("event_ticker") val eventTicker: String = "", @SerialName("series_ticker") val seriesTicker: String = "")
