package com.dirk.kalshiodds.signal.scalper

/**
 * Puts every strategy back on the band it was researched on.
 *
 * Since 1.25.0 the arena buys 15–58¢ for every strategy ([ScalpBand.ALL_STRATEGIES_USE_DEFAULT]), so a favourite's
 * signal can no longer fire at its own prices. Tests about a *signal* — does Kashi's rule fire in the last 45 s
 * while the ask is under fair, does the heavy favourite wait for minute 12 — set the band back the way you would
 * with the sliders, because a band you set yourself still wins. Tests about the arena's band itself do not call this.
 */
fun ScalpTuning.useOwnBands(vararg strategies: ScalpStrategy): ScalpTuning = apply {
    val list = if (strategies.isEmpty()) ScalpStrategy.values() else strategies
    for (st in list) {
        set(st, knobs(st).copy(entryMinCents = st.entryMin * 100.0, entryMaxCents = st.entryMax * 100.0))
    }
}

/** [useOwnBands] as a plain function, for tests in a sub-package that do not import the extension. */
fun useOwnBands(tuning: ScalpTuning): ScalpTuning = tuning.useOwnBands()
