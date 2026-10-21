package com.dirk.kalshiodds.signal.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrostructureEncoderReuseTest {
    @Test fun reusedLatentHasIdenticalReconstructionError() {
        val encoder = MicrostructureEncoder.defaults()
        val snapshots = listOf(FloatArray(8), floatArrayOf(.3f, .4f, .1f, -.7f, .4f, .2f, .1f, .8f))
        snapshots.forEach { snapshot ->
            assertEquals(encoder.surprise(snapshot), encoder.reconstructionErrorFromLatent(snapshot, encoder.encode(snapshot)), 0.0)
        }
    }
}
