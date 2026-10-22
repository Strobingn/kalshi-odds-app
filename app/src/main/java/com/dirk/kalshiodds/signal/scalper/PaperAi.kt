package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** One paper bet the Paper AI placed. Settles when its window does. */
@Serializable
data class PaperBet(
    val id: Long,
    val ticker: String,
    /** "YES" (UP) or "NO" (DOWN). */
    val side: String,
    val price: Double,
    val contracts: Int,
    val feeUsd: Double,
    val openedMs: Long,
    val confidence: Int,
    val reason: String,
    val result: String? = null,
    val pnlUsd: Double? = null,
    /** The math's chance for this side when the bet was placed (fair price, 0–100), if the feed had one. */
    val fairCents: Double? = null,
    /** The AI's confidence cut to its proven skill, averaged with [fairCents]: for the review, never a block. */
    val chanceCents: Double? = null,
    /** The AI's best case for UP and for DOWN, written before it picked a side. */
    val bullCase: String? = null,
    val bearCase: String? = null
)

@Serializable
data class PaperAiMemory(
    val bets: List<PaperBet> = emptyList(),
    val lessons: List<String> = emptyList(),
    /** Settled windows whose bets the AI has not yet looked back on. */
    val toReflect: List<String> = emptyList()
)

/**
 * How much of the edge the Paper AI claims has actually shown up (TradingAgents' "settle, then reflect" idea, as numbers).
 *
 * Claimed edge on a bet = its confidence − its price. Realised edge = 100 if it won else 0, − its price. [Read.trust] is
 * realised ÷ claimed over the settled bets, cut to 0..1 and pulled toward [PRIOR_TRUST] by [PRIOR_BETS] pretend bets, so
 * a short lucky run cannot make the AI's word worth its full face value. Pure.
 */
object PaperAiCalibration {
    const val PRIOR_BETS = 20.0
    const val PRIOR_TRUST = 0.5

    data class Read(val bets: Int, val claimedEdgeCents: Double, val realisedEdgeCents: Double, val trust: Double)

    fun read(settled: List<PaperBet>): Read {
        val done = settled.filter { it.result == "YES" || it.result == "NO" }
        if (done.isEmpty()) return Read(0, 0.0, 0.0, PRIOR_TRUST)
        val claimed = done.sumOf { it.confidence - it.price * 100.0 } / done.size
        val realised = done.sumOf { (if (it.side == it.result) 100.0 else 0.0) - it.price * 100.0 } / done.size
        val observed = if (claimed > 0.5) (realised / claimed).coerceIn(0.0, 1.0) else if (realised > 0.0) 1.0 else 0.0
        val n = done.size.toDouble()
        val trust = (n * observed + PRIOR_BETS * PRIOR_TRUST) / (n + PRIOR_BETS)
        return Read(done.size, round1(claimed), round1(realised), Math.round(trust * 1000.0) / 1000.0)
    }

    /** The AI's [confidence] for a side priced [askCents], keeping only [trust] of the edge it claims over that price. */
    fun adjust(confidence: Double, askCents: Double, trust: Double): Double = askCents + (confidence - askCents) * trust

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}

/**
 * The Paper AI: a chat LLM that decides paper BETS, one call a minute. It is a different thing from
 * the scalping AI ([LlmSupervisor]), which only tunes the scalping algorithms. This one has its own prompt, its own
 * memory of its bets, its own journal and its own provider order.
 *
 * The model only answers `place_bet` (YES, NO or SKIP). Code enforces the rules: no cap on bets per window, any time in
 * [ENTRY_FROM_S]..[ENTRY_UNTIL_S] seconds, the side's ask between [MIN_ASK_CENTS] and [MAX_ASK_CENTS], at most
 * [MAX_CONTRACTS] contracts, taker fee charged. It never reaches a real order: it only writes paper bets here.
 */
class PaperAi(
    private val chat: LlmChat,
    private val feed: () -> JsonObject,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sink: (String, String) -> Unit = { _, _ -> },
    private val persistMemory: (String) -> Unit = {},
    initialMemory: PaperAiMemory = PaperAiMemory(),
    /** Told of each bet, skip, rejection and settlement, for the AI tab. */
    private val onEvent: (String, JsonObject) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var bets: List<PaperBet> = initialMemory.bets
    private var lessons: List<String> = initialMemory.lessons
    private var toReflect: List<String> = initialMemory.toReflect
    private var nextId: Long = (bets.maxOfOrNull { it.id } ?: 0L) + 1

    private val _status = MutableStateFlow("Paper AI · not started")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _record = MutableStateFlow(recordLine())
    /** "12 bets · 7 won · +$3.10", for the screens. */
    val record: StateFlow<String> = _record.asStateFlow()

    fun idle(reason: String) { _status.value = "Paper AI · $reason" }

    fun settledBets(): List<PaperBet> = synchronized(lock) { bets.filter { it.result != null } }
    fun openBets(): List<PaperBet> = synchronized(lock) { bets.filter { it.result == null } }

    private fun recordLine(): String {
        val done = bets.filter { it.result != null }
        val won = done.count { (it.pnlUsd ?: 0.0) > 0 }
        val net = done.sumOf { it.pnlUsd ?: 0.0 }
        return "${bets.size} bets · $won of ${done.size} won · " + (if (net < 0) "-" else "+") + "$" + "%.2f".format(abs(net))
    }

    private fun abs(v: Double) = kotlin.math.abs(v)

    /** A window settled: close this window's open bets. [result] is "yes" or "no". */
    fun onSettled(ticker: String, result: String) {
        val closed = synchronized(lock) body@{
            val yes = result.equals("yes", true)
            if (!yes && !result.equals("no", true) && !result.equals("void", true)) return@body emptyList<PaperBet>()
            val void = result.equals("void", true)
            val out = ArrayList<PaperBet>()
            bets = bets.map { b ->
                if (b.result != null || !b.ticker.equals(ticker, true)) return@map b
                val won = (b.side == "YES") == yes
                val pnl = if (void) 0.0 else (if (won) b.contracts * (1.0 - b.price) else -b.contracts * b.price) - b.feeUsd
                b.copy(result = if (void) "VOID" else if (yes) "YES" else "NO", pnlUsd = Math.round(pnl * 10_000.0) / 10_000.0).also { out += it }
            }
            if (out.isNotEmpty()) {
                if (out.any { it.result != "VOID" } && toReflect.none { it.equals(ticker, true) }) {
                    toReflect = (toReflect + ticker).takeLast(MAX_REFLECT_QUEUE)
                }
                save()
            }
            out
        }
        for (b in closed) {
            journal("settled", json.encodeToJsonElement(PaperBet.serializer(), b) as JsonObject)
        }
        if (closed.isNotEmpty()) _record.value = synchronized(lock) { recordLine() }
    }

    /** One pass. Calls the model once per tick (a minute); any number of bets per window. Blocks on the network: run it off the main thread. */
    fun tick(): String {
        reflect()
        val f = feed()
        val ticker = (f["ticker"] as? JsonPrimitive)?.contentOrNull ?: return note("no live 15-minute market yet")
        val secIn = (f["secondsIntoWindow"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return note("no window clock yet")
        if (secIn < ENTRY_FROM_S) return note("watching the first ${ENTRY_FROM_S}s of $ticker")
        if (secIn > ENTRY_UNTIL_S) return note("too late to enter $ticker; waiting for the next window")
        val ctx = synchronized(lock) { context(f) }
        val answer = chat.ask(SYSTEM, ctx.toString(), TOOLS)
            ?: return note("no answer: " + chat.lastFailure().ifBlank { "no provider answered" })
        val obj = runCatching { Json.parseToJsonElement(answer).jsonObject }.getOrNull() ?: return note("answer was not JSON")
        val decision = (obj["decision"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase().orEmpty()
        val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(300)
        val conf = ((obj["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 50.0).roundToInt().coerceIn(0, 100)
        if (decision != "YES" && decision != "NO") {
            journal("skip", buildJsonObject { put("ticker", ticker); put("reason", reason) })
            return note("skipped $ticker: $reason")
        }
        val askCents = (f[if (decision == "YES") "yesAskCents" else "noAskCents"] as? JsonPrimitive)?.doubleOrNull
        if (askCents == null || askCents < MIN_ASK_CENTS || askCents > MAX_ASK_CENTS) {
            journal("rejected", buildJsonObject { put("ticker", ticker); put("why", "ask $askCents outside $MIN_ASK_CENTS..$MAX_ASK_CENTS") })
            return note("rejected $decision on $ticker: ask ${askCents ?: "unknown"}c is outside $MIN_ASK_CENTS-$MAX_ASK_CENTS")
        }
        val contracts = ((obj["contracts"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).roundToInt().coerceIn(1, MAX_CONTRACTS)
        val price = askCents / 100.0
        val bull = (obj["bull_case"] as? JsonPrimitive)?.contentOrNull?.take(300)?.takeIf { it.isNotBlank() }
        val bear = (obj["bear_case"] as? JsonPrimitive)?.contentOrNull?.take(300)?.takeIf { it.isNotBlank() }

        // Recorded for the review afterwards, never used to block: a bet inside the limits is always placed.
        val fairYes = (f["fairYesCents"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..100.0 }
        val fairSide = fairYes?.let { if (decision == "YES") it else 100.0 - it }
        val trust = synchronized(lock) { PaperAiCalibration.read(bets.filter { it.result != null }).trust }
        val adjusted = PaperAiCalibration.adjust(conf.toDouble(), askCents, trust)
        val chance = if (fairSide != null) (adjusted + fairSide) / 2.0 else adjusted

        val bet = synchronized(lock) {
            PaperBet(
                id = nextId++, ticker = ticker, side = decision, price = price, contracts = contracts,
                feeUsd = KalshiFee.total(contracts, price), openedMs = nowMs(), confidence = conf, reason = reason,
                fairCents = fairSide?.let { Math.round(it * 10.0) / 10.0 }, chanceCents = Math.round(chance * 10.0) / 10.0,
                bullCase = bull, bearCase = bear
            ).also { bets = (bets + it).takeLast(KEEP_BETS); save() }
        }
        journal("bet", json.encodeToJsonElement(PaperBet.serializer(), bet) as JsonObject)
        _record.value = synchronized(lock) { recordLine() }
        return note("bet $decision x$contracts on $ticker at ${askCents}c ($conf%): $reason")
    }

    /**
     * Looks back on the oldest settled window it bet on (at most one per tick): what it argued, what happened, one
     * lesson. Lessons come only from here, after the result is known, never from the bet itself.
     */
    private fun reflect() {
        val (ticker, window) = synchronized(lock) {
            val t = toReflect.firstOrNull() ?: return
            t to bets.filter { it.ticker.equals(t, true) && it.result != null && it.result != "VOID" }
        }
        if (window.isEmpty()) {
            synchronized(lock) { toReflect = toReflect.drop(1); save() }
            return
        }
        val ctx = buildJsonObject {
            put("window", ticker)
            put("finished", if (window.first().result == "YES") "UP (YES won)" else "DOWN (NO won)")
            put("netUsd", Math.round(window.sumOf { it.pnlUsd ?: 0.0 } * 100.0) / 100.0)
            putJsonArray("yourBets") {
                for (b in window) add(buildJsonObject {
                    put("side", b.side); put("priceCents", Math.round(b.price * 100.0)); put("confidence", b.confidence)
                    b.fairCents?.let { put("fairCents", it) }
                    put("pnlUsd", b.pnlUsd ?: 0.0); put("reason", b.reason)
                    b.bullCase?.let { put("bullCase", it) }; b.bearCase?.let { put("bearCase", it) }
                })
            }
            put("calibration", calibrationJson())
            put("yourNotes", buildJsonArray { for (l in synchronized(lock) { lessons.takeLast(10) }) add(JsonPrimitive(l)) })
        }
        // No answer (provider down, rate limit): keep the window queued and look back on it next tick.
        val answer = chat.ask(REFLECT_SYSTEM, ctx.toString(), REFLECT_TOOLS) ?: return
        val lesson = answer.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { (it["lesson"] as? JsonPrimitive)?.contentOrNull?.trim() }?.takeIf { it.isNotBlank() }?.take(300)
        synchronized(lock) {
            toReflect = toReflect.drop(1)
            if (lesson != null) lessons = (lessons + lesson).takeLast(MAX_LESSONS)
            save()
        }
        if (lesson != null) journal("lesson", buildJsonObject { put("ticker", ticker); put("lesson", lesson) })
    }

    private fun calibrationJson(): JsonObject {
        val c = synchronized(lock) { PaperAiCalibration.read(bets.filter { it.result != null }) }
        return buildJsonObject {
            put("settledBets", c.bets); put("avgClaimedEdgeCents", c.claimedEdgeCents)
            put("avgRealisedEdgeCents", c.realisedEdgeCents); put("trustInYourConfidence", c.trust)
        }
    }

    fun lessons(): List<String> = synchronized(lock) { lessons.toList() }

    private fun note(s: String): String {
        _status.value = "Paper AI · $s"
        _log.value = (listOf(s) + _log.value).take(20)
        return s
    }

    private fun journal(kind: String, body: JsonObject) {
        val line = buildJsonObject { put("ts", nowMs()); put("event", kind); for ((k, v) in body) put(k, v) }
        runCatching { sink("paper_ai", line.toString()) }
        runCatching { onEvent(kind, body) }
    }

    private fun save() {
        runCatching { persistMemory(json.encodeToString(PaperAiMemory.serializer(), PaperAiMemory(bets, lessons, toReflect))) }
    }

    private fun context(f: JsonObject): JsonObject = buildJsonObject {
        put("feed", f)
        put("limits", buildJsonObject {
            put("askCentsRange", "$MIN_ASK_CENTS..$MAX_ASK_CENTS"); put("maxContracts", MAX_CONTRACTS)
            put("betsPerWindow", "unlimited, one per minute"); put("entrySeconds", "$ENTRY_FROM_S..$ENTRY_UNTIL_S")
        })
        put("yourRecord", recordLine())
        put("yourCalibration", calibrationJson())
        putJsonArray("yourLastBets") {
            for (b in bets.takeLast(15)) add(buildJsonObject {
                put("ticker", b.ticker); put("side", b.side); put("priceCents", Math.round(b.price * 100.0))
                put("confidence", b.confidence); put("result", b.result ?: "open")
                b.fairCents?.let { put("fairCents", it) }
                b.pnlUsd?.let { put("pnlUsd", it) }; put("reason", b.reason)
            })
        }
        put("lessonsFromResults", buildJsonArray { for (l in lessons.takeLast(10)) add(JsonPrimitive(l)) })
    }

    companion object {
        const val ENTRY_FROM_S = 20
        const val ENTRY_UNTIL_S = 880
        const val MIN_ASK_CENTS = 10.0
        const val MAX_ASK_CENTS = 85.0
        const val MAX_CONTRACTS = 50
        const val KEEP_BETS = 300
        const val MAX_LESSONS = 30
        const val MAX_REFLECT_QUEUE = 5

        val SYSTEM = """You are the PAPER BETTING AI for a Bitcoin-only Kalshi 15-minute UP/DOWN market. You are not the scalping AI: another AI tunes the scalping algorithms, you decide, every minute, whether to place another paper bet (there is no limit on how many bets you place in a window; you may bet again, the same side or the other, as the window moves).
Each call you get the live feed, the limits, your record, your calibration, your last bets with results and lessons you wrote after earlier windows settled. YES = Bitcoin finishes UP (at or above strikePrice), NO = DOWN.
The feed's fairYesCents is the math's chance of YES (0-100) from the settlement index, the strike, its volatility and the time left. spotMinusStrike says how far Bitcoin is from the strike now.
Before you choose, write bull_case (the best reason UP wins) and bear_case (the best reason DOWN wins), then pick the side whose case is stronger at its price, or SKIP.
Goal: positive net profit after the taker fee (0.07 x price x (1 - price) per contract). Every bet you place inside the limits is placed: bet often to learn, but only with an edge (the side's ask below your real chance of winning). Give your real chance as confidence; trustInYourConfidence shows how much of the edge you claimed has shown up. SKIP is a good answer when there is no edge.
Paper only. Answer only by calling place_bet."""

        val REFLECT_SYSTEM = """You are the PAPER BETTING AI looking back on one settled Bitcoin 15-minute window you bet on. You get how it finished, your bets (side, price, confidence, the fair price at entry, your reason, your bull and bear cases) and your calibration.
Say in one sentence what to do differently, or keep doing, next time: name the situation (time left, price, distance from the strike, fair price vs your confidence) so the lesson can be used. If the result was luck either way, say so. Answer only by calling write_lesson."""

        val REFLECT_TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "write_lesson")
                    put("description", "Save one lesson from this settled window.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("lesson") { put("type", "string"); put("description", "One sentence.") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("lesson")) }
                    }
                }
            })
        }

        val TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "place_bet")
                    put("description", "Place one paper bet now, or SKIP.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("bull_case") { put("type", "string"); put("description", "The best reason UP (YES) wins this window.") }
                            putJsonObject("bear_case") { put("type", "string"); put("description", "The best reason DOWN (NO) wins this window.") }
                            putJsonObject("decision") { put("type", "string"); put("description", "YES, NO or SKIP") }
                            putJsonObject("contracts") { put("type", "number"); put("description", "1 to $MAX_CONTRACTS") }
                            putJsonObject("confidence") { put("type", "number"); put("description", "Your chance of winning, 0 to 100") }
                            putJsonObject("reason") { put("type", "string") }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("bull_case")); add(JsonPrimitive("bear_case"))
                            add(JsonPrimitive("decision")); add(JsonPrimitive("reason"))
                        }
                    }
                }
            })
        }
    }
}
