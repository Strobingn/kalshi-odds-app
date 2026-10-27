package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpModels
import org.junit.Assert.*
import org.junit.Test

/** 1.0.4: AI set_setting tool — allowlist, real-money hard block, range validation. */
class SettingToolsTest {
    private val c = ScalpControls()
    private val code = ScalpModels.ALL.first { ScalpModels.isV2(it) && it != ScalpModels.Model.V2_LLM }.code
    private val original = KeptScalpers.FAIR_ID

    @Test fun allowlistCoversPaperSettings() {
        assertNull(SettingTools.specFor("trailCents")) // hard 3¢ trail
        assertNull(SettingTools.specFor("armCents")) // peak rule forced, arm unused
        assertTrue(SettingTools.propose(c, "stopCents", "6") is SettingTools.Result.Rejected) // no-op
        assertNull(SettingTools.specFor("strategy.v2-llm.contracts")) // LLM strategy freezes its own params
        for (k in listOf("stopCents", "maxDailyLossUsd", "maxTradesPerHour", "research.kellyBankrollUsd", "bankroll",
                "strategy.$code.cadenceSeconds", "strategy.$code.stopCents", "strategy.$code.enabled"))
            assertNotNull(k, SettingTools.specFor(k))
        assertNull(SettingTools.specFor("strategy.not_a_code.stopCents"))
        assertNull(SettingTools.specFor("llm.model"))
        assertNull(SettingTools.specFor("peakTrailEnabled")) // inert in execution (hard 3¢ trail)
        val r = SettingTools.propose(c, "somethingElse", "1") as SettingTools.Result.Rejected
        assertFalse(r.realMoneyBlocked)
    }

    @Test fun noAllowlistedKeyLooksLikeRealMoney() {
        SettingTools.GLOBAL.keys.forEach { assertFalse(it, SettingTools.isRealMoneyKey(it)) }
        for (m in ScalpModels.ALL) for (f in SettingTools.STRATEGY.keys) {
            val k = "strategy.${m.code}.$f"
            assertFalse(k, SettingTools.isRealMoneyKey(k))
        }
    }

    @Test fun realMoneyIsHardBlocked() {
        for (k in listOf("liveTradingArmed", "armLive", "realMoney", "real_money_confirm", "approve", "approveGate", "apiKey", "kalshiApiKey",
                "llm.key", "privateKeyPem", "placeOrder", "orders.autoPlace", "liveSignals", "cashWithdraw")) {
            val r = SettingTools.propose(c, k, "true")
            assertTrue(k, r is SettingTools.Result.Rejected && r.realMoneyBlocked)
        }
        assertTrue((SettingTools.propose(c, "stopCents", "live") as SettingTools.Result.Rejected).realMoneyBlocked)
    }

    @Test fun rangesAreValidatedNotClamped() {
        assertTrue(SettingTools.propose(c, "stopCents", "0") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "stopCents", "100") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "maxAskCents", "56") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "research.kellyFraction", "1.5") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "strategy.$code.cadenceSeconds", "31") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "stopCents", "abc") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "maxStakeUsd", "NaN") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(c, "enabled", "maybe") is SettingTools.Result.Rejected)
        val ok = SettingTools.propose(c, "stopCents", "99") as SettingTools.Result.Proposal
        assertEquals("6" to "99", ok.oldValue to ok.newValue)
    }

    @Test fun applyChangesOnlyTheConfirmedKey() {
        val p = SettingTools.propose(c, "bankroll", "2500") as SettingTools.Result.Proposal
        val next = SettingTools.apply(c, p)
        assertEquals(2500.0, next.research.kellyBankrollUsd, 0.0)
        assertEquals(c.copy(research = c.research.copy(kellyBankrollUsd = 2500.0)).normalized(), next)

        val cd = SettingTools.apply(c, SettingTools.propose(c, "strategy.$code.cadenceSeconds", "20") as SettingTools.Result.Proposal)
        assertEquals(20, cd.settings(code).cadenceSeconds)
        val off = SettingTools.apply(c, SettingTools.propose(c, "strategy.$code.enabled", "false") as SettingTools.Result.Proposal)
        assertTrue(code in off.disabledStrategies)
    }

    @Test fun perFamilyAllowlistOnlyExposesConsumedKnobs() {
        assertNull(SettingTools.specFor("strategy.$original.enabled")) // original entries never consult disabledStrategies
        assertNull(SettingTools.specFor("strategy.$original.cadenceSeconds"))
        assertNotNull(SettingTools.specFor("strategy.$original.maxEntryCents"))
        assertNull(SettingTools.specFor("strategy.r-late.edgeCents"))
        assertNull(SettingTools.specFor("strategy.r-kelly.stopCents"))
        assertNull(SettingTools.specFor("strategy.$code.trailCents")) // 3¢ trail is a hard rule
    }

    @Test fun crossFieldConflictsAreRejectedNotSilentlyClamped() {
        assertTrue(SettingTools.propose(c, "research.lateMaxS", "60") is SettingTools.Result.Rejected) // default lateMinS 120
        assertTrue(SettingTools.propose(c, "research.lateHiCents", "70") is SettingTools.Result.Rejected) // default lateLo 80
        val withMin = SettingTools.apply(c, SettingTools.propose(c, "strategy.$code.minEntryCents", "30") as SettingTools.Result.Proposal)
        assertTrue(SettingTools.propose(withMin, "strategy.$code.maxEntryCents", "20") is SettingTools.Result.Rejected)
        assertTrue(SettingTools.propose(withMin, "strategy.$code.maxEntryCents", "50") is SettingTools.Result.Proposal)
    }

    @Test fun parsesToolCallLines() {
        val calls = SettingTools.parseCalls("Sure.\nSET_SETTING stopCents = 8\nset_setting(\"strategy.$code.cadenceSeconds\", 10)\n")
        assertEquals(listOf("stopCents" to "8", "strategy.$code.cadenceSeconds" to "10"), calls)
        assertEquals("Sure.", SettingTools.stripCalls("Sure.\nSET_SETTING stopCents = 8"))
    }
}
