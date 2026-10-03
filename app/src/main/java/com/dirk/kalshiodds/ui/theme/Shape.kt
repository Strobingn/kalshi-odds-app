package com.dirk.kalshiodds.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape scale — larger radii for the FieldOps chrome.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object FieldShapes {
    val card = RoundedCornerShape(20.dp)
    val cardLarge = RoundedCornerShape(20.dp)
    val chip = RoundedCornerShape(100.dp)
    val button = RoundedCornerShape(14.dp)
    val search = RoundedCornerShape(16.dp)
    val fab = RoundedCornerShape(18.dp)
    val bottomSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val hero = RoundedCornerShape(24.dp)
}
