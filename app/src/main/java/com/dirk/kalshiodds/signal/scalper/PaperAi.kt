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

/**
 * A window the AI looked at and chose not to bet on, with the asks it turned down. Once the window settles,
 * [result] and [wouldHavePaidUsd] say what one contract on the side the maths liked would have made.
 *
 * Without this the AI could only ever learn from windows it BET on, so "stay out" was unfalsifiable: skipping
 * produced no evidence, and the lessons telling it to skip could never be contradicted.
 */
@Serializable
data class PaperSkip(
    val ticker: String,
    val atMs: Long,
    val reason: String = "",
    val yesAskCents: Double? = null,
    val noAskCents: Double? = null,
    val fairYesCents: Double? = null,
    /** "YES", "NO" or "VOID" once the window settles. */
    val result: String? = null,
    /** One contract on the side the fair price favoured, after the taker fee. Null until it settles. */
    val wouldHavePaidUsd: Double? = null,
    /** The side that was priced under its fair value when it skipped, if either was. */
    val sideItPassedUp: String? = null
)

/**
 * One lesson, with the window it came from and what that window made. The outcome is kept so the betting prompt can
 * be shown lessons from windows that WON as well as ones that lost: taking the newest lessons outright let a losing
 * run fill every slot with "stay out", and since lessons are only written about windows it bet on, nothing could
 * then contradict them — the AI talked itself out of betting and had no way back.
 */
@Serializable
data class PaperLesson(
    val text: String,
    val ticker: String = "",
    /** What the window made, or null for a lesson written before 1.24.0, when the outcome was not kept. */
    val netUsd: Double? = null,
    val atMs: Long = 0L
)

@Serializable
data class PaperAiMemory(
    val bets: List<PaperBet> = emptyList(),
    /** Lessons written before 1.24.0, text only. Read once and carried into [written]. */
    val lessons: List<String> = emptyList(),
    /** Settled windows whose bets the AI has not yet looked back on. */
    val toReflect: List<String> = emptyList(),
    val written: List<PaperLesson> = emptyList(),
    /** Skips in a row since its last placed bet: how far it has talked itself out of betting. */
    val skipsSinceBet: Int = 0,
    /** Windows it passed on, with what they would have paid once settled. */
    val skips: List<PaperSkip> = emptyList()
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
 * [ENTRY_FROM_S]..[ENTRY_UNTIL_S] seconds, the side's ask between [MIN_ASK_CENTS] and [MAX_ASK_CENTS], any number of
 * contracts (no upper limit), taker fee charged. It never reaches a real order: it only writes paper bets here.
 */
class PaperAi(
    private val chat: LlmChat,
    private val feed: () -> JsonObject,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sink: (String, String) -> Unit = { _, _ -> },
    private val persistMemory: (String) -> Unit = {},
    initialMemory: PaperAiMemory = PaperAiMemory(),
    /** Told of each bet, skip, rejection and settlement, for the AI tab. */
    private val onEvent: (String, JsonObject) -> Unit = { _, _ -> },
    /** The offers on (ticker, "YES"/"NO"), (price, contracts), cheapest first; null = the book shows no sizes. */
    private val depth: (String, String) -> List<Pair<Double, Double>>? = { _, _ -> null }
) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var bets: List<PaperBet> = initialMemory.bets
    private var lessons: List<PaperLesson> =
        initialMemory.written.ifEmpty { initialMemory.lessons.map { PaperLesson(it) } }
    private var toReflect: List<String> = initialMemory.toReflect
    private var skipsSinceBet: Int = initialMemory.skipsSinceBet
    private var skips: List<PaperSkip> = initialMemory.skips
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
            settleSkips(ticker, if (void) "VOID" else if (yes) "YES" else "NO")
            if (out.isNotEmpty()) {
                if (out.any { it.result != "VOID" } && toReflect.none { it.equals(ticker, true) }) {
                    toReflect = (toReflect + ticker).takeLast(MAX_REFLECT_QUEUE)
                }
            }
            save()
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
            val yesAsk = (f["yesAskCents"] as? JsonPrimitive)?.doubleOrNull
            val noAsk = (f["noAskCents"] as? JsonPrimitive)?.doubleOrNull
            val fair = (f["fairYesCents"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..100.0 }
            val runOfSkips = synchronized(lock) {
                skipsSinceBet += 1
                rememberSkip(ticker, reason, yesAsk, noAsk, fair)
                save()
                skipsSinceBet
            }
            journal("skip", buildJsonObject { put("ticker", ticker); put("reason", reason); put("inARow", runOfSkips) })
            return note("skipped $ticker ($runOfSkips in a row): $reason")
        }
        val askCents = (f[if (decision == "YES") "yesAskCents" else "noAskCents"] as? JsonPrimitive)?.doubleOrNull
        if (askCents == null || askCents < MIN_ASK_CENTS || askCents > MAX_ASK_CENTS) {
            journal("rejected", buildJsonObject { put("ticker", ticker); put("why", "ask $askCents outside $MIN_ASK_CENTS..$MAX_ASK_CENTS") })
            return note("rejected $decision on $ticker: ask ${askCents ?: "unknown"}c is outside $MIN_ASK_CENTS-$MAX_ASK_CENTS")
        }
        val asked = ((obj["contracts"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).roundToInt().coerceAtLeast(1)
        // The bet fills only against the offers the book shows (at most 5¢ above the ask, never over the 85¢ cap).
        val top = askCents / 100.0
        val fill = BookFill.buy(
            runCatching { depth(ticker, decision) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(top to UNKNOWN_DEPTH),
            asked, KalshiFee.TAKER_COEFFICIENT, limit = minOf(top + BookFill.MAX_SLIP, MAX_ASK_CENTS / 100.0)
        )
        if (fill == null) {
            journal("rejected", buildJsonObject { put("ticker", ticker); put("why", "nothing offered at or under ${MAX_ASK_CENTS}c") })
            return note("rejected $decision on $ticker: nothing offered at or under ${MAX_ASK_CENTS.toInt()}c")
        }
        val contracts = fill.contracts
        val price = Math.round(fill.avgPrice * 10_000.0) / 10_000.0
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
                feeUsd = fill.feeUsd, openedMs = nowMs(), confidence = conf, reason = reason,
                fairCents = fairSide?.let { Math.round(it * 10.0) / 10.0 }, chanceCents = Math.round(chance * 10.0) / 10.0,
                bullCase = bull, bearCase = bear
            ).also { bets = (bets + it).takeLast(KEEP_BETS); skipsSinceBet = 0; save() }
        }
        journal("bet", json.encodeToJsonElement(PaperBet.serializer(), bet) as JsonObject)
        _record.value = synchronized(lock) { recordLine() }
        val partial = if (contracts < asked) " (asked $asked; the book showed $contracts)" else ""
        return note("bet $decision x$contracts$partial on $ticker at ${Math.round(price * 1000.0) / 10.0}c ($conf%): $reason")
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
            // The real record, so a lesson that claims "historically this loses" has the numbers in front of it.
            // Without these the model invented the history and the invention was stored as memory.
            put("yourRecordOverall", overallRecord(ticker))
            put("yourRecordInSimilarSpots", similarRecord(window, ticker))
            put("whatSkippingHasCost", skipRecord())
            put("yourNotes", buildJsonArray { for (l in synchronized(lock) { lessonsForPrompt(10) }) add(JsonPrimitive(l.text)) })
        }
        // No answer (provider down, rate limit): keep the window queued and look back on it next tick.
        val answer = chat.ask(REFLECT_SYSTEM, ctx.toString(), REFLECT_TOOLS) ?: return
        val lesson = answer.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.let { (it["lesson"] as? JsonPrimitive)?.contentOrNull?.trim() }?.takeIf { it.isNotBlank() }?.take(300)
        synchronized(lock) {
            toReflect = toReflect.drop(1)
            if (lesson != null) {
                val net = window.sumOf { it.pnlUsd ?: 0.0 }
                lessons = (lessons + PaperLesson(lesson, ticker, Math.round(net * 100.0) / 100.0, nowMs())).takeLast(MAX_LESSONS)
            }
            save()
        }
        if (lesson != null) journal("lesson", buildJsonObject { put("ticker", ticker); put("lesson", lesson) })
    }

    /**
     * Keeps the first skip of each window, with the asks it turned down. One per window: a skip every minute of a
     * quiet window would otherwise drown out the bets. Caller holds [lock].
     */
    private fun rememberSkip(ticker: String, reason: String, yesAsk: Double?, noAsk: Double?, fair: Double?) {
        if (skips.any { it.ticker.equals(ticker, true) }) return
        // The side the maths liked: its ask under its fair price. That is the bet it passed up.
        val passed = when {
            fair == null -> null
            yesAsk != null && yesAsk < fair -> "YES"
            noAsk != null && noAsk < 100.0 - fair -> "NO"
            else -> null
        }
        skips = (skips + PaperSkip(ticker, nowMs(), reason.take(200), yesAsk, noAsk, fair, sideItPassedUp = passed))
            .takeLast(KEEP_SKIPS)
    }

    /** Fills in what [ticker]'s skip would have paid, one contract on the side it passed up. Caller holds [lock]. */
    private fun settleSkips(ticker: String, result: String) {
        skips = skips.map { sk ->
            if (sk.result != null || !sk.ticker.equals(ticker, true)) return@map sk
            val side = sk.sideItPassedUp
            val ask = if (side == "YES") sk.yesAskCents else sk.noAskCents
            val paid = if (result == "VOID" || side == null || ask == null) null else {
                val price = ask / 100.0
                val gross = if (side == result) 1.0 - price else -price
                Math.round((gross - KalshiFee.total(1, price)) * 10_000.0) / 10_000.0
            }
            sk.copy(result = result, wouldHavePaidUsd = paid)
        }
    }

    /**
     * What skipping has cost, over the settled skips: how many it passed up, how many of those would have won and
     * the net of one contract on each. This is the counterweight to a wall of "stay out" lessons.
     */
    private fun skipRecord(): JsonObject {
        val done = synchronized(lock) { skips.filter { it.wouldHavePaidUsd != null } }
        return buildJsonObject {
            put("settledSkipsWithABetYouPassedUp", done.size)
            put("thoseThatWouldHaveWon", done.count { (it.wouldHavePaidUsd ?: 0.0) > 0.0 })
            put("netUsdOneContractEach", Math.round(done.sumOf { it.wouldHavePaidUsd ?: 0.0 } * 100.0) / 100.0)
        }
    }

    /** Confidence band of [c], the buckets the record is grouped into. */
    private fun band(c: Int): String = when {
        c < 60 -> "<60"
        c < 70 -> "60-69"
        c < 80 -> "70-79"
        else -> "80+"
    }

    /** Every settled bet except [exclude]'s window: the base rate a one-window lesson has to be judged against. */
    private fun overallRecord(exclude: String): JsonObject {
        val done = synchronized(lock) {
            bets.filter { it.result != null && it.result != "VOID" && !it.ticker.equals(exclude, true) }
        }
        return recordOf(done)
    }

    /**
     * What the same kind of bet has really done: one row per (side, confidence band) in the window being looked
     * back on, over every other settled bet. A row with few bets is the model's signal that one window proves
     * nothing.
     */
    private fun similarRecord(window: List<PaperBet>, exclude: String): JsonArray {
        val done = synchronized(lock) {
            bets.filter { it.result != null && it.result != "VOID" && !it.ticker.equals(exclude, true) }
        }
        val shapes = window.map { it.side to band(it.confidence) }.distinct()
        return buildJsonArray {
            for ((side, b) in shapes) {
                val like = done.filter { it.side == side && band(it.confidence) == b }
                add(buildJsonObject {
                    put("side", side)
                    put("confidence", b)
                    for ((k, v) in recordOf(like)) put(k, v)
                })
            }
        }
    }

    private fun recordOf(done: List<PaperBet>): JsonObject = buildJsonObject {
        put("settledBets", done.size)
        put("won", done.count { it.side == it.result })
        put("netUsd", Math.round(done.sumOf { it.pnlUsd ?: 0.0 } * 100.0) / 100.0)
    }

    /**
     * Up to [n] lessons for the betting prompt: half from windows that made money, half from the ones that lost,
     * oldest first. See [PaperLesson] for why the newest [n] outright was a trap.
     */
    private fun lessonsForPrompt(n: Int): List<PaperLesson> {
        val won = lessons.filter { (it.netUsd ?: 0.0) > 0.0 }
        val rest = lessons.filter { (it.netUsd ?: 0.0) <= 0.0 }
        val fromWon = won.takeLast(n / 2)
        val fromRest = rest.takeLast(n - fromWon.size)
        return (fromWon + fromRest).sortedBy { it.atMs }.takeLast(n)
    }

    private fun calibrationJson(): JsonObject {
        val c = synchronized(lock) { PaperAiCalibration.read(bets.filter { it.result != null }) }
        return buildJsonObject {
            put("settledBets", c.bets); put("avgClaimedEdgeCents", c.claimedEdgeCents)
            put("avgRealisedEdgeCents", c.realisedEdgeCents); put("trustInYourConfidence", c.trust)
        }
    }

    fun lessons(): List<String> = synchronized(lock) { lessons.map { it.text } }

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
        runCatching {
            persistMemory(
                json.encodeToString(
                    PaperAiMemory.serializer(),
                    PaperAiMemory(
                        bets = bets, toReflect = toReflect, written = lessons,
                        skipsSinceBet = skipsSinceBet, skips = skips
                    )
                )
            )
        }
    }

    private fun context(f: JsonObject): JsonObject = buildJsonObject {
        put("feed", f)
        put("limits", buildJsonObject {
            put("askCentsRange", "$MIN_ASK_CENTS..$MAX_ASK_CENTS")
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
        put("skipsSinceYourLastBet", skipsSinceBet)
        put("whatSkippingHasCost", skipRecord())
        put("lessonsFromResults", buildJsonArray { for (l in lessonsForPrompt(10)) add(JsonPrimitive(l.text)) })
    }

    companion object {
        const val ENTRY_FROM_S = 20
        const val ENTRY_UNTIL_S = 880
        const val MIN_ASK_CENTS = 10.0
        const val MAX_ASK_CENTS = 85.0
        /** Contracts assumed at the ask when the book shows no sizes (the arena's unknown queue). */
        const val UNKNOWN_DEPTH = 3_500.0
        /** No upper limit on contracts a bet (you asked 2026-10-23). */
        const val MAX_CONTRACTS = Int.MAX_VALUE
        const val KEEP_BETS = 300
        const val MAX_LESSONS = 30
        const val MAX_REFLECT_QUEUE = 5
        const val KEEP_SKIPS = 60

        /** Settled bets a row needs before a lesson may turn it into a rule. Below this, one window is one sample. */
        const val MIN_FOR_RULE = 8

        val SYSTEM = """You are the PAPER BETTING AI for a Bitcoin-only Kalshi 15-minute UP/DOWN market. You are not the scalping AI: another AI tunes the scalping algorithms, you decide, every minute, whether to place another paper bet (there is no limit on how many bets you place in a window; you may bet again, the same side or the other, as the window moves).
Each call you get the live feed, the limits, your record, your calibration, your last bets with results and lessons you wrote after earlier windows settled. YES = Bitcoin finishes UP (at or above strikePrice), NO = DOWN.
The feed's fairYesCents is the math's chance of YES (0-100) from the settlement index, the strike, its volatility and the time left. spotMinusStrike says how far Bitcoin is from the strike now.
Before you choose, write bull_case (the best reason UP wins) and bear_case (the best reason DOWN wins), then pick the side whose case is stronger at its price, or SKIP.
Goal: positive net profit after the taker fee (0.07 x price x (1 - price) per contract). Every bet you place inside the limits is placed: bet often to learn, but only with an edge (the side's ask below your real chance of winning). Give your real chance as confidence; trustInYourConfidence shows how much of the edge you claimed has shown up. SKIP is a good answer when there is no edge.
The lessons you are shown come from single settled windows unless they name a count, so treat them as evidence, not as rules: your record and your calibration outrank them. skipsSinceYourLastBet is how many times in a row you have skipped - a long run means you have stopped gathering the results you learn from, so take any real edge you find. whatSkippingHasCost scores the windows you passed on, one contract on the side the maths liked: a positive netUsd there means your skipping is costing you money, and you should be betting more of those spots, not fewer.
Paper only. Answer only by calling place_bet."""

        val REFLECT_SYSTEM = """You are the PAPER BETTING AI looking back on one settled Bitcoin 15-minute window you bet on. You get how it finished, your bets (side, price, confidence, the fair price at entry, your reason, your bull and bear cases), your calibration, your record overall and your record in spots like this one.
Say in one sentence what to do differently, or keep doing, next time: name the situation (time left, price, distance from the strike, fair price vs your confidence) so the lesson can be used.
Rules for the lesson:
- NEVER say "historical data shows", "historically" or anything like it unless the numbers are in yourRecordInSimilarSpots or yourRecordOverall, and then give the count: "0 of 4 NO bets at 60-69% have won". You do not have any history beyond what you are given here. Inventing one poisons every later decision.
- One window is one sample. If the matching row has fewer than $MIN_FOR_RULE settled bets, write what happened and that it may be luck - do NOT write a rule telling yourself to avoid the setup.
- A lesson that would stop you betting ("avoid", "stay out", "wait for clearer bias") needs the numbers to back it: a losing row of at least $MIN_FOR_RULE bets. Otherwise say what would have made the bet better (a better price, more time left, a bigger gap to the strike, a different side), not that you should not have bet.
- Prefer a lesson that tells you what to DO. You only learn by betting, and lessons are only written about windows you bet on, so a wall of "stay out" leaves you nothing to learn from.
- whatSkippingHasCost is the windows you passed on, scored the same way: if skipping is making money, say so; if it is losing money, a "stay out" lesson is the wrong lesson.
- One losing window after a good decision is variance, not a mistake. Say so when the price and your confidence were right.
Answer only by calling write_lesson."""

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
                            putJsonObject("contracts") { put("type", "number"); put("description", "Any number, 1 or more: there is no upper limit") }
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
