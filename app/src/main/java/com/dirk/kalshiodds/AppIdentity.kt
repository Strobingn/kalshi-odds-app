package com.dirk.kalshiodds

/**
 * Side-by-side install identity for Kashi's 0.3.16 build.
 *
 * [APPLICATION_ID] is the Android package name (must differ from the
 * Claude-branch app, which keeps `com.dirk.kalshiodds`). The Kotlin
 * namespace stays [NAMESPACE]. Storage file names are unchanged from
 * 0.3.15 so `adb run-as` can copy the old app's DB/prefs in.
 */
object AppIdentity {
    const val APPLICATION_ID = "com.dirk.kalshiodds.kashi"
    const val NAMESPACE = "com.dirk.kalshiodds"
    const val LABEL = "Kalshi Trader"

    /** FileProvider for the in-app debug APK. Authority stays on this applicationId. */
    const val FILE_PROVIDER_AUTHORITY = "$APPLICATION_ID.fileprovider"

    const val DB_RESULTS = "diphunter_results.db"
    const val DB_VERSION = 8

    const val PREFS_KEEPALIVE = "diphunter_keepalive"
    const val PREFS_PAPER = "diphunter_paper_book"
    const val PREFS_SHADOW = "diphunter_shadow_book"
    const val PREFS_LAST_MINUTE = "diphunter_last_minute"
    const val PREFS_D3 = "diphunter_d3"
    const val PREFS_LAST_ORDER = "diphunter_last_order_error"
    const val PREFS_OOM = "diphunter_oom"
    const val PREFS_SECRETS = "kalshi_signal_secrets"
    const val PREFS_SECRETS_FALLBACK = "kalshi_signal_secrets_fallback"
    const val PREFS_SECRETS_LEGACY = "kalshi_signal_secrets_legacy"
    const val PREFS_EXTRA = "diphunter_extra_secrets"
    const val PREFS_EXTRA_FALLBACK = "diphunter_extra_secrets_fallback"

    const val DS_SIGNAL = "diphunter_signal_prefs"
    const val DS_PREDICTION = "diphunter_prediction_log"
    const val DS_GUARDRAILS = "diphunter_guardrails"
    const val DS_ADAPTER = "diphunter_adapter"
    const val DS_HUB = "diphunter_data_hub"
    const val DS_HEAVY_ML = "diphunter_heavy_ml"
    const val DS_MARKET = "kalshi_market_cache"

    const val CHANNEL_ALERTS = "diphunter_signal_alerts"
    const val CHANNEL_FOREGROUND = "diphunter_live_signals_ongoing"
    const val CHANNEL_FOREGROUND_LEGACY = "diphunter_live_signals"
    const val CHANNEL_LAST_MINUTE = "diphunter_last_minute"
    const val CHANNEL_D3 = "diphunter_d3"
    const val CHANNEL_OPPORTUNITY = "diphunter_opportunities"

    const val WM_LIVE_PERIODIC = "diphunter_live_signals_watchdog"
    const val WM_LIVE_SOON = "diphunter_live_signals_watchdog_soon"
    const val WM_CLOUD_SYNC = "diphunter_cloud_sync"
    const val WM_CLOUD_ONCE = "diphunter_cloud_sync_once"
    const val WM_BACKFILL = "diphunter-backfill"
    const val WM_MARKET_REFRESH = "kalshi_market_refresh_15m"

    /**
     * Every applicationId-dependent surface. Channel IDs, WorkManager
     * names, PendingIntent components, and storage files are per-UID
     * once [APPLICATION_ID] changes — they keep 0.3.15 file names so
     * an adb copy lands in the new sandbox.
     */
    fun wiringAuditItems(): List<String> = listOf(
        "applicationId=$APPLICATION_ID",
        "namespace=$NAMESPACE (Kotlin package unchanged)",
        "launcher/notification label=$LABEL",
        "FileProvider=\${applicationId}.fileprovider = $FILE_PROVIDER_AUTHORITY",
        "ContentProvider=FileProvider",
        "deep links=none",
        "manifest authorities=\${applicationId}.fileprovider",
        "PendingIntent targets=explicit MainActivity / LiveSignalsService",
        "intent actions stay $NAMESPACE.signal.service.STOP_LIVE_SIGNALS / RESUME_LIVE_SIGNALS",
        "battery settings URI=package:\$packageName",
        "BuildConfig.APPLICATION_ID=$APPLICATION_ID",
        "SQLite databases/$DB_RESULTS v$DB_VERSION (+ -wal -shm); paper_fills lives in this file",
        "SharedPreferences $PREFS_KEEPALIVE.xml",
        "SharedPreferences $PREFS_PAPER.xml",
        "SharedPreferences $PREFS_SHADOW.xml",
        "SharedPreferences $PREFS_LAST_MINUTE.xml",
        "SharedPreferences $PREFS_D3.xml",
        "SharedPreferences $PREFS_LAST_ORDER.xml",
        "SharedPreferences $PREFS_OOM.xml",
        "EncryptedSharedPreferences $PREFS_SECRETS.xml",
        "SharedPreferences $PREFS_SECRETS_FALLBACK.xml",
        "SharedPreferences $PREFS_SECRETS_LEGACY.xml",
        "EncryptedSharedPreferences $PREFS_EXTRA.xml",
        "SharedPreferences $PREFS_EXTRA_FALLBACK.xml",
        "DataStore files/datastore/$DS_SIGNAL.preferences_pb",
        "DataStore files/datastore/$DS_PREDICTION.preferences_pb",
        "DataStore files/datastore/$DS_GUARDRAILS.preferences_pb",
        "DataStore files/datastore/$DS_ADAPTER.preferences_pb",
        "DataStore files/datastore/$DS_HUB.preferences_pb",
        "DataStore files/datastore/$DS_HEAVY_ML.preferences_pb",
        "DataStore files/datastore/$DS_MARKET.preferences_pb",
        "channel $CHANNEL_ALERTS",
        "channel $CHANNEL_FOREGROUND",
        "channel $CHANNEL_FOREGROUND_LEGACY",
        "channel $CHANNEL_LAST_MINUTE",
        "channel $CHANNEL_D3",
        "channel $CHANNEL_OPPORTUNITY",
        "WorkManager $WM_LIVE_PERIODIC",
        "WorkManager $WM_LIVE_SOON",
        "WorkManager $WM_CLOUD_SYNC",
        "WorkManager $WM_CLOUD_ONCE",
        "WorkManager $WM_BACKFILL",
        "WorkManager $WM_MARKET_REFRESH",
        "WakeLock tag diphunter:live-signals (per-UID)"
    )
}
