package com.dirk.kalshiodds.signal.lastminute

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot

/**
 * Pure last-minute taker rule. One evaluation, no I/O, no orders.
 *
 * First qualifying side in a tick wins; if both qualify, higher EV/$ wins.
 * The engine enforces one fire per window.
 */
object LastMinuteStrategy {

    data class Inputs(
        val ticker: String,
        val tauSec: Int,
        val x: Double,
        val obsMean: Double,
        val sigS: Double,
        val upAsk: Double?,
        val downAsk: Double?,
        val book: BookLevelSnapshot? = null,
        val upQuotedSize: Double? = null,
        val downQuotedSize: Double? = null,
        val stakeUsd: Double = LastMinuteConstants.MAX_STAKE_USD,
        val alreadyFired: Boolean = false,
        val nowMs: Long = 0L,
        val startsInMs: Long? = null,
        val windowClosed: Boolean = false,
        val spotUsd: Double? = null,
        val strikeUsd: Double? = null,
        val spotSource: String = "none"
    )

    fun evaluate(input: Inputs): LastMinuteSnapshot {
        if (input.alreadyFired) {
            return LastMinuteSnapshot(
                phase = LastMinutePhase.FIRED,
                tauSec = input.tauSec.takeIf { it > 0 },
                startsInMs = null,
                pUp = null,
                fired = null,
                spotUsd = input.spotUsd,
                strikeUsd = input.strikeUsd,
                spotSource = input.spotSource,
                x = input.x,
                obsMean = input.obsMean,
                sigS = input.sigS
            )
        }
        if (input.windowClosed || input.tauSec <= 0) {
            return LastMinuteSnapshot(
                phase = LastMinutePhase.NO_PLAY,
                tauSec = input.tauSec.coerceAtLeast(0),
                startsInMs = null,
                spotUsd = input.spotUsd,
                strikeUsd = input.strikeUsd,
                spotSource = input.spotSource
            )
        }
        if (input.tauSec > LastMinuteConstants.FINAL_MINUTE_SEC) {
            return LastMinuteSnapshot(
                phase = LastMinutePhase.WAITING,
                tauSec = input.tauSec,
                startsInMs = input.startsInMs
                    ?: ((input.tauSec - LastMinuteConstants.FINAL_MINUTE_SEC).toLong() * 1000L),
                spotUsd = input.spotUsd,
                strikeUsd = input.strikeUsd,
                spotSource = input.spotSource,
                x = input.x,
                obsMean = 0.0,
                sigS = input.sigS
            )
        }
        val pUp = LastMinuteMath.fairP(input.x, input.tauSec.toDouble(), input.obsMean, input.sigS)
        val up = quoteSide(
            side = "YES",
            winChance = pUp,
            ask = input.upAsk,
            book = input.book,
            quoted = input.upQuotedSize,
            stakeUsd = input.stakeUsd
        )
        val down = quoteSide(
            side = "NO",
            winChance = 1.0 - pUp,
            ask = input.downAsk,
            book = input.book,
            quoted = input.downQuotedSize,
            stakeUsd = input.stakeUsd
        )
        val winner = listOfNotNull(up, down)
            .filter { it.qualifies }
            .maxByOrNull { it.evPerDollar ?: Double.NEGATIVE_INFINITY }
        if (winner != null) {
            val ask = winner.ask ?: return live(input, pUp, up, down)
            val fired = LastMinuteFired(
                ticker = input.ticker,
                side = winner.side,
                displaySide = winner.displaySide,
                winChance = winner.winChance,
                ask = ask,
                evPerDollar = winner.evPerDollar ?: 0.0,
                contracts = winner.contracts,
                costUsd = winner.costUsd,
                feeUsd = winner.feeUsd,
                profitIfWinUsd = winner.profitIfWinUsd,
                depthLimited = winner.depthLimited,
                depthContracts = winner.depthContracts,
                tauSec = input.tauSec,
                x = input.x,
                obsMean = input.obsMean,
                sigS = input.sigS,
                firedAtMs = input.nowMs
            )
            return LastMinuteSnapshot(
                phase = LastMinutePhase.FIRED,
                tauSec = input.tauSec,
                startsInMs = null,
                pUp = pUp,
                up = up,
                down = down,
                fired = fired,
                spotUsd = input.spotUsd,
                strikeUsd = input.strikeUsd,
                spotSource = input.spotSource,
                x = input.x,
                obsMean = input.obsMean,
                sigS = input.sigS
            )
        }
        return live(input, pUp, up, down)
    }

    fun quoteSide(
        side: String,
        winChance: Double,
        ask: Double?,
        book: BookLevelSnapshot?,
        quoted: Double?,
        stakeUsd: Double
    ): LastMinuteSideQuote {
        val display = if (side.equals("NO", true)) "DOWN" else "UP"
        val p = KalshiPrice.usable(ask)
        if (p == null || !winChance.isFinite()) {
            return LastMinuteSideQuote(
                side = if (side.equals("NO", true)) "NO" else "YES",
                displaySide = display,
                winChance = winChance,
                ask = p,
                evPerDollar = null,
                contracts = 0,
                costUsd = 0.0,
                feeUsd = 0.0,
                profitIfWinUsd = 0.0,
                qualifies = false,
                depthLimited = false,
                depthContracts = null
            )
        }
        val (rawC, _) = LastMinuteMath.sizeBet(p, stakeUsd)
        val depth = LastMinuteBook.depthAtOrBelow(side, p, book, quoted)
        val (c, limited) = LastMinuteBook.capContracts(rawC, depth)
        val cost = if (c > 0) LastMinuteMath.allInCost(c, p) else 0.0
        val fee = (cost - c * p).coerceAtLeast(0.0)
        val ev = LastMinuteMath.evPerDollar(c, winChance, cost)
        val qualifies = c > 0 && ev != null && ev >= LastMinuteConstants.MARGIN_EV_PER_DOLLAR - 1e-12
        return LastMinuteSideQuote(
            side = if (side.equals("NO", true)) "NO" else "YES",
            displaySide = display,
            winChance = winChance,
            ask = p,
            evPerDollar = ev,
            contracts = c,
            costUsd = cost,
            feeUsd = fee,
            profitIfWinUsd = if (c > 0) c - cost else 0.0,
            qualifies = qualifies,
            depthLimited = limited,
            depthContracts = depth
        )
    }

    private fun live(
        input: Inputs,
        pUp: Double,
        up: LastMinuteSideQuote,
        down: LastMinuteSideQuote
    ) = LastMinuteSnapshot(
        phase = LastMinutePhase.LIVE,
        tauSec = input.tauSec,
        startsInMs = null,
        pUp = pUp,
        up = up,
        down = down,
        fired = null,
        spotUsd = input.spotUsd,
        strikeUsd = input.strikeUsd,
        spotSource = input.spotSource,
        x = input.x,
        obsMean = input.obsMean,
        sigS = input.sigS
    )
}
