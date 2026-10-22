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
    val pnlUsd: Double? = null
)

@Serializable
data class PaperAiMemory(val bets: List<PaperBet> = emptyList(), val lessons: List<String> = emptyList())

/**
 * The Paper AI: a chat LLM that decides paper BETS, one call per 15-minute window. It is a different thing from
 * the scalping AI ([LlmSupervisor]), which only tunes the scalping algorithms. This one has its own prompt, its own
 * memory of its bets, its own journal and its own provider order.
 *
 * The model only answers `place_bet` (YES, NO or SKIP). Code enforces the rules: one bet per window, the first
 * [ENTRY_FROM_S]..[ENTRY_UNTIL_S] seconds, the side's ask between [MIN_ASK_CENTS] and [MAX_ASK_CENTS], at most
 * [MAX_CONTRACTS] contracts, taker fee charged. It never reaches a real order: it only writes paper bets here.
 */
class PaperAi(
    private val chat: LlmChat,
    private val feed: () -> JsonObject,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sink: (String, String) -> Unit = { _, _ -> },
    private val persistMemory: (String) -> Unit = {},
    initialMemory: PaperAiMemory = PaperAiMemory()
) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var bets: List<PaperBet> = initialMemory.bets
    private var lessons: List<String> = initialMemory.lessons
    private var nextId: Long = (bets.maxOfOrNull { it.id } ?: 0L) + 1
    /** The window already asked about, so the model is called once per window (a SKIP counts). */
    private var askedTicker: String? = null

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
            if (out.isNotEmpty()) save()
            out
        }
        for (b in closed) {
            journal("settled", json.encodeToJsonElement(PaperBet.serializer(), b) as JsonObject)
        }
        if (closed.isNotEmpty()) _record.value = synchronized(lock) { recordLine() }
    }

    /** One pass. Calls the model at most once per window. Blocks on the network: run it off the main thread. */
    fun tick(): String {
        val f = feed()
        val ticker = (f["ticker"] as? JsonPrimitive)?.contentOrNull ?: return note("no live 15-minute market yet")
        val secIn = (f["secondsIntoWindow"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: return note("no window clock yet")
        if (synchronized(lock) { askedTicker == ticker }) return note("already decided this window ($ticker)")
        if (secIn < ENTRY_FROM_S) return note("watching the first ${ENTRY_FROM_S}s of $ticker")
        if (secIn > ENTRY_UNTIL_S) return note("too late to enter $ticker; waiting for the next window")
        val ctx = synchronized(lock) { context(f) }
        synchronized(lock) { askedTicker = ticker }
        val answer = chat.ask(SYSTEM, ctx.toString(), TOOLS)
            ?: return note("no answer from the LLM (check the keys); will retry next window")
        val obj = runCatching { Json.parseToJsonElement(answer).jsonObject }.getOrNull() ?: return note("answer was not JSON")
        (obj["lesson"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { l ->
            synchronized(lock) { lessons = (lessons + l.take(300)).takeLast(MAX_LESSONS); save() }
        }
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
        val bet = synchronized(lock) {
            PaperBet(
                id = nextId++, ticker = ticker, side = decision, price = price, contracts = contracts,
                feeUsd = KalshiFee.total(contracts, price), openedMs = nowMs(), confidence = conf, reason = reason
            ).also { bets = (bets + it).takeLast(KEEP_BETS); save() }
        }
        journal("bet", json.encodeToJsonElement(PaperBet.serializer(), bet) as JsonObject)
        _record.value = synchronized(lock) { recordLine() }
        return note("bet $decision x$contracts on $ticker at ${askCents}c ($conf%): $reason")
    }

    private fun note(s: String): String {
        _status.value = "Paper AI · $s"
        _log.value = (listOf(s) + _log.value).take(20)
        return s
    }

    private fun journal(kind: String, body: JsonObject) {
        val line = buildJsonObject { put("ts", nowMs()); put("event", kind); for ((k, v) in body) put(k, v) }
        runCatching { sink("paper_ai", line.toString()) }
    }

    private fun save() {
        runCatching { persistMemory(json.encodeToString(PaperAiMemory.serializer(), PaperAiMemory(bets, lessons))) }
    }

    private fun context(f: JsonObject): JsonObject = buildJsonObject {
        put("feed", f)
        put("limits", buildJsonObject {
            put("askCentsRange", "$MIN_ASK_CENTS..$MAX_ASK_CENTS"); put("maxContracts", MAX_CONTRACTS)
            put("oneBetPerWindow", true); put("entrySeconds", "$ENTRY_FROM_S..$ENTRY_UNTIL_S")
        })
        put("yourRecord", recordLine())
        putJsonArray("yourLastBets") {
            for (b in bets.takeLast(15)) add(buildJsonObject {
                put("ticker", b.ticker); put("side", b.side); put("priceCents", Math.round(b.price * 100.0))
                put("confidence", b.confidence); put("result", b.result ?: "open")
                b.pnlUsd?.let { put("pnlUsd", it) }; put("reason", b.reason)
            })
        }
        put("yourNotes", buildJsonArray { for (l in lessons.takeLast(10)) add(JsonPrimitive(l)) })
    }

    companion object {
        const val ENTRY_FROM_S = 20
        const val ENTRY_UNTIL_S = 420
        const val MIN_ASK_CENTS = 10.0
        const val MAX_ASK_CENTS = 90.0
        const val MAX_CONTRACTS = 50
        const val KEEP_BETS = 300
        const val MAX_LESSONS = 30

        val SYSTEM = """You are the PAPER BETTING AI for a Bitcoin-only Kalshi 15-minute UP/DOWN market. You are not the scalping AI: another AI tunes the scalping algorithms, you only decide whether to place one paper bet in this window.
Each call you get the live feed, the limits, your record, your last bets with results and your own notes. YES = Bitcoin finishes UP (at or above the strike), NO = DOWN.
Goal: positive net profit after the taker fee (0.07 x price x (1 - price) per contract). Only bet when you see an edge: the side's ask should be below your real chance of winning. SKIP is a good answer when there is no edge. Learn from your last bets and notes; do not repeat what lost.
Paper only. Answer only by calling place_bet."""

        val TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "place_bet")
                    put("description", "Place one paper bet in this window, or SKIP.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("decision") { put("type", "string"); put("description", "YES, NO or SKIP") }
                            putJsonObject("contracts") { put("type", "number"); put("description", "1 to $MAX_CONTRACTS") }
                            putJsonObject("confidence") { put("type", "number"); put("description", "Your chance of winning, 0 to 100") }
                            putJsonObject("reason") { put("type", "string") }
                            putJsonObject("lesson") { put("type", "string"); put("description", "One sentence to remember.") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("decision")); add(JsonPrimitive("reason")) }
                    }
                }
            })
        }
    }
}
