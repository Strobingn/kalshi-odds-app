package com.dirk.kalshiodds.signal.config

import java.security.KeyStoreException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreRecoveryTest {
    @Test
    fun invalidKeystoreFailuresAreRecoverableAndOtherErrorsAreNot() {
        assertTrue(KeystoreRecovery.isInvalidKey(AEADBadTagException("tag")))
        assertTrue(KeystoreRecovery.isInvalidKey(KeyStoreException("gone")))
        assertTrue(KeystoreRecovery.isInvalidKey(RuntimeException("wrapped", AEADBadTagException("tag"))))
        assertFalse(KeystoreRecovery.isInvalidKey(IllegalStateException("offline")))
        assertFalse(KeystoreRecovery.isInvalidKey(java.io.IOException("disk")))
        assertTrue(KeystoreRecovery.REENTER_AFTER_RESET.contains("plain storage"))
        assertTrue(KeystoreRecovery.REENTER_AFTER_RESET.contains("Re-enter"))
    }
}
