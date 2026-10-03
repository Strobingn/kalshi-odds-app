package com.dirk.kalshiodds.signal.config

import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/**
 * Android Keystore / EncryptedSharedPreferences can fail with a bad auth tag
 * after a key rotation. The live key must still be replaceable: delete only
 * the broken encrypted prefs, recreate the master key, and open a new
 * encrypted file. Never write the replacement into plaintext.
 */
object KeystoreLockout {
    fun isLockout(error: Throwable): Boolean {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 8) {
            if (current is AEADBadTagException || current is KeyStoreException) return true
            val name = current.javaClass.name
            if (name.contains("AEADBadTag", ignoreCase = true) ||
                name.contains("KeyStoreException", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            guard += 1
        }
        return false
    }
}

/**
 * Opens encrypted prefs. On a keystore lockout, [resetBroken] runs once
 * (delete the broken file and master key) and create is tried again.
 */
class EncryptedStoreOpen<T>(
    private val create: () -> T,
    private val resetBroken: () -> Unit
) {
    fun open(): T? {
        try {
            return create()
        } catch (e: Exception) {
            if (!KeystoreLockout.isLockout(e)) return null
        }
        resetBroken()
        return try {
            create()
        } catch (_: Exception) {
            null
        }
    }
}
