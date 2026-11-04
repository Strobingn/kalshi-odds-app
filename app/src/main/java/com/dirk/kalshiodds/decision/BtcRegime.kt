package com.dirk.kalshiodds.decision

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.floor
import kotlin.math.round

/**
 * Paper scalper for KXBTC15M. Time left in the 15-minute window picks a regime.
 * Entries, spikes, and exits are pure functions of that regime and [Settings].
 * Size is the existing paper clip. Nothing here places a live order.
 */
object BtcRegime {
    const val ID = "btc15r"
    const val LABEL = "BTC 15m regime"
    const val VELOCITY_LOOKBACK_SEC = 30
    const val VWAP_WINDOW_SEC = 3600
    const val VWAP_MIN_SAMPLES = 30

    /** Owner research hours. Off unless the settings toggle is on. */
    val AVOID_HOURS: Set<Int> = setOf(8, 11, 16, 18, 21)

    enum class Name { EARLY, PRIME, LATE, FINAL }

    /**
     * Rules for one time-left bucket.
     * [newEntries] is false only in [Name.FINAL]. EARLY and PRIME still require
     * velocity and 1h VWAP agreement. LATE is the favourite side only.
     */
    data class Regime(
        val name: Name,
        val newEntries: Boolean,
        val requiresAlignment: Boolean,
        val favouriteOnly: Boolean,
        val usesLateStop: Boolean,
        val flatten: Boolean
    )

    enum class ExitKind { TAKE_PROFIT, STOP, FINAL_CUT }

    enum class SpikeAction { CLEAR, WAIT, SELL }

    /** One ask (or mid) observation. Spike detection uses [ask]. */
    data class Px(val atMs: Long, val ask: Double, val mid: Double = ask)

    @Serializable
    data class Settings(
        val floorCents: Int = PaperFleetDefaults.BUY_MIN,
        /** EARLY highest buy. Default 55¢, never above 75¢. */
        val maxAskCents: Int = PaperFleetDefaults.BUY_MAX,
        val primeYesMax: Int = 72,
        val primeNoMax: Int = 65,
        val lateMaxAsk: Int = PaperFleetDefaults.BUY_CEILING,
        val lateStopCents: Int = 8,
        val fallLookbackTicks: Int = 3,
        val avoidUtcHours: Boolean = false,
        val maxEntriesPerWindow: Int = 2,
        val spikeCents: Int = 6,
        val spikeWindowSec: Int = 10,
        val takeProfitCents: Int = PaperFleetDefaults.PROFIT_CENTS,
        val stopCents: Int = 12,
        val finalCutSeconds: Int = 45
    ) {
        /** Owner clamps. Floor stays at least 15¢. Every ask cap stays at most 75¢. */
        fun normalized(): Settings {
            val floor = floorCents.coerceIn(PaperFleetDefaults.BUY_MIN, PaperFleetDefaults.BUY_CEILING - 1)
            fun cap(v: Int) = v.coerceIn(floor, PaperFleetDefaults.BUY_CEILING)
            val tp = takeProfitCents.coerceIn(1, 60)
            return copy(
                floorCents = floor,
                maxAskCents = cap(maxAskCents),
                primeYesMax = cap(primeYesMax),
                primeNoMax = cap(primeNoMax),
                lateMaxAsk = cap(lateMaxAsk),
                lateStopCents = lateStopCents.coerceIn(1, 48),
                fallLookbackTicks = fallLookbackTicks.coerceIn(2, 20),
                maxEntriesPerWindow = maxEntriesPerWindow.coerceIn(1, 20),
                spikeCents = spikeCents.coerceIn(1, 40),
                spikeWindowSec = spikeWindowSec.coerceIn(1, 60),
                takeProfitCents = tp,
                stopCents = stopCents.coerceIn(1, 48),
                finalCutSeconds = finalCutSeconds.coerceIn(1, 59)
            )
        }
    }

    /**
     * AI Learn / Apply advice. The 15¢ floor and 25¢ take-profit stay.
     * Other knobs may move, still inside [Settings.normalized].
     */
    fun protectDefaults(@Suppress("UNUSED_PARAMETER") current: Settings, proposed: Settings): Settings {
        val n = proposed.normalized()
        return n.copy(
            floorCents = PaperFleetDefaults.BUY_MIN,
            takeProfitCents = PaperFleetDefaults.PROFIT_CENTS
        )
    }

    fun matches(id: String?): Boolean = id == ID

    fun tradesTicker(ticker: String): Boolean =
        ticker.trim().uppercase(Locale.US).startsWith("KXBTC15M")

    fun utcHour(atMs: Long): Int =
        Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC).hour

    /** Whole seconds left. 10:00 is EARLY. 9:59 is PRIME. 0:59 is FINAL. */
    fun forSecondsLeft(s: Int): Regime = when {
        s >= 10 * 60 -> Regime(Name.EARLY, newEntries = true, requiresAlignment = true, favouriteOnly = false, usesLateStop = false, flatten = false)
        s >= 5 * 60 -> Regime(Name.PRIME, newEntries = true, requiresAlignment = true, favouriteOnly = false, usesLateStop = false, flatten = false)
        s >= 60 -> Regime(Name.LATE, newEntries = true, requiresAlignment = false, favouriteOnly = true, usesLateStop = true, flatten = false)
        else -> Regime(Name.FINAL, newEntries = false, requiresAlignment = false, favouriteOnly = false, usesLateStop = false, flatten = true)
    }

    fun forSecondsLeft(s: Double): Regime =
        forSecondsLeft(if (!s.isFinite()) -1 else floor(s).toInt())

    fun cents(px: Double): Int = round(px * 100.0).toInt()

    /** Last [n] ticks are non-decreasing and the short-term slope is at least zero. */
    fun priceNotFalling(prices: List<Double>, n: Int): Boolean {
        if (n < 2 || prices.size < n) return false
        val w = prices.takeLast(n)
        for (i in 1 until w.size) if (w[i] + 1e-12 < w[i - 1]) return false
        val slope = (w.last() - w.first()) / (w.size - 1).toDouble()
        return slope >= -1e-12
    }

    /** YES needs an up move and spot above VWAP. NO needs the opposite. A zero does not agree. */
    fun aligned(side: String, velocity: Double?, spot: Double?, vwap: Double?): Boolean {
        if (velocity == null || spot == null || vwap == null) return false
        if (!velocity.isFinite() || velocity == 0.0 || !spot.isFinite() || !vwap.isFinite()) return false
        return if (side == "YES") velocity > 0.0 && spot > vwap else side == "NO" && velocity < 0.0 && spot < vwap
    }

    /**
     * Ask increase inside the spike window, in cents.
     * A later print has to clear an earlier print. A drop does not count as a chase.
     */
    fun jumpCents(path: List<Px>, nowMs: Long, windowSec: Int): Double {
        val from = nowMs - windowSec.coerceAtLeast(1) * 1000L
        val w = path.filter { it.atMs in from..nowMs && it.ask.isFinite() }.sortedBy { it.atMs }
        if (w.size < 2) return 0.0
        var low = w.first().ask
        var jump = 0.0
        for (p in w) {
            val up = p.ask - low
            if (up > jump) jump = up
            if (p.ask < low) low = p.ask
        }
        return jump * 100.0
    }

    /**
     * Spike: never open or add. An open position sells only when the bid already
     * clears entry plus take-profit. Otherwise wait.
     */
    fun spikeAction(
        path: List<Px>,
        nowMs: Long,
        settings: Settings = Settings(),
        openEntry: Double? = null,
        bid: Double? = null
    ): SpikeAction {
        val s = settings.normalized()
        if (jumpCents(path, nowMs, s.spikeWindowSec) <= s.spikeCents + 1e-9) return SpikeAction.CLEAR
        val tp = openEntry?.let { it + s.takeProfitCents / 100.0 }
        if (openEntry != null && bid != null && bid.isFinite() && tp != null && bid + 1e-9 >= tp) return SpikeAction.SELL
        return SpikeAction.WAIT
    }

    data class Entry(
        val secondsLeft: Int,
        val side: String,
        val ask: Double,
        val fair: Double = 0.5,
        val prices: List<Double> = emptyList(),
        val velocity: Double? = null,
        val spot: Double? = null,
        val vwap: Double? = null,
        val utcHour: Int = 0,
        val entriesThisWindow: Int = 0,
        val openEntry: Double? = null,
        val openBid: Double? = null,
        val askPath: List<Px> = emptyList(),
        val nowMs: Long = 0L,
        val settings: Settings = Settings()
    )

    /** Null when every entry rule passes. Otherwise a short reason. */
    fun blockEntry(e: Entry): String? {
        val s = e.settings.normalized()
        val regime = forSecondsLeft(e.secondsLeft)
        if (!regime.newEntries) return "final minute: no new entries"
        val side = e.side.trim().uppercase(Locale.US)
        if (side != "YES" && side != "NO") return "side"
        if (s.avoidUtcHours && e.utcHour in AVOID_HOURS) return "avoid UTC hour ${e.utcHour}"
        if (e.entriesThisWindow >= s.maxEntriesPerWindow) return "max entries this window"
        if (e.openEntry != null && (e.openBid == null || !e.openBid.isFinite() || e.openBid + 1e-9 < e.openEntry)) {
            return "no averaging down"
        }
        if (spikeAction(e.askPath, e.nowMs, s) != SpikeAction.CLEAR) return "spike: no chase"
        val askC = cents(e.ask)
        if (askC < s.floorCents) return "below ${s.floorCents}¢ floor"
        val cap = when (regime.name) {
            Name.EARLY -> s.maxAskCents
            Name.PRIME -> if (side == "YES") s.primeYesMax else s.primeNoMax
            Name.LATE -> s.lateMaxAsk
            Name.FINAL -> return "final minute: no new entries"
        }
        if (askC > cap) return "ask above ${cap}¢"
        if (regime.favouriteOnly) {
            if (!e.fair.isFinite() || e.fair + 1e-12 < 0.50) return "late underdog"
            if (e.ask + 1e-9 < 0.50) return "late under 50¢"
        }
        if (!priceNotFalling(e.prices, s.fallLookbackTicks)) return "price still falling"
        if (regime.requiresAlignment && !aligned(side, e.velocity, e.spot, e.vwap)) return "velocity and VWAP disagree"
        return null
    }

    /** Take-profit, stop, or the final-minute cut. Null means keep the position. */
    fun exitKind(secondsLeft: Int, entry: Double, bid: Double, settings: Settings = Settings()): ExitKind? {
        if (!entry.isFinite() || !bid.isFinite() || bid <= 0.0) return null
        val s = settings.normalized()
        val regime = forSecondsLeft(secondsLeft)
        if (regime.flatten && secondsLeft <= s.finalCutSeconds) return ExitKind.FINAL_CUT
        if (bid + 1e-9 >= entry + s.takeProfitCents / 100.0) return ExitKind.TAKE_PROFIT
        val stopC = if (regime.usesLateStop) s.lateStopCents else s.stopCents
        if (bid <= entry - stopC / 100.0 + 1e-9) return ExitKind.STOP
        return null
    }

    fun exitReason(kind: ExitKind, settings: Settings = Settings()): String {
        val s = settings.normalized()
        return when (kind) {
            ExitKind.TAKE_PROFIT -> "take profit +${s.takeProfitCents}¢"
            ExitKind.STOP -> "stop"
            ExitKind.FINAL_CUT -> "final cut ${s.finalCutSeconds}s"
        }
    }

    /** One-second BTC spot tape. Velocity is the sign of the last [VELOCITY_LOOKBACK_SEC]. VWAP is the 1h average. */
    class SpotTape {
        private val secs = LongArray(RING) { Long.MIN_VALUE }
        private val px = DoubleArray(RING)

        fun put(sec: Long, price: Double) {
            if (!price.isFinite() || price <= 0.0) return
            val i = (sec and MASK).toInt()
            secs[i] = sec
            px[i] = price
        }

        fun at(sec: Long): Double? {
            for (d in 0..2) {
                val s = sec - d
                val i = (s and MASK).toInt()
                if (secs[i] == s) return px[i]
            }
            return null
        }

        fun velocity(nowSec: Long, lookbackSec: Int = VELOCITY_LOOKBACK_SEC): Double? {
            val now = at(nowSec) ?: return null
            val old = at(nowSec - lookbackSec) ?: return null
            return now - old
        }

        fun vwap(nowSec: Long, windowSec: Int = VWAP_WINDOW_SEC): Double? {
            var sum = 0.0
            var n = 0
            var s = nowSec - windowSec + 1
            while (s <= nowSec) {
                val i = (s and MASK).toInt()
                if (secs[i] == s) {
                    sum += px[i]
                    n++
                }
                s++
            }
            if (n < VWAP_MIN_SAMPLES) return null
            return sum / n
        }

        private companion object {
            const val RING = 4096
            const val MASK = 4095L
        }
    }
}
