package com.dirk.kalshiodds.signal.lastminute

import org.junit.rules.ExternalResource

/**
 * 0.3.39 retired the last-minute play. Legacy tests that still cover its dormant code
 * (fixtures with a pre-attached fired snapshot) opt back in for the duration of the test.
 * The app itself never flips this; ReleaseGate0339Test asserts the retired behaviour.
 */
class LegacyLastMinuteRule : ExternalResource() {
    override fun before() {
        LastMinuteRetired.retired = false
    }

    override fun after() {
        LastMinuteRetired.retired = true
    }
}
