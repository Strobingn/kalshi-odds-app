package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.components.MarketCard
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.Bg
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OddsScreen(viewModel: OddsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Kalshi Odds") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Bg,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = AccentBlue
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val snapshot = state.snapshot
            if (snapshot == null && state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Bg),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        MetaHeader(
                            fetchedAtEpochMs = snapshot?.fetchedAtEpochMs ?: 0L,
                            fromCache = snapshot?.fromCache == true,
                            message = state.userMessage
                        )
                    }
                    item { SectionHeader("Bitcoin · KXBTC15M") }
                    marketsOrEmpty(snapshot?.btc.orEmpty())
                    item { Spacer(Modifier.height(8.dp)); SectionHeader("WTI Crude · KXWTI15M") }
                    marketsOrEmpty(snapshot?.wti.orEmpty())
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.marketsOrEmpty(markets: List<MarketUiModel>) {
    if (markets.isEmpty()) {
        item {
            Text(
                text = "No open markets",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    } else {
        items(markets, key = { it.ticker }) { market ->
            MarketCard(market)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun MetaHeader(fetchedAtEpochMs: Long, fromCache: Boolean, message: String?) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (fetchedAtEpochMs > 0) {
                    "Updated ${formatLocal(fetchedAtEpochMs)}"
                } else {
                    "Not yet updated"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            if (fromCache) {
                Text(
                    text = "CACHED",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentBlue
                )
            }
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            text = "YES % = mid(bid, ask) when both present, else last price. Auto-refresh every 15 min.",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private val stampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)

private fun formatLocal(epochMs: Long): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return stampFormatter.format(zoned)
}
