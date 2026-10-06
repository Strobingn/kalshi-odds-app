package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Isolated paper book. Never calls Kalshi. $1,000 start / Kelly-sized AI fills.
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
    val pickSource: String? = null,
    /** Full Kelly f at fill time. Null on pre-0.3.19 rows. */
    val kellyF: Double? = null,
    /** Settings Kelly fraction (0.1–1.0) used to size this fill. */
    val kellyFraction: Double? = null,
    /** Paper bankroll (start + settled P&L) after this fill settled. */
    val bankrollAfterUsd: Double? = null,
    /** Expected $ of this clip at fill time (`n × (p − ask − fee)`). */
    val evUsd: Double? = null,
    /**
     * Bumped when the fill is created and again when it settles.
     * 0 on pre-0.3.28 JSON — [syncAtMs] falls back to [createdAtMs].
     */
    val updatedAtMs: Long = 0L,
    /** Volatility bucket at fill time. Null on pre-0.3.32 rows. */
    val regimeVol: String? = null,
    /** favorite or underdog, from the filled ask. */
    val regimeRole: String? = null,
    /** Session tag or ET time-of-day bucket. */
    val regimeSession: String? = null,
    /** trend / chop / quiet / vol from the card regime tag. */
    val regimePath: String? = null,
    /** Distance-to-strike bucket. */
    val regimeStrike: String? = null
) {
    fun syncAtMs(): Long = if (updatedAtMs > 0L) updatedAtMs else createdAtMs
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
    val archived: List<PaperArchive> = emptyList(),
    /**
     * Lifetime settled paper P&L, independent of the truncated fill
     * ledger ([SignalConstants.PAPER_LEDGER_MAX]). Null on pre-0.3.19
     * JSON — [migrate] seeds it from the fills still on disk.
     */
    val lifetimeRealizedPnlUsd: Double? = null,
    /**
     * Fills evicted from the 80-row ledger, kept so cloud sync still
     * upserts them. Cleared after a successful push of those rows.
     */
    val syncTail: List<PaperFill> = emptyList(),
    /**
     * Every fill in the current book, including rows already pushed
     * and dropped from [syncTail]. The 80-row ledger is a window;
     * this list is the post-reset history the scorecard sums.
     * Cleared only by [PaperBook.reset], which moves it into [archived].
     *
     * 0.3.29 does not wipe on-device data. Scorecard dollars recompute
     * from this history (and ignore pre-reset [archived] ids that sync
     * tried to put back into [fills]).
     */
    val postResetFills: List<PaperFill> = emptyList()
) {
    val openStakeUsd: Double get() = fills.filter { !it.settled }.sumOf { it.stakeUsd }
    val realizedPnlUsd: Double get() = lifetimeRealizedPnlUsd ?: fills.mapNotNull { it.pnlUsd }.sum()
    /** Paper P&L for the live Bitcoin series only — stored ETH/SOL fills stay in the ledger. */
    val liveRealizedPnlUsd: Double
        get() = fills.filter { CryptoMarkets.isLiveTicker(it.ticker) }.mapNotNull { it.pnlUsd }.sum()
    val equityUsd: Double get() = cashUsd + openStakeUsd
    val openCount: Int get() = fills.count { !it.settled }
    /** Start + lifetime settled P&L — the Kelly bankroll shown on the scorecard. */
    val paperBankrollUsd: Double get() = startingUsd + realizedPnlUsd

    /** Live ledger + trimmed tail + reset archives, newest status per id. */
    fun fillsForSync(): List<PaperFill> {
        val all = fills + syncTail + postResetFills + archived.flatMap { it.fills }
        return all.groupBy { it.id }.map { (_, rows) -> rows.maxBy { it.syncAtMs() } }
    }

    /** Ids sitting in a reset archive. Sync must not put these back into the live book. */
    fun archivedFillIds(): Set<String> = archived.flatMap { it.fills }.map { it.id }.toSet()

    /**
     * Settled-and-open fills that belong to the current book: live
     * ledger, sync tail, and retained post-reset history, deduped by
     * id. Pre-reset archive ids are excluded even if a bad sync copied
     * them back into [fills].
     */
    fun scorecardFills(): List<PaperFill> = rememberFills(this)

    /** Pre-reset archive, deduped. Not part of [scorecardFills]. */
    fun archivedFills(): List<PaperFill> =
        archived.flatMap { it.fills }
            .groupBy { it.id }
            .map { (_, rows) -> rows.maxBy { it.syncAtMs() } }

    companion object {

        /**
         * Current-book fills, newest copy per id, minus anything that
         * already lives in a reset archive.
         */
        @JvmStatic
        fun rememberFills(state: PaperBookState): List<PaperFill> {
            val archivedIds = state.archivedFillIds()
            val pool = state.postResetFills + state.fills + state.syncTail
            return pool
                .filter { it.id !in archivedIds }
                .groupBy { it.id }
                .map { (_, rows) -> rows.maxBy { it.syncAtMs() } }
        }

        /** 0.3.19: $100 start → $1,000, preserving realized P&L. */
        @JvmStatic
        fun migrateStartUsd(state: PaperBookState): PaperBookState {
            val old = 100.0
            val neu = SignalConstants.PAPER_START_USD
            if (kotlin.math.abs(state.startingUsd - old) > 1e-6) return state
            val bump = neu - old
            return state.copy(startingUsd = neu, cashUsd = state.cashUsd + bump)
        }

        /**
         * Seed [lifetimeRealizedPnlUsd] from the current settled fills
         * when the counter is missing (pre-0.3.19 books).
         */
        @JvmStatic
        fun migrate(state: PaperBookState): PaperBookState {
            val afterStart = migrateStartUsd(state)
            if (afterStart.lifetimeRealizedPnlUsd != null) return afterStart
            return afterStart.copy(
                lifetimeRealizedPnlUsd = afterStart.fills.mapNotNull { it.pnlUsd }.sum()
            )
        }
    }
}

class PaperBook(
    initial: PaperBookState = PaperBookState(),
    private val persist: (PaperBookState) -> Unit = {},
    private val idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile var kellyFraction: Double = SignalConstants.DEFAULT_PAPER_KELLY_FRACTION
    @Volatile var feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    @Volatile var startUsd: Double = SignalConstants.PAPER_START_USD
    private val lock = Any()
    private val _state = MutableStateFlow(PaperBookState.migrate(initial))
    val state: StateFlow<PaperBookState> = _state.asStateFlow()

    fun snapshot(): PaperBookState = _state.value

    fun openTickers(): Set<String> = synchronized(lock) {
        _state.value.fills.filter { !it.settled }.map { it.ticker.uppercase() }.toSet()
    }

    fun configure(
        kellyFraction: Double = this.kellyFraction,
        feeRate: Double = this.feeRate,
        startUsd: Double = this.startUsd
    ) {
        this.kellyFraction = kellyFraction
        this.feeRate = feeRate
        this.startUsd = startUsd
    }

    fun reset(toUsd: Double = startUsd) {
        synchronized(lock) {
            val cur = _state.value
            val start = toUsd.takeIf { it.isFinite() && it > 0.0 } ?: SignalConstants.PAPER_START_USD
            val prior = PaperBookState.rememberFills(cur)
            val archive = PaperArchive(
                archivedAtMs = nowMs(),
                startingUsd = cur.startingUsd,
                cashUsd = cur.cashUsd,
                fills = prior.map { it.copy(updatedAtMs = nowMs()) },
                note = "Paper book reset — ledger archived"
            )
            publish(
                PaperBookState(
                    startingUsd = start,
                    cashUsd = start,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "Paper book reset to $%.0f — prior run archived",
                        start
                    ),
                    archived = cur.archived + archive,
                    lifetimeRealizedPnlUsd = 0.0
                )
            )
        }
    }

    fun hydrate(next: PaperBookState) {
        synchronized(lock) {
            _state.value = PaperBookState.migrate(next)
        }
    }

    /**
     * Auto-log a Kelly-sized paper fill when an AI hunter / configured ticket
     * would trade. Manual live tickets are ignored — those need an explicit Paper tap.
     */
    fun considerTicket(
        ticket: TradeTicket,
        enabled: Boolean,
        depthContracts: Int? = null
    ): PaperFill? {
        if (!enabled) return null
        if (!ticket.canApprove) return null
        if (ticket.kind == TicketKind.MANUAL || ticket.kind == TicketKind.SELL ||
            ticket.kind == TicketKind.LAST_MINUTE || ticket.kind == TicketKind.D3
        ) return null
        val model = ticket.modelChance
        val ask = KalshiPrice.usable(ticket.limitPrice)
        if (ask != null && ask + 1e-12 < com.dirk.kalshiodds.decision.TradeEligibility.LOTTERY_ASK) return null
        if (ask != null && ask + 1e-12 < com.dirk.kalshiodds.signal.flip.FlipCheck.CHEAP_ASK &&
            (model == null || model < com.dirk.kalshiodds.signal.flip.FlipCheck.CHEAP_FLIP_SUPPORT)
        ) {
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
            note = "Paper Kelly · ${ticket.kind.name.lowercase()} signal · never sent to Kalshi",
            winChance = model,
            depthContracts = depthContracts,
            winTargetUsd = null,
            meta = meta.copy(pickSource = pick)
        )
    }

    fun considerAlert(
        alert: SignalAlert,
        ask: Double?,
        enabled: Boolean,
        depthContracts: Int? = null
    ): PaperFill? {
        if (!enabled) return null
        if (SignalStance.isNoBetSide(alert.predictedSide)) return null
        val px = KalshiPrice.usable(ask) ?: return null
        if (px + 1e-12 < com.dirk.kalshiodds.decision.TradeEligibility.LOTTERY_ASK) return null
        val model = PaperFill.metaFromAlert(alert).aiPct?.div(100.0)
        if (px + 1e-12 < com.dirk.kalshiodds.signal.flip.FlipCheck.CHEAP_ASK &&
            (model == null || model < com.dirk.kalshiodds.signal.flip.FlipCheck.CHEAP_FLIP_SUPPORT)
        ) {
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
            winChance = model,
            depthContracts = depthContracts,
            meta = meta
        )
    }

    fun considerLastMinute(
        fired: com.dirk.kalshiodds.signal.lastminute.LastMinuteFired,
        enabled: Boolean,
        market: MarketUiModel? = null,
        book: BookLevelSnapshot? = null
    ): PaperFill? {
        if (!enabled) return null
        val ctx = com.dirk.kalshiodds.signal.trade.TicketBuilder.Context(
            settings = com.dirk.kalshiodds.signal.config.SignalSettings(),
            alertsPaused = false,
            books = book?.let { mapOf(fired.ticker to it) } ?: emptyMap()
        )
        val liveAsk = market?.let {
            com.dirk.kalshiodds.signal.trade.TicketBuilder.liveAsk(it, fired.side, ctx)
        } ?: com.dirk.kalshiodds.signal.trade.TicketBuilder.bookAskOrNull(fired.side, book)
        if (book != null && !book.isEmpty() && liveAsk == null) {
            rememberMessage("Paper skip ${fired.ticker} — live book has no sellers")
            return null
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
        val px = liveAsk ?: fired.ask
        val meta = PaperFillMeta(
            aiPct = PaperFill.pctFromUnit(fired.winChance),
            aiConfidence = null,
            marketPct = PaperFill.pctFromUnit(px),
            pickSource = PaperPickSource.LAST_MINUTE
        )
        if (!PaperFill.allowCreate(PaperPickSource.LAST_MINUTE, meta.aiPct)) return null
        val depth = PaperAskDepth.contracts(fired.side, px, book, market)
            ?: fired.depthContracts
        return fill(
            ticker = fired.ticker,
            side = fired.side,
            limitPrice = px,
            source = PaperPickSource.LAST_MINUTE.label,
            note = "Last-minute strategy · Kelly paper · never sent to Kalshi",
            winChance = fired.winChance,
            depthContracts = depth,
            meta = meta
        )
    }

    /**
     * Autopilot paper fill. Allows more than one open clip on the same
     * ticker (re-entry guard lives in [PaperAutopilot]). Never hits Kalshi.
     */
    fun considerAutopilot(
        ticker: String,
        side: String,
        ask: Double,
        winProb: Double,
        depthContracts: Int?,
        evPerContract: Double? = null,
        enabled: Boolean,
        bankrollUsd: Double? = null,
        maxStakeUsd: Double? = null,
        regime: AutopilotRegime.Tags? = null
    ): PaperFill? {
        if (!enabled) return null
        val px = KalshiPrice.usable(ask) ?: return null
        val p = winProb.takeIf { it.isFinite() } ?: return null
        val meta = PaperFillMeta(
            aiPct = PaperFill.pctFromUnit(p),
            marketPct = PaperFill.pctFromUnit(px),
            pickSource = PaperPickSource.AUTOPILOT,
            evUsd = evPerContract,
            regimeVol = regime?.vol,
            regimeRole = regime?.role,
            regimeSession = regime?.session,
            regimePath = regime?.path,
            regimeStrike = regime?.strike
        )
        if (!PaperFill.allowCreate(PaperPickSource.AUTOPILOT, meta.aiPct)) return null
        return fill(
            ticker = ticker,
            side = side,
            limitPrice = px,
            source = PaperAutopilot.SOURCE,
            note = "AI paper autopilot · Kelly · never sent to Kalshi",
            winChance = p,
            depthContracts = depthContracts,
            meta = meta,
            allowMultipleOpen = true,
            bankrollUsd = bankrollUsd,
            maxStakeUsd = maxStakeUsd
        )
    }

    /**
     * Cloud sync upsert. Updates settled status / P&L / AI fields on a
     * row we already have, or inserts a fill this phone has not seen.
     */
    fun upsertFromSync(incoming: PaperFill) {
        synchronized(lock) {
            val cur = _state.value
            if (incoming.id in cur.archivedFillIds()) return
            val idx = cur.fills.indexOfFirst { it.id == incoming.id }
            if (idx >= 0) {
                val merged = mergeSyncFill(cur.fills[idx], incoming)
                if (merged == cur.fills[idx]) return
                val next = cur.fills.toMutableList()
                next[idx] = merged
                publish(cur.copy(fills = next))
                return
            }
            val tailIdx = cur.syncTail.indexOfFirst { it.id == incoming.id }
            if (tailIdx >= 0) {
                val merged = mergeSyncFill(cur.syncTail[tailIdx], incoming)
                if (merged == cur.syncTail[tailIdx]) return
                val next = cur.syncTail.toMutableList()
                next[tailIdx] = merged
                publish(cur.copy(syncTail = next))
                return
            }
            val keptIdx = cur.postResetFills.indexOfFirst { it.id == incoming.id }
            if (keptIdx >= 0) {
                val merged = mergeSyncFill(cur.postResetFills[keptIdx], incoming)
                if (merged == cur.postResetFills[keptIdx]) return
                val next = cur.postResetFills.toMutableList()
                next[keptIdx] = merged
                publish(cur.copy(postResetFills = next))
                return
            }
            val (kept, tail) = retainLedger(cur, listOf(incoming) + cur.fills)
            publish(cur.copy(fills = kept, syncTail = tail))
        }
    }

    /** Drop tail rows whose sync timestamp is at or before a successful push. */
    fun acknowledgePaperSync(beforeMs: Long) {
        synchronized(lock) {
            val cur = _state.value
            val remembered = PaperBookState.rememberFills(cur)
            val next = cur.syncTail.filter { it.syncAtMs() > beforeMs }
            if (next.size == cur.syncTail.size && remembered == cur.postResetFills) return
            publish(cur.copy(postResetFills = remembered, syncTail = next))
        }
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
        // Fee on every paper fill: all-in cost = contracts × price + Kalshi fee rounded up.
        val stake = com.dirk.kalshiodds.signal.trade.KalshiFee.totalCost(qty, px, feeRate)
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
            val (fills, tail) = retainLedger(cur, listOf(row) + cur.fills)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - stake,
                    fills = fills,
                    syncTail = tail,
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
            val (fills, tail) = retainLedger(cur, listOf(row) + cur.fills)
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
                    syncTail = tail,
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
            val (fills, tail) = retainLedger(cur, listOf(row) + cur.fills)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - allInUsd,
                    fills = fills,
                    syncTail = tail,
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
                updatedAtMs = nowMs(),
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
            }
            val (keptSells, tail) = retainLedger(cur, nextFills)
            val lifetime = nextLifetime(cur, pnl)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd + proceeds,
                    fills = keptSells,
                    syncTail = tail,
                    lifetimeRealizedPnlUsd = lifetime,
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
                val pnl = com.dirk.kalshiodds.decision.HonestScorecard.pnlAfterFee(
                    contracts = fill.contracts,
                    price = fill.limitPrice,
                    stakeUsd = fill.stakeUsd,
                    won = won,
                    voided = outcome == "void",
                    feeRate = feeRate
                )
                // Legacy fills recorded without the fee pay it now so cash matches P&L.
                val unpaidFee = if (outcome == "void") 0.0 else ((payout - pnl) - fill.stakeUsd).coerceAtLeast(0.0)
                cash += payout - unpaidFee
                fill.copy(
                    settled = true,
                    outcome = outcome,
                    won = won,
                    pnlUsd = pnl,
                    updatedAtMs = nowMs()
                ).also { changed += it }
            }
            if (changed.isEmpty()) return emptyList()
            val added = changed.sumOf { it.pnlUsd ?: 0.0 }
            val lifetime = nextLifetime(cur, added)
            val bankrollAfter = cur.startingUsd + lifetime
            val stamped = nextFills.map { fill ->
                val match = changed.firstOrNull { it.id == fill.id } ?: return@map fill
                fill.copy(bankrollAfterUsd = bankrollAfter)
            }
            val stampedChanged = changed.map { it.copy(bankrollAfterUsd = bankrollAfter) }
            changed.clear()
            changed.addAll(stampedChanged)
            val msg = stampedChanged.last().let { f ->
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
            publish(
                cur.copy(
                    cashUsd = cash,
                    fills = stamped,
                    lastMessage = msg,
                    lifetimeRealizedPnlUsd = lifetime
                )
            )
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
        winChance: Double? = null,
        depthContracts: Int? = null,
        winTargetUsd: Double? = null,
        meta: PaperFillMeta = PaperFillMeta(),
        allowMultipleOpen: Boolean = false,
        bankrollUsd: Double? = null,
        maxStakeUsd: Double? = null
    ): PaperFill? {
        if (CryptoMarkets.isRetiredTicker(ticker)) return null
        val want = if (side.equals("NO", true)) "NO" else "YES"
        val px = KalshiPrice.usable(limitPrice) ?: return null
        val p = winChance ?: meta.aiPct?.let { if (it <= 1.0 + 1e-9) it else it / 100.0 }
        synchronized(lock) {
            val cur = _state.value
            if (!allowMultipleOpen &&
                cur.fills.any { !it.settled && it.ticker.equals(ticker, ignoreCase = true) }
            ) {
                return null
            }
            val bankroll = bankrollUsd?.takeIf { it.isFinite() && it > 0.0 }
                ?: cur.paperBankrollUsd.coerceAtLeast(cur.cashUsd)
            val sized = PaperKellySizer.size(
                winProb = p,
                ask = px,
                bankrollUsd = bankroll,
                kellyFraction = kellyFraction,
                feeRate = feeRate,
                depthContracts = depthContracts,
                maxStakeUsd = maxStakeUsd
            )
            if (!sized.ok) {
                publish(cur.copy(lastMessage = sized.reason ?: "Paper skip $ticker — Kelly ≤ 0"))
                return null
            }
            val (useQty, _) = PaperBuy.capContracts(
                want = sized.contracts,
                cashUsd = cur.cashUsd,
                price = px,
                feeRate = feeRate
            )
            if (useQty < 1) {
                publish(
                    cur.copy(
                        lastMessage = "Paper skip $ticker — cash ${fmt(cur.cashUsd)} cannot cover Kelly size"
                    )
                )
                return null
            }
            val allIn = KalshiFee.totalCost(useQty, px, feeRate)
            val fee = KalshiFee.total(useQty, px, feeRate)
            val row = newFill(
                ticker = ticker,
                side = want,
                stakeUsd = allIn,
                contracts = useQty,
                limitPrice = px,
                source = source,
                note = buildString {
                    append(note)
                    append(String.format(java.util.Locale.US, " · fee $%.2f", fee))
                    append(String.format(java.util.Locale.US, " · Kelly f=%.3f × %.2f", sized.kellyF, sized.kellyFraction))
                },
                winTargetUsd = winTargetUsd,
                meta = meta,
                kellyF = sized.kellyF,
                kellyFraction = sized.kellyFraction,
                evUsd = (meta.evUsd ?: p?.let { win ->
                    win * SignalConstants.CONTRACT_SETTLEMENT_USD - sized.costPerContract
                })?.let { it * useQty }
            )
            val (fills, tail) = retainLedger(cur, listOf(row) + cur.fills)
            publish(
                cur.copy(
                    cashUsd = cur.cashUsd - allIn,
                    fills = fills,
                    syncTail = tail,
                    lastMessage = String.format(
                        java.util.Locale.US,
                        "PAPER %s %s · $%.2f · %d ct @ %.0f¢ · Kelly f=%.3f · %s",
                        row.displaySide,
                        row.ticker,
                        row.stakeUsd,
                        row.contracts,
                        row.limitPrice * 100,
                        sized.kellyF,
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
        meta: PaperFillMeta = PaperFillMeta(),
        kellyF: Double? = null,
        kellyFraction: Double? = null,
        bankrollAfterUsd: Double? = null,
        evUsd: Double? = null
    ): PaperFill {
        val at = nowMs()
        return PaperFill(
        id = idFactory(),
        ticker = ticker,
        side = side,
        stakeUsd = stakeUsd,
        contracts = contracts,
        limitPrice = limitPrice,
        source = source,
        createdAtMs = at,
        updatedAtMs = at,
        note = note,
        winTargetUsd = winTargetUsd,
        aiPct = meta.aiPct,
        aiConfidence = meta.aiConfidence,
        marketPct = meta.marketPct,
        pickSource = meta.pickSource?.label,
        kellyF = kellyF,
        kellyFraction = kellyFraction,
        bankrollAfterUsd = bankrollAfterUsd,
        evUsd = evUsd ?: meta.evUsd,
        regimeVol = meta.regimeVol,
        regimeRole = meta.regimeRole,
        regimeSession = meta.regimeSession,
        regimePath = meta.regimePath,
        regimeStrike = meta.regimeStrike
    )
    }

    private fun retainLedger(
        cur: PaperBookState,
        newestFirst: List<PaperFill>
    ): Pair<List<PaperFill>, List<PaperFill>> {
        val kept = newestFirst.take(SignalConstants.PAPER_LEDGER_MAX)
        val at = nowMs()
        val overflow = newestFirst.drop(SignalConstants.PAPER_LEDGER_MAX).map { row ->
            if (row.updatedAtMs >= at) row else row.copy(updatedAtMs = at)
        }
        return kept to mergeSyncTail(cur.syncTail, overflow)
    }

    private fun mergeSyncTail(tail: List<PaperFill>, extra: List<PaperFill>): List<PaperFill> {
        if (extra.isEmpty()) return tail
        val byId = LinkedHashMap<String, PaperFill>()
        (extra + tail).forEach { row ->
            val prev = byId[row.id]
            if (prev == null || row.syncAtMs() >= prev.syncAtMs()) byId[row.id] = row
        }
        return byId.values.toList()
    }

    private fun mergeSyncFill(local: PaperFill, incoming: PaperFill): PaperFill {
        if (incoming.syncAtMs() < local.syncAtMs()) return local
        return local.copy(
            settled = incoming.settled || local.settled,
            outcome = incoming.outcome ?: local.outcome,
            won = incoming.won ?: local.won,
            pnlUsd = incoming.pnlUsd ?: local.pnlUsd,
            source = incoming.source.ifBlank { local.source },
            aiPct = incoming.aiPct ?: local.aiPct,
            aiConfidence = incoming.aiConfidence ?: local.aiConfidence,
            marketPct = incoming.marketPct ?: local.marketPct,
            pickSource = incoming.pickSource ?: local.pickSource,
            regimeVol = incoming.regimeVol ?: local.regimeVol,
            regimeRole = incoming.regimeRole ?: local.regimeRole,
            regimeSession = incoming.regimeSession ?: local.regimeSession,
            regimePath = incoming.regimePath ?: local.regimePath,
            regimeStrike = incoming.regimeStrike ?: local.regimeStrike,
            updatedAtMs = incoming.syncAtMs()
        )
    }

    private fun nextLifetime(cur: PaperBookState, addedPnl: Double): Double {
        val prev = cur.lifetimeRealizedPnlUsd ?: cur.fills.mapNotNull { it.pnlUsd }.sum()
        return prev + addedPnl
    }

    private fun publish(next: PaperBookState) {
        val remembered = PaperBookState.rememberFills(next)
        val stamped = if (remembered == next.postResetFills) next else next.copy(postResetFills = remembered)
        _state.value = stamped
        runCatching { persist(stamped) }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "$%.2f", v)
}
