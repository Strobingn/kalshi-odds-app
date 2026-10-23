package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.llm.LlmProviders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.0.36: live entries, fee break-even, and the on-device analyst. */
class Release1036Test {
    @After fun reset() {
        ModelFilter.resetForTest()
        LocalAnalyst.resetForTest()
    }

    @Test fun keptFamiliesTwinsAndVariantsSkipTheFairGapGate() {
        for (code in listOf("sf41", "sf42", "sf04", "v2-dip", "v2-fair", "fair", "fcfade")) {
            assertFalse(code, FairGapTune.entryGated(code))
            assertFalse(code, FairGapTune.fairGapHoldExit(code))
        }
        assertTrue(FairGapTune.entryGated("sf01"))
        val twin = ModelFilter.TWINS.first().id
        assertTrue(ModelFilter.isTwin(twin))
        assertFalse(FairGapTune.entryGated(twin))
        assertFalse(FairGapTune.entryGated(KeptScalpers.IDS.first()))
    }

    @Test fun fairAndDipLanesDoNotShareTheEntryClock() {
        assertTrue(ModelFilter.dueForEntry("KXBTC", 5_000L, "fair"))
        assertTrue(ModelFilter.dueForEntry("KXBTC", 5_000L, "dip"))
        assertFalse(ModelFilter.dueForEntry("KXBTC", 5_100L, "fair"))
        assertTrue(ModelFilter.dueForEntry("KXBTC", 5_000L + ModelFilter.ENTRY_CADENCE_MS, "fair"))
    }

    @Test fun coldStartEntersWhenTheResidualClearsFees() {
        val logistic = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }
        val tree = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LGBM }
        val yes = ModelFilter.decide(logistic, quote(), "YES", 0.20, 0.55)
        assertTrue(yes.reason, yes.enter)
        val lgbm = ModelFilter.decide(tree, quote(), "YES", 0.20, 0.55)
        assertTrue(lgbm.reason, lgbm.enter)
        assertTrue(lgbm.expectedNet > 0.0)
        val blocked = ModelFilter.decide(logistic, quote(), "YES", 0.50, 0.50)
        assertFalse(blocked.enter)
    }

    @Test fun controlArmRecordsAClosedParentTrade() {
        ModelFilter.noteControl("sf41", 1.25, "YES", 40L)
        ModelFilter.noteControl("sf41", -0.25, "NO", 10L)
        val card = ModelFilter.scorecards().first { it.arm == "control" }
        assertEquals(2, card.trades)
        assertEquals(1.00, card.netUsd, 1e-9)
        assertEquals(50.0, card.centsPerFill, 1e-9)
        assertEquals(1.25, card.yesNet, 1e-9)
        assertEquals(-0.25, card.noNet, 1e-9)
        assertEquals(0.25, card.drawdownUsd, 1e-9)
        assertEquals(10L, card.freshnessMs)
    }

    @Test fun unroundedFeeBreakEvenMatchesThe50CentCard() {
        assertEquals(4.5448, ModelFilter.rawNetCents(0.50, 0.58), 1e-4)
        assertEquals(-8.4825, ModelFilter.rawNetCents(0.50, 0.45), 1e-4)
        assertEquals(-0.4937, ModelFilter.rawNetCents(0.50, 0.53), 1e-4)
        val wr = ModelFilter.breakEvenWinRate(4.5448, -8.4825)
        assertEquals(0.651, wr, 1e-3)
        val cents = ModelFilter.netCentsPerContract(5.30, 5.00, 0.18, 0.17, 10)
        assertEquals(-0.5, cents, 1e-9)
    }

    @Test fun litertImportFallsBackUntilAFileIsHashed() {
        assertFalse(LocalAnalyst.imported())
        assertTrue(QuantCompanion.answer("why").contains("quant analyst"))
        val file = File.createTempFile("gemma", ".litertlm")
        file.writeText("gemma-bytes")
        val line = LocalAnalyst.importFile(file)
        assertTrue(LocalAnalyst.imported())
        assertEquals(ModelImport.sha256(file), LocalAnalyst.sha256())
        assertTrue(line.contains(LocalAnalyst.sha256()!!))
        val yes = QuantCompanion.paperLine("Strike 100. BTC spot 101. minutes left 8. YES bid/ask 0.40/0.42. Model fair YES 0.70. Reply ONE line")
        assertTrue(yes, yes.startsWith("YES") || yes == "SKIP" || yes.startsWith("NO"))
        assertTrue(LlmProviders.builtinEndpoint().usable)
        file.delete()
    }

    private fun quote() = ScalpRule.Quote(
        ticker = "KXBTC15M-26OCT221200-00",
        nowMs = 1_700_000_000_000L,
        closeMs = 1_700_000_600_000L,
        bookAtMs = 1_700_000_000_000L,
        yesBid = 0.19,
        yesBidSize = 20.0,
        yesAsk = 0.20,
        yesAskSize = 20.0,
        spot = 100_000.0,
        strike = 100_000.0,
        sigmaPerSec = 3e-5
    )
}
