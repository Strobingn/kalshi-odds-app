package com.dirk.kalshiodds.signal.trade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperEntryPolicyTest {
    @Test fun automaticEntriesUseTheHardTenToNinetyCentBand() {
        assertFalse(PaperEntryPolicy.allowsAutomaticEntry(0.099))
        assertTrue(PaperEntryPolicy.allowsAutomaticEntry(0.10))
        assertTrue(PaperEntryPolicy.allowsAutomaticEntry(0.899))
        assertFalse(PaperEntryPolicy.allowsAutomaticEntry(0.90))
        assertFalse(PaperEntryPolicy.allowsAutomaticEntry(Double.NaN))
    }
}
