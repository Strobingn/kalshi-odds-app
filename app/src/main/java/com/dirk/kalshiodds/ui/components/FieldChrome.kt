package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import com.dirk.kalshiodds.ui.AppRoutes
import com.dirk.kalshiodds.ui.DipNav
import com.dirk.kalshiodds.ui.MoreDestination
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.FieldMetrics
import com.dirk.kalshiodds.ui.theme.FieldShapes
import com.dirk.kalshiodds.ui.theme.FieldSwatch

@Composable
fun FieldCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = FieldShapes.card
    val cardModifier = if (onClick != null) {
        modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth().clip(shape)
    }
    Surface(
        modifier = cardModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (accentColor != null) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .heightIn(min = 72.dp)
                        .align(Alignment.CenterVertically)
                        .background(accentColor)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(FieldMetrics.cardPadding),
                content = content
            )
        }
    }
}

@Composable
fun FieldSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search tools"
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = FieldShapes.search
    )
}

@Composable
fun FieldHeaderCard(
    title: String,
    subtitle: String,
    version: String,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = FieldMetrics.space8)
            .background(
                Brush.horizontalGradient(listOf(colors.gradientStart, colors.gradientEnd)),
                FieldShapes.hero
            )
            .padding(FieldMetrics.space20)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.onAccentBlue.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "D",
                    color = colors.onAccentBlue,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onAccentBlue,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onAccentBlue.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                version,
                color = colors.onAccentBlue.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun moreActionContainer(): Color {
    val dark = DipTheme.colors.bg.luminance() < 0.5f
    return if (dark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    }
}

@Composable
fun FieldActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(FieldMetrics.primaryTouch),
        shape = FieldShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = moreActionContainer(),
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun FieldSyncLine(text: String, failed: Boolean, pending: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        failed -> MaterialTheme.colorScheme.error
        pending -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

private fun tabIcon(route: String): ImageVector = when (route) {
    AppRoutes.HOME -> Icons.Filled.Home
    AppRoutes.SCALP_TAB -> Icons.Filled.ShowChart
    AppRoutes.SCORECARD -> Icons.Filled.Assessment
    AppRoutes.LIVE -> Icons.Filled.Receipt
    AppRoutes.REAL_MONEY -> Icons.Filled.AccountBalance
    AppRoutes.DATA -> Icons.Filled.Storage
    AppRoutes.SETTINGS -> Icons.Filled.Settings
    AppRoutes.HISTORY -> Icons.Filled.History
    AppRoutes.SIGNAL_HISTORY -> Icons.Filled.Notifications
    else -> Icons.Filled.MoreHoriz
}

@Composable
fun DipBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = DipTheme.colors.bg.luminance() < 0.5f
    NavigationBar(
        modifier = modifier,
        containerColor = if (dark) {
            MaterialTheme.colorScheme.surfaceDim
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp
    ) {
        DipNav.tabs.forEach { route ->
            val selected = current == route
            val label = DipNav.tabLabel.getValue(route)
            NavigationBarItem(
                icon = { Icon(tabIcon(route), contentDescription = label) },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                },
                selected = selected,
                onClick = { onSelect(route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = if (dark) {
                        Color(FieldSwatch.Dark.NavIndicator)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    },
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
fun MoreToolRow(dest: MoreDestination, onClick: () -> Unit) {
    FieldCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FieldMetrics.minTouch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                tabIcon(dest.route),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.size(FieldMetrics.space12))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dest.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    dest.blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
