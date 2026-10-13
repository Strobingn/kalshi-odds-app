package com.dirk.kalshiodds.crash

import android.util.Log
import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * 0.3.64: every background loop runs under SupervisorJob + CoroutineExceptionHandler, so one failed coroutine is
 * logged (breadcrumb + non-fatal crash file synced as kind "crash") instead of killing the process.
 */
object SafeScopes {
    fun handler(tag: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        runCatching { Log.e("SafeScopes", tag, t) }
        runCatching { CrashBreadcrumb.record(tag, t) }
        runCatching { CrashLog.write("coroutine:$tag", t, CrashLog.versionName, fatal = false) }
    }
    /** Child of [parent]'s Job (cancelled with it) that never propagates failures upward. */
    fun child(parent: CoroutineContext, tag: String): CoroutineScope =
        CoroutineScope(parent + SupervisorJob(parent[Job]) + handler(tag))
}
