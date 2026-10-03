package com.dirk.kalshiodds.ui.theme

/**
 * Packed ARGB (`0xAARRGGBB`) for Dirk's FieldOps chrome, ported onto DipHunter.
 * Compose [androidx.compose.ui.graphics.Color] tokens in Color.kt read these so
 * unit tests can assert WCAG contrast without pulling in Android views.
 *
 * Dark: greyscale chrome + light-gray primary.
 * Light: paper surfaces, dark-gray primary, AA-safe body/status text.
 * Semantic red and green stay chromatic. Urgent is a strong red, never yellow,
 * amber, gold, orange, or lime. Primary and accent fills are neutral gray (no blue).
 *
 * DipHunter keeps green for UP and red for DOWN. Those roles use [Dark.StatusCompleted]
 * / [Light.Success] and [Dark.Error] / [Light.Error], which clear 4.5:1 on both themes.
 */
object FieldSwatch {
    object Dark {
        const val Primary: Long = 0xFFD0D0D0
        const val PrimaryDark: Long = 0xFFB8B8B8
        const val PrimaryLight: Long = 0xFFE8E8E8
        const val PrimaryContainer: Long = 0xFF333333
        const val OnPrimary: Long = 0xFF111111
        const val OnPrimaryContainer: Long = 0xFFE8E8E8
        /** Page background. Stays near-black so lifted surfaces read as cards. */
        const val Background: Long = 0xFF121212
        /** Material surface and surfaceContainer. */
        const val Surface: Long = 0xFF2C2C2C
        /** Bottom navigation bar. A step under cards, still clear of the page. */
        const val NavBar: Long = 0xFF2A2A2A
        /** Selected bottom-nav pill. Light gray text on this fill stays above 4.5:1. */
        const val NavIndicator: Long = 0xFF4A4A4A
        /** Cards, search fields, and surfaceContainerHigh. */
        const val Card: Long = 0xFF333333
        const val Elevated: Long = 0xFF2C2C2C
        const val SurfaceVariant: Long = 0xFF333333
        const val SurfaceBright: Long = 0xFF404040
        const val OnBackground: Long = 0xFFF5F5F5
        const val OnSurface: Long = 0xFFF5F5F5
        const val OnSurfaceVariant: Long = 0xFFBDBDBD
        const val OnSurfaceMuted: Long = 0xFFB0B0B0
        const val Outline: Long = 0xFF5A5A5A
        const val OutlineVariant: Long = 0xFF5A5A5A
        /**
         * Light red on the lifted dark cards. Brighter than a dull red so a
         * 15% badge wash still clears 4.5:1 on #333333. Not yellow.
         * DipHunter DOWN text uses this red.
         */
        const val Error: Long = 0xFFFFB4B4
        const val OnError: Long = 0xFF3B0002
        const val ErrorContainer: Long = 0xFF5C1A1A
        const val OnErrorContainer: Long = 0xFFFFDAD6
        const val StatusPending: Long = 0xFFD6D6D6
        const val StatusInProgress: Long = 0xFFC4C4C4
        const val StatusCompleted: Long = 0xFFA5D6A7
        const val StatusCancelled: Long = 0xFFFFB4B4
        /** Light red for urgent text on dark cards. Not yellow. */
        const val StatusUrgent: Long = 0xFFFFB0A8
        /** Dark red for urgent text on the light Today hero. Not for dark cards. */
        const val OnHeroWarning: Long = 0xFF4A0C0C
        const val AccentBlue: Long = 0xFFD0D0D0
        const val AccentPurple: Long = 0xFFC0C0C0
        const val AccentOrange: Long = 0xFFB8B8B8
        const val AccentCyan: Long = 0xFFE0E0E0
        const val AccentPink: Long = 0xFFA8A8A8
        const val AccentAmber: Long = 0xFFD0D0D0
        const val Success: Long = 0xFF81C784
        const val GradientStart: Long = 0xFFD0D0D0
        const val GradientMid: Long = 0xFFC0C0C0
        const val GradientEnd: Long = 0xFFB0B0B0
        const val Scrim: Long = 0xCC000000
        /** Opaque 15% UP wash of [StatusCompleted] over [Card]. */
        const val UpContainer: Long = 0xFF444B44
        /** Opaque 15% DOWN wash of [Error] over [Card]. */
        const val DownContainer: Long = 0xFF524646
    }

    object Light {
        const val Primary: Long = 0xFF3A3A3A
        const val PrimaryDark: Long = 0xFF2A2A2A
        const val PrimaryLight: Long = 0xFF5C5C5C
        const val PrimaryContainer: Long = 0xFFE6E6E6
        const val OnPrimary: Long = 0xFFFFFFFF
        const val OnPrimaryContainer: Long = 0xFF1A1A1A
        const val Background: Long = 0xFFF5F5F5
        const val Card: Long = 0xFFFFFFFF
        const val Elevated: Long = 0xFFEEEEEE
        const val SurfaceVariant: Long = 0xFFE6E6E6
        const val SurfaceBright: Long = 0xFFFFFFFF
        const val OnBackground: Long = 0xFF1A1C1E
        const val OnSurface: Long = 0xFF1A1C1E
        const val OnSurfaceVariant: Long = 0xFF44474F
        const val OnSurfaceMuted: Long = 0xFF575B63
        const val Outline: Long = 0xFF74777F
        const val OutlineVariant: Long = 0xFFC4C4C4
        const val Error: Long = 0xFF9B1B1B
        const val OnError: Long = 0xFFFFFFFF
        const val ErrorContainer: Long = 0xFFFFDAD6
        const val OnErrorContainer: Long = 0xFF410002
        /** Dark enough that pending chips clear 4.5:1 on the light card wash. */
        const val StatusPending: Long = 0xFF585C61
        const val StatusInProgress: Long = 0xFF3A3A3A
        const val StatusCompleted: Long = 0xFF1B5E20
        const val StatusCancelled: Long = 0xFF9B1B1B
        /** Dark red for urgent text on light cards. Not yellow or orange. */
        const val StatusUrgent: Long = 0xFF93000A
        /** Light red for urgent text on the dark Today hero. Not for light cards. */
        const val OnHeroWarning: Long = 0xFFFFCDD2
        /** Secondary fill. Distinct from PrimaryContainer so the label stays AA. */
        const val SecondaryContainer: Long = 0xFFE0E0E0
        const val AccentBlue: Long = 0xFF3A3A3A
        const val AccentPurple: Long = 0xFF4A4A4A
        const val AccentOrange: Long = 0xFF5A5A5A
        const val AccentCyan: Long = 0xFF2E2E2E
        const val AccentPink: Long = 0xFF454545
        const val AccentAmber: Long = 0xFF3A3A3A
        const val Success: Long = 0xFF1B5E20
        const val GradientStart: Long = 0xFF3A3A3A
        const val GradientMid: Long = 0xFF2E2E2E
        const val GradientEnd: Long = 0xFF242424
        const val Scrim: Long = 0x66000000
        /** Opaque 15% UP wash of [Success] over the light card [Elevated]. */
        const val UpContainer: Long = 0xFFCED8CF
        /** Opaque 15% DOWN wash of [Error] over the light card [Elevated]. */
        const val DownContainer: Long = 0xFFE2CECE
    }

    const val OverlayOnDark: Long = 0xFFF5F5F5
    const val OverlayOnDarkMuted: Long = 0xFFBDBDBD
    const val OverlayScrim: Long = 0xC8000000
    const val Paper: Long = 0xFFFFFFFF
    const val OnPaper: Long = 0xFF111111
}
