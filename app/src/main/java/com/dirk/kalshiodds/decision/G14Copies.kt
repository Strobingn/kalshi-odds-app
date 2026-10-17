package com.dirk.kalshiodds.decision

/**
 * 0.3.80 "rebuilt like Fair-gap g14" copies. Each copy waits for its original's signal (same side), then only enters
 * when the Fair-gap g14 h60 gate passes on that side (fair beats ask by the g14 gap after fees, cheap side) and manages
 * the trade exactly like g14 h60: stop armed after 60 s held, otherwise hold to settlement. Originals keep running
 * unchanged (retired originals stay retired and keep feeding signals as hidden rows).
 * g14 itself is NOT proven: per-trade 95% range about -$0.48 … +$0.72 (117 trades, Oct 17 2026).
 */
object G14Copies {
    const val SUFFIX = "-g14"
    const val VARIANT_PREFIX = "g14x-"
    const val LABEL = "rebuilt like Fair-gap g14"
    const val PARAMS_ID = "g14-t12-s08-d15-w300-h60"
    const val V2_MAX_ASK = 0.55
    val params: ScalpParams get() = ScalpParams.byId(PARAMS_ID)!!

    data class Snapshot(val trades: Int, val netUsd: Double)
    /** Source model code → its record on Supabase when the copy was made (shown next to the copy). */
    val SOURCES: Map<String, Snapshot> = linkedMapOf(
        "fcride" to Snapshot(185, -85.13),
        "v2-fcfade" to Snapshot(85, -53.59),
        "v2-fcride" to Snapshot(117, -57.6),
        "v2-sf28" to Snapshot(53, -25.56),
        "v2-sf51" to Snapshot(21, -25.55),
        "v2-sf27" to Snapshot(18, -16.06),
        "sf03" to Snapshot(25, -12.74),
        "sf29" to Snapshot(36, -10.18),
        "sf13" to Snapshot(11, -7.52),
        "v2-sf02" to Snapshot(9, -6.57),
        "sf32" to Snapshot(4114, -1602.48),
        "sf30" to Snapshot(4070, -1579.7),
        "sf41" to Snapshot(3324, -1158.01),
        "sf42" to Snapshot(3319, -1154.82),
        "v2-sf30" to Snapshot(1167, -1150.38)
    )
    fun copyCode(source: String) = source + SUFFIX
    fun variantId(source: String) = VARIANT_PREFIX + source
    fun isCopyVariant(vid: String) = vid.startsWith(VARIANT_PREFIX)
    fun isCopyCode(code: String) = code.endsWith(SUFFIX) && code.removeSuffix(SUFFIX) in SOURCES
    fun sourceOf(code: String): String? = code.removeSuffix(SUFFIX).takeIf { code.endsWith(SUFFIX) && it in SOURCES }
    /** The original whose new signal triggers copies: a v2 source listens to its base original. */
    fun triggerOf(source: String): String = source.removePrefix(ScalpV2.PREFIX)
    fun copiesTriggeredBy(originalCode: String): List<String> = SOURCES.keys.filter { triggerOf(it) == originalCode }
}
