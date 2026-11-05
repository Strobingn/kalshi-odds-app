package com.dirk.kalshiodds.signal.updown

import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.scalper.BookFill
import com.dirk.kalshiodds.signal.scalper.PaperBet
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Runs the [UpDownModel] on the live Bitcoin window once a second: keeps the window's YES mid second by second,
 * builds the inputs ([UpDownFeatures]) and keeps every reading of the window for the charts. Nothing here trades.
 */
class UpDownLive(
    private val model: UpDownModel,
    /** Coinbase BTC-USD, one slot a second ending at the second of nowMs, [UpDownFeatures.SPOT_SECONDS] long. */
    private val spot: (Long) -> DoubleArray,
    /** The live Bitcoin 15-minute window, or null. */
    private val window: (Long) -> Window?,
    private val top: (String) -> TopOfBook?
) {
    data class Window(val ticker: String, val openMs: Long, val closeMs: Long, val strike: Double?)

    /** One second's answer: chances 0..1, prices in dollars. */
    data class Reading(
        val ticker: String, val secIn: Int, val atMs: Long, val up: Double, val mid: Double, val fair: Double,
        val yesAsk: Double?, val noAsk: Double?
    )

    /** For the live chart: second of the window, model's chance of UP, the market's mid, the math's fair. */
    data class Point(val sec: Int, val up: Double, val mid: Double, val fair: Double)

    private val lock = Any()
    private var ticker: String? = null
    private val mids = DoubleArray(900) { Double.NaN }
    private val _points = MutableStateFlow<List<Point>>(emptyList())
    val points: StateFlow<List<Point>> = _points.asStateFlow()
    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest.asStateFlow()
    @Volatile var why: String = "waiting for the first second of a Bitcoin window"
        private set

    /** One step (call about once a second, off the main thread). Returns the reading, or null with [why] set. */
    fun tick(nowMs: Long): Reading? = synchronized(lock) {
        val w = window(nowMs) ?: return none("no live Bitcoin 15-minute window")
        if (w.ticker != ticker) {
            ticker = w.ticker
            mids.fill(Double.NaN)
            _points.value = emptyList()
            _latest.value = null
        }
        val nowSec = nowMs / 1000L
        val secIn = (nowSec - w.openMs / 1000L).toInt()
        if (secIn !in 0 until 900) return none("between windows")
        val t = top(w.ticker)
        val bid = t?.yesBid
        val ask = t?.yesAsk
        if (bid != null && ask != null) mids[secIn] = (bid + ask) / 2.0
        val strike = w.strike ?: return none("no strike for ${w.ticker} yet")
        val tte = (w.closeMs / 1000L - nowSec).toDouble()
        val row = UpDownFeatures.row(
            spot(nowMs), strike, tte, secIn, bid, t?.yesBidQty, ask, t?.yesAskQty, mids.copyOfRange(0, secIn + 1)
        ) ?: return none(if (bid == null || ask == null) "no two-sided book" else "Coinbase spot not fresh or volatility still warming up (needs 2 minutes of prints)")
        val x = UpDownFeatures.design(row, model.features) ?: return none("model asks for an unknown input")
        val up = model.up(x)
        val r = Reading(w.ticker, secIn, nowMs, up, UpDownFeatures.mid(row), UpDownFeatures.fair(row), ask, t?.noAsk)
        _latest.value = r
        val pts = _points.value
        if (pts.lastOrNull()?.sec != secIn) _points.value = pts + Point(secIn, up, r.mid, r.fair)
        why = "running on ${model.engine}"
        r
    }

    private fun none(reason: String): Reading? {
        why = reason
        return null
    }
}

/**
 * A paper bettor that follows the model alone, so its live record can be compared with the market and the Paper AI.
 * Once a minute (seconds 20, 80, … of the window, up to 880) it buys the side whose model chance beats its ask plus
 * the taker fee by more than [CUT], at an ask from [MIN_ASK] to [MAX_ASK] (85¢, your cap), [CONTRACTS] contracts
 * filled only from the offers the book shows, never at a price where the edge is gone. No limit on bets.
 * Paper only: it never reaches an order.
 */
class UpDownBettor(
    initial: List<PaperBet> = emptyList(),
    private val persist: (String) -> Unit = {},
    /** The offers on (ticker, "YES"/"NO"), (price, contracts), cheapest first; null = the book shows no sizes. */
    private val depth: (String, String) -> List<Pair<Double, Double>>? = { _, _ -> null },
    private val sink: (String) -> Unit = {}
) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var bets: List<PaperBet> = initial
    private var nextId: Long = (bets.maxOfOrNull { it.id } ?: 0L) + 1
    private val looked = HashSet<String>()
    @Volatile var enabled: Boolean = true
    private val _version = MutableStateFlow(0)
    /** Bumps on every bet or settlement, for the screens. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun bets(): List<PaperBet> = synchronized(lock) { bets.toList() }

    /** Windows with an open bet, so their settlement is fetched. */
    fun openTickers(): Set<String> = synchronized(lock) { bets.filter { it.result == null }.map { it.ticker }.toSet() }

    /** Looks at [r] when it is a new minute of its window; returns the bet it placed, or null. */
    fun onReading(r: UpDownLive.Reading, nowMs: Long): PaperBet? {
        if (!enabled) return null
        if (r.secIn < ENTRY_FROM_S || r.secIn > ENTRY_UNTIL_S) return null
        val key = r.ticker + "#" + (r.secIn - ENTRY_FROM_S) / 60
        synchronized(lock) {
            if (!looked.add(key)) return null
            if (looked.size > 200) { looked.clear(); looked.add(key) }
        }
        val pick = choose(r.up, r.yesAsk, r.noAsk) ?: return null
        val (side, ask, chance) = pick
        val limit = minOf(ask + BookFill.MAX_SLIP, MAX_ASK, maxPayable(chance))
        val fill = BookFill.buy(
            runCatching { depth(r.ticker, side) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(ask to UNKNOWN_DEPTH),
            CONTRACTS, KalshiFee.TAKER_COEFFICIENT, limit = limit
        ) ?: return null
        val bet = synchronized(lock) {
            PaperBet(
                id = nextId++, ticker = r.ticker, side = side, price = Math.round(fill.avgPrice * 10_000.0) / 10_000.0,
                contracts = fill.contracts, feeUsd = fill.feeUsd, openedMs = nowMs, confidence = Math.round(chance * 100).toInt(),
                reason = "model ${"%.1f".format(chance * 100)}% vs ask ${"%.0f".format(ask * 100)}c at ${900 - r.secIn}s left",
                fairCents = Math.round((if (side == "YES") r.fair else 1 - r.fair) * 1000.0) / 10.0,
                modelCents = Math.round(chance * 1000.0) / 10.0
            ).also { bets = (bets + it).takeLast(KEEP_BETS); save() }
        }
        runCatching { sink(json.encodeToString(PaperBet.serializer(), bet)) }
        _version.value++
        return bet
    }

    /** A window settled: close its open bets. [result] is "yes", "no" or "void". */
    fun onSettled(ticker: String, result: String) {
        val yes = result.equals("yes", true)
        val void = result.equals("void", true)
        if (!yes && !void && !result.equals("no", true)) return
        val changed = synchronized(lock) {
            var any = false
            bets = bets.map { b ->
                if (b.result != null || !b.ticker.equals(ticker, true)) return@map b
                any = true
                val won = (b.side == "YES") == yes
                val pnl = if (void) 0.0 else (if (won) b.contracts * (1.0 - b.price) else -b.contracts * b.price) - b.feeUsd
                b.copy(result = if (void) "VOID" else if (yes) "YES" else "NO", pnlUsd = Math.round(pnl * 10_000.0) / 10_000.0)
            }
            if (any) save()
            any
        }
        if (changed) _version.value++
    }

    private fun save() {
        runCatching { persist(json.encodeToString(ListSerializer(PaperBet.serializer()), bets)) }
    }

    companion object {
        const val ENTRY_FROM_S = 20
        const val ENTRY_UNTIL_S = 880
        const val MIN_ASK = 0.10
        const val MAX_ASK = 0.85
        /** Edge over the ask and the fee, in dollars a contract, before it buys. */
        const val CUT = 0.01
        const val CONTRACTS = 100
        const val UNKNOWN_DEPTH = 3_500.0
        const val KEEP_BETS = 1_000

        fun fee(price: Double) = KalshiFee.TAKER_COEFFICIENT * price * (1.0 - price)

        /** The side to buy (side, its ask, the model's chance for it), or null when neither beats ask + fee + cut. */
        fun choose(up: Double, yesAsk: Double?, noAsk: Double?): Triple<String, Double, Double>? {
            var best: Triple<String, Double, Double>? = null
            var bestEdge = CUT
            for ((side, ask, chance) in listOf(Triple("YES", yesAsk, up), Triple("NO", noAsk, 1.0 - up))) {
                if (ask == null || ask < MIN_ASK || ask > MAX_ASK) continue
                val edge = chance - ask - fee(ask)
                if (edge > bestEdge) { bestEdge = edge; best = Triple(side, ask, chance) }
            }
            return best
        }

        /** The highest price at which a contract still beats the fee and the cut for [chance] (the fee taken at that price). */
        fun maxPayable(chance: Double): Double {
            var p = chance - CUT
            repeat(4) { p = chance - CUT - fee(p) }
            return p
        }

        fun parse(text: String?): List<PaperBet> = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(PaperBet.serializer()), text ?: return emptyList())
        }.getOrDefault(emptyList())
    }
}
