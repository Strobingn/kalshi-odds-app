package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the UI needs to render the scalper panel. */
data class ScalpUiState(
    val enabled: Boolean = false,
    val liveMode: Boolean = false,
    val running: Boolean = false,
    /** FLAT or IN_POSITION. */
    val state: String = "FLAT",
    val openPosition: ScalpPosition? = null,
    val lastMidPp: Double? = null,
    val lastEvent: ScalpEvent? = null
)

sealed class ScalpEvent {
    data class Entered(val position: ScalpPosition) : ScalpEvent()

    data class Exited(
        val position: ScalpPosition,
        val pnlCents: Int,
        val reason: ExitReason
    ) : ScalpEvent()

    data class GuardrailBlocked(val reason: String) : ScalpEvent()

    data class Error(val message: String) : ScalpEvent()
}

/**
 * DipHunter scalper engine — "buy the dip, sell the bounce".
 *
 * The ONE non-approve-gated trading path in the app. Hard rules baked in:
 * - never trades unless `settings.enabled && !settings.killSwitch`
 * - paper by default (`!settings.liveMode`)
 * - guardrails checked BEFORE every entry ([ScalpGuardrails])
 * - max one open position, debounce between round trips
 * - every soft failure becomes a [ScalpEvent], never a thrown exception
 *
 * The wiring agent feeds ticks via [onTick] (any thread) and lifecycle via
 * [start]/[stop]. Book quotes come from [bookProvider]; when the book is
 * null or one-sided the engine falls back to the tick's yesBid/yesAsk so a
 * REST-only session still works.
 */
class ScalpEngine(
    private val settings: Flow<ScalpSettings>,
    private val bookProvider: () -> LocalOrderBook?,
    private val paperExecutor: ScalpExecutor,
    private val liveExecutor: ScalpExecutor,
    private val store: ScalpLedger,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val onEvent: (ScalpEvent) -> Unit = {},
    private val feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
    private val maxSpreadCents: Int = 3,
    /** Min milliseconds between the end of one round trip and the next entry. */
    private val debounceMs: Long = 15_000L,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    @Volatile
    private var settingsSnapshot: ScalpSettings = ScalpSettings()

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var lastMidPp: Double? = null

    @Volatile
    private var lastEvent: ScalpEvent? = null

    @Volatile
    private var lastTradeEndMs: Long = 0L

    @Volatile
    private var lastGuardrailEventMs: Long = 0L

    private var math = ScalpMath(windowSeconds = ScalpSettings().windowSeconds)

    private var settingsJob: Job? = null

    /** Feed one market tick. Safe from any thread; never blocks, never throws. */
    fun onTick(tick: MarketTick) {
        runCatching {
            val s = settingsSnapshot
            val mid = tick.midPp
            if (mid != null) {
                if (math.windowSeconds != s.windowSeconds) {
                    math = ScalpMath(windowSeconds = s.windowSeconds)
                }
                math.onPrice(mid, now())
                lastMidPp = mid
            }
            if (!s.enabled || s.killSwitch) return
            if (!running) return

            val position = store.openPosition()
            if (position == null && now() - lastTradeEndMs < debounceMs) return

            val book = bookProvider()
            val bidCents = bookQuote(book?.bestYesBid()) ?: tickQuote(tick.yesBid)
            val askCents = bookQuote(book?.bestYesAsk()) ?: tickQuote(tick.yesAsk)
            if (bidCents == null || askCents == null) return

            val features = math.snapshot(now())
            val imbalance = book?.imbalance()
            val decider = deciderFor(s)
            val decision = decider.decide(
                features = features,
                bestBidCents = bidCents,
                bestAskCents = askCents,
                imbalance = imbalance,
                position = position?.let {
                    ScalpDecision.OpenPosition(it.entryPriceCents, it.entryTimeMs)
                },
                nowMs = now()
            )
            when (decision) {
                is ScalpDecision.ScalpDecision.Enter ->
                    scope.launch { enter(s, tick.ticker, decision.entryPriceCents) }
                is ScalpDecision.ScalpDecision.Exit ->
                    position?.let { pos ->
                        scope.launch { exit(s, pos, decision.reason, decision.exitPriceCents) }
                    }
                else -> Unit
            }
        }.onFailure { emit(ScalpEvent.Error("tick failed: ${it.message ?: it.javaClass.simpleName}")) }
    }

    /** Begin collecting settings and restore the ledger from disk. */
    suspend fun start() {
        store.hydrateFromDisk()
        settingsSnapshot = runCatching { settings.first() }.getOrElse { ScalpSettings() }
        running = true
        settingsJob?.cancel()
        settingsJob = scope.launch {
            runCatching {
                settings.collect { settingsSnapshot = it }
            }
        }
    }

    fun stop() {
        running = false
        settingsJob?.cancel()
        settingsJob = null
    }

    /** Synchronous state snapshot for UI. */
    fun currentState(): ScalpUiState {
        val s = settingsSnapshot
        val open = store.openPosition()
        return ScalpUiState(
            enabled = s.enabled,
            liveMode = s.liveMode,
            running = running,
            state = if (open == null) "FLAT" else "IN_POSITION",
            openPosition = open,
            lastMidPp = lastMidPp,
            lastEvent = lastEvent
        )
    }

    // ---- internals ----------------------------------------------------------

    private fun deciderFor(s: ScalpSettings): ScalpDecision = ScalpDecision(
        dipMinDropPp = s.dipMinDropPp,
        takeProfitPp = s.takeProfitPp,
        stopLossPp = s.stopLossPp,
        maxHoldMs = s.maxHoldMs,
        maxSpreadCents = maxSpreadCents
    )

    private suspend fun enter(settings: ScalpSettings, ticker: String, askCents: Int) {
        if (store.openPosition() != null) return
        val block = ScalpGuardrails.checkEnter(
            ScalpGuardrails.snapshot(store, settings, now()),
            settings
        )
        if (block != null) {
            // Throttle guardrail spam: at most one event per 60s.
            if (now() - lastGuardrailEventMs >= 60_000L) {
                lastGuardrailEventMs = now()
                emit(ScalpEvent.GuardrailBlocked(block))
            }
            return
        }
        val ask01 = askCents / 100.0
        val contracts = KalshiFee.contractsForStake(settings.maxStakeUsd, ask01)
        if (contracts < 1) {
            emit(ScalpEvent.Error("scalp entry skipped — $${settings.maxStakeUsd} cannot buy 1 ct @ ${askCents}c"))
            return
        }
        val position = ScalpPosition(
            id = idFactory(),
            ticker = ticker,
            side = "YES",
            entryPriceCents = askCents,
            contracts = contracts,
            entryTimeMs = now(),
            mode = if (settings.liveMode) ScalpMode.LIVE else ScalpMode.PAPER
        )
        val executor = if (settings.liveMode) liveExecutor else paperExecutor
        val ok = runCatching { executor.enter(position) }
            .onFailure { emit(ScalpEvent.Error("scalp entry failed: ${safeMsg(it)}")) }
            .getOrDefault(false)
        if (!ok) {
            emit(ScalpEvent.Error("scalp entry not filled @ ${askCents}c — will retry on next setup"))
            return
        }
        val entryFeeCents = feeCents(contracts, ask01)
        val stored = store.recordEnter(position, entryFeeCents, clientOrderId = null)
        lastEvent = ScalpEvent.Entered(stored)
        onEvent(ScalpEvent.Entered(stored))
    }

    private suspend fun exit(
        settings: ScalpSettings,
        position: ScalpPosition,
        reason: ExitReason,
        bidCents: Int
    ) {
        val executor = if (settings.liveMode) liveExecutor else paperExecutor
        val withExit = position.copy(exitPriceCents = bidCents)
        val fill = runCatching { executor.exit(withExit, reason) }
            .onFailure { emit(ScalpEvent.Error("scalp exit failed: ${safeMsg(it)}")) }
            .getOrNull()
        if (fill == null || fill.filledContracts < 1) return // retry next tick
        val entry01 = position.entryPriceCents / 100.0
        val exit01 = fill.avgPriceCents / 100.0
        val n = fill.filledContracts
        val exitFeeCents = fill.feeCents
        val entryFeeCents = feeCents(n, entry01)
        val costCents = n * position.entryPriceCents + entryFeeCents
        val proceedsCents = n * fill.avgPriceCents - exitFeeCents
        val pnlCents = proceedsCents - costCents
        store.recordExit(
            positionId = position.id,
            exitPriceCents = fill.avgPriceCents,
            exitTimeMs = now(),
            reason = reason,
            pnlCents = pnlCents,
            exitFeeCents = exitFeeCents
        )
        lastTradeEndMs = now()
        val closed = position.copy(
            status = ScalpPositionStatus.CLOSED,
            exitPriceCents = fill.avgPriceCents,
            exitTimeMs = now(),
            exitReason = reason,
            pnlCents = pnlCents,
            entryFeeCents = entryFeeCents,
            exitFeeCents = exitFeeCents
        )
        emit(ScalpEvent.Exited(closed, pnlCents, reason))
    }

    private fun bookQuote(price01: Double?): Int? =
        price01?.takeIf { it.isFinite() && it in 0.01..0.99 }
            ?.let { kotlin.math.round(it * 100.0).toInt() }

    private fun tickQuote(price01: Double?): Int? =
        price01?.takeIf { it.isFinite() && it in 0.01..0.99 }
            ?.let { kotlin.math.round(it * 100.0).toInt() }

    private fun feeCents(contracts: Int, price01: Double): Int =
        kotlin.math.round(KalshiFee.total(contracts, price01, feeRate) * 100.0).toInt()

    private fun emit(event: ScalpEvent) {
        lastEvent = event
        runCatching { onEvent(event) }
    }

    private fun safeMsg(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
