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
 * Builds the facts and the prompt for "Explain this trade" (the arena's trade cards). The words come from an LLM
 * ([com.dirk.kalshiodds.llm.AiWriter]: the on-phone model first); every number in the prompt comes from the trade
 * file, the order file and the model, so the explanation is grounded in what happened. Pure apart from what it is
 * handed.
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
