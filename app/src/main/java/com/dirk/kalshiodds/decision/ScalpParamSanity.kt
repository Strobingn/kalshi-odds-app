package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.config.SignalConstants
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One clamp for paper scalper settings. Runs on load, on every AI or learner
 * write, and on manual edits. It does not place orders and it does not bench.
 *
 * A stored lowest-buy of 1¢ cannot be told apart from the old default, so it is
 * raised to the strategy floor.
 */
object ScalpParamSanity {
    data class Audit(
        val id: String,
        val floor: Int,
        val ceiling: Int,
        val stopCents: Int,
        val targetCents: Int,
        val clip: Int,
        val window: String,
        val changed: String
    )

    fun settings(code: String, raw: ScalpStrategySettings): ScalpStrategySettings {
        val s = raw.normalized()
        val floor = ScalpFloors.cents(code)
        val ceilCap = ScalpFloors.ceilingCents(code)
        var min = s.minEntryCents.coerceAtLeast(floor).coerceAtMost(98)
        var max = s.maxEntryCents
        if (ScalpFloors.isFavorite(code) && (max == null || max < floor)) max = ceilCap
        // A ceiling under 30¢ on a v2 dip / fair-gap copy is the signal price, not a band.
        if (max != null && ScalpFloors.repairsTightAsk(code) && max < ScalpFloors.minCeilingCents(code)) max = ceilCap
        if (max != null && max <= min) max = (min + 1).coerceAtMost(99)
        if (max != null && max > 99) max = 99
        if (min >= (max ?: 99)) min = ((max ?: 99) - 1).coerceAtLeast(floor)
        val stop = s.stopCents?.let { it.coerceIn(1, 48) }
        val stopPct = s.stopLossPercent?.let { it.coerceIn(1, 80) }
        var target = s.takeProfitCents?.let { it.coerceIn(0, 60) }
        val entry = ((min + (max ?: ceilCap)) / 2).coerceIn(floor, 98)
        if (target != null && target > 0 && entry + target > 99) target = (99 - entry).coerceAtLeast(1)
        return s.copy(
            minEntryCents = min,
            maxEntryCents = max,
            stopCents = stop,
            stopLossPercent = stopPct,
            takeProfitCents = target,
            contracts = s.contracts?.coerceIn(1, 500),
            untilMinute = s.untilMinute.coerceIn(1, 14),
            maxHoldSeconds = s.maxHoldSeconds?.coerceIn(0, 900),
            edgeCents = s.edgeCents?.coerceIn(0, 30)
        ).normalized()
    }

    fun sanitize(c: ScalpControls): ScalpControls = c.normalized()

    fun side(raw: String): String? = when (raw.trim().uppercase(Locale.US)) {
        "YES", "UP" -> "YES"
        "NO", "DOWN" -> "NO"
        else -> null
    }

    /** YES price + NO price should be a dollar. A 100-minus flip fails this. */
    fun pricesComplement(yes: Double, no: Double): Boolean =
        yes.isFinite() && no.isFinite() && abs((yes + no) - 1.0) <= 0.02

    /** Built-in clip for this scalper. A saved contracts value, when present, replaces it. */
    fun builtinClip(code: String): Int {
        ScalpVariants.clipOf(code)?.let { return it }
        if (code.startsWith("v2-")) return ScalpV2.spec(code).clip.coerceAtLeast(1)
        if (ShockFav.byVariantId(code) != null) return ShockFav.CLIP
        return ScalpRule.CONTRACTS
    }

    /**
     * The size this scalper may fill. A saved contracts value can shrink the clip.
     * It cannot grow past the variant's own clip (sf42-v1 is 5, not 8). Green size is not a clip.
     */
    fun ownClip(code: String, explicit: Int? = null): Int {
        val builtin = builtinClip(code)
        val chosen = (explicit ?: builtin).coerceIn(1, 500)
        val variantCap = ScalpVariants.clipOf(code)
        return if (variantCap != null) minOf(chosen, variantCap) else chosen
    }

    /** Algo column for the sync payload. Every live scalper names its clip. */
    fun algoText(label: String, clip: Int): String {
        if (label == KeptScalpers.REMOVED_LABEL) return label
        if (label.contains("clip")) return label
        return "$label · clip $clip"
    }

    /**
     * Ask band written into v2 notes and read back at fill time.
     * v2min cannot sit under the strategy floor. A ceiling inside the longshot
     * band is replaced with the parent ceiling (55¢ for dip and fair-gap).
     */
    fun v2Band(code: String, savedMin: Double, savedMax: Double): Pair<Double, Double> {
        val floor = ScalpFloors.cents(code) / 100.0
        val ceil = ScalpFloors.ceilingCents(code) / 100.0
        val minPx = (if (savedMin.isFinite()) savedMin else floor).coerceAtLeast(floor).coerceIn(floor, 0.98)
        var maxPx = if (savedMax.isFinite() && savedMax > 0.0) savedMax else ceil
        if (ScalpFloors.repairsTightAsk(code) && maxPx + 1e-9 < ScalpFloors.minCeilingCents(code) / 100.0) maxPx = ceil
        if (maxPx <= minPx + 1e-9) maxPx = ceil
        maxPx = maxPx.coerceAtMost(ceil).coerceAtLeast(minPx + 0.01)
        return minPx to maxPx
    }

    /**
     * Why a new paper entry must be skipped, or null. Does not throw.
     * [contracts] of 0 is an unfilled pending clip, not a bad size.
     * [clip] is this scalper's own clip. A size above it cannot fill.
     */
    fun entryBlock(side: String, price: Double, contracts: Int, floor: Double, cash: Double, clip: Int = Int.MAX_VALUE): String? {
        if (side(side) == null) return "sanity: side $side is not YES/NO (UP/DOWN)"
        if (!price.isFinite() || price <= 0.0 || price >= 1.0) return "sanity: price is not a contract price"
        if (price + 1e-9 < floor) {
            return String.format(Locale.US, "sanity: %.0f¢ is below the %.0f¢ floor", price * 100.0, floor * 100.0)
        }
        if (contracts < 0 || contracts > 1_000) return "sanity: clip $contracts"
        if (contracts > clip) {
            return String.format(Locale.US, "sanity: size %d is above the clip %d", contracts, clip)
        }
        if (contracts > 0 && cash.isFinite() && contracts * price > cash + 1.0) return "sanity: clip costs more than free cash"
        return null
    }

    /** Why an exit price or size is nonsense, or null. */
    fun exitBlock(exitPx: Double?, bid: Double?, sold: Int, bidDepth: Double?): String? {
        if (exitPx != null && (!exitPx.isFinite() || exitPx <= 0.0 || exitPx >= 1.0)) return "sanity: exit price"
        if (exitPx != null && bid != null && bid.isFinite() && exitPx > bid + 0.011) return "sanity: exit above the bid"
        if (bidDepth != null && bidDepth.isFinite() && sold > bidDepth + 1e-6) return "sanity: exit size above bid depth"
        return null
    }

    /** Signed gap between booked net and entry/exit fees. Null when the inputs are not a closed round trip. */
    fun pnlGap(entry: Double, exit: Double, n: Int, entryFee: Double, exitFee: Double, net: Double): Double? {
        if (n <= 0 || !entry.isFinite() || !exit.isFinite() || !entryFee.isFinite() || !exitFee.isFinite() || !net.isFinite()) return null
        val expected = (exit - entry) * n - entryFee - exitFee
        return net - expected
    }

    fun audit(code: String): Audit {
        val raw = ScalpStrategySettings()
        val s = settings(code, raw)
        val floor = s.minEntryCents
        val ceiling = s.maxEntryCents ?: ScalpFloors.ceilingCents(code)
        val spec = ScalpVariants.of(code)
        val shock = ScalpFloors.params(code)
        val stop = s.stopCents
            ?: spec?.stopCents
            ?: spec?.fair?.let { (it.stop * 100.0).roundToInt() }
            ?: shock?.let { ShockFav.stopC(it) }
            ?: 8
        val target = when {
            s.takeProfitCents != null && s.takeProfitCents > 0 -> s.takeProfitCents
            spec?.kind == ScalpVariants.Kind.SIMPLE || shock?.kind == ShockFav.Kind.SIMPLE_DIP ->
                (shock?.tp ?: 0.0).let { if (it > 0.0) (it * 100.0).roundToInt() else 0 }
            spec?.fair != null && spec.fair.target > 0.0 && spec.kind != ScalpVariants.Kind.FAV ->
                (spec.fair.target * 100.0).roundToInt()
            ScalpParams.byId(code)?.target?.let { it in 0.02..0.40 } == true ->
                (ScalpParams.byId(code)!!.target * 100.0).roundToInt()
            else -> 0
        }
        val clip = s.contracts ?: spec?.clip ?: if (code.startsWith("v2-")) ScalpV2.CLIP else ShockFav.CLIP
        val window = when {
            spec?.kind == ScalpVariants.Kind.FAV -> {
                val p = spec.shock
                if (p != null) "τ ${p.favTmin.toInt()}–${p.favTmax.toInt()}s" else "until minute ${s.untilMinute}"
            }
            shock != null && shock.fav && !shock.shock -> "τ ${shock.favTmin.toInt()}–${shock.favTmax.toInt()}s"
            spec?.fair != null -> "from ${spec.fair.tauMinS.toInt()}s"
            else -> "until minute ${s.untilMinute}"
        }
        val changed = buildList {
            if (raw.minEntryCents != s.minEntryCents) add("floor ${raw.minEntryCents}→${s.minEntryCents}")
            if (raw.maxEntryCents != s.maxEntryCents && s.maxEntryCents != null && ScalpFloors.isFavorite(code)) {
                add("ceiling unset→${s.maxEntryCents}")
            }
        }.joinToString("; ").ifBlank { "—" }
        return Audit(code, floor, ceiling, stop, target, clip, window, changed)
    }

    /** Empty when the default profile satisfies the invariants. */
    fun violations(code: String): List<String> {
        val row = audit(code)
        val out = ArrayList<String>()
        if (row.floor < GENERAL_FLOOR_UNLESS_FAV(code)) out += "floor ${row.floor} is under the strategy minimum"
        if (row.floor >= row.ceiling || row.ceiling > 99) out += "range ${row.floor}–${row.ceiling}"
        val fair = ScalpFloors.fairMinCents(code)
        if (fair != null && fair >= 85 && row.ceiling < fair - 5) out += "fair ≥ $fair% but ceiling is ${row.ceiling}¢"
        if (row.stopCents <= 0) out += "stop ${row.stopCents}"
        val entryCents = ((row.floor + row.ceiling) / 2).coerceAtLeast(row.floor)
        if (row.stopCents >= entryCents) out += "stop ${row.stopCents}¢ is not under a ${entryCents}¢ entry"
        val entry = entryCents / 100.0
        val n = row.clip.coerceAtLeast(1)
        if (row.targetCents > 0) {
            val exit = (entry + row.targetCents / 100.0).coerceAtMost(0.99)
            if (entry + row.targetCents / 100.0 > 0.99 + 1e-9) out += "target not reachable"
            val fees = ScalpRule.orderFee(n, entry) + ScalpRule.orderFee(n, exit)
            val gain = n * row.targetCents / 100.0
            if (gain <= fees + 1e-9) out += "target does not clear fees"
        }
        if (row.clip < 1) out += "clip ${row.clip}"
        val cost = n * entry + ScalpRule.orderFee(n, entry)
        if (cost > SignalConstants.PAPER_START_USD) out += "clip costs more than free cash"
        if (row.window.isBlank()) out += "window"
        if (!sideOk()) out += "side map"
        return out
    }

    private fun GENERAL_FLOOR_UNLESS_FAV(code: String): Int = ScalpFloors.cents(code)

    private fun sideOk(): Boolean =
        side("YES") == "YES" && side("UP") == "YES" && side("NO") == "NO" && side("DOWN") == "NO" &&
            side("MAYBE") == null && pricesComplement(0.72, 0.28) && !pricesComplement(0.72, 0.72)

    fun ids(): List<String> {
        val live = ScalpModels.ALL.map { it.code }
        val variants = ScalpVariants.ALL.map { it.id }
        return (live + variants).distinct()
    }
}
