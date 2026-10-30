package com.dirk.kalshiodds.decision

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

/**
 * Deterministic evidence for one paper decision. Every figure is a number the
 * gates already used, or a book field recorded on that same quote.
 * An LLM may only phrase [prompt]. It cannot change [Card.gateEnter].
 */
object EvidenceCard {
    const val MARK = "EVIDENCE CARD"
    const val TEMPLATE = "TEMPLATE:"
    private const val OFI_ALPHA = HftMathEngines.OFI_ALPHA

    data class TickPx(val mid: Double, val spread: Double, val bidSz: Double, val askSz: Double)

    data class Driver(val name: String, val push: Double, val value: Double)

    data class Card(
        val scalperId: String,
        val side: String,
        val gateEnter: Boolean,
        val gateReason: String,
        val blockedBy: String,
        val regime: String,
        val logisticScore: Double,
        val lgbmScore: Double,
        val ofi: Double,
        val ofi20: Double,
        val ofi5: Double,
        val studentT: Double?,
        val entryFair: Double,
        val ask: Double,
        val takerFee: Double,
        val edgeAfterFee: Double,
        val slope1m: Double?,
        val slope5m: Double?,
        val mtfAgrees: Boolean?,
        val pStrongCents: Double?,
        val hurdleCents: Double,
        val fibPos: Double?,
        val fibSigned: Double?,
        val fibBarrier: Boolean,
        val fibRatio: Double?,
        val fibLevels: List<Double>,
        val bestBid: Double,
        val bestAsk: Double,
        val bidDepth: Double,
        val askDepth: Double,
        val spread: Double,
        val vamp: Double,
        val timeLeftS: Double,
        val histN: Int,
        val histWins: Int,
        val histWinRate: Double,
        val histAvgPnl: Double,
        val scoreBucket: String,
        val abstain: Boolean,
        val abstainWhy: String,
        val drivers: List<Driver>
    )

    data class Input(
        val scalperId: String,
        val side: String,
        val gateEnter: Boolean,
        val gateReason: String,
        val regime: String,
        val logisticScore: Double,
        val lgbmScore: Double,
        val ofi: Double,
        val ofi20: Double,
        val ofi5: Double,
        val studentT: Double?,
        val entryFair: Double,
        val ask: Double,
        val slope1m: Double?,
        val slope5m: Double?,
        val pStrongCents: Double?,
        val fibPos: Double?,
        val fibSigned: Double?,
        val fibBarrier: Boolean,
        val fibRatio: Double?,
        val fibLevels: List<Double>,
        val bestBid: Double,
        val bestAsk: Double,
        val bidDepth: Double,
        val askDepth: Double,
        val spread: Double,
        val vamp: Double,
        val timeLeftS: Double,
        val histN: Int = 0,
        val histWins: Int = 0,
        val histWinRate: Double = 0.0,
        val histAvgPnl: Double = 0.0,
        val useLgbm: Boolean = false,
        val scoreBucket: String = ""
    )

    data class Phrase(val text: String, val fallback: Boolean, val disagrees: Boolean)

    /** Last 20 best-level OFI increments, scaled like the gate feature and signed with the trade. */
    fun ofi20(ticks: List<TickPx>, side: String): Double {
        if (ticks.size < 2) return 0.0
        val window = if (ticks.size > 21) ticks.takeLast(21) else ticks
        var sum = 0.0
        for (i in 1 until window.size) {
            val prev = window[i - 1]
            val last = window[i]
            val pb = prev.mid - prev.spread / 2.0
            val pa = prev.mid + prev.spread / 2.0
            val lb = last.mid - last.spread / 2.0
            val la = last.mid + last.spread / 2.0
            sum += HftSignals.ofi(pb, prev.bidSz, pa, prev.askSz, lb, last.bidSz, la, last.askSz)
        }
        val scaled = HftSignals.ofiFeature(sum)
        return if (side.equals("NO", true) || side.equals("DOWN", true)) -scaled else scaled
    }

    /** Top-5 depth imbalance on this side's book. Positive means more size on the bid. */
    fun ofi5(bids: List<Pair<Double, Double>>, asks: List<Pair<Double, Double>>): Double {
        val n = minOf(5, maxOf(bids.size, asks.size))
        if (n == 0) return 0.0
        var sum = 0.0
        var wsum = 0.0
        for (i in 0 until n) {
            val w = exp(-OFI_ALPHA * i)
            val b = bids.getOrNull(i)?.second ?: 0.0
            val a = asks.getOrNull(i)?.second ?: 0.0
            val den = b + a
            if (den <= 1e-9) continue
            sum += w * (b - a) / den
            wsum += w
        }
        if (wsum <= 0.0) return 0.0
        return (sum / wsum).coerceIn(-1.0, 1.0)
    }

    /**
     * Logistic scores split at 0.45 and 0.65. LGBM [predictedNet] is a dollar payoff on the
     * prior (0.02 is 2¢) and cents per contract once the forest is fitted. Both land in the
     * same weak / mid / strong tokens so similar-setup history is not one bucket.
     */
    fun bucketToken(score: Double, predictedNet: Boolean = false): String {
        if (!predictedNet) {
            return when {
                score < 0.45 -> "lt45"
                score <= 0.65 -> "mid"
                else -> "gt65"
            }
        }
        val dollars = if (abs(score) >= 1.0) score / 100.0 else score
        return when {
            dollars < 0.01 -> "lt45"
            dollars <= 0.03 -> "mid"
            else -> "gt65"
        }
    }

    fun blockedBy(enter: Boolean, reason: String): String = when {
        !enter && reason.contains(HybridQuantGates.MTF_DISAGREE) -> HybridQuantGates.MTF_DISAGREE
        !enter && reason.contains(HybridQuantGates.STRONG_MOVE_LOW) -> HybridQuantGates.STRONG_MOVE_LOW
        !enter && reason.contains(HybridQuantGates.FIB_BARRIER) -> HybridQuantGates.FIB_BARRIER
        else -> "none"
    }

    fun build(input: Input): Card {
        val fee = ScalpRule.orderFee(1, input.ask)
        val fairForEdge = input.studentT?.takeIf { it.isFinite() } ?: input.entryFair
        val edge = if (fairForEdge.isFinite() && input.ask.isFinite()) fairForEdge - input.ask - fee else 0.0
        val hurdle = HybridQuantGates.hurdleCents(input.ask)
        val up = input.side.equals("YES", true) || input.side.equals("UP", true)
        val mtf = if (input.slope1m != null && input.slope5m != null &&
            input.slope1m.isFinite() && input.slope5m.isFinite()
        ) HybridQuantGates.signsAgree(input.side, input.slope1m, input.slope5m) else null
        val drivers = drivers(input, edge, hurdle, up)
        val abstain = abstain(input, up)
        return Card(
            scalperId = input.scalperId,
            side = input.side,
            gateEnter = input.gateEnter,
            gateReason = input.gateReason,
            blockedBy = blockedBy(input.gateEnter, input.gateReason),
            regime = input.regime.ifBlank { VolRegime.UNKNOWN },
            logisticScore = finite(input.logisticScore),
            lgbmScore = finite(input.lgbmScore),
            ofi = finite(input.ofi),
            ofi20 = finite(input.ofi20),
            ofi5 = finite(input.ofi5),
            studentT = input.studentT?.takeIf { it.isFinite() },
            entryFair = finite(input.entryFair),
            ask = finite(input.ask),
            takerFee = fee,
            edgeAfterFee = finite(edge),
            slope1m = input.slope1m?.takeIf { it.isFinite() },
            slope5m = input.slope5m?.takeIf { it.isFinite() },
            mtfAgrees = mtf,
            pStrongCents = input.pStrongCents?.takeIf { it.isFinite() },
            hurdleCents = if (hurdle.isFinite()) hurdle else 0.0,
            fibPos = input.fibPos?.takeIf { it.isFinite() },
            fibSigned = input.fibSigned?.takeIf { it.isFinite() },
            fibBarrier = input.fibBarrier,
            fibRatio = input.fibRatio?.takeIf { it.isFinite() },
            fibLevels = input.fibLevels.filter { it.isFinite() },
            bestBid = finite(input.bestBid),
            bestAsk = finite(input.bestAsk),
            bidDepth = finite(input.bidDepth),
            askDepth = finite(input.askDepth),
            spread = finite(input.spread),
            vamp = finite(input.vamp),
            timeLeftS = finite(input.timeLeftS),
            histN = input.histN.coerceAtLeast(0),
            histWins = input.histWins.coerceAtLeast(0),
            histWinRate = finite(input.histWinRate),
            histAvgPnl = finite(input.histAvgPnl),
            scoreBucket = input.scoreBucket.ifBlank {
                bucketToken(if (input.useLgbm) input.lgbmScore else input.logisticScore, input.useLgbm)
            },
            abstain = abstain.first,
            abstainWhy = abstain.second,
            drivers = drivers
        )
    }

    fun prompt(card: Card): String {
        val b = StringBuilder()
        b.appendLine(MARK)
        b.appendLine("scalper: ${card.scalperId}")
        b.appendLine("side: ${card.side}")
        b.appendLine("gates_enter: ${card.gateEnter}")
        b.appendLine("gate_reason: ${card.gateReason}")
        b.appendLine("blocked_by: ${card.blockedBy}")
        b.appendLine("regime: ${card.regime}")
        b.appendLine("logistic: ${f(card.logisticScore)}")
        b.appendLine("lgbm: ${f(card.lgbmScore)}")
        b.appendLine("ofi: ${f(card.ofi)}")
        b.appendLine("ofi20: ${f(card.ofi20)}")
        b.appendLine("ofi5: ${f(card.ofi5)}")
        b.appendLine("ofi_window: 20")
        b.appendLine("ofi_levels: 5")
        b.appendLine("nu: ${f(HftSignals.NU)}")
        b.appendLine("student_t: ${num(card.studentT)}")
        b.appendLine("entry_fair: ${f(card.entryFair)}")
        b.appendLine("ask: ${f(card.ask)}")
        b.appendLine("taker_fee: ${f(card.takerFee)}")
        b.appendLine("edge_after_fee: ${f(card.edgeAfterFee)}")
        b.appendLine("slope_1m: ${num(card.slope1m)}")
        b.appendLine("slope_5m: ${num(card.slope5m)}")
        b.appendLine("mtf_agrees: ${card.mtfAgrees?.toString() ?: "na"}")
        b.appendLine("p_strong_cents: ${num(card.pStrongCents)}")
        b.appendLine("hurdle_cents: ${f(card.hurdleCents)}")
        b.appendLine("fib_pos: ${num(card.fibPos)}")
        b.appendLine("fib_signed: ${num(card.fibSigned)}")
        b.appendLine("fib_barrier: ${card.fibBarrier}")
        b.appendLine("fib_ratio: ${num(card.fibRatio)}")
        b.appendLine("fib_levels: ${if (card.fibLevels.isEmpty()) "na" else card.fibLevels.joinToString(" ") { f(it) }}")
        b.appendLine("best_bid: ${f(card.bestBid)}")
        b.appendLine("best_ask: ${f(card.bestAsk)}")
        b.appendLine("bid_depth: ${f(card.bidDepth)}")
        b.appendLine("ask_depth: ${f(card.askDepth)}")
        b.appendLine("spread: ${f(card.spread)}")
        b.appendLine("vamp: ${f(card.vamp)}")
        b.appendLine("time_left_s: ${f(card.timeLeftS)}")
        b.appendLine("hist_n: ${card.histN}")
        b.appendLine("hist_wins: ${card.histWins}")
        b.appendLine("hist_win_rate: ${f(card.histWinRate)}")
        b.appendLine("hist_avg_pnl: ${f(card.histAvgPnl)}")
        b.appendLine("score_bucket: ${card.scoreBucket}")
        b.appendLine("abstain: ${card.abstain}")
        b.appendLine("abstain_why: ${card.abstainWhy}")
        b.appendLine("driver_count: ${card.drivers.size}")
        b.appendLine("DRIVERS")
        card.drivers.forEach { d ->
            b.appendLine("${d.name} ${f(d.push)} ${f(d.value)}")
        }
        b.appendLine("Phrase only numbers printed above. Do not invent reasons or numbers. gates_enter decides. No orders.")
        b.appendLine(TEMPLATE)
        b.append(template(card))
        return b.toString().trimEnd()
    }

    fun template(card: Card): String {
        val gates = if (card.gateEnter) "Gates entered." else "Gates skipped."
        val block = if (card.blockedBy == "none") "Block none." else "Block ${card.blockedBy}."
        val mtf = when (card.mtfAgrees) {
            true -> "MTF agrees."
            false -> "MTF disagrees."
            null -> "MTF na."
        }
        val abstain = if (card.abstain) "Abstain: yes. ${card.abstainWhy}" else "Abstain: no."
        val verdict = if (card.gateEnter) "take" else "skip"
        val drv = card.drivers.joinToString(", ") { d ->
            val word = if (d.push > 0.0) "plus" else if (d.push < 0.0) "minus" else "flat"
            "${d.name} $word ${f(d.push)} value ${f(d.value)}"
        }
        val levels = if (card.fibLevels.isEmpty()) "na" else card.fibLevels.joinToString(" ") { f(it) }
        return buildString {
            appendLine("Evidence card ${card.scalperId} ${card.side}.")
            appendLine("$gates $block Reason: ${card.gateReason}. Regime ${card.regime}.")
            appendLine("Logistic ${f(card.logisticScore)}. LightGBM ${f(card.lgbmScore)}.")
            appendLine("OFI ${f(card.ofi)}. 20-tick ${f(card.ofi20)}. 5-level ${f(card.ofi5)}. nu ${f(HftSignals.NU)}.")
            appendLine("Student-t fair ${num(card.studentT)} vs ask ${f(card.ask)}. Entry fair ${f(card.entryFair)}.")
            appendLine("Taker fee ${f(card.takerFee)}. Edge after fee ${f(card.edgeAfterFee)}.")
            appendLine("Slope 1m ${num(card.slope1m)}. Slope 5m ${num(card.slope5m)}. $mtf")
            appendLine("P(strong) ${num(card.pStrongCents)} vs ask+fee ${f(card.hurdleCents)}.")
            appendLine("Fib pos ${num(card.fibPos)} signed ${num(card.fibSigned)} ratio ${num(card.fibRatio)} barrier ${card.fibBarrier} levels $levels.")
            appendLine("Book bid ${f(card.bestBid)} ask ${f(card.bestAsk)} depth ${f(card.bidDepth)} ${f(card.askDepth)} spread ${f(card.spread)} VAMP ${f(card.vamp)}.")
            appendLine("Time left ${f(card.timeLeftS)} s.")
            appendLine("Similar setups n ${card.histN} wins ${card.histWins} win rate ${f(card.histWinRate)} avg P&L ${f(card.histAvgPnl)} bucket ${card.scoreBucket}.")
            appendLine("Top ${card.drivers.size} drivers: $drv.")
            appendLine(abstain)
            append("AI verdict: $verdict")
        }
    }

    fun fieldBlock(prompt: String): String {
        val start = prompt.indexOf(MARK)
        val end = prompt.indexOf(TEMPLATE)
        if (start < 0 || end < 0 || end <= start) return ""
        return prompt.substring(start, end)
    }

    fun templateOf(prompt: String): String {
        val i = prompt.indexOf(TEMPLATE)
        if (i < 0) return ""
        return prompt.substring(i + TEMPLATE.length).trim()
    }

    fun driverRows(prompt: String): List<Driver> {
        val block = fieldBlock(prompt)
        val out = ArrayList<Driver>()
        var on = false
        for (line in block.lineSequence()) {
            if (line.trim() == "DRIVERS") {
                on = true
                continue
            }
            if (!on || line.isBlank()) continue
            val parts = line.trim().split(' ')
            if (parts.size < 3) continue
            val push = parts[1].toDoubleOrNull() ?: continue
            val value = parts[2].toDoubleOrNull() ?: continue
            out += Driver(parts[0], push, value)
        }
        return out
    }

    fun numberTokens(text: String): List<String> =
        Regex("[-+]?\\d+(?:\\.\\d+)?").findAll(text).map { it.value.trimStart('+') }.toList()

    fun normNum(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (!d.isFinite()) return raw
        val s = String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s.ifEmpty { "0" }
    }

    /** Every number in [llm] must already appear in the evidence field block. */
    fun validates(prompt: String, llm: String): Boolean {
        val allowed = numberTokens(fieldBlock(prompt)).map { normNum(it) }.toSet()
        if (allowed.isEmpty()) return false
        for (tok in numberTokens(llm)) {
            if (normNum(tok) !in allowed) return false
        }
        return true
    }

    /**
     * Phrase [llm] only when every cited number is on the card.
     * A blank model, a failed check, or a missing model returns the template.
     * A take/skip line that fights [Card.gateEnter] is flagged and logged.
     * The returned text does not change the gate.
     */
    fun phrase(prompt: String, llm: String?, scalperId: String = ""): Phrase {
        val template = templateOf(prompt)
        val gates = gatesEnter(prompt)
        val clean = llm?.trim().orEmpty()
        if (template.isBlank() || clean.isBlank() || !validates(prompt, clean)) {
            return Phrase(template, fallback = true, disagrees = false)
        }
        val said = llmVerdict(clean)
        val disagrees = gates != null && said != null && said != gates
        if (disagrees) {
            EvidenceDisagreements.log(
                EvidenceDisagreements.Event(
                    scalperId = scalperId.ifBlank { scalperOf(prompt) },
                    gatesEnter = gates,
                    llmTake = said
                )
            )
        }
        val text = if (disagrees) clean + "\n\nAI disagrees with gates" else clean
        return Phrase(text, fallback = false, disagrees = disagrees)
    }

    fun syncSummary(prompt: String): String = templateOf(prompt).lineSequence().firstOrNull().orEmpty().take(160)

    private val latest = HashMap<String, String>()

    fun remember(scalperId: String, prompt: String) {
        if (scalperId.isBlank() || prompt.isBlank()) return
        synchronized(latest) { latest[scalperId] = prompt }
    }

    fun latestPrompt(scalperId: String): String = synchronized(latest) { latest[scalperId].orEmpty() }

    fun latestBlurb(scalperId: String): String = templateOf(latestPrompt(scalperId))

    fun reset() {
        synchronized(latest) { latest.clear() }
        EvidenceDisagreements.reset()
    }

    private fun gatesEnter(prompt: String): Boolean? =
        Regex("gates_enter:\\s*(true|false)").find(prompt)?.groupValues?.getOrNull(1)?.equals("true")

    private fun scalperOf(prompt: String): String =
        Regex("scalper:\\s*(\\S+)").find(prompt)?.groupValues?.getOrNull(1).orEmpty()

    private fun llmVerdict(text: String): Boolean? {
        val hit = Regex("(?i)AI verdict:\\s*(take|skip)").findAll(text).lastOrNull() ?: return null
        return hit.groupValues[1].equals("take", true)
    }

    private fun drivers(input: Input, edge: Double, hurdle: Double, up: Boolean): List<Driver> {
        val rows = ArrayList<Driver>(8)
        rows += Driver("logistic", (finite(input.logisticScore) - 0.5) * 20.0, finite(input.logisticScore))
        rows += Driver("lgbm", finite(input.lgbmScore) * 100.0, finite(input.lgbmScore))
        rows += Driver("ofi", finite(input.ofi) * 10.0, finite(input.ofi))
        rows += Driver("edge", finite(edge) * 100.0, finite(edge))
        val s1 = slopeSign(input.slope1m, up)
        val s5 = slopeSign(input.slope5m, up)
        val mtfPush = when {
            input.slope1m == null || input.slope5m == null -> 0.0
            s1 > 0 && s5 > 0 -> 4.0
            s1 < 0 || s5 < 0 -> -6.0
            else -> 0.0
        }
        rows += Driver("mtf", mtfPush, mtfPush)
        val strong = if (input.pStrongCents == null || !input.pStrongCents.isFinite() || !hurdle.isFinite()) 0.0
        else input.pStrongCents - hurdle
        rows += Driver("strong", strong, input.pStrongCents ?: 0.0)
        rows += Driver("fib", if (input.fibBarrier) -8.0 else 0.0, if (input.fibBarrier) 1.0 else 0.0)
        rows += Driver("spread", -finite(input.spread) * 100.0, finite(input.spread))
        return rows.sortedWith(compareByDescending<Driver> { abs(it.push) }.thenBy { it.name }).take(3)
    }

    private fun abstain(input: Input, up: Boolean): Pair<Boolean, String> {
        val scorePush = if (input.useLgbm) finite(input.lgbmScore) * 100.0 else (finite(input.logisticScore) - 0.5) * 20.0
        val scoreSign = signOf(scorePush)
        val ofiSign = signOf(input.ofi)
        val s1 = slopeSign(input.slope1m, up)
        val s5 = slopeSign(input.slope5m, up)
        val ofiOpp = scoreSign != 0 && ofiSign != 0 && scoreSign != ofiSign
        val slopeOpp = scoreSign != 0 && ((s1 != 0 && scoreSign != s1) || (s5 != 0 && scoreSign != s5))
        val slopesSplit = s1 != 0 && s5 != 0 && s1 != s5
        if (!ofiOpp && !slopeOpp && !slopesSplit) return false to "none"
        val why = when {
            ofiOpp && slopeOpp -> "Score, OFI, and slope point opposite ways."
            ofiOpp -> "Score and OFI point opposite ways."
            slopesSplit && slopeOpp -> "Score and slope point opposite ways."
            slopesSplit -> "1m and 5m slopes disagree."
            else -> "Score and slope point opposite ways."
        }
        return true to why
    }

    private fun slopeSign(slope: Double?, up: Boolean): Int {
        if (slope == null || !slope.isFinite() || slope == 0.0) return 0
        val with = if (up) slope > 0.0 else slope < 0.0
        return if (with) 1 else -1
    }

    private fun signOf(v: Double): Int {
        if (!v.isFinite() || abs(v) <= 1e-9) return 0
        return if (v > 0.0) 1 else -1
    }

    private fun finite(v: Double): Double = if (v.isFinite()) v else 0.0

    private fun f(v: Double): String = String.format(Locale.US, "%.4f", if (v.isFinite()) v else 0.0)

    private fun num(v: Double?): String = if (v == null || !v.isFinite()) "na" else f(v)
}

object EvidenceDisagreements {
    data class Event(val scalperId: String, val gatesEnter: Boolean, val llmTake: Boolean)

    private val rows = ArrayDeque<Event>()

    fun log(event: Event) = synchronized(rows) {
        rows.addLast(event)
        while (rows.size > 50) rows.removeFirst()
    }

    fun snapshot(): List<Event> = synchronized(rows) { rows.toList() }

    fun reset() = synchronized(rows) { rows.clear() }
}
