package com.dirk.kalshiodds.decision

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Per-algorithm PAPER experiments. Manual settings are frozen into each new trade's note. */
object ScalpControlSpec {
    enum class Key(val title: String, val unit: String, val lo: Double, val hi: Double, val step: Double, val group: String) {
        CLIP("Contracts", "ct", 1.0, 500.0, 1.0, "Execution"),
        MIN_ASK("Minimum entry", "¢", 1.0, 98.0, 1.0, "Entry"),
        MAX_ASK("Maximum entry", "¢", 2.0, 99.0, 1.0, "Entry"),
        SPREAD("Maximum spread", "¢", 0.0, 20.0, 1.0, "Entry"),
        MIN_LEFT("Minimum time remaining", "s", 0.0, 900.0, 5.0, "Entry"),
        MAX_LEFT("Maximum time remaining", "s", 0.0, 900.0, 5.0, "Entry"),
        WIN_PROFIT("Minimum profit if it wins", "$", 0.0, 100.0, 1.0, "Entry"),
        STOP("Stop distance", "¢", 1.0, 99.0, 1.0, "Exits"),
        TARGET("Net profit target (0 = off)", "¢/ct", 0.0, 99.0, 1.0, "Exits"),
        TRAIL("Trailing distance (0 = off)", "¢", 0.0, 30.0, 1.0, "Exits"),
        ARM("Net gain to arm trailing", "¢/ct", 0.0, 99.0, 1.0, "Exits"),
        HOLD("Maximum hold (0 = off)", "s", 0.0, 900.0, 5.0, "Exits"),
        EXIT_BY("Exit before close (0 = settle)", "s", 0.0, 180.0, 5.0, "Exits"),
        TURN("Fair-value turn-down (0 = off)", "¢", 0.0, 99.0, 1.0, "Exits"),
        DELAY("Entry delay", "ms", 0.0, 10000.0, 100.0, "Execution"),
        TTL("Entry expiry", "ms", 1000.0, 120000.0, 1000.0, "Execution"),
        SLIP("Maximum entry slippage", "¢", 0.0, 20.0, 1.0, "Execution"),
        EXIT_DELAY("Exit delay", "ms", 0.0, 3000.0, 50.0, "Execution"),
        COOLDOWN("Re-entry cooldown", "s", 0.0, 120.0, 1.0, "Execution"),
        ENTRIES("Entries per market", "", 1.0, 100.0, 1.0, "Execution"),
        TRIGGER("Entry gap / price-move trigger", "¢", 1.0, 60.0, 1.0, "Signal"),
        SHOCK("Spot shock threshold", "bp", 1.0, 100.0, 1.0, "Signal"),
        LOOKBACK("Shock lookback", "s", 1.0, 60.0, 1.0, "Signal"),
        IMBALANCE("Book imbalance threshold", "%", 51.0, 99.0, 1.0, "Signal"),
        FAIR("Minimum favourite probability", "%", 50.0, 99.0, 1.0, "Signal"),
        CONVERGENCE("Fair catch-up exit (0 = off)", "¢", 0.0, 30.0, 1.0, "Signal"),
        FORECAST("Forecast margin above costs", "¢", 0.0, 20.0, .1, "Signal")
    }
    data class Config(val values: Map<Key, Double>, val enabled: Boolean = true) {
        operator fun get(key: Key): Double = values.getValue(key)
        fun set(key: Key, value: Double): Config = copy(values = values + (key to value))
        fun normalized(): Config = copy(values = Key.values().associateWith { k ->
            val v = values[k]?.takeIf { it.isFinite() } ?: k.lo
            (k.lo + ((v.coerceIn(k.lo, k.hi) - k.lo) / k.step).roundToInt() * k.step).coerceIn(k.lo, k.hi)
        })
        fun error(): String? = when {
            this[Key.MIN_ASK] > this[Key.MAX_ASK] -> "Minimum entry must be at or below maximum entry"
            this[Key.MIN_LEFT] > this[Key.MAX_LEFT] -> "Minimum time must be at or below maximum time"
            this[Key.TTL] < this[Key.DELAY] -> "Entry expiry must be at least the entry delay"
            else -> null
        }
        fun encode(): String = (if (enabled) "1" else "0") + ":" + Key.values().joinToString(",") { this[it].toString() }
        val revision: String get() = Integer.toHexString(encode().hashCode())
    }
    fun decode(token: String): Config? = runCatching {
        val flags = token.substringBefore(':'); require(flags == "0" || flags == "1")
        val nums = token.substringAfter(':').split(',').map { it.toDouble() }
        require(nums.size == Key.values().size && nums.all { it.isFinite() })
        Config(Key.values().zip(nums).toMap(), flags == "1").normalized().also { require(it.error() == null) }
    }.getOrNull()
    private val TOKEN = Regex("(?:^|[ ;·])ctl1=([^ ;·]+)")
    fun frozen(t: ScalpTrade): Config? = TOKEN.find(t.note)?.groupValues?.get(1)?.let(::decode)
    fun code(t: ScalpTrade): String = ScalpModels.scalperIdOf(t) ?: t.variantId
    fun variantFor(code: String): String {
        val v2 = code.startsWith("v2-"); val raw = code.removePrefix("v2-")
        val p = when (raw) {
            "fair" -> ScalpParams.DEFAULT
            "dip" -> ScalpParams.seedFor("BTC", ScalpStrategy.DIP_HUNTER)
            "momo" -> ScalpParams.seedFor("BTC", ScalpStrategy.MOMENTUM)
            "xrev" -> ScalpParams.seedFor("BTC", ScalpStrategy.EXTREME_REVERSION)
            "cfr" -> ScalpParams.seedFor("BTC", ScalpStrategy.CF_REPRICE)
            "mkdip" -> ScalpModels.MAKER_DIP_PARAMS
            "diph60" -> ScalpParams.HOLD60_SIBLINGS[0]
            "momoh60" -> ScalpParams.HOLD60_SIBLINGS[1]
            "fairh60" -> ScalpParams.QUEUED_COPIES[0]
            "dipnotp" -> ScalpParams.QUEUED_COPIES[1]
            "momonotp" -> ScalpParams.QUEUED_COPIES[2]
            else -> null
        }
        return (if (v2) "v2-" else "") + (p?.id ?: raw)
    }
    fun defaults(code: String): Config {
        val vid = variantFor(code); val v2 = vid.startsWith("v2-")
        val sp = ScalpV2.spec(vid)
        val raw = if (v2) sp.base else vid
        val p = ScalpParams.byId(raw); val sf = ShockFav.byVariantId(raw)
        val fav = sf?.fav == true && !sf.shock
        val stop = if (v2) sp.stop ?: ScalpV2.stopFor(raw) else sf?.let { ShockFav.stopC(it) / 100.0 } ?: p?.stop ?: .10
        val trigger = sf?.let { if (it.kind == ShockFav.Kind.SIMPLE_DIP) it.dip else if (it.fav && !it.shock) it.gap else it.lag } ?: p?.minGap ?: .06
        return Config(mapOf(
            Key.CLIP to (if (v2) sp.clip else 10).toDouble(),
            Key.MIN_ASK to (if (sf != null || v2) 1.0 else if (p?.strategy == ScalpStrategy.EXTREME_REVERSION) 2.0 else 10.0),
            Key.MAX_ASK to (if (v2) sp.maxAsk * 100 else if (p?.strategy == ScalpStrategy.EXTREME_REVERSION) 15.0 else 99.0),
            Key.SPREAD to 2.0, Key.MIN_LEFT to (if (v2) 60.0 else if (fav) sf!!.favTmin else p?.tauMinS ?: 0.0),
            Key.MAX_LEFT to (if (fav) sf!!.favTmax else p?.tauMaxS ?: 900.0), Key.WIN_PROFIT to 0.0,
            Key.STOP to stop * 100, Key.TARGET to (if (v2 || fav || p?.hold60 == true) 0.0 else (sf?.tpNetO50 ?: sf?.tp ?: p?.target ?: .08) * 100),
            Key.TRAIL to (if (v2) sp.trail * 100 else (sf?.trail ?: 0.0) * 100), Key.ARM to stop * 100,
            Key.HOLD to (if (v2 || fav || p?.hold60 == true) 0.0 else sf?.maxHoldS?.toDouble() ?: if (raw.startsWith("fcr")) 60.0 else 0.0),
            Key.EXIT_BY to (if (fav || p?.hold60 == true) 0.0 else 60.0), Key.TURN to (p?.turnDown ?: 0.0) * 100,
            Key.DELAY to (if (v2 || sf != null) 2000.0 else 3000.0), Key.TTL to (if (p?.maker == true) 30000.0 else 6000.0),
            Key.SLIP to 1.0, Key.EXIT_DELAY to 250.0, Key.COOLDOWN to 15.0, Key.ENTRIES to 12.0,
            Key.TRIGGER to trigger * 100,
            Key.SHOCK to (sf?.xBp ?: 5.0), Key.LOOKBACK to (sf?.yS ?: 5).toDouble(),
            Key.IMBALANCE to (sf?.imbR ?: .85) * 100, Key.FAIR to (sf?.fmin ?: .90) * 100,
            Key.CONVERGENCE to (sf?.conv ?: .01) * 100, Key.FORECAST to .5
        )).normalized()
    }
    fun keys(code: String): List<Key> {
        val common = Key.values().filter { it.group != "Signal" }
        if (code.startsWith("v2-")) return common // V2 copies follow the original's signals, with independent entry/exits.
        val sf = ShockFav.byVariantId(code)
        val signal = when {
            code.startsWith("fc") -> listOf(Key.FORECAST)
            sf == null -> listOf(Key.TRIGGER)
            sf.kind == ShockFav.Kind.SIMPLE_DIP -> listOf(Key.TRIGGER)
            sf.kind == ShockFav.Kind.IMBALANCE -> listOf(Key.IMBALANCE)
            sf.kind == ShockFav.Kind.OPEN50 -> emptyList()
            sf.kind == ShockFav.Kind.LAGGARD || sf.kind == ShockFav.Kind.FADE_MAKER -> listOf(Key.SHOCK, Key.LOOKBACK)
            else -> buildList {
                if (sf.shock) addAll(listOf(Key.SHOCK, Key.LOOKBACK, Key.TRIGGER, Key.CONVERGENCE))
                if (sf.fav) addAll(listOf(Key.TRIGGER, Key.FAIR))
            }.distinct()
        }
        return common + signal
    }
    fun cheapEarly(code: String): Config = defaults(code).set(Key.MIN_ASK, 1.0).set(Key.MAX_ASK, 40.0)
        .set(Key.MIN_LEFT, 540.0).set(Key.MAX_LEFT, 660.0).set(Key.WIN_PROFIT, 0.0)
}

interface ScalpControlStore {
    fun load(): Map<String, ScalpControlSpec.Config>
    fun put(code: String, config: ScalpControlSpec.Config?)
}
class MemoryScalpControlStore : ScalpControlStore {
    private var rows = emptyMap<String, ScalpControlSpec.Config>()
    override fun load() = rows
    override fun put(code: String, config: ScalpControlSpec.Config?) { rows = if (config == null) rows - code else rows + (code to config) }
}
class PrefsScalpControlStore(context: Context) : ScalpControlStore {
    private val prefs = context.applicationContext.getSharedPreferences("scalp_manual_v1", Context.MODE_PRIVATE)
    override fun load(): Map<String, ScalpControlSpec.Config> = prefs.all.mapNotNull { (k, v) ->
        (v as? String)?.let(ScalpControlSpec::decode)?.let { k to it }
    }.toMap()
    override fun put(code: String, config: ScalpControlSpec.Config?) {
        prefs.edit().also { if (config == null) it.remove(code) else it.putString(code, config.encode()) }.apply()
    }
}
class ScalpControls(private val store: ScalpControlStore = MemoryScalpControlStore()) {
    private val mutable = MutableStateFlow(store.load())
    val state = mutable.asStateFlow()
    fun get(code: String) = mutable.value[code]
    @Synchronized fun apply(code: String, config: ScalpControlSpec.Config) {
        val next = config.normalized(); require(next.error() == null) { next.error().orEmpty() }
        store.put(code, next); mutable.value = mutable.value + (code to next)
    }
    @Synchronized fun reset(code: String) { store.put(code, null); mutable.value = mutable.value - code }
    fun params(p: ScalpParams): ScalpParams = get(ScalpModels.modelOf(p).code)?.let { c ->
        p.copy(minGap = c[ScalpControlSpec.Key.TRIGGER] / 100, tauMinS = c[ScalpControlSpec.Key.MIN_LEFT], tauMaxS = c[ScalpControlSpec.Key.MAX_LEFT],
            maxSpread = c[ScalpControlSpec.Key.SPREAD] / 100, minAsk = c[ScalpControlSpec.Key.MIN_ASK] / 100, maxAsk = c[ScalpControlSpec.Key.MAX_ASK] / 100)
    } ?: p
    fun shock(p: ShockFav.Params): ShockFav.Params = get(p.code)?.let { c ->
        p.copy(xBp = c[ScalpControlSpec.Key.SHOCK], yS = c[ScalpControlSpec.Key.LOOKBACK].toInt(),
            gap = c[ScalpControlSpec.Key.TRIGGER] / 100, lag = c[ScalpControlSpec.Key.TRIGGER] / 100, dip = c[ScalpControlSpec.Key.TRIGGER] / 100,
            imbR = c[ScalpControlSpec.Key.IMBALANCE] / 100, fmin = c[ScalpControlSpec.Key.FAIR] / 100,
            conv = c[ScalpControlSpec.Key.CONVERGENCE] / 100, tauLo = c[ScalpControlSpec.Key.MIN_LEFT], tauHi = c[ScalpControlSpec.Key.MAX_LEFT],
            favTmin = c[ScalpControlSpec.Key.MIN_LEFT], favTmax = c[ScalpControlSpec.Key.MAX_LEFT], amax = c[ScalpControlSpec.Key.MAX_ASK] / 100)
    } ?: p
}
