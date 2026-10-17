package com.dirk.kalshiodds

import android.os.StrictMode

/** 0.3.81: debug builds log (never crash on) disk reads/writes and network on the main thread. */
object StartupStrictMode {
    fun enable() {
        runCatching {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build())
        }
    }
}
