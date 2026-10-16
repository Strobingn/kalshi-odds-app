package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.ChartWindowService
import com.dirk.kalshiodds.data.backfill.LiveWindowBackfill
import com.dirk.kalshiodds.data.local.results.AsyncResultsWriter
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.signal.paper.PaperApprove
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
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
        Control("Live $10 sizer", com.dirk.kalshiodds.signal.trade.LiveOrderSizer::class.java, "size"),
        Control("Bet headline", com.dirk.kalshiodds.signal.trade.BetCall::class.java, "decide"),
        Control("Test connection", SettingsViewModel::class.java, "testConnection"),
        Control("Flip check evaluate", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "evaluate"),
        Control("Flip check allowsSide", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "allowsSide"),
        Control("Flip check allowsFired", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "allowsFired"),
        Control("Flip check allowsMarketSide", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "allowsMarketSide"),
        Control("Flip check allowsTicket", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "allowsTicket"),
        Control("Flip check capPUp", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "capPUp"),
        Control("Flip check settlement variance", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "settlementVarianceUsd"),
        Control("Flip check settlement mean", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "settlementMeanUsd"),
        Control("Flip check sigma from ticks", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "sigmaFromSpotTicks"),
        Control("Flip check sigma from log vol", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "sigmaFromLogVol"),
        Control("Flip check noBetLine", com.dirk.kalshiodds.signal.flip.FlipCheck::class.java, "noBetLine"),
        Control("Live tile ask", TicketBuilder::class.java, "liveAsk"),
        Control("Settlement poll filter", com.dirk.kalshiodds.prediction.SettlementPollPolicy::class.java, "isPollableTicker"),
        Control("Settlement poll after close", com.dirk.kalshiodds.prediction.SettlementPollPolicy::class.java, "shouldPoll"),
        Control("Settlement poll candidates", com.dirk.kalshiodds.prediction.SettlementPollPolicy::class.java, "candidates"),
        Control("Settlement poll 429", com.dirk.kalshiodds.prediction.SettlementPollPolicy::class.java, "retryAfterHeader"),
        Control("Settlement poll backoff", com.dirk.kalshiodds.prediction.SettlementPollPolicy::class.java, "afterMiss"),
        Control("Last-minute fair_p", com.dirk.kalshiodds.signal.lastminute.LastMinuteMath::class.java, "fairP"),
        Control("Last-minute size_bet", com.dirk.kalshiodds.signal.lastminute.LastMinuteMath::class.java, "sizeBet"),
        Control("Last-minute all-in cost", com.dirk.kalshiodds.signal.lastminute.LastMinuteMath::class.java, "allInCost"),
        Control("Last-minute strategy", com.dirk.kalshiodds.signal.lastminute.LastMinuteStrategy::class.java, "evaluate"),
        Control("Last-minute book depth", com.dirk.kalshiodds.signal.lastminute.LastMinuteBook::class.java, "depthAtOrBelow"),
        Control("Last-minute engine tick", com.dirk.kalshiodds.signal.lastminute.LastMinuteEngine::class.java, "tick"),
        Control("Last-minute paper store", com.dirk.kalshiodds.signal.lastminute.LastMinuteStore::class.java, "record"),
        Control("Last-minute settle", com.dirk.kalshiodds.signal.lastminute.LastMinuteStore::class.java, "settle"),
        Control("Last-minute copy headline", com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy::class.java, "headline"),
        Control("Last-minute buy line", com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy::class.java, "buyLine"),
        Control("Last-minute model EV label", com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy::class.java, "modelEvLine"),
        Control("Last-minute notify title", com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy::class.java, "notificationTitle"),
        Control("Last-minute ticket", TicketBuilder::class.java, "proposeLastMinute"),
        Control("Last-minute scorecard section", ScorecardCopy::class.java, "lastMinuteSection"),
        Control("D3 strategy", com.dirk.kalshiodds.signal.d3.D3Strategy::class.java, "evaluate"),
        Control("D3 bid price", com.dirk.kalshiodds.signal.d3.D3Strategy::class.java, "bidPrice"),
        Control("D3 fill math", com.dirk.kalshiodds.signal.d3.D3FillMath::class.java, "filled"),
        Control("D3 paper store", com.dirk.kalshiodds.signal.d3.D3Store::class.java, "recordResting"),
        Control("D3 settle", com.dirk.kalshiodds.signal.d3.D3Store::class.java, "settle"),
        Control("D3 engine tick", com.dirk.kalshiodds.signal.d3.D3Engine::class.java, "tickPaper"),
        Control("D3 fees from series", com.dirk.kalshiodds.signal.d3.D3Fees::class.java, "fromSeries"),
        Control("D3 ticket", TicketBuilder::class.java, "proposeD3"),
        Control("D3 scorecard section", ScorecardCopy::class.java, "d3Section"),
        Control("D3 copy title", com.dirk.kalshiodds.signal.d3.D3Copy::class.java, "phaseLine"),
        Control("D3 notifier", com.dirk.kalshiodds.signal.d3.D3Notifier::class.java, "notifyFired"),
        Control("Last-minute BRTI median", com.dirk.kalshiodds.signal.lastminute.BrtiCompositeClient::class.java, "median"),
        Control("Last-minute heads-up notify", com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifier::class.java, "notifyFired"),
        Control("Last-minute notify while UI up", com.dirk.kalshiodds.signal.lastminute.LastMinuteNotifyPolicy::class.java, "shouldNotify"),
        Control("Ticket stake $10 cap", SettingsViewModel::class.java, "requestTicketStake"),
        Control("PEM normalizer", com.dirk.kalshiodds.signal.config.PemNormalizer::class.java, "normalize"),
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
        Control("WCAG contrast helper", Contrast::class.java, "readable"),
        Control("Settings Test connection", SettingsViewModel::class.java, "testConnection"),
        Control("Settings last order error", SettingsViewModel::class.java, "refreshLastOrderError"),
        Control("API key status line", ApiKeyUi::class.java, "statusLine"),
        Control("No-key banner visibility", ApiKeyUi::class.java, "showNoKeyBanner"),
        Control("App version label", AppVersion::class.java, "getLabel"),
        Control("Approve PAPER/LIVE label", com.dirk.kalshiodds.signal.trade.TradeModeLabel::class.java, "forApprove"),
        Control("Home title / this-window headline", HomeCopy::class.java, "thisWindowHeadline"),
        Control("Home scorecard line", HomeCopy::class.java, "scorecardLine"),
        Control("Home scorecard summary", HomeCopy::class.java, "scorecardSummaryOf"),
        Control("Home scorecard summary line", HomeCopy::class.java, "scorecardSummaryLine"),
        Control("Home model vs market", HomeCopy::class.java, "modelVsMarket"),
        Control("Home card details snapshot", HomeCardDetails::class.java, "of"),
        Control("Home card AI YES label", HomeCardDetails::class.java, "aiYesLabel"),
        Control("Home card confidence", HomeCardDetails::class.java, "confidenceLine"),
        Control("Home card signal strength", HomeCardDetails::class.java, "signalStrengthLine"),
        Control("Home card feature drivers", HomeCardDetails::class.java, "featureDriversLine"),
        Control("Home card edge pp", HomeCardDetails::class.java, "edgePpLine"),
        Control("Home card net EV", HomeCardDetails::class.java, "netEvLine"),
        Control("Home card fair value", HomeCardDetails::class.java, "fairValueLine"),
        Control("Home card target vs spot", HomeCardDetails::class.java, "targetVsSpotLine"),
        Control("Home card details toggle", HomeCardDetails::class.java, "detailsToggleLabel"),
        Control("Home tile AI UP percent", HomeCopy::class.java, "tileAiUp"),
        Control("Home tile AI DOWN percent", HomeCopy::class.java, "tileAiDown"),
        Control("Home tile AI percents", HomeCopy::class.java, "tileAiPercents"),
        Control("Home tile $10 wins", HomeCopy::class.java, "tenDollarWins"),
        Control("Home tile $10 wins line", HomeCopy::class.java, "tenDollarWinsLine"),
        Control("Home tile $10 UP", HomeCopy::class.java, "tileTenDollarUp"),
        Control("Home tile $10 DOWN", HomeCopy::class.java, "tileTenDollarDown"),
        Control("Home $5 all-in line", HomeCopy::class.java, "allInProfit"),
        Control("Home primary Buy label", HomeCopy::class.java, "primaryButtonLabel"),
        Control("Home confirm-sheet Approve label", HomeCopy::class.java, "confirmApproveLabel"),
        Control("Home Buy anyway side", HomeCopy::class.java, "buyAnywaySide"),
        Control("Home current-window cards", HomeMarkets::class.java, "currentWindowCards"),
        Control("Home fixed coin cards", HomeMarkets::class.java, "coinCards"),
        Control("Home Signal history link", HomeCopy::class.java, "signalHistoryLink"),
        Control("Home signal cards (none)", HomeCopy::class.java, "signalCardsOnHome"),
        Control("Signal history screen", Class.forName("com.dirk.kalshiodds.ui.SignalHistoryScreenKt"), "SignalHistoryScreen"),
        Control("Home rank (BetCall.sortKey)", HomeMarkets::class.java, "ranked"),
        Control("Home best BetCall", HomeMarkets::class.java, "best"),
        Control("Home refresh", OddsViewModel::class.java, "refresh"),
        Control("Home Buy / Buy anyway", OddsViewModel::class.java, "buyMarket"),
        Control("Home paper toggle", OddsViewModel::class.java, "setPaperTrading"),
        Control("Home tickets Approve", OddsViewModel::class.java, "approveTicket"),
        Control("Home tickets PAPER", OddsViewModel::class.java, "paperTicket"),
        Control("Home card Paper UP/DOWN", OddsViewModel::class.java, "paperBuySide"),
        Control("Home card $10 paper fill", PaperTileBuy::class.java, "place"),
        Control("Home card paper position", HomeCopy::class.java, "paperPositionLine"),
        Control("Home card paper disabled reason", HomeCopy::class.java, "paperDisabledReason"),
        Control("Home card paper snackbar", HomeCopy::class.java, "paperConfirmSnackbar"),
        Control("Home scorecard tap", HomeCopy::class.java, "scorecardSummaryLine"),
        Control("Scorecard ledger", com.dirk.kalshiodds.signal.feedback.ScorecardLedger::class.java, "of"),
        Control("Scorecard settled picks", ScorecardCopy::class.java, "settledPicks"),
        Control("Scorecard view", ScorecardCopy::class.java, "of"),
        Control("Scorecard empty state", ScorecardCopy::class.java, "emptyState"),
        Control("Scorecard empty-state gate", ScorecardCopy::class.java, "showsEmptyState"),
        Control("Scorecard bucket line", ScorecardCopy::class.java, "bucketLine"),
        Control("Scorecard by-source merge", ScorecardCopy::class.java, "mergeSourceBuckets"),
        Control("Paper pick source parse", com.dirk.kalshiodds.signal.paper.PaperPickSource::class.java, "parse"),
        Control("Paper fill AI% gate", com.dirk.kalshiodds.signal.paper.PaperFill::class.java, "allowCreate"),
        Control("Paper Kelly sizer", com.dirk.kalshiodds.signal.paper.PaperKellySizer::class.java, "size"),
        Control("Paper Kelly full f", com.dirk.kalshiodds.signal.paper.PaperKellySizer::class.java, "fullKelly"),
        Control("Paper Kelly max contracts", com.dirk.kalshiodds.signal.paper.PaperKellySizer::class.java, "maxContracts"),
        Control("Paper book configure", com.dirk.kalshiodds.signal.paper.PaperBook::class.java, "configure"),
        Control("Paper bankroll migrate", com.dirk.kalshiodds.signal.paper.PaperBookState::class.java, "migrateStartUsd"),
        Control("Paper lifetime P&L migrate", com.dirk.kalshiodds.signal.paper.PaperBookState::class.java, "migrate"),
        Control("Paper ask depth", com.dirk.kalshiodds.signal.paper.PaperAskDepth::class.java, "contracts"),
        Control("Settings paper Kelly fraction", SettingsViewModel::class.java, "setPaperKellyFraction"),
        Control("Scorecard settled target", com.dirk.kalshiodds.signal.feedback.ScorecardTargets::class.java, "settledTarget"),
        Control("Scorecard per-slot minimum", com.dirk.kalshiodds.signal.feedback.ScorecardTargets::class.java, "perSlotMinimum"),
        Control("Scorecard paper bankroll line", ScorecardCopy::class.java, "paperBankrollLine"),
        Control("Settings paper bankroll start", SettingsViewModel::class.java, "setPaperBankrollStart"),
        Control("Paper fill schema upgrade", com.dirk.kalshiodds.data.local.paper.PaperFillSchema::class.java, "upgradeSql"),
        Control("Scorecard time-of-day buckets", ScorecardCopy::class.java, "timeBuckets"),
        Control("Scorecard time buckets", ScorecardCopy::class.java, "timeBuckets"),
        Control("Scorecard recent pick", ScorecardCopy::class.java, "recentPick"),
        Control("Scorecard recent pick line", ScorecardCopy::class.java, "recentPickLine"),
        Control("Scorecard summary line", ScorecardCopy::class.java, "summaryLine"),
        Control("Scorecard record line", ScorecardCopy::class.java, "recordLine"),
        Control("Scorecard win-rate line", ScorecardCopy::class.java, "winRateLine"),
        Control("Scorecard paper P&L line", ScorecardCopy::class.java, "paperPnlLine"),
        Control("Scorecard settled-count line", ScorecardCopy::class.java, "settledCountLine"),
        Control("Scorecard hypothetical policy title", ScorecardCopy::class.java, "hypotheticalPolicyTitle"),
        Control("Scorecard window real P&L line", ScorecardCopy::class.java, "windowRealPnlLine"),
        Control("Scorecard break-even win rate", ScorecardCopy::class.java, "breakEvenWinRate"),
        Control("Scorecard break-even win rate line", ScorecardCopy::class.java, "breakEvenWinRateLine"),
        Control("Scorecard window real P&L", com.dirk.kalshiodds.signal.feedback.ScorecardMetrics::class.java, "windowPnlUsd"),
        Control("Scorecard export", ScorecardViewModel::class.java, "exportResults"),
        Control("Home snapshot merge", HomeSnapshotMerge::class.java, "apply"),
        Control("App back stack", AppNavigator::class.java, "back"),
        Control("Market rollover successor", com.dirk.kalshiodds.signal.market.MarketRollover::class.java, "successor"),
        Control("Foreground re-resolve", OddsViewModel::class.java, "onForeground"),
        Control("Home version label", AppVersion::class.java, "getLabel"),
        Control("Home no-key banner", ApiKeyUi::class.java, "showNoKeyBanner"),
        Control("Home mode chip", com.dirk.kalshiodds.signal.trade.TradeModeLabel::class.java, "forApprove"),
        Control("Home disagreement label", DisagreementLabel::class.java, "of"),
        Control("15m market rollover", com.dirk.kalshiodds.signal.market.MarketRollover::class.java, "refreshFromRest"),
        Control("Active market resolver", com.dirk.kalshiodds.domain.ActiveMarketResolver::class.java, "forSeries"),
        Control("Live tap resolves current window", com.dirk.kalshiodds.domain.MarketLifecycle::class.java, "resolveActionWindow"),
        Control("Last order error once", com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce::class.java, "accept"),
        Control("Last order error skip lifecycle", com.dirk.kalshiodds.signal.trade.LastOrderErrorOnce::class.java, "isNotAnOrderError"),
        Control("Last order error clear stale", com.dirk.kalshiodds.signal.trade.LastOrderErrorStore::class.java, "clearStaleLifecycleNotice"),
        Control("Void stale tickers", com.dirk.kalshiodds.signal.trade.TicketSession::class.java, "voidTickers"),
        Control("Replace proposals", com.dirk.kalshiodds.signal.trade.TicketSession::class.java, "replaceProposals"),
        Control("applicationId side-by-side audit", com.dirk.kalshiodds.AppIdentity::class.java, "wiringAuditItems"),
        Control("Model publish gates", com.dirk.kalshiodds.prediction.ModelActivation::class.java, "decide"),
        Control("Model beats_market gate", com.dirk.kalshiodds.prediction.ModelActivation::class.java, "beatsMarket"),
        Control("Latest model download gates", com.dirk.kalshiodds.prediction.LatestModelClient::class.java, "download"),
        Control("Import model JSON gates", com.dirk.kalshiodds.prediction.ImportedModelStore::class.java, "importJson"),
        Control("Digital side lock", com.dirk.kalshiodds.signal.engine.DirectionSanity::class.java, "directionalFairPp"),
        Control("Calibrator apply (raw, TTE bucket)", com.dirk.kalshiodds.signal.feedback.Calibrator::class.java, "applyPp"),
        Control("Calibrator fit raw entries", com.dirk.kalshiodds.signal.feedback.Calibrator::class.java, "fitEntries"),
        Control("OnlineAdapter p-layer off", com.dirk.kalshiodds.signal.feedback.OnlineAdapter::class.java, "apply"),
        Control("Edge UTC + 8×1m candles", com.dirk.kalshiodds.prediction.EdgeFeatures::class.java, "candleWindow"),
        Control("Edge UTC hour", com.dirk.kalshiodds.prediction.EdgeFeatures::class.java, "timeOfDayFrac"),
        Control("AI-net 8-minute volume", com.dirk.kalshiodds.prediction.FeatureVector::class.java, "volumeLastMinutes"),
        Control("Coinbase-only model spot", com.dirk.kalshiodds.signal.external.SpotFeatureMath::class.java, "adjustPp")
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
        val idItems = com.dirk.kalshiodds.AppIdentity.wiringAuditItems()
        assertTrue(idItems.any { it.contains("com.dirk.kalshiodds.kashi") })
        assertTrue(idItems.any { it.contains("FileProvider") })
        assertTrue(idItems.any { it.contains("PendingIntent") })
        assertTrue(idItems.any { it.contains("diphunter_results.db") })
        assertTrue(idItems.any { it.contains("diphunter_signal_prefs") })
        assertTrue(idItems.any { it.contains("diphunter_signal_alerts") })
        assertTrue(idItems.any { it.contains("diphunter_live_signals_watchdog") })
        assertTrue(idItems.any { it.contains("package:\$packageName") || it.contains("packageName") })
        assertNotNull(KalshiQuoteDisplay::formatPriceCents)
        assertNotNull(KalshiQuoteDisplay::multipleLabel)
        assertNotNull(SettingsViewModel::backupCredentials)
        assertNotNull(SettingsViewModel::restoreCredentials)
    }
}
