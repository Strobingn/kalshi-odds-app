package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Isolated paper book. Never calls Kalshi. $100 start / $5 per AI fill.
 */
@Serializable
data class PaperFill(
    val id: String,
    val ticker: String,
    val side: String,
    val stakeUsd: Double,
    val contracts: Int,
    val limitPrice: Double,
    val source: String,
    val createdAtMs: Long,
    val settled: Boolean = false,
    val outcome: String? = null,
    val won: Boolean? = null,
    val pnlUsd: Double? = null,
    val note: String,
    val winTargetUsd: Double? = null,
    /** Picked-side AI probability in percent (0–100). Null on pre-0.3.16 rows. */
    val aiPct: Double? = null,
    /** Model confidence 0–1. Null on pre-0.3.16 rows. */
    val aiConfidence: Double? = null,
    /** Picked-side market/implied percent (0–100). Null on pre-0.3.16 rows. */
    val marketPct: Double? = null,
    /** Canonical [PaperPickSource.label]. Null on pre-0.3.16 rows. */
    val pickSource: String? = null
) {
    val displaySide: String get() = side.uppercase()

    companion object {
        @JvmStatic
        fun hasAiPct(aiPct: Double?): Boolean =
            aiPct != null && aiPct.isFinite() && aiPct > 0.0

        /** Convert a 0–1 or 0–100 probability to scorecard percent. */
        fun pctFromUnit(raw: Double?): Double? {
            val v = raw?.takeIf { it.isFinite() } ?: return null
            val pct = if (v <= 1.0 + 1e-9) v * 100.0 else v
            return pct.takeIf { it.isFinite() && it > 0.0 }
        }

        fun metaFromTicket(ticket: com.dirk.kalshiodds.signal.trade.TradeTicket): PaperFillMeta {
            val sideYes = !ticket.side.equals("NO", true)
            val implied = ticket.impliedChance
            val market = when {
                implied == null -> null
                sideYes -> pctFromUnit(implied)
                else -> pctFromUnit(1.0 - implied)
            }
            return PaperFillMeta(
                aiPct = pctFromUnit(ticket.modelChance),
                aiConfidence = ticket.modelConfidence,
                marketPct = market ?: pctFromUnit(implied),
                pickSource = PaperPickSource.fromTicketKind(ticket.kind)
            )
        }

        fun metaFromAlert(alert: com.dirk.kalshiodds.signal.model.SignalAlert): PaperFillMeta {
            val sideYes = !alert.predictedSide.equals("NO", true)
            val fair = alert.fairValuePp.takeIf { it.isFinite() }
            val mid = alert.marketMidPp.takeIf { it.isFinite() }
            val ai = fair?.let { if (sideYes) it else 100.0 - it }
            val mkt = mid?.let { if (sideYes) it else 100.0 - it }
            return PaperFillMeta(
                aiPct = ai,
                aiConfidence = alert.confidence,
                marketPct = mkt,
                pickSource = PaperPickSource.AI_ALERT
            )
        }

        /**
         * Auto paper picks must carry an AI %. Manual paper may omit it.
         * Returns false when the fill must be skipped.
         */
        @JvmStatic
        fun allowCreate(source: PaperPickSource, aiPct: Double?): Boolean =
            !source.requiresAiPct || hasAiPct(aiPct)
    }
}

@Serializable
data class PaperArchive(
    val archivedAtMs: Long,
    val startingUsd: Double,
    val cashUsd: Double,
    val fills: List<PaperFill>,
    val note: String = "paper reset"
)

@Serializable
data class PaperBookState(
    val startingUsd: Double = SignalConstants.PAPER_START_USD,
    val cashUsd: Double = SignalConstants.PAPER_START_USD,
    val fills: List<PaperFill> = emptyList(),
    val lastMessage: String? = null,
    val archived: List<PaperArchive> = emptyList()
) {
    val openStakeUsd: Double get() = fills.filter { !it.settled }.sumOf { it.stakeUsd }
    val realizedPnlUsd: Double get() = fills.mapNotNull { it.pnlUsd }.sum()
    /** Paper P&L for the live Bitcoin series only — stored ETH/SOL fills stay in the ledger. */
    val liveRealizedPnlUsd: Double
        get() = fills.filter { CryptoMarkets.isLiveTicker(it.ticker) }.mapNotNull { it.pnlUsd }.sum()
    val equityUsd: Double get() = cashUsd + openStakeUsd
    val openCount: Int get() = fills.count { !it.settled }
}

class PaperBook(
    initial: PaperBookState = PaperBookState(),
    private val persist: (PaperBookState) -> Unit = {},
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PaperBookState> = _state.asStateFlow()

    fun snapshot(): PaperBookState = _state.value

    fun openTickers(): Set<String> = synchronized(lock) {
        _state.value.fills.filter { !it.settled }.map { it.ticker.uppercase() }.toSet()
    }

    fun reset() {
        synchronized(lock) {
            val cur = _state.value
            val archive = PaperArchive(
                archivedAtMs = nowMs(),
                startingUsd = cur.startingUsd,
                cashUsd = cur.cashUsd,
                fills = cur.fills,
                note = "Paper book reset — ledger archived"
            )
            publish(
                PaperBookState(
                    lastMessage = "Paper book reset to $100 — prior run archived",
                    archived = cur.archived + archive
                )
            )
        }
    }

    fun hydrate(next: PaperBookState) {
        synchronized(lock) {
            _state.value = next
        }
    }

    /**
     * Auto-log a $5 paper fill when an AI hunter / configured ticket would trade.
     * Manual live tickets are ignored — those need an explicit Paper tap.
     */
    fun considerTicket(ticket: TradeTicket, enabled: Boolean): PaperFill? {
        if (!enabled) return null
        if (!ticket.canApprove) return null
        if (ticket.kind == TicketKind.MANUAL || ticket.kind == TicketKind.SELL ||
            ticket.kind == TicketKind.LAST_MINUTE
        ) return null
        val model = ticket.modelChance
        val ask = KalshiPrice.usable(ticket.limitPrice)
        if (model != null && ask != null && !com.dirk.kalshiodds.signal.flip.FlipCheck.beatsAllIn(model, ask)) {
            return null
        }
        val pick = PaperPickSource.fromTicketKind(ticket.kind)
        val meta = PaperFill.metaFromTicket(ticket)
        if (!PaperFill.allowCreate(pick, meta.aiPct)) return null
        val source = when (ticket.kind) {
            TicketKind.HUNTER -> "AI hunter"
            TicketKind.HUNTER_VALUE -> PaperPickSource.LONG_SHOT.label
            else -> "AI ticket"
        }
        return fill(
            ticker = ticket.ticker,
            side = ticket.side,
            limitPrice = ticket.limitPrice,
            source = source,
            note = if (ticket.winTargetUsd != null) {
                "Paper win-target · ${ticket.kind.name.lowercase()} · never sent to Kalshi"
            } else {
                "Paper fill · ${ticket.kind.name.lowercase()} signal · never sent to Kalshi"
            },
            contracts = ticket.contracts.takeIf { ticket.winTargetUsd != null && it > 0 },
            stakeUsd = ticket.stakeUsd.takeIf { ticket.winTargetUsd != null && it > 0.0 },
            winTargetUsd = ticket.winTargetUsd,
            meta = meta.copy(pickSource = pick)
        )
    }

    fun considerAlert(alert: SignalAlert, ask: Double?, enabled: Boolean): PaperFill? {
        if (!enabled) return null
        if (SignalStance.isNoBetSide(alert.predictedSide)) return null
        val px = KalshiPrice.usable(ask) ?: return null
        val model = PaperFill.metaFromAlert(alert).aiPct?.div(100.0)
        if (model != null && !com.dirk.kalshiodds.signal.flip.FlipCheck.beatsAllIn(model, px)) {
            return null
        }
        val meta = PaperFill.metaFromAlert(alert)
        if (!PaperFill.allowCreate(PaperPickSource.AI_ALERT, meta.aiPct)) return null
        return fill(
            ticker = alert.ticker,
            side = alert.predictedSide,
            limitPrice = px,
            source = "AI signal",
            note = alert.reason.ifBlank { "LiveCall / Dip Hunter signal" },
            meta = meta
        )
    }

    fun considerLastMinute(
        fired: com.dirk.kalshiodds.signal.lastminute.LastMinuteFired,
        enabled: Boolean,
        market: com.dirk.kalshiodds.domain.MarketUiModel? = null
    ): PaperFill? {
        if (!enabled) return null
        val liveAsk = market?.let {
            com.dirk.kalshiodds.signal.trade.TicketBuilder.liveAsk(it, fired.side)
        }
        if (!com.dirk.kalshiodds.signal.flip.FlipCheck.allowsFired(
                fired,
                market?.spotUsd ?: market?.lastMinute?.spotUsd,
                market?.floorStrike ?: market?.lastMinute?.strikeUsd,
                liveAsk
            )
        ) {
            return null
        }
        val meta = PaperFillMeta(
            aiPct = PaperFill.pctFromUnit(fired.winChance),
            aiConfidence = null,
            marketPct = PaperFill.pctFromUnit(fired.ask),
            pickSource = PaperPickSource.LAST_MINUTE
        )
        if (!PaperFill.allowCreate(PaperPickSource.LAST_MINUTE, meta.aiPct)) return null
        return fill(
            ticker = fired.ticker,
            side = fired.side,
            limitPrice = fired.ask,
            source = PaperPickSource.LAST_MINUTE.label,
            note = "Last-minute strategy · never sent to Kalshi",
            contracts = fired.contracts.takeIf { it > 0 },
            stakeUsd = fired.costUsd.takeIf { it > 0.0 },
            meta = meta
        )
    }

    /** User tapped Paper on a ticket. Still never hits Kalshi. */
    fun manualFill(ticket: TradeTicket): PaperFill? {
        if (ticket.isSell) return sell(ticket)
        return PaperBuy.execute(this, ticket).fill
    }

    /**
     * Explicit paper buy used by [PaperBuy]. Caps to cash, reuses a ticker
     * after the prior fill settled, and always writes [lastMessage].
     */
    internal fun forceFill(
        ticker: String,
        side: String,
        limitPrice: Double,
        contracts: Int,
        source: String,
        note: String,
        winTargetUsd: Double? = null,
        meta: PaperFillMeta = PaperFillMeta()
    ): PaperFill? {
        if (CryptoMarkets.isRetiredTicker(ticker)) {
            rememberMessage("Paper skip $ticker — Bitcoin-only")
            return null
        }
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(limitPrice) ?: return null.also {
            rememberMessage("Paper skip $ticker — unusable limit")
        }
        val qty = contracts.coerceAtLeast(0)
        if (qty < 1) {
            rememberMessage("Paper skip $ticker — 0 contracts")
            return null
        }
        val stake = qty * px
        synchronized(lock) {
            val cur = _state.value
            if (cur.cashUsd + 1e-9 < stake) {
                rememberMessage("Paper skip $ticker — need ${fmt(stake)} (cash ${fmt(cur.cashUsd)})")
                return null
            }
            val row = newFill(
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = qty,
                limitPrice = px,
                source = source,
                note = note,
                winTargetUsd = winTargetUsd,
                meta = meta
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake,
                    fills = fills,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "PAPER %s %s · $%.2f · %d ct @ %.0f¢ · %s",
                        row.displaySide,
                        row.ticker,
                        row.stakeUsd,
                        row.contracts,
                        row.limitPrice * 100,
                        source
                    )
                )
            )
            return row
        }
    }

    internal fun rememberMessage(message: String) {
        synchronized(lock) {
            publish(_state.value.copy(lastMessage = message))
        }
    }

    /**
     * Explicit paper buy used by [PaperBuy]. Caps to cash, reuses a ticker
     * after the prior fill settled, and always writes [lastMessage].
     */
    fun explicitBuy(ticket: TradeTicket): PaperBuy.Outcome {
        val px = KalshiPrice.usable(ticket.estimatedAvgFill.takeIf { it > 0.0 } ?: ticket.limitPrice)
            ?: return PaperBuy.Outcome(ok = false, message = "No usable ask to paper-fill ${ticket.ticker}")
        val wantSide = if (ticket.side.equals("NO", true)) "NO" else "YES"
        val wantQty = when {
            ticket.contracts > 0 -> ticket.contracts
            ticket.stakeUsd > 0.0 -> floor(ticket.stakeUsd / px).toInt()
            else -> floor(SignalConstants.PAPER_STAKE_USD / px).toInt()
        }
        return explicitFill(
            ticker = ticket.ticker,
            side = wantSide,
            limitPrice = px,
            wantContracts = wantQty,
            source = "paper buy · ${ticket.kind.name.lowercase()}",
            note = buildString {
                append("Paper buy · ${ticket.kind.name.lowercase()}")
                if (ticket.winTargetUsd != null) append(" · win-target \$${ticket.winTargetUsd.toInt()}")
                append(" · never sent to Kalshi")
            },
            winTargetUsd = ticket.winTargetUsd,
            meta = PaperFill.metaFromTicket(ticket)
        )
    }

    fun explicitFill(
        ticker: String,
        side: String,
        limitPrice: Double,
        wantContracts: Int,
        source: String,
        note: String,
        winTargetUsd: Double? = null,
        meta: PaperFillMeta = PaperFillMeta()
    ): PaperBuy.Outcome {
        if (CryptoMarkets.isRetiredTicker(ticker)) {
            return PaperBuy.Outcome(ok = false, message = "Paper skip $ticker — Bitcoin-only")
        }
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(limitPrice)
            ?: return PaperBuy.Outcome(ok = false, message = "Paper skip $ticker — unusable limit")
        synchronized(lock) {
            val cur = _state.value
            val open = cur.fills.firstOrNull {
                !it.settled && it.ticker.equals(ticker, ignoreCase = true)
            }
            if (open != null) {
                val msg = "Already have an open paper fill on $ticker (${open.displaySide} ${open.contracts} ct)"
                publish(cur.copy(lastMessage = msg))
                return PaperBuy.Outcome(ok = false, message = msg)
            }
            val (qty, capped) = PaperBuy.capContracts(wantContracts, cur.cashUsd, px)
            if (qty < 1) {
                val msg = String.format(
                    java.util.Locale.US,
                    "Paper cash %s cannot buy 1 ct @ %.1f¢ on %s (need %s with fees)",
                    fmt(cur.cashUsd),
                    px * 100,
                    ticker,
                    fmt(PaperBuy.costUsd(1, px))
                )
                publish(cur.copy(lastMessage = msg))
                return PaperBuy.Outcome(ok = false, message = msg)
            }
            val stake = qty * px
            val fees = com.dirk.kalshiodds.signal.trade.KalshiFee.total(qty, px)
            val debit = stake + fees
            val row = newFill(
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = qty,
                limitPrice = px,
                source = source,
                note = buildString {
                    append(note)
                    if (capped) append(" · capped to paper cash")
                    if (fees > 0.0) append(String.format(java.util.Locale.US, " · fee $%.2f", fees))
                },
                winTargetUsd = winTargetUsd,
                meta = meta
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            val msg = String.format(
                java.util.Locale.US,
                "PAPER %s %s · $%.2f · %d ct @ %.1f¢%s · fee $%.2f · never Kalshi",
                row.displaySide,
                row.ticker,
                row.stakeUsd,
                row.contracts,
                row.limitPrice * 100,
                if (capped) " · capped" else "",
                fees
            )
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - debit,
                    fills = fills,
                    lastMessage = msg
                )
            )
            return PaperBuy.Outcome(
                ok = true,
                fill = row,
                message = msg,
                capped = capped,
                contracts = qty,
                stakeUsd = stake
            )
        }
    }

    /**
     * Card-level $10 paper buy. [allInUsd] already includes the same
     * `ceil_cent(0.07 × C × P × (1−P))` fee [LiveOrderSizer] used for the
     * tile profit line. Never hits Kalshi.
     */
    fun fillTenDollar(
        ticker: String,
        side: String,
        ask: Double,
        contracts: Int,
        feeUsd: Double,
        allInUsd: Double,
        source: String,
        message: String,
        meta: PaperFillMeta = PaperFillMeta(pickSource = PaperPickSource.MANUAL)
    ): PaperBuy.Outcome {
        if (CryptoMarkets.isRetiredTicker(ticker)) {
            return PaperBuy.Outcome(ok = false, message = "Paper skip $ticker — Bitcoin-only")
        }
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(ask)
            ?: return PaperBuy.Outcome(ok = false, message = "No ask to paper ${if (want == "NO") "DOWN" else "UP"}")
        val qty = contracts.coerceAtLeast(0)
        if (qty < 1) {
            return PaperBuy.Outcome(ok = false, message = "No ask to paper ${if (want == "NO") "DOWN" else "UP"}")
        }
        synchronized(lock) {
            val cur = _state.value
            val open = cur.fills.firstOrNull {
                !it.settled && it.ticker.equals(ticker, ignoreCase = true)
            }
            if (open != null) {
                val msg = "Already have an open paper fill on $ticker (${open.displaySide} ${open.contracts} ct)"
                publish(cur.copy(lastMessage = msg))
                return PaperBuy.Outcome(ok = false, message = msg)
            }
            if (cur.cashUsd + 1e-9 < allInUsd) {
                val msg = "Paper cash ${fmt(cur.cashUsd)} cannot cover ${fmt(allInUsd)}"
                publish(cur.copy(lastMessage = msg))
                return PaperBuy.Outcome(ok = false, message = msg)
            }
            val row = newFill(
                ticker = ticker,
                side = want,
                stakeUsd = allInUsd,
                contracts = qty,
                limitPrice = px,
                source = source,
                note = String.format(
                    java.util.Locale.US,
                    "Paper tile $10 · %d ct @ %.1f¢ · fee $%.2f · never sent to Kalshi",
                    qty,
                    px * 100,
                    feeUsd
                ),
                meta = meta.copy(pickSource = meta.pickSource ?: PaperPickSource.MANUAL)
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - allInUsd,
                    fills = fills,
                    lastMessage = message
                )
            )
            return PaperBuy.Outcome(
                ok = true,
                fill = row,
                message = message,
                contracts = qty,
                stakeUsd = allInUsd
            )
        }
    }

    /**
     * Simulated sell of an open paper fill at the ticket's bid. Never hits Kalshi.
     */
    fun sell(ticket: TradeTicket): PaperFill? {
        if (!ticket.canPaper || !ticket.isSell) return null
        val want = if (ticket.side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(ticket.limitPrice) ?: return null
        synchronized(lock) {
            val cur = _state.value
            val open = cur.fills.firstOrNull {
                !it.settled &&
                    it.ticker.equals(ticket.ticker, ignoreCase = true) &&
                    it.side.equals(want, ignoreCase = true)
            } ?: run {
                publish(cur.copy(lastMessage = "Paper sell skip ${ticket.ticker} — no open $want fill"))
                return null
            }
            val qty = min(ticket.contracts, open.contracts).coerceAtLeast(0)
            if (qty <= 0) return null
            val proceeds = qty * px
            val cost = qty * open.limitPrice
            val pnl = proceeds - cost
            val remaining = open.contracts - qty
            val sold = open.copy(
                settled = remaining <= 0,
                contracts = if (remaining <= 0) open.contracts else qty,
                outcome = "sell",
                won = pnl >= 0.0,
                pnlUsd = pnl,
                note = "Paper sell $qty ct @ ${String.format(java.util.Locale.US, "%.1f¢", px * 100)} · never sent to Kalshi"
            )
            val leftover = if (remaining > 0) {
                open.copy(
                    contracts = remaining,
                    stakeUsd = remaining * open.limitPrice,
                    note = open.note
                )
            } else {
                null
            }
            val nextFills = buildList {
                leftover?.let { add(it) }
                add(sold)
                cur.fills.filterNot { it.id == open.id }.forEach { add(it) }
            }.take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd + proceeds,
                    fills = nextFills,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "PAPER SELL %s %s · %d ct @ %.0f¢ · %+.2f · never Kalshi",
                        sold.displaySide,
                        sold.ticker,
                        qty,
                        px * 100,
                        pnl
                    )
                )
            )
            return sold
        }
    }

    fun settle(ticker: String, result: String): List<PaperFill> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val changed = mutableListOf<PaperFill>()
        synchronized(lock) {
            val cur = _state.value
            var cash = cur.cashUsd
            val nextFills = cur.fills.map { fill ->
                if (fill.settled || !fill.ticker.equals(ticker, ignoreCase = true)) return@map fill
                val won = when (outcome) {
                    "void" -> null
                    "yes" -> fill.side.equals("YES", true)
                    else -> fill.side.equals("NO", true)
                }
                val payout = when {
                    outcome == "void" -> fill.stakeUsd
                    won == true -> fill.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD
                    else -> 0.0
                }
                val pnl = payout - fill.stakeUsd
                cash += payout
                fill.copy(
                    settled = true,
                    outcome = outcome,
                    won = won,
                    pnlUsd = pnl
                ).also { changed += it }
            }
            if (changed.isEmpty()) return emptyList()
            val msg = changed.last().let { f ->
                when {
                    f.outcome == "void" -> "Paper void ${f.ticker} — stake returned"
                    f.won == true -> String.format(
                        java.util.Locale.US,
                        "Paper WIN %s %s  %+.2f",
                        f.displaySide,
                        f.ticker,
                        f.pnlUsd ?: 0.0
                    )
                    else -> String.format(
                        java.util.Locale.US,
                        "Paper LOSS %s %s  %+.2f",
                        f.displaySide,
                        f.ticker,
                        f.pnlUsd ?: 0.0
                    )
                }
            }
            publish(cur.copy(cashUsd = cash, fills = nextFills, lastMessage = msg))
        }
        return changed
    }

    fun settleFromLog(entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>) {
        entries.forEach { e ->
            val o = e.outcome ?: return@forEach
            settle(e.ticker, o)
        }
    }

    private fun fill(
        ticker: String,
        side: String,
        limitPrice: Double,
        source: String,
        note: String,
        contracts: Int? = null,
        stakeUsd: Double? = null,
        winTargetUsd: Double? = null,
        meta: PaperFillMeta = PaperFillMeta()
    ): PaperFill? {
        if (CryptoMarkets.isRetiredTicker(ticker)) return null
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(limitPrice) ?: return null
        synchronized(lock) {
            val cur = _state.value
            if (cur.fills.any { !it.settled && it.ticker.equals(ticker, ignoreCase = true) }) return null
            val qty = contracts?.takeIf { it > 0 } ?: floor(SignalConstants.PAPER_STAKE_USD / px).toInt()
            if (qty < 1) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — ask too high for a $5 clip"))
                return null
            }
            val rawStake = stakeUsd?.takeIf { it > 0.0 } ?: (qty * px)
            val (cappedQty, _) = PaperBuy.capContracts(
                want = if (stakeUsd != null && stakeUsd > 0.0) qty else qty,
                cashUsd = cur.cashUsd,
                price = px
            )
            val useQty = if (rawStake > cur.cashUsd + 1e-9) cappedQty else qty
            if (useQty < 1) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — cash ${fmt(cur.cashUsd)} cannot cover ${fmt(rawStake)}"))
                return null
            }
            val stake = useQty * px
            val row = newFill(
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = useQty,
                limitPrice = px,
                source = source,
                note = note,
                winTargetUsd = winTargetUsd,
                meta = meta
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake,
                    fills = fills,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "PAPER %s %s · $%.2f · %d ct @ %.0f¢ · %s",
                        row.displaySide,
                        row.ticker,
                        row.stakeUsd,
                        row.contracts,
                        row.limitPrice * 100,
                        source
                    )
                )
            )
            return row
        }
    }

    private fun newFill(
        ticker: String,
        side: String,
        stakeUsd: Double,
        contracts: Int,
        limitPrice: Double,
        source: String,
        note: String,
        winTargetUsd: Double? = null,
        meta: PaperFillMeta = PaperFillMeta()
    ): PaperFill = PaperFill(
        id = idFactory(),
        ticker = ticker,
        side = side,
        stakeUsd = stakeUsd,
        contracts = contracts,
        limitPrice = limitPrice,
        source = source,
        createdAtMs = nowMs(),
        note = note,
        winTargetUsd = winTargetUsd,
        aiPct = meta.aiPct,
        aiConfidence = meta.aiConfidence,
        marketPct = meta.marketPct,
        pickSource = meta.pickSource?.label
    )

    private fun publish(next: PaperBookState) {
        _state.value = next
        runCatching { persist(next) }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "$%.2f", v)
}
