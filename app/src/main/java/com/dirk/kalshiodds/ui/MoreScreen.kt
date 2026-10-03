package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.AppIdentity
import com.dirk.kalshiodds.ui.components.FieldActionButton
import com.dirk.kalshiodds.ui.components.FieldHeaderCard
import com.dirk.kalshiodds.ui.components.FieldSearchBar
import com.dirk.kalshiodds.ui.components.FieldSyncLine
import com.dirk.kalshiodds.ui.components.MoreToolRow
import com.dirk.kalshiodds.ui.theme.FieldMetrics

@Composable
fun MoreScreen(
    onOpen: (MoreDestination) -> Unit,
    syncText: String,
    syncFailed: Boolean,
    syncPending: Boolean,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val buttonRoutes = DipNav.primaryActions.map { it.route }.toSet()
    val visible = DipNav.moreDestinations.filter { dest ->
        dest.route !in buttonRoutes && (
            needle.isBlank() ||
                dest.label.contains(needle, ignoreCase = true) ||
                dest.blurb.contains(needle, ignoreCase = true) ||
                dest.group.contains(needle, ignoreCase = true)
            )
    }
    val groups = DipNav.groupsInOrder.map { group ->
        group to visible.filter { it.group == group }
    }.filter { it.second.isNotEmpty() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = dipContentInsets(),
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = FieldMetrics.screenPadding),
            verticalArrangement = Arrangement.spacedBy(FieldMetrics.space8)
        ) {
            item {
                FieldHeaderCard(
                    title = AppIdentity.LABEL,
                    subtitle = "BTC windows · paper and live",
                    version = "v${AppVersion.versionName}"
                )
                Column(
                    modifier = Modifier.padding(top = FieldMetrics.space12),
                    verticalArrangement = Arrangement.spacedBy(FieldMetrics.space8)
                ) {
                    DipNav.primaryActions.forEach { action ->
                        FieldActionButton(
                            label = action.label,
                            onClick = { onOpen(action) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FieldMetrics.space8))
                FieldSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search tools"
                )
                Spacer(modifier = Modifier.height(FieldMetrics.space8))
                FieldSyncLine(text = syncText, failed = syncFailed, pending = syncPending)
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        "No tools match “$needle”.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = FieldMetrics.space24)
                    )
                }
            }
            groups.forEach { (group, rows) ->
                item(key = "group-$group") {
                    Text(
                        group,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = FieldMetrics.space12, bottom = FieldMetrics.space4)
                    )
                }
                items(rows, key = { "${it.group}-${it.label}" }) { dest ->
                    MoreToolRow(dest = dest, onClick = { onOpen(dest) })
                }
            }
            item { Spacer(modifier = Modifier.height(FieldMetrics.space24)) }
        }
    }
}
