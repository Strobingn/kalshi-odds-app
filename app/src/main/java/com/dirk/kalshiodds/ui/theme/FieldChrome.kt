package com.dirk.kalshiodds.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** List card: surfaceContainerLow fill, 1 dp outline, 20 dp corners. */
fun Modifier.fieldCard(container: Color, stroke: Color): Modifier =
    this
        .border(1.dp, stroke, FieldShapes.card)
        .background(container, FieldShapes.card)

@Composable
fun fieldTextColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedBorderColor = MaterialTheme.colorScheme.outline,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    disabledBorderColor = MaterialTheme.colorScheme.outline,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/**
 * Header card. Gray gradient, 24 dp corners, 20 dp padding.
 * Title is [titleLarge] Bold in onPrimary.
 */
@Composable
fun FieldHero(
    title: String,
    subtitle: String,
    version: String,
    mark: String,
    modifier: Modifier = Modifier
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(GradientStart, GradientEnd)),
                FieldShapes.hero
            )
            .padding(20.dp)
    ) {
        Box(
            Modifier
                .size(FieldMetrics.logo)
                .background(onPrimary.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                mark,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onPrimary
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = onPrimary,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = onPrimary.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            version,
            style = MaterialTheme.typography.labelMedium,
            color = onPrimary.copy(alpha = 0.90f),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
