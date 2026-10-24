package com.dirk.kalshiodds.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Dark Arena dashboard chrome from the Scalper Arena mock (#0b0f17 / #131a26).
 * Kept in ui/theme so HardcodedColorGuardTest stays green; [ArenaChrome] in
 * ui/ picks light vs dark at composition time.
 */
object ArenaChromeColors {
    val BgDark = Color(0xFF0B0F17)
    val SurfaceDark = Color(0xFF131A26)
    val SurfaceAltDark = Color(0xFF1B2434)
    val BorderDark = Color(0xFF263248)
}
