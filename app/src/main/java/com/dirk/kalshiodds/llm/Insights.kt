package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.signal.scalper.DailyReview
import com.dirk.kalshiodds.signal.scalper.DayStats
import com.dirk.kalshiodds.signal.scalper.SavedReview
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalpTradeLog
import com.dirk.kalshiodds.signal.scalper.ScalperModel
import com.dirk.kalshiodds.signal.scalper.TradeExplainer
import com.dirk.kalshiodds.signal.scalper.TradeFacts
import com.dirk.kalshiodds.signal.scalper.Tuned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The Explain sheet: one trade's facts, and the AI's words as they arrive. */
data class ExplainUi(
    val key: String,
    val facts: TradeFacts,
    val text: String = "",
    val running: Boolean = true,
    val source: String = "",
    val error: String? = null
)

/**
 * "Explain this trade": reads the trade's order row (the book it saw, the model's inputs), builds the facts
 * ([TradeExplainer]) and has [writer] put them into words, streaming. The last [CACHE] explanations are kept, so
 * opening a trade again shows its explanation at once.
 */
class ExplainSession(
    private val scope: CoroutineScope,
    private val writer: AiWriter,
    private val log: ScalpTradeLog,
    private val model: () -> ScalperModel?,
    private val tuned: (ScalpStrategy) -> Tuned?
) {
    private val _state = MutableStateFlow<ExplainUi?>(null)
    val state: StateFlow<ExplainUi?> = _state.asStateFlow()
    private var job: Job? = null
    private val done = LinkedHashMap<String, ExplainUi>()

    fun explain(t: ScalpTrade) {
        val key = "${t.run}-${t.id}"
        job?.cancel()
        synchronized(done) { done[key] }?.let { _state.value = it; return }
        job = scope.launch(Dispatchers.IO) {
            val st = ScalpStrategy.values().firstOrNull { it.name == t.strategy }
            val facts = TradeExplainer.facts(t, runCatching { log.orderFor(t) }.getOrNull(), model(), st?.let { tuned(it) })
            _state.value = ExplainUi(key, facts)
            val r = writer.write(TradeExplainer.SYSTEM, TradeExplainer.prompt(facts), MAX_TOKENS) { partial ->
                _state.update { s -> if (s?.key == key) s.copy(text = partial) else s }
            }
            val final = r.fold(
                { w -> ExplainUi(key, facts, w.text, running = false, source = w.source) },
                { e -> ExplainUi(key, facts, _state.value?.takeIf { it.key == key }?.text.orEmpty(), running = false, error = e.message ?: "no answer") }
            )
            if (final.error == null) synchronized(done) {
                done[key] = final
                while (done.size > CACHE) done.remove(done.keys.first())
            }
            _state.update { s -> if (s?.key == key) final else s }
        }
    }

    fun close() {
        job?.cancel()
        _state.value = null
    }

    companion object {
        const val MAX_TOKENS = 260
        const val CACHE = 50
    }
}

/** The Reviews tab while a review is being written. */
data class ReviewUi(
    val running: Boolean = false,
    val day: String = "",
    val text: String = "",
    val source: String = "",
    val error: String? = null,
    val stats: DayStats? = null
)

/**
 * The daily review ([DailyReview]): writes up a UTC day of the arena's paper trades. [startAuto] reviews yesterday
 * once it is over (when that day had trades and has no finished review yet), checking every [checkMs]; [run] does
 * any day now (today so far is marked partial).
 */
class ReviewRunner(
    private val scope: CoroutineScope,
    private val writer: AiWriter,
    private val log: ScalpTradeLog,
    private val store: DailyReview.Store,
    private val autoOn: () -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val checkMs: Long = 5 * 60_000L
) {
    private val _state = MutableStateFlow(ReviewUi())
    val state: StateFlow<ReviewUi> = _state.asStateFlow()
    private val _reviews = MutableStateFlow(store.all())
    val reviews: StateFlow<List<SavedReview>> = _reviews.asStateFlow()
    private var job: Job? = null
    private val tried = HashMap<String, Long>()

    fun startAuto() {
        scope.launch(Dispatchers.IO) {
            delay(60_000L)
            while (isActive) {
                runCatching { maybeAuto() }
                delay(checkMs)
            }
        }
    }

    /** Reviews yesterday when it is due. True when a review was started. */
    fun maybeAuto(): Boolean {
        if (!autoOn() || job?.isActive == true) return false
        val now = nowMs()
        val day = DailyReview.yesterday(now)
        if (store.hasFinal(day)) return false
        if ((tried[day] ?: Long.MIN_VALUE / 2) > now - RETRY_MS) return false
        if (log.day(day).isEmpty()) return false
        tried[day] = now
        run(day)
        return true
    }

    fun runToday() = run(ScalpTradeLog.utcDay(nowMs()))

    fun run(day: String) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val partial = day >= ScalpTradeLog.utcDay(nowMs())
            val stats = DailyReview.stats(day, log.day(day))
            if (stats.trades == 0) {
                _state.value = ReviewUi(running = false, day = day, error = "no closed paper trades on $day yet", stats = stats)
                return@launch
            }
            _state.value = ReviewUi(running = true, day = day, stats = stats)
            val r = writer.write(DailyReview.SYSTEM, DailyReview.prompt(stats, partial), MAX_TOKENS) { partialText ->
                _state.update { it.copy(text = partialText) }
            }
            r.onSuccess { w ->
                store.put(SavedReview(day, nowMs(), w.source, w.text, stats, partial))
                _reviews.value = store.all()
                _state.value = ReviewUi(running = false, day = day, text = w.text, source = w.source, stats = stats)
            }.onFailure { e ->
                _state.update { it.copy(running = false, error = e.message ?: "no answer") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    companion object {
        const val MAX_TOKENS = 380
        const val RETRY_MS = 30 * 60_000L
    }
}
