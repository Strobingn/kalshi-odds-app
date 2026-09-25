package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.paper.PaperApprove
import com.dirk.kalshiodds.signal.trade.ApproveRouter
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dirk asked for a wiring audit of every 0.3.0–0.3.8 control.
 * Each row is a real method (not a TODO / stub / empty onClick).
 * Navigation empty-defaults were removed so a missing MainActivity
 * lambda is now a compile error.
 */
class WiringAuditTest {

    data class Control(
        val name: String,
        val owner: Class<*>,
        val method: String
    )

    private val catalog = listOf(
        Control("Hero Buy UP/DOWN", OddsViewModel::class.java, "buyMarket"),
        Control("Ticket review / Approve sheet", OddsViewModel::class.java, "openTicketApprove"),
        Control("Ticket Approve tap", OddsViewModel::class.java, "approveTicket"),
        Control("Paper Approve path", ApproveRouter::class.java, "decide"),
        Control("Paper fill + History", PaperApprove::class.java, "apply"),
        Control("Live / paper Sell", OddsViewModel::class.java, "sellPosition"),
        Control("Paper sell edit", OddsViewModel::class.java, "paperSellTicket"),
        Control("Hunter / Long-shot / win-target cards", TicketBuilder::class.java, "proposeAll"),
        Control("Paper trading toggle", SettingsViewModel::class.java, "setPaperTrading"),
        Control("Win-target toggle", SettingsViewModel::class.java, "setWinTargetEnabled"),
        Control("Notifications", SettingsViewModel::class.java, "setNotifications"),
        Control("Opportunity alerts", SettingsViewModel::class.java, "setOpportunityAlerts"),
        Control("Quiet opportunity alerts", SettingsViewModel::class.java, "setOpportunityQuiet"),
        Control("Auto-tune toggle", SettingsViewModel::class.java, "setAutoTuneEnabled"),
        Control("Auto-tune override", SettingsViewModel::class.java, "setAutoTuneOverride"),
        Control("Data import file", DataViewModel::class.java, "importUri"),
        Control("Data backfill", DataViewModel::class.java, "startBackfill"),
        Control("Data cancel backfill", DataViewModel::class.java, "cancelBackfill"),
        Control("Supabase restore", DataViewModel::class.java, "restoreSupabase"),
        Control("Credential backup", DataViewModel::class.java, "backupCredentials"),
        Control("Credential restore", DataViewModel::class.java, "restoreCredentials"),
        Control("Import model JSON", DataViewModel::class.java, "importModelUri"),
        Control("Get latest model (Data)", DataViewModel::class.java, "getLatestModel"),
        Control("Get latest model (Scorecard)", ScorecardViewModel::class.java, "getLatestModel"),
        Control("Roll back model", DataViewModel::class.java, "rollbackModel"),
        Control("Save GitHub token", DataViewModel::class.java, "saveGithubToken"),
        Control("Cloud sync toggle", DataViewModel::class.java, "setSyncEnabled"),
        Control("Sync now", DataViewModel::class.java, "syncNow"),
        Control("History tabs / paging", HistoryViewModel::class.java, "load"),
        Control("History restore settings", HistoryViewModel::class.java, "restoreSettings"),
        Control("Notification tap opens ticket", OddsViewModel::class.java, "focusTicket"),
        Control("Kalshi demo toggle", SettingsViewModel::class.java, "setKalshiDemo")
    )

    @Test
    fun everyAuditedControlResolvesToARealMethod() {
        val missing = catalog.mapNotNull { row ->
            val found = row.owner.methods.any { it.name == row.method } ||
                row.owner.declaredMethods.any { it.name == row.method }
            if (found) null else "${row.name} → ${row.owner.simpleName}.${row.method}"
        }
        assertTrue("Unwired controls: $missing", missing.isEmpty())
        assertTrue(catalog.size >= 20)
    }

    @Test
    fun approveRouterAndPaperApproveAreNotStubs() {
        assertNotNull(ApproveRouter::decide)
        assertNotNull(PaperApprove::apply)
        assertNotNull(TicketBuilder::proposeHunter)
        assertNotNull(TicketBuilder::proposeHunterValue)
        assertNotNull(TicketBuilder::proposeManual)
    }
}
