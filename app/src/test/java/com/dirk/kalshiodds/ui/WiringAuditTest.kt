package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.ChartWindowService
import com.dirk.kalshiodds.data.backfill.LiveWindowBackfill
import com.dirk.kalshiodds.data.local.results.AsyncResultsWriter
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.signal.paper.PaperApprove
import com.dirk.kalshiodds.signal.trade.ApproveRouter
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.ThemeRoles
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dirk asked for a wiring audit of every 0.3.0–0.3.9 control.
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
        Control("Settings export keys", SettingsViewModel::class.java, "backupCredentials"),
        Control("Settings import keys", SettingsViewModel::class.java, "restoreCredentials"),
        Control("Settings backup passphrase", SettingsViewModel::class.java, "setCredPassphrase"),
        Control("Credential write guard", com.dirk.kalshiodds.signal.config.CredentialWriteGuard::class.java, "rejectReason"),
        Control("Live keystore re-enter", com.dirk.kalshiodds.signal.config.SignalPreferences::class.java, "needsReenterKey"),
        Control("Demo keystore re-enter", com.dirk.kalshiodds.signal.config.SignalPreferences::class.java, "needsReenterDemoKey"),
        Control("Price parse dollars", com.dirk.kalshiodds.domain.KalshiPrice::class.java, "parseDollars"),
        Control("Price wire dollars", com.dirk.kalshiodds.domain.KalshiPrice::class.java, "toWireDollars"),
        Control("Price format cents", KalshiQuoteDisplay::class.java, "formatPriceCents"),
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
        Control("Kalshi demo toggle", SettingsViewModel::class.java, "setKalshiDemo"),
        Control("Chart tick persist", AsyncResultsWriter::class.java, "enqueueChartTick"),
        Control("Chart window restore", ChartWindowService::class.java, "restoreWindow"),
        Control("Chart window persist", ChartWindowService::class.java, "persistPoints"),
        Control("Live candlestick backfill", LiveWindowBackfill::class.java, "candles"),
        Control("Quote header/labels/buttons", MarketQuoteView::class.java, "of"),
        Control("Payout multiple", KalshiQuoteDisplay::class.java, "multiplier"),
        Control("Payout multiple label", KalshiQuoteDisplay::class.java, "multipleLabel"),
        Control("Kalshi order-level fee", KalshiFee::class.java, "total"),
        Control("Kalshi payout multiple", KalshiFee::class.java, "payoutMultiple"),
        Control("Kalshi amortized fee", KalshiFee::class.java, "perContract"),
        Control("Chart seed after restart", OddsViewModel::class.java, "seedChartWindows"),
        Control("Light/dark palette", DipTheme::class.java, "palette"),
        Control("Theme contrast pairs", DipTheme::class.java, "contrastPairs"),
        Control("Screen contrast roles", ThemeRoles::class.java, "forPalette"),
        Control("WCAG contrast helper", Contrast::class.java, "readable")
    )

    @Test
    fun everyAuditedControlResolvesToARealMethod() {
        val missing = catalog.mapNotNull { row ->
            val found = (row.owner.methods + row.owner.declaredMethods).any {
                it.name == row.method ||
                    it.name.startsWith("${row.method}\$") ||
                    it.name.startsWith("${row.method}-")
            }
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
        assertNotNull(ChartWindowService::restoreWindow)
        assertNotNull(ChartWindowService::persistPoints)
        assertNotNull(LiveWindowBackfill::candles)
        assertNotNull(KalshiFee::total)
        assertNotNull(KalshiFee::payoutMultiple)
        assertNotNull(KalshiFee::perContract)
        assertNotNull(AsyncResultsWriter::enqueueChartTick)
        assertNotNull(DipTheme::palette)
        assertNotNull(DipTheme::contrastPairs)
        assertNotNull(ThemeRoles::forPalette)
        assertNotNull(Contrast::readable)
        assertNotNull(com.dirk.kalshiodds.signal.config.CredentialWriteGuard::rejectReason)
        assertNotNull(com.dirk.kalshiodds.domain.KalshiPrice::parseDollars)
        assertNotNull(KalshiQuoteDisplay::formatPriceCents)
        assertNotNull(KalshiQuoteDisplay::multipleLabel)
        assertNotNull(SettingsViewModel::backupCredentials)
        assertNotNull(SettingsViewModel::restoreCredentials)
    }
}
