package com.dirk.kalshiodds.signal.ml

import kotlinx.serialization.Serializable

/**
 * Settlement replay buffer for last-layer / stack updates.
 * Stores compact activations — not raw books — so it stays small on-device.
 */
@Serializable
data class ReplaySample(
    val ticker: String,
    val series: String,
    val tte: String,
    val pYes: Double,
    val outcomeYes: Boolean,
    val settledAtMs: Long,
    val backbone: List<Float> = emptyList(),
    val tabular: List<Float> = emptyList(),
    val mlpYes: Double? = null,
    val cnnYes: Double? = null,
    val lstmYes: Double? = null,
    val gbmYes: Double? = null,
    val mid: Double? = null,
    val edgePp: Double? = null
)

class ReplayBuffer(private val maxSize: Int = MAX) {
    private val items = ArrayDeque<ReplaySample>()

    @Synchronized
    fun snapshot(): List<ReplaySample> = items.toList()

    @Synchronized
    fun replaceAll(samples: List<ReplaySample>) {
        items.clear()
        samples.takeLast(maxSize).forEach { items.addLast(it) }
    }

    @Synchronized
    fun add(sample: ReplaySample) {
        items.addLast(sample)
        while (items.size > maxSize) items.removeFirst()
    }

    @Synchronized
    fun addNew(samples: List<ReplaySample>, afterMs: Long): List<ReplaySample> {
        val fresh = samples.filter { it.settledAtMs > afterMs }.sortedBy { it.settledAtMs }
        fresh.forEach { add(it) }
        return fresh
    }

    @Synchronized
    fun size(): Int = items.size

    companion object {
        const val MAX = 128
    }
}
