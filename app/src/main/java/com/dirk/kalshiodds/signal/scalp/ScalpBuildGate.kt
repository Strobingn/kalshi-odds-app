package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.BuildConfig

/**
 * Compile-time paper-only lock for the bitcoin-swarm experiment branch.
 *
 * `SCALP_LIVE_TRADING` is a `buildConfigField` constant in `defaultConfig`
 * — it is identical in debug and release and there is no product-flavor
 * override. Every live-scalp path in the module checks this flag:
 *
 * - [ScalpEngine] routes to the paper executor unless it is true
 * - [LiveScalpExecutor] refuses enter/exit when it is false
 * - `SettingsViewModel.confirmScalpLiveMode` no-ops when it is false
 *
 * On this branch it is always `false`, so the app is structurally
 * incapable of live scalping regardless of settings.
 */
val scalpLiveTradingEnabled: Boolean
    get() = BuildConfig.SCALP_LIVE_TRADING
