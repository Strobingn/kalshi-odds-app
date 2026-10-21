package com.dirk.kalshiodds.llm

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.ScalpSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaperTuningTest {
    private lateinit var tuning: PaperTuning
    private val version = ScalpSignal.Track.DIP_HUNTER.formulaVersion
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("paper_tuning_v1", Context.MODE_PRIVATE).edit().clear().commit()
        tuning = PaperTuning(context)
    }
    private fun response(value: String) = """{"message":"test","changes":[{"strategy":"$version","max_net_loss":$value}]}"""
    @Test fun manualPaperChangeIsPersistedAndAudited() {
        tuning.applyResponse(response("0.03"), emptyList(), false)
        assertEquals(0.03, tuning.settings(version).maxNetLossPerContract, 0.00001)
        assertTrue(tuning.audit().contains(version))
    }
    @Test fun automaticPaperChangesDoNotRequireASettledLoss() {
        tuning.applyResponse(response("0.03"), emptyList(), true)
        assertEquals(0.03, tuning.settings(version).maxNetLossPerContract, 0.00001)
        tuning.applyResponse(response("0.04"), emptyList(), true)
        assertEquals(0.04, tuning.settings(version).maxNetLossPerContract, 0.00001)
    }
    @Test fun malformedOrOutOfRangeSettingsAreRejected() {
        tuning.applyResponse(response("9"), emptyList(), false)
        tuning.applyResponse("not json", emptyList(), false)
        assertEquals(0.01, tuning.settings(version).maxNetLossPerContract, 0.00001)
    }
    @Test fun snapshotKeepsExistingOpenPositionsAfterChangingSettings() {
        val old = PaperFill(id = "1", ticker = "KXBTC15M", side = "YES", stakeUsd = 1.0, contracts = 1, limitPrice = 0.5, source = "AI test", createdAtMs = 1_700_000_000_000L, strategyVersion = version, note = "")
        tuning.applyResponse(response("0.03"), listOf(old), true)
        val track = tuning.snapshot(listOf(old)).lineSequence().first { it.startsWith(version) }
        assertTrue(track.contains("open=1"))
        assertTrue(track.contains("since_change_settled=0"))
    }
}
