package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.AtmStrike
import java.util.Locale

/** One point on the live card chart. Prices in dollars, odds in 0–1. */
data class AiLivePoint(
    val atMs: Long,
    val btc: Double?,
    val strike: Double?,
    val up: Double?,
    val down: Double?,
    val upAsk: Double? = null,
    val downAsk: Double? = null,
    val modelUp: Double? = null
)

data class AiLivePosition(val label: String, val side: String, val contracts: Int, val pnlUsd: Double)

/** One line on the live card. A zero clip is flat, not a position. */
data class AiLiveRow(val id: String, val text: String, val pnlUsd: Double, val flat: Boolean)

/**
 * Latest KXBTC15M picture. Replaced on every Kalshi odds/book tick and every BTC price tick.
 * [atMs] is the tick time, not a 1-second timer.
 */
data class AiLiveSnapshot(
    val atMs: Long,
    val windowKey: String,
    val ticker: String,
    val closeMs: Long,
    val strike: Double?,
    val btc: Double?,
    val yesBid: Double?,
    val yesAsk: Double?,
    val noBid: Double?,
    val noAsk: Double?,
    val modelUp: Double?,
    val positions: List<AiLivePosition> = emptyList(),
    val points: List<AiLivePoint> = emptyList(),
    val fillNote: String = ""
) {
    val distanceUsd: Double? get() = if (btc != null && strike != null && btc.isFinite() && strike.isFinite()) btc - strike else null
    fun ageMs(nowMs: Long): Long = (nowMs - atMs).coerceAtLeast(0L)
}

enum class LiveTrigger { WINDOW, BIG_MOVE, FILL, LIVE_TICK, NONE }

/**
 * 1.0.15 live feed. The card and tick history update on every Kalshi tick with no LLM call.
 * Automatic chat-LLM re-checks (every provider) wait at least [MIN_SPACING_MS].
 * On-device scalpers, the forecast, auto-freeze, and the paper strategy are not on this clock.
 */
object AiLiveFeed {
    /** Automatic chat LLM calls, live posts, and odds/BTC re-checks: at most once every 60 seconds, all providers. */
    const val MIN_SPACING_MS = 120_000L
    const val MATERIAL_CENTS = 2.0
    const val BIG_MOVE_CENTS = 10.0
    const val MAX_POINTS = 180

    fun reduce(prev: AiLiveSnapshot?, incoming: AiLiveSnapshot): AiLiveSnapshot {
        val kept = if (prev != null && prev.windowKey.isNotBlank() && prev.windowKey == incoming.windowKey &&
            prev.ticker.isNotBlank() && incoming.ticker.isNotBlank() && prev.ticker != incoming.ticker && !closer(prev, incoming)) {
            prev.copy(atMs = incoming.atMs, btc = incoming.btc ?: prev.btc)
        } else incoming
        val point = AiLivePoint(
            kept.atMs, kept.btc, kept.strike, kept.yesBid, kept.noBid,
            upAsk = kept.yesAsk, downAsk = kept.noAsk, modelUp = kept.modelUp
        )
        val sameWindow = prev != null && prev.windowKey.isNotBlank() && prev.windowKey == kept.windowKey
        val points = (if (sameWindow) prev.points else emptyList()).plus(point).takeLast(MAX_POINTS)
        return kept.copy(points = points)
    }

    private fun closer(current: AiLiveSnapshot, incoming: AiLiveSnapshot): Boolean {
        fun dist(s: AiLiveSnapshot) = s.distanceUsd?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) } ?: Double.MAX_VALUE
        return dist(incoming) < dist(current)
    }

    fun trigger(prev: AiLiveSnapshot?, next: AiLiveSnapshot): LiveTrigger {
        if (prev == null) return if (next.windowKey.isBlank()) LiveTrigger.NONE else LiveTrigger.WINDOW
        if (next.windowKey.isNotBlank() && next.windowKey != prev.windowKey) return LiveTrigger.WINDOW
        if (prev.closeMs > 0 && prev.atMs < prev.closeMs && next.atMs >= prev.closeMs) return LiveTrigger.WINDOW
        val move = centsMove(prev, next)
        val crossed = crossedStrike(prev, next)
        if (move >= BIG_MOVE_CENTS || crossed) return LiveTrigger.BIG_MOVE
        if (next.fillNote.isNotBlank() && next.fillNote != prev.fillNote) return LiveTrigger.FILL
        if (move >= MATERIAL_CENTS) return LiveTrigger.LIVE_TICK
        return LiveTrigger.NONE
    }

    /**
     * True when the decision loop may call the model.
     * A call inside the last [MIN_SPACING_MS] is refused even if a tick or the regular interval is due.
     */
    fun llmDue(lastCallAt: Long, nowMs: Long, trigger: LiveTrigger, intervalDue: Boolean): Boolean {
        if (lastCallAt != Long.MIN_VALUE && nowMs - lastCallAt < MIN_SPACING_MS) return false
        return trigger != LiveTrigger.NONE || intervalDue
    }

    fun source(trigger: LiveTrigger): String = when (trigger) {
        LiveTrigger.WINDOW -> LlmSources.WINDOW
        LiveTrigger.BIG_MOVE -> LlmSources.BIG_MOVE
        LiveTrigger.FILL, LiveTrigger.LIVE_TICK -> LlmSources.LIVE_TICK
        LiveTrigger.NONE -> LlmSources.WATCHER
    }

    fun prefer(current: LiveTrigger, next: LiveTrigger): LiveTrigger =
        if (current == LiveTrigger.NONE || next.ordinal < current.ordinal) next else current

    /** Soonest open window, then the strike nearest the live BTC print. Same rule as the chat. Exact ties use the window open, then neither. */
    fun atm(snaps: List<AiLiveSnapshot>, openPx: Double? = null): AiLiveSnapshot? {
        val open = snaps.filter { it.ticker.isNotBlank() && it.closeMs > it.atMs }
        if (open.isEmpty()) return null
        val close = open.minOf { it.closeMs }
        return AtmStrike.pick(open.filter { it.closeMs == close },
            { s -> s.distanceUsd?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) } ?: Double.POSITIVE_INFINITY },
            { s -> AtmStrike.priceDistance(s.strike, openPx) })
    }

    /**
     * Cents for the live card. 99.6¢ stays 99.6¢. Only a real 100.0 price shows as 100¢.
     */
    fun centsLabel(v: Double?): String {
        val p = v?.takeIf { it.isFinite() } ?: return "—"
        val cents = (kotlin.math.round(p * 1000.0) / 10.0)
        val whole = kotlin.math.round(cents)
        return if (kotlin.math.abs(cents - whole) < 0.05) "${whole.toInt()}¢" else String.format(Locale.US, "%.1f¢", cents)
    }

    /** One decimal so 98.4% is not shown as 100%. */
    fun upChanceLabel(p: Double?): String {
        val v = p?.takeIf { it.isFinite() } ?: return "—"
        val pct = kotlin.math.round(v.coerceIn(0.0, 1.0) * 1000.0) / 10.0
        val whole = kotlin.math.round(pct)
        return if (kotlin.math.abs(pct - whole) < 0.05) "${whole.toInt()}%" else String.format(Locale.US, "%.1f%%", pct)
    }

    fun cardOddsLine(s: AiLiveSnapshot): String =
        "UP ${centsLabel(s.yesBid)}/${centsLabel(s.yesAsk)} · DOWN ${centsLabel(s.noBid)}/${centsLabel(s.noAsk)} · AI UP ${upChanceLabel(s.modelUp)}"

    /**
     * One row per full variant id. `-v1` / `-v2` / `-v3` stay on the id.
     * Two pending clips of the same id (the parent opened twice, both at 0 contracts)
     * collapse to a single flat line. A real clip keeps its side, size, and P&L.
     */
    fun positionRows(rows: List<AiLivePosition>): List<AiLiveRow> {
        val grouped = LinkedHashMap<String, AiLivePosition>()
        for (p in rows) {
            val id = p.label.trim()
            if (id.isEmpty()) continue
            val side = p.side.trim().uppercase(Locale.US)
            val prev = grouped["$id|$side"]
            grouped["$id|$side"] = if (prev == null) p.copy(label = id, side = side)
            else prev.copy(contracts = prev.contracts + p.contracts, pnlUsd = prev.pnlUsd + p.pnlUsd)
        }
        val out = ArrayList<AiLiveRow>()
        for ((id, list) in grouped.values.groupBy { it.label }) {
            val open = list.filter { it.contracts > 0 }
            if (open.isEmpty()) {
                out += AiLiveRow(id, "$id flat", list.sumOf { it.pnlUsd }, true)
            } else {
                for (p in open) {
                    out += AiLiveRow(
                        id,
                        "$id ${p.side} ${p.contracts} · ${String.format(Locale.US, "%+.2f", p.pnlUsd)}",
                        p.pnlUsd,
                        false
                    )
                }
            }
        }
        return out
    }

    fun post(live: AiLiveSnapshot?, trigger: LiveTrigger, tweaks: List<AiAdjustment>): String {
        val s = live
        val call = s?.modelUp?.takeIf { it.isFinite() }?.let { if (it >= 0.5) "UP" else "DOWN" } ?: "no call"
        val pct = upChanceLabel(s?.modelUp)
        val btc = s?.btc?.let { money(it) } ?: "—"
        val strike = s?.strike?.let { money(it) } ?: "—"
        val dist = s?.distanceUsd?.let { signed(it) } ?: "—"
        val up = centsLabel(s?.yesBid) + "/" + centsLabel(s?.yesAsk)
        val down = centsLabel(s?.noBid) + "/" + centsLabel(s?.noAsk)
        val why = when (trigger) {
            LiveTrigger.WINDOW -> "window open/close"
            LiveTrigger.BIG_MOVE -> "big move"
            LiveTrigger.FILL -> "new fill"
            LiveTrigger.LIVE_TICK -> "odds move"
            LiveTrigger.NONE -> "scheduled tick"
        }
        val changes = if (tweaks.isEmpty()) "no setting changes" else tweaks.joinToString("; ") {
            "${it.scalper} ${it.setting} ${it.oldValue} → ${it.newValue}"
        }
        return "Live feed · $why · ${s?.ticker ?: "KXBTC15M"} · BTC $btc vs strike $strike ($dist) · UP $up · DOWN $down · call $call ($pct). $changes"
    }

    private fun centsMove(a: AiLiveSnapshot, b: AiLiveSnapshot): Double {
        val d = listOf(a.yesAsk to b.yesAsk, a.yesBid to b.yesBid, a.noAsk to b.noAsk).firstNotNullOfOrNull { (x, y) ->
            if (x != null && y != null && x.isFinite() && y.isFinite()) kotlin.math.abs(y - x) * 100.0 else null
        }
        return d ?: 0.0
    }

    private fun crossedStrike(a: AiLiveSnapshot, b: AiLiveSnapshot): Boolean {
        val as_ = a.btc; val ak = a.strike; val bs = b.btc; val bk = b.strike
        if (as_ == null || ak == null || bs == null || bk == null) return false
        if (!as_.isFinite() || !ak.isFinite() || !bs.isFinite() || !bk.isFinite()) return false
        return (as_ - ak) * (bs - bk) < 0.0
    }

    private fun money(v: Double) = String.format(Locale.US, "$%,.2f", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+,.2f", v)
}
