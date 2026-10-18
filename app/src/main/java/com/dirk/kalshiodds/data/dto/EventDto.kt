package com.dirk.kalshiodds.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EventsResponse(
    val events: List<EventDto> = emptyList(),
    val cursor: String? = null
)

@Serializable
data class EventDto(
    @SerialName("event_ticker") val eventTicker: String? = null,
    @SerialName("series_ticker") val seriesTicker: String? = null
)
