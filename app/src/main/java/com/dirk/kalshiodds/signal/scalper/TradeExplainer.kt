package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.TakerCost
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One feature's push on a model prediction, for the Explain screen's bar chart. */
data class FeaturePush(
    val name: String,
    /** "the side's mid price", from [TradeExplainer.FEATURE_WORDS]. */
    val label: String,
    val value: Float,
    /** Change in the model's log-odds (classifier) or cents (regression) from this feature. */
    val push: Double
)

/** Everything the Explain screen shows about one trade, before and besides the AI's words. */
data class TradeFacts(
    val trade: ScalpTrade,
    val order: OrderDetail?,
    val rules: List<String>,
    /** Seconds into the 15-minute window at the fill / the post, when the ticker says when it closes. */
    val enteredSecond: Int?,
    val holdSeconds: Long,
    val pnlCentsPerContract: Double,
    /** ML scalper with its inputs on file and the current model able to read them. */
    val modelChance: Double? = null,
    val modelCents: Double? = null,
    val modelTheta: Double? = null,
    val pushes: List<FeaturePush> = emptyList(),
    /** Why there is no model breakdown, when the trade is the ML scalper's but none could be made. */
    val modelNote: String? = null
)

/**
 * Builds "Explain this trade" (the arena's trade cards). Since 1.20.4 there is no AI-written story: every line
 * comes from the trade file, the order file (the book it saw), the strategy's own rule in code and its record, so
 * nothing is guessed. [prompt] is the same facts as plain text (the Copy button). Pure apart from what it is handed.
 */
object TradeExplainer {

    const val TOP_PUSHES = 6

    fun facts(trade: ScalpTrade, order: OrderDetail?, model: ScalperModel?, tuned: Tuned? = null): TradeFacts {
        val st = ScalpStrategy.values().firstOrNull { it.name == trade.strategy }
        val rules = st?.let { ArenaTable.rules(it, tuned ?: Tuned(it, ScalpKnobs(), ScalpTuning.DEFAULT_CONTRACTS)) }.orEmpty()
        val close = TakerCost.closeEpochMs(trade.ticker)
        val enteredAt = if (trade.filledAtMs > 0) trade.filledAtMs else trade.postedAtMs
        val entered = close?.let { ((enteredAt - (it - PrintGrid.SECONDS * 1000L)) / 1000L).toInt() }?.takeIf { it in 0..PrintGrid.SECONDS }
        val hold = ((trade.closedAtMs - enteredAt) / 1000L).coerceAtLeast(0L)
        val perContract = if (trade.contracts > 0) trade.pnlUsd / trade.contracts * 100.0 else 0.0
        var f = TradeFacts(trade, order, rules, entered, hold, perContract)
        if (st != ScalpStrategy.ML_REST) return f
        val x = order?.features
        f = when {
            model == null -> f.copy(modelNote = "the model is not loaded")
            x == null -> f.copy(modelNote = "this order's model inputs are not on file")
            x.size != model.features.size -> f.copy(
                modelNote = "made by an earlier model with ${x.size} inputs (the app now runs one with ${model.features.size})"
            )
            else -> {
                val contrib = model.contributions(x)
                val pushes = contrib?.let { c ->
                    model.features.indices.map { i -> FeaturePush(model.features[i], label(model.features[i]), x[i], c[i]) }
                        .sortedByDescending { abs(it.push) }
                }.orEmpty()
                f.copy(
                    modelChance = model.probability(x), modelCents = model.predict(x), modelTheta = model.thetaCents,
                    pushes = pushes,
                    modelNote = if (contrib == null) "the model file has no node values, so no breakdown" else null
                )
            }
        }
        return f
    }

    /** Strategies whose rule reads the side's 10 s / 30 s / 60 s price moves (for every other one they are only logged). */
    private val USES_MOVES: Set<ScalpStrategy> by lazy {
        setOf(
            ScalpStrategy.DIP_HUNTER, ScalpStrategy.DIP_HUNTER_REST, ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.MOMENTUM_SNIPER_REST,
            ScalpStrategy.EXTREME_REVERSION, ScalpStrategy.EXTREME_REVERSION_REST, ScalpStrategy.MOMENTUM_HOLD,
            ScalpStrategy.TREND_PULLBACK, ScalpStrategy.STEADY_CLIMB, ScalpStrategy.PANIC_FADE, ScalpStrategy.LATE_COMEBACK,
            ScalpStrategy.LATE_TIEBREAK, ScalpStrategy.OPENING_DRIVE
        )
    }

    /**
     * The explanation, one fact a line: what the strategy's rule looks for (from the code), the entry with the book it
     * saw, the exit, the result, and the strategy's whole record ([record], its ledger stats) — never a guess about
     * why the market moved or whether the trade was skill.
     */
    fun story(f: TradeFacts, record: ScalpStats? = null): List<String> {
        val t = f.trade
        val st = ScalpStrategy.values().firstOrNull { it.name == t.strategy }
        val out = ArrayList<String>()
        st?.let { out += "What its rule looks for: ${ArenaTable.signal(it)}" }
        val took = t.entryFeeUsd > 0.0
        val at = f.enteredSecond?.let { " at ${it / 60}:${"%02d".format(Locale.US, it % 60)} into the window" }.orEmpty()
        out += "Entry: bought ${ScalperResults.sideLabel(t.side)} x${t.contracts} at ${cents(t.entry)}$at " +
            (if (took) "by paying the ask (taker fee ${money(t.entryFeeUsd).removePrefix("+")})." else "with a resting bid that sellers filled (no fee).")
        f.order?.let { o ->
            val book = listOfNotNull(
                o.bid?.let { b -> "bid ${cents(b)}" + (o.bidQty?.let { " (${it.roundToInt()} contracts)" } ?: "") },
                o.ask?.let { a -> "ask ${cents(a)}" + (o.askQty?.let { " (${it.roundToInt()} contracts)" } ?: "") }
            )
            if (book.isNotEmpty()) out += "Book when it posted: ${book.joinToString(", ")}."
            val q = o.askQty
            if (took && q != null && q > 0 && t.contracts > q) {
                out += "The order was ${t.contracts} contracts, ${fmt1(t.contracts / q)}× what the ask showed. Paper orders before " +
                    "1.20.4 filled in full at the ask whatever the book showed, so a result this size is overstated; " +
                    "from 1.20.4 an order fills only against the offers shown."
            }
            val moves = listOfNotNull(o.move10?.let { "${signedCents(it)} in 10 s" }, o.move30?.let { "${signedCents(it)} in 30 s" })
            if (moves.isNotEmpty()) {
                out += "The side's price had moved ${moves.joinToString(" and ")}" +
                    if (st != null && st !in USES_MOVES) " (logged with every order; not part of this strategy's rule)." else "."
            }
        }
        if (st == ScalpStrategy.ML_REST && f.modelCents != null) {
            out += "Model: expected ${fmt2(f.modelCents)}c a contract" + (f.modelChance?.let { ", ${pct(it)} chance to make money" } ?: "") +
                "; it posts at ${fmt2(f.modelTheta ?: 0.0)}c or more."
            f.pushes.take(3).takeIf { it.isNotEmpty() }?.let { top ->
                out += "Inputs that moved the model most: " + top.joinToString("; ") {
                    "${it.label} ${fmtValue(it.name, it.value)} (${if (it.push >= 0) "+" else ""}${fmt3(it.push)})"
                } + "."
            }
        } else if (st == ScalpStrategy.ML_REST && f.modelNote != null) {
            out += "Model breakdown: not available (${f.modelNote})."
        }
        val exit = when {
            t.kind == "SETTLED" && t.exit >= 0.5 -> "held to the close and settled at \$1 (its side won)"
            t.kind == "SETTLED" -> "held to the close and settled at \$0 (its side lost)"
            else -> "${ScalperResults.kindLabel(t.kind).lowercase(Locale.US)} at ${cents(t.exit)}"
        }
        out += "Exit: $exit, ${f.holdSeconds} s after the entry."
        out += "Result: ${money(t.pnlUsd)} net after ${money(t.feesUsd).removePrefix("+")} fees (${fmt1(f.pnlCentsPerContract)}c a contract)."
        out += if (record != null && record.closed > 0) {
            "One trade says nothing about skill or luck. ${ScalperResults.strategyLabel(t.strategy)} so far: ${record.closed} trades, " +
                "${pct(record.wins.toDouble() / record.closed)} won, ${money(record.pnlUsd)} net (${money(record.pnlUsd / record.closed)} a trade)."
        } else {
            "One trade says nothing about skill or luck; judge the strategy on its whole record (Arena → its page)."
        }
        out += "The settings listed are today's; they may have changed since this trade."
        return out
    }

    val SYSTEM = """You explain one closed PAPER scalp from a Bitcoin-only Kalshi 15-minute UP/DOWN app to its owner.
Use only the facts given; never invent prices or numbers. In 4 to 6 short sentences: what the strategy looked for, what it saw when it entered, why it entered (for the ML scalper, name the two or three inputs that pushed the model most and which way), what happened after, and whether one trade like this says skill or mostly luck. Plain words, no headings, no lists. Paper only: never advise real-money trades."""

    /** The user message: the facts, compact enough for a small on-phone model. */
    fun prompt(f: TradeFacts): String = buildString {
        val t = f.trade
        appendLine("Strategy: ${ScalperResults.strategyLabel(t.strategy)}")
        for (r in f.rules.take(4)) appendLine("Rule: $r")
        appendLine("Market: ${t.ticker} (Bitcoin finishes UP or DOWN at the 15-minute close)")
        appendLine("Bought ${ScalperResults.sideLabel(t.side)} x${t.contracts} at ${cents(t.entry)}" +
            (f.enteredSecond?.let { " at minute ${it / 60}:${"%02d".format(Locale.US, it % 60)} of the window" } ?: ""))
        val exit = if (t.kind == "SETTLED") (if (t.exit >= 0.5) "settled at \$1 (its side won)" else "settled at \$0 (its side lost)") else "out at ${cents(t.exit)}"
        appendLine("Exit: ${ScalperResults.kindLabel(t.kind)}, $exit after ${f.holdSeconds} s")
        appendLine("Result: ${money(t.pnlUsd)} net after ${money(t.feesUsd)} fees (${fmt1(f.pnlCentsPerContract)}c a contract)")
        f.order?.let { o ->
            val parts = buildList {
                o.bid?.let { add("bid ${cents(it)}") }
                o.ask?.let { add("ask ${cents(it)}") }
                o.bidQty?.let { add("${it.roundToInt()} contracts on the bid") }
                o.askQty?.let { add("${it.roundToInt()} on the ask") }
                o.move10?.let { add("moved ${signedCents(it)} in 10 s") }
                o.move30?.let { add("${signedCents(it)} in 30 s") }
            }
            if (parts.isNotEmpty()) appendLine("At entry: ${parts.joinToString(", ")}")
        }
        if (t.strategy == ScalpStrategy.ML_REST.name) {
            if (f.modelCents != null) {
                appendLine("Model: " + (f.modelChance?.let { "${pct(it)} chance this order makes money, " } ?: "") +
                    "expected ${fmt2(f.modelCents)}c a contract; it acts at ${fmt2(f.modelTheta ?: 0.0)}c or more")
                if (t.queueKnown) appendLine("Queue ahead when it posted: ${t.queueAhead.roundToInt()} contracts")
                val top = f.pushes.take(TOP_PUSHES)
                if (top.isNotEmpty()) {
                    appendLine("Inputs that pushed the model most (+ toward making money, - against):")
                    for (p in top) appendLine("- ${p.label} = ${fmtValue(p.name, p.value)}: ${if (p.push >= 0) "+" else ""}${fmt3(p.push)}")
                }
            } else if (f.modelNote != null) {
                appendLine("Model breakdown: not available (${f.modelNote})")
            }
        }
    }.trim()

    /** A readable name for each model input. */
    val FEATURE_WORDS: Map<String, String> = buildMap {
        put("mid", "the side's mid price"); put("ask", "the side's ask"); put("bid", "the side's bid")
        put("spread", "the spread"); put("tte", "seconds left in the window"); put("dist50", "distance of the mid from 50c")
        put("hour", "hour of day (UTC)"); put("weekend", "weekend")
        for (k in listOf(5, 10, 20, 30, 60, 120, 180, 300)) put("dm$k", "mid change over $k s")
        put("from_hi", "mid below its window high"); put("from_lo", "mid above its window low"); put("from_first", "mid change since the window's first quote")
        put("hi60", "mid below its 60 s high"); put("lo60", "mid above its 60 s low")
        for (w in listOf(30, 60, 120, 300)) put("vol$w", "volatility over $w s")
        for (w in listOf(30, 60, 120)) put("nchg$w", "price changes in $w s")
        for (w in listOf(5, 10, 30, 60, 120, 300)) {
            put("imb$w", "buy/sell imbalance over $w s"); put("lvol$w", "volume over $w s (log)")
        }
        put("imb_all", "buy/sell imbalance since the open"); put("lvol_all", "volume since the open (log)")
        for (w in listOf(30, 60, 120)) put("lcnt$w", "trade count over $w s (log)")
        for (w in listOf(30, 120)) {
            put("lmaxbuy$w", "biggest buy in $w s (log)"); put("lmaxsell$w", "biggest sell in $w s (log)")
        }
        put("stale_bid", "seconds since a print at the bid"); put("stale_ask", "seconds since a print at the ask")
    }

    fun label(name: String): String = FEATURE_WORDS[name] ?: name

    private fun fmtValue(name: String, v: Float): String = when {
        name in setOf("mid", "ask", "bid") -> cents(v.toDouble())
        name == "spread" || name == "dist50" || name.startsWith("dm") || name.startsWith("from_") || name == "hi60" || name == "lo60" ->
            "${fmt1(v * 100.0)}c"
        else -> String.format(Locale.US, "%.3g", v)
    }

    private fun cents(p: Double) = "${fmt1(p * 100.0).removeSuffix(".0")}c"
    private fun signedCents(d: Double) = (if (d >= 0) "+" else "") + "${fmt1(d * 100.0)}c"
    private fun money(v: Double) = (if (v < 0) "-" else "+") + "$" + String.format(Locale.US, "%.2f", abs(v))
    private fun pct(p: Double) = "${(p * 100.0).roundToInt()}%"
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun fmt3(v: Double) = String.format(Locale.US, "%.3f", v)
}
