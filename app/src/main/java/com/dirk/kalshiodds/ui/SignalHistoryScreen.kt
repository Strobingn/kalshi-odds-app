package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.ui.components.SignalSummaryCard
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalHistoryScreen(
    cards: List<SignalCopy.Card>,
    onBack: () -> Unit
) {
    val colors = DipTheme.colors
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(HomeCopy.SIGNAL_HISTORY) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accentBlue
                )
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .background(colors.bg),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (cards.isEmpty()) {
                item {
                    Text(
                        "No signals yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            } else {
                items(cards, key = { "${it.title}-${it.call}-${it.modelLine}" }) { card ->
                    SignalSummaryCard(card)
                }
            }
        }
    }
}

fun signalHistoryCards(alerts: List<SignalAlert>): List<SignalCopy.Card> =
    alerts.map { SignalCopy.card(it) }
