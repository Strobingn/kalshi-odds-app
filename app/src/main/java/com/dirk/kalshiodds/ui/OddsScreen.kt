package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel

@Composable
fun OddsScreen(
    viewModel: OddsViewModel,
    onOpenSettings: () -> Unit,
    onOpenApiKeySettings: () -> Unit = onOpenSettings,
    onOpenScorecard: () -> Unit,
    onOpenData: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSignalHistory: () -> Unit = onOpenHistory,
    onOpenChart: (MarketUiModel) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenApiKeySettings = onOpenApiKeySettings,
        onOpenScorecard = onOpenScorecard,
        onOpenHistory = onOpenHistory,
        onOpenSignalHistory = onOpenSignalHistory,
        onOpenChart = onOpenChart,
        onRefresh = { viewModel.refresh() },
        onBuyMarket = { market, side -> viewModel.buyMarket(market, side) },
        onPaperSide = { market, side -> viewModel.paperBuySide(market, side) },
        onSellMarket = { market ->
            val pos = state.positions.firstOrNull { it.ticker == market.ticker } ?: return@HomeScreen
            viewModel.sellPosition(pos.ticker, pos.side)
        },
        onSetPaperTrading = viewModel::setPaperTrading,
        onResetPaper = viewModel::resetPaperBook,
        onSellPosition = { ticker, side -> viewModel.sellPosition(ticker, side) },
        onReviewTicket = { viewModel.openTicketApprove(it) },
        onDismissTicket = { viewModel.dismissTicket(it) },
        onApproveTicket = { viewModel.approveTicket(it) },
        onApproveSellTicket = { id, count, price -> viewModel.approveSellTicket(id, count, price) },
        onPaperTicket = { viewModel.paperTicket(it) },
        onPaperSellTicket = { id, count, price -> viewModel.paperSellTicket(id, count, price) },
        onCancelApprove = { viewModel.cancelTicketApprove() },
        onCancelOrder = { viewModel.cancelWorkingOrder(it) },
        onLimitCents = { id, text -> viewModel.reviseTicketLimit(id, text) },
        onResumeAlerts = { viewModel.resumeAlerts() }
    )
}

@Composable
fun LiveApproveScreen(viewModel: OddsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey = state.settings.tradingCredentialsConfigured()
    androidx.compose.material3.Scaffold(
        containerColor = com.dirk.kalshiodds.ui.theme.DipTheme.colors.bg,
        contentWindowInsets = dipContentInsets()
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(com.dirk.kalshiodds.ui.theme.FieldMetrics.screenPadding)
        ) {
            com.dirk.kalshiodds.ui.components.TradeTicketsSection(
                tickets = state.tickets,
                credentialsConfigured = hasKey,
                paperTradingEnabled = state.settings.paperTradingEnabled,
                feeRate = state.settings.feeRate,
                homeMode = false,
                listVisible = true,
                onReview = { viewModel.openTicketApprove(it) },
                onDismiss = { viewModel.dismissTicket(it) },
                onApprove = { viewModel.approveTicket(it) },
                onApproveSell = { id, count, price -> viewModel.approveSellTicket(id, count, price) },
                onPaper = { viewModel.paperTicket(it) },
                onPaperSell = { id, count, price -> viewModel.paperSellTicket(id, count, price) },
                onCancelApprove = { viewModel.cancelTicketApprove() },
                onCancelOrder = { viewModel.cancelWorkingOrder(it) },
                onLimitCents = { id, text -> viewModel.reviseTicketLimit(id, text) }
            )
        }
    }
}

internal fun featuredLiveMarket(
    markets: List<MarketUiModel>,
    nowMs: Long = System.currentTimeMillis()
): MarketUiModel? = MarketLifecycle.featuredLive(markets, nowMs)
