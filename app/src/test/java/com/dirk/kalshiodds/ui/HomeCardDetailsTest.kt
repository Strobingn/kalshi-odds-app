package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeCardDetailsTest {

    private val market = HomeFixtures.screenshotPhoneBtc()
    private val decision = BetCall.decide(
        market,
        SignalSettings(minProfitIfWinUsd = 20.0),
        HomeFixtures.NOW_MS
    )
    private val snap = HomeCardDetails.of(market, decision, HomeFixtures.NOW_MS)

    @Test
    fun phoneCaseUsesRealModelAndMarketNumbers() {
        assertEquals(39.0, market.aiYesPercent!!, 1e-9)
        assertEquals(61.0, market.aiNoPercent!!, 1e-9)
        assertEquals(33.5, market.yesProbabilityPercent!!, 1e-9)
        assertEquals(66.5, market.noProbabilityPercent!!, 1e-9)
        assertEquals(39.0, market.importedModelPp!!, 1e-9)
        assertEquals(39.0 - 33.5, market.edgePp!!, 1e-9)
        assertEquals(0.62, market.aiConfidence!!, 1e-9)
        assertFalse(market.aiNote.isNullOrBlank())
        assertTrue(market.featureDevs.isNotEmpty())
    }

    @Test
    fun restoredAiPercentsMatchModelNotPlaceholders() {
        assertEquals("FV YES", snap.aiYesLabel)
        assertEquals("FV NO", snap.aiNoLabel)
        assertEquals("39.0%", snap.aiYes)
        assertEquals("61.0%", snap.aiNo)
        assertFalse(snap.aiYes.contains("TODO"))
        assertFalse(snap.aiNo.contains("—"))
    }

    @Test
    fun modelVsMarketAndEdgeComeFromLivePercents() {
        assertEquals("33.5%", snap.mktYes)
        assertEquals("66.5%", snap.mktNo)
        assertEquals("Value edge +5.5 pp UP", snap.edge)
        val expected = HomeCopy.modelVsMarket(market, decision)
        assertEquals(expected, snap.modelVsMarket)
        assertTrue(snap.modelVsMarket.contains("61%") || snap.modelVsMarket.contains("39%"))
        assertTrue(snap.modelVsMarket.contains("market"))
        assertTrue(snap.modelVsMarket.contains("edge"))
        assertFalse(snap.modelVsMarket.contains("placeholder"))
    }

    @Test
    fun confidenceSignalStrengthAndReasonsAreReal() {
        assertEquals("Confidence 62%", snap.confidence)
        assertEquals("Signal strength 62% (medium)", snap.signalStrength)
        assertEquals(market.aiNote, snap.reasons)
        assertTrue(snap.reasons!!.contains("AI 39% vs mkt 34%"))
        assertTrue(snap.reasons!!.contains("flow NO"))
        assertTrue(snap.drivers!!.contains("flow smart-flow"))
        assertTrue(snap.drivers!!.contains("ai +5.5pp"))
        assertTrue(snap.drivers!!.contains("flow -4.2pp") || snap.drivers!!.contains("flow −4.2pp"))
    }

    @Test
    fun expectedValueFairPriceTargetSpotVolumeSpread() {
        assertEquals("Net EV +3.2 pp  ·  +0.032 $/ct  ·  fee 1.4¢", snap.netEv)
        assertEquals("Fair value 39¢  ·  model 39¢", snap.fairValue)
        assertEquals("Target $84,144  ·  spot $84,140  ·  −$4", snap.targetVsSpot)
        assertEquals("−$4", snap.spotMove)
        assertEquals("1.0¢", snap.spread)
        assertEquals("2.0K", snap.volume)
        assertEquals("200", snap.openInterest)
        assertEquals("2.0K", snap.volume24h)
        assertEquals("8.0K", snap.liquidity)
        assertEquals("11:47 left", snap.timeLeft)
        assertEquals("12 contracts max · Kelly clip after fee + half-spread", snap.sizing)
        assertEquals("TTM 90s · vol 2.4pp · P(fill) 71% · unc 0.18", snap.microstructure)
        assertEquals("Value side: UP · Likely side: DOWN 61%", snap.stance)
        assertTrue(snap.notes!!.contains("MLP 41¢"))
        val quotes = MarketQuoteView.of(market)
        assertEquals(
            "Payout  UP ${quotes.upMultipleLabel}  ·  DOWN ${quotes.downMultipleLabel}",
            snap.payout
        )
        assertEquals(null, snap.modelLean)
        assertEquals(null, snap.conflict)
        assertEquals(
            "Value: UP is underpriced (model 39% vs ask 34¢) · More likely: DOWN 61%",
            snap.value
        )
    }

    @Test
    fun phoneCaseNeverClaimsAiSaysOrLeansUp() {
        val market = HomeFixtures.screenshotPhoneBtc()
        assertEquals(39.0, market.aiYesPercent!!, 1e-9)
        assertEquals(0.34, market.yesAsk!!, 1e-9)
        assertEquals(0.67, market.noAsk!!, 1e-9)
        assertTrue((market.spotVsTargetUsd ?: 0.0) < 0.0)
        assertEquals("YES", market.modelLeanSide)
        assertEquals("AI says UP, market + spot say DOWN", market.tapeConflictNote)

        val decision = BetCall.decide(
            market,
            SignalSettings(minProfitIfWinUsd = 20.0),
            HomeFixtures.NOW_MS
        )
        val snap = HomeCardDetails.of(market, decision, HomeFixtures.NOW_MS)
        val checklist = com.dirk.kalshiodds.signal.checklist.PreTradeChecklist.items(market)
        val copy = com.dirk.kalshiodds.signal.checklist.PreTradeChecklist.copyText(market)
        val text = (snap.lines().values.filterNotNull() +
            checklist.map { "${it.label} ${it.value}" } +
            listOf(copy)).joinToString("\n")

        assertFalse(text.contains("AI says UP"))
        assertFalse(text.contains("Model lean UP"))
        assertFalse(text.contains("Slight UP"))
        assertFalse(text.contains("leans UP", ignoreCase = true))
        assertFalse(text.contains("Lean UP"))
        assertFalse(checklist.any { it.label == "Likely side" && it.value.contains("UP") })
        assertFalse(checklist.any { it.label == "Likely side" && it.value.contains("YES") })
        assertEquals("Likely side", checklist[0].label)
        assertEquals("DOWN 61%", checklist[0].value)
        assertEquals("Value side", checklist[1].label)
        assertTrue(checklist[1].value.contains("UP"))
        assertEquals(
            "Value: UP is underpriced (model 39% vs ask 34¢) · More likely: DOWN 61%",
            snap.value
        )
        assertEquals(null, snap.conflict)
        assertNotEquals("Market closed", decision.noBetReason)
        assertEquals("11:47 left", snap.timeLeft)
    }

    @Test
    fun cardUsesPassedNowMsSoFixtureWindowIsTradable() {
        val market = HomeFixtures.screenshotPhoneBtc()
        assertTrue(
            com.dirk.kalshiodds.domain.MarketLifecycle.isTradable(market, HomeFixtures.NOW_MS)
        )
        assertFalse(
            com.dirk.kalshiodds.domain.MarketLifecycle.isTradable(
                market,
                System.currentTimeMillis()
            )
        )
        val live = BetCall.decide(
            market,
            SignalSettings(minProfitIfWinUsd = 20.0),
            HomeFixtures.NOW_MS
        )
        assertTrue(live.noBetReason == null || live.noBetReason != "Market closed")
        val stale = BetCall.decide(market, SignalSettings(minProfitIfWinUsd = 20.0))
        assertEquals("Market closed", stale.noBetReason)
        val card = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt")
        ).first { it.isFile }.readText()
        assertTrue(card.contains("BetCall.decide(market, settings, clock)"))
        assertTrue(card.contains("details.conflict"))
        assertTrue(card.contains("details.value"))
        assertFalse(card.contains("market.tapeConflictNote.orEmpty()"))
    }

    @Test
    fun missingModelDataOmitsLinesInsteadOfPlaceholders() {
        val bare = HomeFixtures.actionableBtc().copy(
            aiNote = null,
            aiConfidence = null,
            digitalFairPp = null,
            netEdgePp = null,
            featureDevs = emptyMap(),
            flowNote = null,
            spotLabel = null,
            tapeTrend = null,
            mlpPp = null,
            cnnPp = null,
            gbmPp = null,
            mmShadowPp = null,
            newsLabel = null,
            timeToMoveSec = null,
            midVolPp = null,
            pFill = null,
            uncertainty = null,
            suggestedContracts = null,
            sizingNote = null,
            stance = null,
            ensembleNote = null,
            extendedNote = null,
            rlNote = null,
            modelLeanSide = null,
            importedModelPp = null,
            aiYesPercent = null,
            aiNoPercent = null,
            edgePp = null
        )
        val empty = HomeCardDetails.of(
            bare,
            BetCall.decide(bare, SignalSettings(), HomeFixtures.NOW_MS),
            HomeFixtures.NOW_MS
        )
        assertEquals(null, empty.reasons)
        assertEquals(null, empty.confidence)
        assertEquals(null, empty.signalStrength)
        assertEquals(null, empty.drivers)
        assertEquals(null, empty.fairValue)
        assertEquals(null, empty.netEv)
        assertEquals(null, empty.microstructure)
        assertEquals(null, empty.sizing)
        assertEquals(null, empty.modelLean)
        assertEquals(null, empty.value)
        assertEquals(null, empty.stance)
        assertEquals(null, empty.edge)
        assertNotNull(empty.modelVsMarket)
        assertFalse(empty.modelVsMarket.contains("TODO"))
    }

    @Test
    fun everyAuditedFieldHasAVersionAndASnapshotKey() {
        val keys = snap.lines().keys
        HomeCardDetails.RESTORED_FIELDS.forEach { field ->
            assertTrue("${field.name} missing key ${field.key}", keys.contains(field.key))
            assertTrue(field.firstSeen.startsWith("v0.3."))
        }
        assertTrue(HomeCardDetails.RESTORED_FIELDS.size >= 20)
    }

    @Test
    fun marketCardWiresRestoredDetailsExpandedByDefault() {
        val card = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/components/MarketCard.kt")
        ).first { it.isFile }.readText()
        assertTrue(card.contains("detailsInitiallyOpen: Boolean = true"))
        assertTrue(card.contains("HomeCardDetails.of(market, call,"))
        assertTrue(card.contains("HomeCardDetails.SECTION"))
        assertTrue(card.contains("HomeCardDetails.EDGE_TITLE"))
        assertTrue(card.contains("HomeCardDetails.MARKET_REF"))
        assertTrue(card.contains("HomeCardDetails.LIVE_BOOK"))
        assertTrue(card.contains("HomeCardDetails.FAIR_NOTE"))
        assertTrue(card.contains("details.confidence"))
        assertTrue(card.contains("details.signalStrength"))
        assertTrue(card.contains("details.drivers"))
        assertTrue(card.contains("details.modelVsMarket"))
        assertTrue(card.contains("HomeCopy.tileAiUp(market)"))
        assertTrue(card.contains("HomeCopy.PAPER_UP"))
        assertTrue(card.contains("HomeCopy.tileTenDollarUp(market)"))
        assertEquals("Hide details", HomeCardDetails.detailsToggleLabel(true))
        assertEquals("Details", HomeCardDetails.detailsToggleLabel(false))
    }
}
