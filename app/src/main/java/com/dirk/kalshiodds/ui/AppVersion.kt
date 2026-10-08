package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.BuildConfig

/**
 * On-screen version. Always [BuildConfig.VERSION_NAME] / [BuildConfig.VERSION_CODE]
 * — never a literal in UI or tests.
 */
object AppVersion {
    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE

    val label: String
        get() = "grok-bitcoin v$versionName ($versionCode)"
}
