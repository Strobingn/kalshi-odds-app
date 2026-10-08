package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.ScalpExit
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** One simulated fill. Never sent to Kalshi. */
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
    /** Taker fee debited with the stake. Old ledgers load as 0. */
    val feeUsd: Double = 0.0,
    val note: String,
    val winTargetUsd: Double? = null,
    /** Highest bid seen since the fill. 0 means not tracked yet. */
    val peakBid: Double = 0.0
) {
    val displaySide: String get() = side.uppercase()
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

/**
 * Isolated paper book. Never calls Kalshi.
 * The AI buys under 50¢. A cheaper ask gets a larger share of the cash.
 * It holds while the bid is rising and sells when the bid comes off the high.
 */
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
     * Auto-log a paper fill when an AI ticket would trade under 50¢.
     * Cheaper asks are sized larger. Manual tickets need an explicit Paper tap.
     * The exit is a sale once the bid comes off its high, not settlement.
     */
    fun considerTicket(ticket: TradeTicket, enabled: Boolean): PaperFill? {
        if (!enabled) return null
        if (!ticket.canApprove) return null
        if (ticket.kind == TicketKind.MANUAL || ticket.kind == TicketKind.SELL) return null
        if (!ScalpExit.isLowPrice(ticket.limitPrice)) return null
        val source = if (ticket.kind == TicketKind.HUNTER) "AI hunter" else "AI ticket"
        return fill(
            ticker = ticket.ticker,
            side = ticket.side,
            limitPrice = ticket.limitPrice,
            source = source,
            note = if (ticket.winTargetUsd != null) {
                "Paper win-target · ${ticket.kind.name.lowercase()} · hold until the bid rolls over · never sent to Kalshi"
            } else {
                "Paper fill · ${ticket.kind.name.lowercase()} signal · hold until the bid rolls over · never sent to Kalshi"
            },
            contracts = ticket.contracts.takeIf { ticket.winTargetUsd != null && it > 0 },
            stakeUsd = ticket.stakeUsd.takeIf { ticket.winTargetUsd != null && it > 0.0 },
            winTargetUsd = ticket.winTargetUsd
        )
    }

    fun considerAlert(alert: SignalAlert, ask: Double?, enabled: Boolean): PaperFill? {
        if (!enabled) return null
        if (SignalStance.isNoBetSide(alert.predictedSide)) return null
        val px = KalshiPrice.usable(ask) ?: return null
        if (!ScalpExit.isLowPrice(px)) return null
        return fill(
            ticker = alert.ticker,
            side = alert.predictedSide,
            limitPrice = px,
            source = "AI signal",
            note = alert.reason.ifBlank { "LiveCall / grok-bitcoin signal" },
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
        winTargetUsd: Double? = null
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
        val fees = com.dirk.kalshiodds.signal.trade.KalshiFee.total(qty, px)
        synchronized(lock) {
            val cur = _state.value
            if (cur.cashUsd + 1e-9 < stake + fees) {
                rememberMessage("Paper skip $ticker — need ${fmt(stake + fees)} with fees (cash ${fmt(cur.cashUsd)})")
                return null
            }
            val row = PaperFill(
                id = idFactory(),
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = qty,
                limitPrice = px,
                source = source,
                createdAtMs = nowMs(),
                feeUsd = fees,
                note = note,
                winTargetUsd = winTargetUsd
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake - fees,
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
            winTargetUsd = ticket.winTargetUsd
        )
    }

    fun explicitFill(
        ticker: String,
        side: String,
        limitPrice: Double,
        wantContracts: Int,
        source: String,
        note: String,
        winTargetUsd: Double? = null
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
            val row = PaperFill(
                id = idFactory(),
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = qty,
                limitPrice = px,
                source = source,
                createdAtMs = nowMs(),
                feeUsd = fees,
                note = buildString {
                    append(note)
                    if (capped) append(" · capped to paper cash")
                    if (fees > 0.0) append(String.format(java.util.Locale.US, " · fee $%.2f", fees))
                },
                winTargetUsd = winTargetUsd
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
        message: String
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
            val row = PaperFill(
                id = idFactory(),
                ticker = ticker,
                side = want,
                stakeUsd = allInUsd,
                contracts = qty,
                limitPrice = px,
                source = source,
                createdAtMs = nowMs(),
                note = String.format(
                    java.util.Locale.US,
                    "Paper tile $10 · %d ct @ %.1f¢ · fee $%.2f · never sent to Kalshi",
                    qty,
                    px * 100,
                    feeUsd
                )
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

    /**
     * Hold an open AI fill while the bid is making highs. Sell when the bid
     * falls 3¢ off that high and the sale still clears both taker fees.
     * [bids] is keyed `TICKER|SIDE` (uppercase). Manual and tile fills stay open.
     * Never hits Kalshi. A fill that never rises can still settle later.
     */
    fun exitIfRisen(bids: Map<String, Double>): List<PaperFill> {
        synchronized(lock) {
            val cur = _state.value
            var cash = cur.cashUsd
            val sold = mutableListOf<PaperFill>()
            var lastMsg: String? = null
            var changed = false
            val next = ArrayList<PaperFill>(cur.fills.size)
            for (fill in cur.fills) {
                if (fill.settled || !ScorecardLedger.isAiSource(fill.source)) {
                    next += fill
                    continue
                }
                val prevPeak = if (fill.peakBid > 0.0) fill.peakBid else fill.limitPrice
                val bid = KalshiPrice.usable(
                    bids["${fill.ticker.uppercase()}|${fill.side.uppercase()}"]
                )
                if (bid == null) {
                    next += fill
                    continue
                }
                if (ScalpExit.shouldSell(fill.limitPrice, bid, prevPeak, fill.contracts, fill.feeUsd)) {
                    val sellFee = KalshiFee.total(fill.contracts, bid)
                    val proceeds = fill.contracts * bid
                    val pnl = proceeds - sellFee - fill.stakeUsd - fill.feeUsd
                    cash += proceeds - sellFee
                    val closed = fill.copy(
                        settled = true,
                        outcome = "sell",
                        won = pnl > 0.0,
                        pnlUsd = pnl,
                        peakBid = maxOf(prevPeak, bid),
                        note = String.format(
                            java.util.Locale.US,
                            "Sold %d ct @ %.1f¢ — bid came off the high, did not wait for settlement",
                            fill.contracts,
                            bid * 100.0
                        )
                    )
                    sold += closed
                    lastMsg = String.format(
                        java.util.Locale.US,
                        "PAPER SELL %s %s · %d ct @ %.0f¢ · %+.2f · sold the rollover, not settlement",
                        closed.displaySide,
                        closed.ticker,
                        closed.contracts,
                        bid * 100.0,
                        pnl
                    )
                    next += closed
                    continue
                }
                val high = maxOf(prevPeak, bid)
                if (high > prevPeak + 1e-9) {
                    next += fill.copy(peakBid = high)
                    changed = true
                } else {
                    next += fill
                }
            }
            if (sold.isEmpty() && !changed) return emptyList()
            publish(
                cur.copy(
                    cashUsd = cash,
                    fills = next,
                    lastMessage = lastMsg ?: cur.lastMessage
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
                val costBack = if (outcome == "void") fill.stakeUsd + fill.feeUsd else 0.0
                val payout = when {
                    outcome == "void" -> costBack
                    won == true -> fill.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD
                    else -> 0.0
                }
                // Void refunds stake and fee into payout, so both come back out of pnl.
                val pnl = payout - fill.stakeUsd - fill.feeUsd
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
        winProb: Double? = null
    ): PaperFill? {
        if (CryptoMarkets.isRetiredTicker(ticker)) return null
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(limitPrice) ?: return null
        synchronized(lock) {
            val cur = _state.value
            if (cur.fills.any { !it.settled && it.ticker.equals(ticker, ignoreCase = true) }) return null
            // A win-target ticket brings its own size. Otherwise a cheaper ask
            // takes a larger share of paper cash. Quarter-Kelly remains only
            // when a caller passes a win probability and no contract count.
            val cheapQty = if (contracts == null && winProb == null) {
                ScalpExit.contractsFor(px, cur.cashUsd)
            } else {
                0
            }
            val edgeQty = if (contracts == null && winProb != null) PaperSizer.contracts(cur.cashUsd, px, winProb) else 0
            val qty = when {
                contracts != null && contracts > 0 -> contracts
                winProb != null -> edgeQty
                else -> cheapQty
            }
            if (winProb != null && contracts == null && edgeQty < 1) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — no edge after the taker fee"))
                return null
            }
            val sizedNote = when {
                edgeQty > 0 && cur.cashUsd > 0.0 -> String.format(
                    java.util.Locale.US,
                    " · quarter-Kelly %.0f%% of paper cash",
                    edgeQty * px / cur.cashUsd * 100.0
                )
                cheapQty > 0 && cur.cashUsd > 0.0 -> String.format(
                    java.util.Locale.US,
                    " · %.0f%% of paper cash · lower price, larger buy",
                    cheapQty * px / cur.cashUsd * 100.0
                )
                else -> ""
            }
            if (qty < 1) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — cannot size a buy under 50¢"))
                return null
            }
            val rawStake = stakeUsd?.takeIf { it > 0.0 } ?: (qty * px)
            val (cappedQty, _) = PaperBuy.capContracts(
                want = qty,
                cashUsd = cur.cashUsd,
                price = px
            )
            val useQty = if (rawStake > cur.cashUsd + 1e-9) cappedQty else qty
            if (useQty < 1) {
                publish(cur.copy(lastMessage = "Paper skip $ticker — cash ${fmt(cur.cashUsd)} cannot cover ${fmt(rawStake)}"))
                return null
            }
            val stake = useQty * px
            val fees = com.dirk.kalshiodds.signal.trade.KalshiFee.total(useQty, px)
            val row = PaperFill(
                id = idFactory(),
                ticker = ticker,
                side = want,
                stakeUsd = stake,
                contracts = useQty,
                limitPrice = px,
                source = source,
                createdAtMs = nowMs(),
                feeUsd = fees,
                note = note + sizedNote,
                winTargetUsd = winTargetUsd
            )
            val fills = (listOf(row) + cur.fills).take(SignalConstants.PAPER_LEDGER_MAX)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake - fees,
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

    private fun publish(next: PaperBookState) {
        _state.value = next
        runCatching { persist(next) }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "$%.2f", v)
}
