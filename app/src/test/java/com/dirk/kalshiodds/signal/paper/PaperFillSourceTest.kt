package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.data.local.paper.PaperFillSchema
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.lastminute.LastMinuteFired
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.ScorecardCopy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperFillSourceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldJsonWithoutNewColumnsStillDecodes() {
        val raw = """
            {"id":"legacy","ticker":"KXBTC15M-OLD","side":"YES","stakeUsd":8.5,
             "contracts":10,"limitPrice":0.85,"source":"AI hunter","createdAtMs":1,
             "settled":true,"won":false,"pnlUsd":-8.5,"note":"old"}
        """.trimIndent()
        val fill = json.decodeFromString(PaperFill.serializer(), raw)
        assertEquals("legacy", fill.id)
        assertNull(fill.aiPct)
        assertNull(fill.aiConfidence)
        assertNull(fill.marketPct)
        assertNull(fill.pickSource)
        assertNull(fill.kellyF)
        assertNull(fill.kellyFraction)
        assertNull(fill.bankrollAfterUsd)
        assertEquals("AI hunter", fill.source)
    }

    @Test
    fun paperFillSchemaIsAdditiveAndNullable() {
        val sql = PaperFillSchema.upgradeSql(PaperFillSchema.FROM_VERSION)
        assertTrue(sql.isNotEmpty())
        assertTrue(sql.none { PaperFillSchema.isDestructive(it) })
        assertTrue(sql.any { it.contains(PaperFillSchema.TABLE) })
        assertTrue(sql.any { it.contains("ai_pct REAL") })
        assertTrue(sql.any { it.contains("pick_source TEXT") })
        assertTrue(sql.any { it.contains("kelly_f REAL") })
        assertTrue(sql.any { it.contains("bankroll_after_usd REAL") })
        assertTrue(PaperFillSchema.upgradeSql(PaperFillSchema.VERSION).isEmpty())
        PaperFillSchema.nullableColumnSql().forEach { line ->
            assertTrue(line.startsWith("ALTER TABLE"))
            assertFalse(line.contains("NOT NULL"))
            assertFalse(PaperFillSchema.isDestructive(line))
        }
        val store = InMemoryResultsStore()
        store.upsertPaperFills(
            listOf(
                PaperFill(
                    id = "p1",
                    ticker = "KXBTC15M-A",
                    side = "YES",
                    stakeUsd = 1.0,
                    contracts = 1,
                    limitPrice = 0.40,
                    source = "AI hunter",
                    createdAtMs = 1L,
                    note = "new",
                    aiPct = 70.0,
                    pickSource = PaperPickSource.TICKET.label
                )
            )
        )
        assertEquals(70.0, store.paperFill("p1")!!.aiPct!!, 1e-9)
    }

    @Test
    fun storedAiPctWinsOverWrongTickerLookup() {
        val fill = PaperFill(
            id = "f1",
            ticker = "KXBTC15M-X",
            side = "YES",
            stakeUsd = 5.0,
            contracts = 10,
            limitPrice = 0.50,
            source = "AI hunter",
            createdAtMs = 1L,
            settled = true,
            won = true,
            pnlUsd = 5.0,
            note = "stored",
            aiPct = 88.0,
            aiConfidence = 0.61,
            marketPct = 50.0,
            pickSource = PaperPickSource.TICKET.label
        )
        val other = PredictionLogEntry(
            ticker = "KXBTC15M-X",
            series = "KXBTC15M",
            predictedYes = 0.55,
            predictedNo = 0.45,
            marketMid = 0.40,
            timestampMs = 1L,
            closeTimeMs = 1L,
            outcome = "yes",
            predictedSide = "YES",
            settledAtMs = 1L
        )
        val snap = ScorecardLedger.of(listOf(other), listOf(fill))
        val row = snap.picks.single { it.ticker == "KXBTC15M-X" && !it.noBetWouldHave }
        assertEquals(88.0, row.aiPct!!, 1e-9)
        assertEquals(50.0, row.marketPct!!, 1e-9)
        assertEquals(0.61, row.aiConfidence!!, 1e-9)
        assertEquals(PaperPickSource.TICKET.label, row.pickSource)
        assertFalse(row.confidenceLegacy)
        assertEquals("gt85", ScorecardLedger.confidenceBandKey(row.aiPct))
    }

    @Test
    fun leftoverFillFallsBackToTickerLookupThenLegacyUnknown() {
        val lookup = PredictionLogEntry(
            ticker = "KXBTC15M-LOOKUP",
            series = "KXBTC15M",
            predictedYes = 0.72,
            predictedNo = 0.28,
            marketMid = 0.60,
            timestampMs = 2L,
            closeTimeMs = 2L,
            outcome = "yes",
            predictedSide = "YES",
            settledAtMs = 2L
        )
        val withLookup = PaperFill(
            id = "lu",
            ticker = "KXBTC15M-LOOKUP",
            side = "YES",
            stakeUsd = 3.0,
            contracts = 5,
            limitPrice = 0.60,
            source = "AI ticket",
            createdAtMs = 2L,
            settled = true,
            won = true,
            pnlUsd = 2.0,
            note = "lookup"
        )
        val orphan = PaperFill(
            id = "or",
            ticker = "KXBTC15M-ORPHAN",
            side = "YES",
            stakeUsd = 10.0,
            contracts = 12,
            limitPrice = 0.80,
            source = "AI hunter",
            createdAtMs = 3L,
            settled = true,
            won = false,
            pnlUsd = -10.0,
            note = "legacy leftover"
        )
        val snap = ScorecardLedger.of(listOf(lookup), listOf(withLookup, orphan))
        val looked = snap.picks.single { it.ticker == "KXBTC15M-LOOKUP" && !it.noBetWouldHave }
        assertEquals(72.0, looked.aiPct!!, 1e-6)
        assertFalse(looked.confidenceLegacy)
        val unknown = snap.picks.single { it.ticker == "KXBTC15M-ORPHAN" }
        assertNull(unknown.aiPct)
        assertTrue(unknown.confidenceLegacy)
        val unk = snap.byConfidence.first { it.key == ScorecardLedger.UNKNOWN_KEY }
        assertEquals(1, unk.settledCount)
        assertEquals(ScorecardLedger.UNKNOWN_CONF_NOTE, unk.note)
        assertEquals(-10.0, unk.pnlUsd, 1e-9)
    }

    @Test
    fun bySourceSplitsWlWinPctCountAndDollars() {
        val fills = listOf(
            stamped("a1", PaperPickSource.AI_ALERT, won = true, pnl = 2.0),
            stamped("a2", PaperPickSource.AI_ALERT, won = false, pnl = -4.0),
            stamped("t1", PaperPickSource.TICKET, won = true, pnl = 1.0),
            stamped("l1", PaperPickSource.LONG_SHOT, won = false, pnl = -3.0),
            stamped("m1", PaperPickSource.MANUAL, won = true, pnl = 5.0)
        )
        val snap = ScorecardLedger.of(emptyList(), fills)
        val alert = snap.bySource.first { it.label == PaperPickSource.AI_ALERT.label }
        assertEquals(1, alert.wins)
        assertEquals(1, alert.losses)
        assertEquals(2, alert.settledCount)
        assertEquals(0.5, alert.hitRate!!, 1e-9)
        assertEquals(-2.0, alert.pnlUsd, 1e-9)
        val ticket = snap.bySource.first { it.label == PaperPickSource.TICKET.label }
        assertEquals(1, ticket.wins)
        assertEquals(1.0, ticket.pnlUsd, 1e-9)
        val longShot = snap.bySource.first { it.label == PaperPickSource.LONG_SHOT.label }
        assertEquals(1, longShot.losses)
        assertEquals(-3.0, longShot.pnlUsd, 1e-9)
        val manual = snap.bySource.first { it.label == PaperPickSource.MANUAL.label }
        assertEquals(1, manual.wins)
        assertEquals(5.0, manual.pnlUsd, 1e-9)
        val view = ScorecardCopy.of(emptyList(), fills, 0.0)
        assertTrue(view.allLines().contains(ScorecardCopy.SOURCE_TITLE))
        assertTrue(view.recent.any { it.line.contains(PaperPickSource.AI_ALERT.label) })
        assertTrue(view.recent.any { it.line.contains(PaperPickSource.MANUAL.label) })
    }

    @Test
    fun lastMinuteBucketMergesFromStoreWhenNoPaperFill() {
        val pick = com.dirk.kalshiodds.signal.lastminute.LastMinutePick(
            id = "lm",
            ticker = "KXBTC15M-LM",
            side = "YES",
            entryAsk = 0.03,
            contracts = 10,
            stakeUsd = 0.30,
            feeUsd = 0.02,
            winChance = 0.99,
            evPerDollar = 2.0,
            depthLimited = false,
            createdAtMs = 1L,
            settled = true,
            outcome = "yes",
            won = true,
            pnlUsd = 9.70
        )
        val view = ScorecardCopy.of(
            entries = emptyList(),
            fills = emptyList(),
            paperPnlUsd = 0.0,
            lastMinutePicks = listOf(pick)
        )
        val lm = view.bySource.first { it.label == PaperPickSource.LAST_MINUTE.label }
        assertEquals(1, lm.wins)
        assertEquals(1, lm.settledCount)
        assertEquals(9.70, lm.pnlUsd, 1e-9)
    }

    @Test
    fun autoPickWithoutAiPctIsRejectedManualIsAllowed() {
        val book = PaperBook(idFactory = { "auto" }, nowMs = { 1L })
        val hunter = ticket(kind = TicketKind.HUNTER, model = null)
        assertNull(book.considerTicket(hunter, enabled = true))
        assertTrue(book.snapshot().fills.isEmpty())

        val longShot = ticket(kind = TicketKind.HUNTER_VALUE, model = null, ticker = "KXBTC15M-LS")
        assertNull(book.considerTicket(longShot, enabled = true))

        val alert = SignalAlert(
            id = "a",
            ticker = "KXBTC15M-AL",
            series = "KXBTC15M",
            deltaPp = 8.0,
            fairValuePp = 0.0,
            marketMidPp = 50.0,
            reason = "edge",
            createdAtMs = 1L,
            receiveElapsedNanos = 1L,
            predictedSide = "YES"
        )
        assertNull(book.considerAlert(alert, ask = 0.40, enabled = true))

        val ok = ticket(kind = TicketKind.HUNTER, model = 0.66, ticker = "KXBTC15M-OK")
        val fill = book.considerTicket(ok, enabled = true)
        assertNotNull(fill)
        assertEquals(66.0, fill!!.aiPct!!, 1e-6)
        assertEquals(PaperPickSource.TICKET.label, fill.pickSource)
        assertEquals("AI hunter", fill.source)

        val ls = ticket(kind = TicketKind.HUNTER_VALUE, model = 0.45, ticker = "KXBTC15M-LS2")
        val lsFill = book.considerTicket(ls, enabled = true)
        assertEquals(PaperPickSource.LONG_SHOT.label, lsFill!!.pickSource)
        assertEquals(PaperPickSource.LONG_SHOT.label, lsFill.source)
        assertTrue(lsFill.kellyF!! > 0.0)
        assertTrue(lsFill.contracts > 5)

        val fired = LastMinuteFired(
            ticker = "KXBTC15M-LM2",
            side = "YES",
            displaySide = "UP",
            winChance = 0.91,
            ask = 0.10,
            evPerDollar = 1.5,
            contracts = 20,
            costUsd = 2.0,
            feeUsd = 0.10,
            profitIfWinUsd = 18.0,
            depthLimited = false,
            depthContracts = null,
            tauSec = 12,
            x = 0.0,
            obsMean = 0.0,
            sigS = 0.0,
            firedAtMs = 2L
        )
        val lm = book.considerLastMinute(fired, enabled = true)
        assertEquals(91.0, lm!!.aiPct!!, 1e-6)
        assertEquals(PaperPickSource.LAST_MINUTE.label, lm.pickSource)
        assertTrue(lm.contracts > fired.contracts)
        assertTrue(lm.kellyF!! > 0.0)

        val manual = ticket(kind = TicketKind.MANUAL, model = null, ticker = "KXBTC15M-MAN")
        val paper = book.manualFill(manual)
        assertNotNull(paper)
        assertNull(paper!!.aiPct)
        assertEquals(PaperPickSource.MANUAL.label, paper.pickSource)
        assertTrue(PaperFill.allowCreate(PaperPickSource.MANUAL, null))
        assertFalse(PaperFill.allowCreate(PaperPickSource.TICKET, null))
    }

    private fun stamped(
        id: String,
        source: PaperPickSource,
        won: Boolean,
        pnl: Double
    ) = PaperFill(
        id = id,
        ticker = "KXBTC15M-$id",
        side = "YES",
        stakeUsd = 5.0,
        contracts = 5,
        limitPrice = 0.40,
        source = source.label,
        createdAtMs = id.hashCode().toLong(),
        settled = true,
        won = won,
        pnlUsd = pnl,
        note = source.label,
        aiPct = 60.0,
        marketPct = 40.0,
        pickSource = source.label
    )

    private fun ticket(
        kind: TicketKind,
        model: Double?,
        ticker: String = "KXBTC15M-T"
    ) = TradeTicket(
        id = ticker,
        ticker = ticker,
        side = "YES",
        bookSide = "bid",
        stakeUsd = 1.0,
        limitPrice = 0.20,
        yesLimitPrice = 0.20,
        contracts = 5,
        estimatedFillUsd = 1.0,
        maxPayoutUsd = 5.0,
        estimatedAvgFill = 0.20,
        sizingNote = "test",
        kind = kind,
        modelChance = model,
        impliedChance = 0.20
    )
}
