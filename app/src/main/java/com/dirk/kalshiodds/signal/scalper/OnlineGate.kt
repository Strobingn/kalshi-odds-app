package com.dirk.kalshiodds.signal.scalper

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * The self-learning entry gate: one small online logistic model per strategy, trained on every closed paper trade the
 * moment it closes (win = made money after fees), with a step that grows with the trade's size in cents a contract.
 *
 * While a strategy has fewer than [WARMUP] trades it learns and lets everything through. After that an entry goes
 * ahead only when the model's chance of a win is at least the strategy's learned threshold ([minConf]) and, once the
 * strategy has [EV_MIN_EACH] wins and losses, the expected cents a contract (chance × average win − the rest ×
 * average loss) is not negative. The threshold moves on every trade: up after a loss the gate let through, down after
 * a win. Paper only; it never places anything, it only says yes or no to the arena's own entries.
 *
 * Thread-safe.
 */
class OnlineGate(
    initial: Map<String, Brain> = emptyMap(),
    private val persist: (Map<String, Brain>) -> Unit = {}
) {
    @Serializable
    data class Brain(
        val w: List<Double> = List(DIM) { 0.0 },
        val n: Int = 0,
        val minConf: Double = START_CONF,
        /** Average cents a contract of the wins and of the losses (exponential, 1/[AVG_SPAN]). */
        val avgWinCents: Double = 0.0,
        val avgLossCents: Double = 0.0,
        val wins: Int = 0,
        val losses: Int = 0,
        val skipped: Int = 0,
        /** Last 50 results, 1 = win, oldest first. */
        val recent: List<Int> = emptyList()
    ) {
        val rollingWin: Double? get() = if (recent.isEmpty()) null else recent.average()
    }

    /** One learned trade, for the AI tab's learning chart. */
    data class Point(val tMs: Long, val strategy: String, val p: Double, val win: Boolean, val rollingWin: Double, val minConf: Double)

    /** One strategy's gate, for the screens. */
    data class Row(val strategy: String, val n: Int, val minConf: Double, val lastP: Double?, val rollingWin: Double?, val skipped: Int, val gating: Boolean)

    @Volatile
    var enabled: Boolean = true

    private val lock = Any()
    private val brains = HashMap(initial)
    private val pending = LinkedHashMap<Long, Pair<String, DoubleArray>>()
    private val lastP = HashMap<String, Double>()
    private val points = ArrayDeque<Point>()
    private var unsaved = 0

    fun predict(strategy: String, x: DoubleArray): Double = synchronized(lock) {
        sigmoid(dot(brains[strategy]?.w ?: ZERO, x))
    }

    /** Whether [strategy] may open an entry that looks like [x]. Counts the ones it stops. */
    fun allows(strategy: String, x: DoubleArray): Boolean = synchronized(lock) {
        val b = brains[strategy] ?: Brain()
        val p = sigmoid(dot(b.w, x))
        lastP[strategy] = p
        if (!enabled || b.n < WARMUP) return true
        val evOk = if (b.wins >= EV_MIN_EACH && b.losses >= EV_MIN_EACH) p * b.avgWinCents - (1 - p) * b.avgLossCents >= 0.0 else true
        val ok = p >= b.minConf - 1e-12 && evOk
        if (!ok) brains[strategy] = b.copy(skipped = b.skipped + 1)
        ok
    }

    /** The order [id] went out with features [x]; it is learned from when it closes. */
    fun remember(id: Long, strategy: String, x: DoubleArray) = synchronized(lock) {
        pending[id] = strategy to x
        if (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
    }

    fun forget(id: Long) = synchronized(lock) { pending.remove(id); Unit }

    /** Learn from a closed order. Returns the learned point, or null when the order's entry was not remembered. */
    fun learn(id: Long, pnlUsd: Double, contracts: Int, nowMs: Long): Point? {
        val out: Point
        val save: Map<String, Brain>?
        synchronized(lock) {
            val (strategy, x) = pending.remove(id) ?: return null
            val b = brains[strategy] ?: Brain()
            val win = pnlUsd > 0.0
            val p = sigmoid(dot(b.w, x))
            val cents = if (contracts > 0) pnlUsd / contracts * 100.0 else 0.0
            // A bigger result (cents a contract) moves the weights further, capped.
            val lr = min(LR * (1.0 + 0.4 * min(abs(cents), 10.0) / 2.5), LR_MAX)
            val err = (if (win) 1.0 else 0.0) - p
            val w = DoubleArray(DIM) { i -> b.w[i] + lr * (err * x[i] - L2 * (if (i == 0) 0.0 else b.w[i])) }
            // The threshold: a loss the gate let through raises it, a win lowers it.
            val passed = b.n < WARMUP || p >= b.minConf
            val conf = when {
                !passed -> b.minConf
                win -> b.minConf - CONF_DOWN
                else -> b.minConf + CONF_UP
            }.coerceIn(CONF_MIN, CONF_MAX)
            val a = 1.0 / AVG_SPAN
            val recent = (b.recent + (if (win) 1 else 0)).takeLast(RECENT)
            val nb = b.copy(
                w = w.toList(), n = b.n + 1, minConf = conf,
                avgWinCents = if (win) (if (b.wins == 0) cents else b.avgWinCents * (1 - a) + cents * a) else b.avgWinCents,
                avgLossCents = if (!win) (if (b.losses == 0) -cents else b.avgLossCents * (1 - a) - cents * a) else b.avgLossCents,
                wins = b.wins + (if (win) 1 else 0), losses = b.losses + (if (win) 0 else 1),
                recent = recent
            )
            brains[strategy] = nb
            out = Point(nowMs, strategy, p, win, nb.rollingWin ?: 0.0, conf)
            points.addLast(out)
            while (points.size > KEEP_POINTS) points.removeFirst()
            unsaved += 1
            save = if (unsaved >= SAVE_EVERY) { unsaved = 0; HashMap(brains) } else null
        }
        save?.let { runCatching { persist(it) } }
        return out
    }

    fun points(): List<Point> = synchronized(lock) { points.toList() }

    fun rows(): List<Row> = synchronized(lock) {
        brains.map { (name, b) -> Row(name, b.n, b.minConf, lastP[name], b.rollingWin, b.skipped, enabled && b.n >= WARMUP) }
            .sortedByDescending { it.n }
    }

    fun snapshot(): Map<String, Brain> = synchronized(lock) { HashMap(brains) }

    /** Start every strategy's gate again. */
    fun reset() = synchronized(lock) {
        brains.clear(); pending.clear(); points.clear(); lastP.clear()
        runCatching { persist(emptyMap()) }
        Unit
    }

    companion object {
        /** Features: bias, price, spread, 10 s move, 30 s move, time left, fair edge, book imbalance. */
        const val DIM = 8
        const val WARMUP = 30
        const val EV_MIN_EACH = 10
        const val START_CONF = 0.45
        const val CONF_MIN = 0.30
        const val CONF_MAX = 0.70
        const val CONF_UP = 0.010
        const val CONF_DOWN = 0.005
        const val LR = 0.05
        const val LR_MAX = 0.125
        const val L2 = 0.001
        const val AVG_SPAN = 30.0
        const val RECENT = 50
        const val KEEP_POINTS = 600
        const val MAX_PENDING = 5_000
        const val SAVE_EVERY = 10
        private val ZERO = List(DIM) { 0.0 }

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val ser = MapSerializer(String.serializer(), Brain.serializer())

        fun encode(m: Map<String, Brain>): String = json.encodeToString(ser, m)
        fun decode(s: String?): Map<String, Brain> = s?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()
            .filterValues { it.w.size == DIM }

        /**
         * The entry's features, each roughly in −3..3. Prices in the side's dollars; [fairEdge] = the side's fair minus
         * the entry price; [obi] = the book imbalance toward the side (−1..1). Unknown values are 0.
         */
        fun features(
            price: Double, spread: Double?, move10: Double?, move30: Double?, secondsLeft: Int, fairEdge: Double?, obi: Double?
        ): DoubleArray {
            fun f(v: Double?, scale: Double) = (v?.takeIf { it.isFinite() }?.times(scale) ?: 0.0).coerceIn(-3.0, 3.0)
            return doubleArrayOf(
                1.0,
                f(price - 0.5, 2.0),
                f(spread, 20.0),
                f(move10, 20.0),
                f(move30, 10.0),
                (secondsLeft / 900.0).coerceIn(0.0, 1.0),
                f(fairEdge, 10.0),
                f(obi, 1.0)
            )
        }

        private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
        private fun dot(w: List<Double>, x: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until min(w.size, x.size)) s += w[i] * x[i]
            return s
        }
    }
}
