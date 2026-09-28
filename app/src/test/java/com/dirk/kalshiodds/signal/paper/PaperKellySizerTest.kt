package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaperKellySizerTest {

    @Test
    fun positiveEvSizesFractionalKellyIncludingFee() {
        val p = 0.60
        val ask = 0.40
        val bankroll = 1_000.0
        val frac = 0.5
        val cost1 = KalshiFee.totalCost(1, ask)
        assertTrue("fee must be in the 1-ct cost", cost1 > ask + 1e-9)
        val feeFree = (p - ask) / (1.0 - ask)
        val f = PaperKellySizer.fullKelly(p, ask)
        assertTrue("fee-aware Kelly $f must be below fee-free $feeFree", f < feeFree - 1e-6)
        val expectedF = (p * 1.0 - cost1) / (1.0 - cost1)
        assertEquals(expectedF, f, 1e-9)
        val sized = PaperKellySizer.size(p, ask, bankroll, frac)
        assertTrue(sized.ok)
        assertEquals(expectedF, sized.kellyF, 1e-9)
        val target = bankroll * expectedF * frac
        assertTrue(sized.allInUsd <= target + 1e-6)
        assertTrue(sized.allInUsd <= bankroll + 1e-9)
        assertEquals(KalshiFee.totalCost(sized.contracts, ask), sized.allInUsd, 1e-9)
        assertTrue(sized.contracts >= 1)
        assertTrue("no \$10 paper cap: all-in ${sized.allInUsd}", sized.allInUsd > 10.0)
    }

    @Test
    fun zeroOrNegativeEvSkips() {
        val ask = 0.40
        val cost1 = KalshiFee.totalCost(1, ask)
        val zero = PaperKellySizer.size(winProb = cost1, ask = ask, bankrollUsd = 1_000.0)
        assertTrue(zero.skip)
        assertEquals(0, zero.contracts)
        assertTrue(zero.reason!!.contains("Kelly"))
        val neg = PaperKellySizer.size(winProb = 0.20, ask = ask, bankrollUsd = 1_000.0)
        assertTrue(neg.skip)
        assertTrue(neg.kellyF <= 0.0)
        val missing = PaperKellySizer.size(winProb = null, ask = ask, bankrollUsd = 1_000.0)
        assertTrue(missing.skip)
    }

    @Test
    fun bankrollCapsContractsAndDepthCapsWhenPresent() {
        val p = 0.80
        val ask = 0.20
        val tiny = PaperKellySizer.size(p, ask, bankrollUsd = 0.15, kellyFraction = 1.0)
        assertTrue(tiny.skip)
        val deep = PaperKellySizer.size(p, ask, bankrollUsd = 5_000.0, kellyFraction = 1.0, depthContracts = 3)
        assertTrue(deep.ok)
        assertEquals(3, deep.contracts)
        val none = PaperKellySizer.size(p, ask, bankrollUsd = 5_000.0, kellyFraction = 1.0, depthContracts = 0)
        assertTrue(none.skip)
        val unlimited = PaperKellySizer.size(p, ask, bankrollUsd = 5_000.0, kellyFraction = 1.0, depthContracts = null)
        assertTrue(unlimited.contracts > 3)
    }

    @Test
    fun feeIsCeilCentOfQuadraticTaker() {
        val ask = 0.31
        val cost1 = KalshiFee.totalCost(1, ask)
        val raw = 0.07 * 1 * ask * (1.0 - ask)
        val expected = KalshiFee.ceilCent(ask + KalshiFee.ceil6dp(raw))
        assertEquals(expected, cost1, 1e-9)
        val f = PaperKellySizer.fullKellyFromCost(0.55, cost1)
        assertTrue(f.isFinite())
    }
}

class PaperBankrollPersistenceTest {

    @Test
    fun startsAtOneThousandAndUpdatesAfterWinAndLoss() {
        val book = PaperBook(idFactory = { "k1" }, nowMs = { 1L })
        assertEquals(SignalConstants.PAPER_START_USD, book.snapshot().cashUsd, 1e-9)
        assertEquals(1_000.0, book.snapshot().paperBankrollUsd, 1e-9)
        val ticket = hunter(p = 0.70, ask = 0.40, ticker = "KXBTC15M-WIN")
        val fill = book.considerTicket(ticket, enabled = true)
        assertTrue(fill != null)
        assertTrue(fill!!.kellyF != null && fill.kellyF!! > 0.0)
        assertEquals(0.5, fill.kellyFraction!!, 1e-9)
        val openCash = book.snapshot().cashUsd
        assertTrue(openCash < 1_000.0)
        val settled = book.settle("KXBTC15M-WIN", "yes")
        assertEquals(true, settled.single().won)
        assertTrue(settled.single().pnlUsd!! > 0.0)
        assertEquals(
            SignalConstants.PAPER_START_USD + settled.single().pnlUsd!!,
            settled.single().bankrollAfterUsd!!,
            1e-6
        )
        assertEquals(settled.single().bankrollAfterUsd!!, book.snapshot().paperBankrollUsd, 1e-6)

        val lossTicket = hunter(p = 0.70, ask = 0.40, ticker = "KXBTC15M-LOSS")
        assertTrue(book.considerTicket(lossTicket, enabled = true) != null)
        val lost = book.settle("KXBTC15M-LOSS", "no")
        assertEquals(false, lost.single().won)
        assertTrue(lost.single().pnlUsd!! < 0.0)
        val expectedBank = SignalConstants.PAPER_START_USD +
            settled.single().pnlUsd!! + lost.single().pnlUsd!!
        assertEquals(expectedBank, lost.single().bankrollAfterUsd!!, 1e-6)
        assertEquals(expectedBank, book.snapshot().paperBankrollUsd, 1e-6)
    }

    @Test
    fun jsonRoundTripKeepsKellyAndBankroll() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val fill = PaperFill(
            id = "p1",
            ticker = "KXBTC15M-A",
            side = "YES",
            stakeUsd = 42.0,
            contracts = 80,
            limitPrice = 0.50,
            source = "AI hunter",
            createdAtMs = 1L,
            settled = true,
            outcome = "yes",
            won = true,
            pnlUsd = 38.0,
            note = "Kelly",
            kellyF = 0.31,
            kellyFraction = 0.5,
            bankrollAfterUsd = 1_038.0
        )
        val state = PaperBookState(startingUsd = 1_000.0, cashUsd = 1_038.0, fills = listOf(fill))
        val encoded = json.encodeToString(PaperBookState.serializer(), state)
        val decoded = json.decodeFromString(PaperBookState.serializer(), encoded)
        assertEquals(1_038.0, decoded.paperBankrollUsd, 1e-9)
        assertEquals(0.31, decoded.fills.single().kellyF!!, 1e-9)
        assertEquals(0.5, decoded.fills.single().kellyFraction!!, 1e-9)
        assertEquals(1_038.0, decoded.fills.single().bankrollAfterUsd!!, 1e-9)
    }

    @Test
    fun migrateOldHundredDollarBookPreservesPnl() {
        val old = PaperBookState(startingUsd = 100.0, cashUsd = 215.0)
        val neu = PaperBookState.migrateStartUsd(old)
        assertEquals(1_000.0, neu.startingUsd, 1e-9)
        assertEquals(1_115.0, neu.cashUsd, 1e-9)
        val already = PaperBookState.migrateStartUsd(PaperBookState())
        assertEquals(1_000.0, already.startingUsd, 1e-9)
        assertEquals(1_000.0, already.cashUsd, 1e-9)
    }

    @Test
    fun skipWhenKellyNonPositiveDoesNotDebit() {
        val book = PaperBook()
        val fill = book.considerTicket(hunter(p = 0.10, ask = 0.40, ticker = "KXBTC15M-SKIP"), enabled = true)
        assertTrue(fill == null)
        assertEquals(1_000.0, book.snapshot().cashUsd, 1e-9)
        assertTrue(book.snapshot().fills.isEmpty())
        assertTrue(book.snapshot().lastMessage!!.contains("Kelly"))
    }

    private fun hunter(p: Double, ask: Double, ticker: String) =
        com.dirk.kalshiodds.signal.trade.TradeTicket(
            id = "t-$ticker",
            ticker = ticker,
            side = "YES",
            bookSide = "bid",
            stakeUsd = 1.0,
            limitPrice = ask,
            yesLimitPrice = ask,
            contracts = 25,
            estimatedFillUsd = 1.0,
            maxPayoutUsd = 25.0,
            estimatedAvgFill = ask,
            sizingNote = "hunter",
            kind = com.dirk.kalshiodds.signal.trade.TicketKind.HUNTER,
            modelChance = p,
            impliedChance = ask
        )
}
