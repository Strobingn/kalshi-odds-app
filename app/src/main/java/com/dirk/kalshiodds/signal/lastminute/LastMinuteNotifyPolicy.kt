package com.dirk.kalshiodds.signal.lastminute

/**
 * Last-minute heads-up is independent of the live-signals FGS and of
 * whether the Activity is visible. Dirk still wants the HIGH-importance
 * notification when the app is in the foreground.
 */
object LastMinuteNotifyPolicy {
    fun shouldNotify(uiInForeground: Boolean): Boolean = true
}
