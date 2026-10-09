package com.dirk.kalshiodds.decision

import android.content.Context
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One paper (or shadow) entry for a ladder strategy. */
@Serializable
data class LadderEntry(
    val id: String,
    val strategy: String,
    val ticker: String,
    val event: String,
    val side: String,
    val price: Double,
    val contracts: Int,
    val costUsd: Double,
    val feeUsd: Double,
    val createdAtMs: Long,
    val stage: String = StrategyLadder.Stage.PAPER.name,
    val note: String = "",
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null
)

@Serializable
data class LadderState(
    val entries: List<LadderEntry> = emptyList(),
    val stages: Map<String, String> = emptyMap(),
    val nofills: Int = 0
)

/** Shared $10 taker sizing: largest whole C with C×P + fee(C,P) ≤ $10, fee rounded up to the cent. */
object TenDollarClip {
    const val STAKE_USD = 10.0

    data class Clip(val contracts: Int, val costUsd: Double, val feeUsd: Double)

    fun size(price: Double, stakeUsd: Double = STAKE_USD, feeRate: Double = KalshiFee.TAKER_COEFFICIENT): Clip? {
        if (!price.isFinite() || price <= 0.0 || price >= 1.0) return null
        var c = kotlin.math.floor(stakeUsd / price).toInt()
        while (c > 0) {
            val cost = KalshiFee.totalCost(c, price, feeRate)
            if (cost <= stakeUsd + 1e-9) return Clip(c, cost, KalshiFee.total(c, price, feeRate))
            c -= 1
        }
        return null
    }
}

/**
 * fav15 (preregistered 2026-10-06): on KXBTC15M/KXETH15M/KXSOL15M, when both
 * asks exist, ≥ 300 s remain, and the cheaper ask is 2–15¢, buy the
 * favourite at its ask with a $10 clip. Fill only if the visible favourite
 * ask size covers the clip. First entry per market only. Held to settlement.
 */
object Fav15Rule {
    const val ID = "fav15"
    const val MIN_SECONDS = 300.0
    const val CHEAP_LO = 0.02
    const val CHEAP_HI = 0.15

    data class Signal(val side: String, val price: Double, val clip: TenDollarClip.Clip)

    sealed class Result {
        data class Enter(val signal: Signal) : Result()
        data class NoFill(val reason: String) : Result()
        data class Skip(val reason: String) : Result()
    }

    fun evaluate(
        yesAsk: Double?,
        noAsk: Double?,
        yesAskSize: Double?,
        noAskSize: Double?,
        secondsRemaining: Double?,
        bookFresh: Boolean
    ): Result {
        if (yesAsk == null || noAsk == null) return Result.Skip("both asks required")
        val t = secondsRemaining ?: return Result.Skip("no close time")
        if (t < MIN_SECONDS) return Result.Skip("under 5 minutes left")
        val cheapYes = yesAsk <= noAsk
        val cheap = if (cheapYes) yesAsk else noAsk
        if (cheap < CHEAP_LO - 1e-12 || cheap > CHEAP_HI + 1e-12) return Result.Skip("cheaper ask outside 2–15¢")
        val favSide = if (cheapYes) "NO" else "YES"
        val favAsk = if (cheapYes) noAsk else yesAsk
        val favSize = if (cheapYes) noAskSize else yesAskSize
        val clip = TenDollarClip.size(favAsk) ?: return Result.Skip("no clip at ${cents(favAsk)}")
        if (!bookFresh) return Result.NoFill("stale book")
        if (favSize == null || favSize + 1e-9 < clip.contracts) return Result.NoFill("visible size under ${clip.contracts}")
        return Result.Enter(Signal(favSide, favAsk, clip))
    }

    private fun cents(p: Double) = com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(p)
}

/**
 * v060 (frozen 2026-09-28, edge-research/v060 Addendum B): KXBTCD daily
 * 5 PM ET above/below. FLB model logit p = a + b·logit(mid) per time-left
 * bucket, coefficients never refit. At each decision time (24…2 h, 30 and
 * 10 min before close) bet the single best (market, side) of the event if
 * EV/$ ≥ 0.06, entry price 3–97¢, $10 clip, one bet per event.
 */
object V060Rule {
    const val ID = "v060"
    const val VERSION = "v060-flb-thr0.06-frozen-20260928"
    const val THRESHOLD = 0.06
    const val ENTRY_LO = 0.03
    const val ENTRY_HI = 0.97
    /** Decision taus (seconds before close) and the tolerance after each checkpoint. */
    val TAUS_S: List<Long> = listOf(86_400, 72_000, 57_600, 43_200, 28_800, 14_400, 7_200, 1_800, 600)
    const val CHECKPOINT_TOLERANCE_S = 300L
    private val COEF = mapOf(0 to (0.07404 to 1.14753), 1 to (0.13693 to 1.28328), 2 to (0.15001 to 1.29602))

    data class Quote(
        val ticker: String,
        val yesBid: Double?,
        val yesAsk: Double?,
        val noAsk: Double?,
        val yesAskSize: Double?,
        val noAskSize: Double?
    )

    data class Pick(val ticker: String, val side: String, val price: Double, val evPerDollar: Double, val p: Double, val clip: TenDollarClip.Clip)

    fun bucket(tauS: Double): Int = when {
        tauS >= 16 * 3600 -> 0
        tauS >= 4 * 3600 -> 1
        else -> 2
    }

    fun probability(mid: Double, tauS: Double): Double {
        val m = mid.coerceIn(0.001, 0.999)
        val (a, b) = COEF.getValue(bucket(tauS))
        val z = a + b * ln(m / (1.0 - m))
        return 1.0 / (1.0 + exp(-z))
    }

    /** Active checkpoint (seconds), or null between checkpoints. */
    fun checkpoint(tauS: Double): Long? =
        TAUS_S.firstOrNull { k -> tauS <= k && tauS > k - CHECKPOINT_TOLERANCE_S }

    fun evPerDollar(p: Double, price: Double): Pair<Double, TenDollarClip.Clip>? {
        if (price < ENTRY_LO - 1e-12 || price > ENTRY_HI + 1e-12) return null
        val clip = TenDollarClip.size(price) ?: return null
        return (p * clip.contracts - clip.costUsd) / clip.costUsd to clip
    }

    /** Best (market, side) of one event at one checkpoint; null if nothing clears EV/$ ≥ 0.06. */
    fun bestPick(quotes: List<Quote>, tauS: Double): Pick? {
        var best: Pick? = null
        for (q in quotes) {
            val bid = q.yesBid ?: continue
            val ask = q.yesAsk ?: continue
            if (ask <= bid) continue
            val p = probability((bid + ask) / 2.0, tauS)
            val yes = evPerDollar(p, ask)?.let { (ev, clip) -> Pick(q.ticker, "YES", ask, ev, p, clip) }
            val noPx = q.noAsk ?: (1.0 - bid)
            val no = evPerDollar(1.0 - p, noPx)?.let { (ev, clip) -> Pick(q.ticker, "NO", noPx, ev, 1.0 - p, clip) }
            for (c in listOfNotNull(yes, no)) {
                if (c.evPerDollar + 1e-12 < THRESHOLD) continue
                if (best == null || c.evPerDollar > best.evPerDollar) best = c
            }
        }
        return best
    }

    fun eventOf(ticker: String): String = ticker.uppercase().substringBeforeLast('-')
}

/**
 * Strategy ladder: paper → shadow (never sent). Promotion needs the
 * sample size and a 95% market-clustered bootstrap CI lower bound > 0 on
 * P&L per entry after fees. 0.3.40: there is no live stage; Autopilot and
 * Scalp cannot place orders. Only manual Approve + typed REAL MONEY can.
 */
object StrategyLadder {
    /** 0.3.40: no live stage — the ladder tops out at shadow (never sent). */
    enum class Stage(val label: String) { PAPER("Paper"), SHADOW("Shadow") }

    enum class Id(
        val key: String,
        val label: String,
        val rule: String,
        val need: Int,
        val countsFilled: Boolean,
        /** Highest stage this build allows. Scalp stays paper in 0.3.38: no shadow or live scalping. */
        val maxStage: Stage = Stage.SHADOW
    ) {
        V060("v060", "v060 daily BTC favourite", "KXBTCD 5 PM ET, frozen FLB model, EV/$ ≥ 0.06 at the ask, one bet per event", 100, true),
        V150("v150", "v150 D3 maker bid", "D3 maker bid 85–97¢, 2–4 PM ET, queue-honest fills", 100, true),
        FAV15("fav15", "fav15 15-minute favourite", "Buy the favourite when the cheaper ask is 2–15¢ with ≥ 5 min left, first entry per market", 300, false),
        SCALP(
            "scalp",
            "scalp 15-minute fair-gap scalp (PAPER)",
            "Buy below spot fair after fee when the move beats spread + both fees; exit at the bid on target / stop / gap close / turn-down, always by 60 s left; fees both legs",
            ScalpRule.PROMOTION_ROUND_TRIPS,
            false,
            Stage.PAPER
        )
    }

    data class Stats(val filled: Int, val settled: Int, val ci: ClusteredBootstrap.Ci?)

    data class Status(
        val id: Id,
        val stage: Stage,
        val stats: Stats,
        val n: Int,
        val eligibleForNext: Boolean,
        val reason: String
    )

    data class Item(val cluster: String, val filled: Boolean, val settled: Boolean, val pnlUsd: Double?)

    fun stats(items: List<Item>): Stats {
        val settled = items.filter { it.settled && it.pnlUsd != null }
        val ci = ClusteredBootstrap.meanCi(settled.map { it.cluster to (it.pnlUsd ?: 0.0) })
        return Stats(filled = items.count { it.filled }, settled = settled.size, ci = ci)
    }

    fun status(id: Id, stage: Stage, items: List<Item>): Status {
        val s = stats(items)
        val n = if (id.countsFilled) s.filled else s.settled
        val lo = s.ci?.lo
        val enough = n >= id.need
        val positive = lo != null && lo > 0.0 && (s.ci?.mean ?: 0.0) > 0.0
        val next = nextStage(stage)?.takeIf { it.ordinal <= id.maxStage.ordinal }
        val eligible = next != null && enough && positive
        val noun = when {
            id == Id.SCALP -> "round trips"
            id.countsFilled -> "filled"
            else -> "settled"
        }
        val ciText = s.ci?.let { String.format(Locale.US, "mean %+.3f, 95%% CI [%+.3f, %+.3f] over %d markets", it.mean, it.lo, it.hi, it.clusters) } ?: "no settled entries"
        val reason = when {
            id.maxStage == Stage.PAPER -> {
                val bar = if (enough && positive) "Bar met" else "Bar: ${id.need} $noun with CI lower bound > 0 (now $n)"
                "$bar · $ciText. Scalp is paper-only."
            }
            next == null -> "At the top stage (shadow, never sent). $n $noun · $ciText."
            !enough -> "Needs ${id.need} $noun (now $n) · $ciText"
            !positive -> "$n $noun but the CI lower bound is not above 0 · $ciText"
            else -> "Eligible for ${next.label} · $n $noun · $ciText"
        }
        return Status(id, stage, s, n, eligible, reason)
    }

    fun nextStage(stage: Stage): Stage? = when (stage) {
        Stage.PAPER -> Stage.SHADOW
        Stage.SHADOW -> null
    }

    fun rulesText(): String =
        "Strategy ladder: paper → shadow (never sent). There is no live stage; Autopilot and Scalp are paper-only. v060 and v150 need ${Id.V060.need} fills, " +
            "fav15 needs ${Id.FAV15.need} settled, scalp needs ${Id.SCALP.need} round trips (paper only in this release), " +
            "each with a market-clustered bootstrap 95% CI lower bound > 0. " +
            "Only manual tickets (Approve + REAL MONEY) can place real orders."

    fun parseStage(raw: String?): Stage = if (raw == "LIMITED_LIVE") Stage.SHADOW else Stage.values().firstOrNull { it.name == raw } ?: Stage.PAPER
}

/** Persists fav15 and v060 entries plus each strategy's ladder stage. No truncation. */
class LadderStore(
    context: Context? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val _state = MutableStateFlow(hydrate())
    val state: StateFlow<LadderState> = _state.asStateFlow()

    fun snapshot(): LadderState = _state.value

    fun stage(id: StrategyLadder.Id): StrategyLadder.Stage = StrategyLadder.parseStage(_state.value.stages[id.key])

    fun hasEntry(strategy: String, key: String): Boolean = _state.value.entries.any {
        it.strategy == strategy && (it.ticker.equals(key, true) || it.event.equals(key, true))
    }

    fun record(strategy: String, ticker: String, event: String, side: String, clip: TenDollarClip.Clip, price: Double, note: String): LadderEntry? {
        synchronized(lock) {
            val cur = _state.value
            val dupKey = if (strategy == V060Rule.ID) event else ticker
            if (cur.entries.any { it.strategy == strategy && (if (strategy == V060Rule.ID) it.event.equals(dupKey, true) else it.ticker.equals(dupKey, true)) }) {
                return null
            }
            val stageName = StrategyLadder.parseStage(cur.stages[strategy]).name
            val e = LadderEntry(
                id = idFactory(),
                strategy = strategy,
                ticker = ticker.uppercase(),
                event = event.uppercase(),
                side = side,
                price = price,
                contracts = clip.contracts,
                costUsd = clip.costUsd,
                feeUsd = clip.feeUsd,
                createdAtMs = nowMs(),
                stage = stageName,
                note = note
            )
            publish(cur.copy(entries = listOf(e) + cur.entries))
            return e
        }
    }

    fun noteNoFill() {
        synchronized(lock) { publish(_state.value.copy(nofills = _state.value.nofills + 1)) }
    }

    fun settle(ticker: String, result: String) {
        val r = result.lowercase()
        synchronized(lock) {
            val cur = _state.value
            var changed = false
            val next = cur.entries.map { e ->
                if (e.settled || !e.ticker.equals(ticker, true)) return@map e
                changed = true
                val voided = r != "yes" && r != "no"
                val won = if (voided) null else e.side.equals(r, true)
                val pnl = when {
                    voided -> 0.0
                    won == true -> e.contracts * 1.0 - e.costUsd
                    else -> -e.costUsd
                }
                e.copy(settled = true, outcome = r, won = won, pnlUsd = pnl)
            }
            if (changed) publish(cur.copy(entries = next))
        }
    }

    fun openTickers(): Set<String> = _state.value.entries.filter { !it.settled }.map { it.ticker.uppercase() }.toSet()

    fun items(strategy: String): List<StrategyLadder.Item> = _state.value.entries.filter { it.strategy == strategy }.map {
        StrategyLadder.Item(
            cluster = if (strategy == V060Rule.ID) it.event else it.ticker,
            filled = true,
            settled = it.settled,
            pnlUsd = it.pnlUsd
        )
    }

    /** Promote one stage, only when the ladder says eligible. Demote to paper any time. */
    fun promote(id: StrategyLadder.Id, status: StrategyLadder.Status): Boolean {
        if (!status.eligibleForNext) return false
        val next = StrategyLadder.nextStage(stage(id)) ?: return false
        if (next.ordinal > id.maxStage.ordinal) return false
        synchronized(lock) { publish(_state.value.copy(stages = _state.value.stages + (id.key to next.name))) }
        return true
    }

    fun demoteToPaper(id: StrategyLadder.Id) {
        synchronized(lock) { publish(_state.value.copy(stages = _state.value.stages + (id.key to StrategyLadder.Stage.PAPER.name))) }
    }

    private fun publish(next: LadderState) {
        _state.value = next
        prefs?.edit()?.putString(KEY, json.encodeToString(next))?.apply()
    }

    private fun hydrate(): LadderState {
        val raw = prefs?.getString(KEY, null) ?: return LadderState()
        return runCatching { json.decodeFromString<LadderState>(raw) }.getOrElse { LadderState() }
    }

    companion object {
        const val PREFS = "kashi_strategy_ladder"
        private const val KEY = "state"
    }
}
