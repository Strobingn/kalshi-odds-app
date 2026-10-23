package com.dirk.kalshiodds.signal.scalper.fleet

import android.content.Context
import com.dirk.kalshiodds.signal.external.CfIndexBook
import com.dirk.kalshiodds.signal.scalper.ClosedScalp
import com.dirk.kalshiodds.signal.scalper.ExitKind
import com.dirk.kalshiodds.signal.scalper.ScalpEvent
import com.dirk.kalshiodds.signal.scalper.ScalpOrder
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A time series forecaster for scalper 20 (TimesFM on the phone). */
interface Forecaster {
    val ready: Boolean
    val name: String
    /** Quantiles q10..q90 for each step ahead (index 0 = one step), from [series] (oldest first), or null. */
    fun forecast(series: DoubleArray): Array<DoubleArray>?
}

/**
 * The fleet's outside world on the phone: polls the public venues while the fleet is deciding (perp, Polymarket,
 * the hourly and altcoin Kalshi markets, three exchange books and their trades), refreshes the candle series, keeps
 * the off-venue paper legs and settles them, runs the forecaster and the regime loop. Market data only: no keys,
 * nothing here can place an order anywhere.
 */
class FleetHub(
    context: Context,
    cf: CfIndexBook,
    strikeOf: (String) -> Double?,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Optional slow-loop scorer: prompt → reply text (an LLM); null = the numbers alone. */
    private val askLlm: (suspend (String) -> Pair<String, String>?)? = null,
    var forecaster: Forecaster? = null
) : IndexFeed(cf, strikeOf, loadBehaviour(context)) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Booked into the arena (the paper scalper's [com.dirk.kalshiodds.signal.scalper.PaperScalper.bookOffBook]). */
    @Volatile
    var onLegEvents: ((List<ScalpEvent>) -> Unit)? = null

    /** What each feed last did, for the screens. */
    data class FeedStatus(val name: String, val ok: Boolean, val detail: String, val atMs: Long)

    private val _status = MutableStateFlow<Map<String, FeedStatus>>(emptyMap())
    val status: StateFlow<Map<String, FeedStatus>> = _status

    private fun note(name: String, ok: Boolean, detail: String) {
        _status.value = _status.value + (name to FeedStatus(name, ok, detail, System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ live state

    @Volatile private var activeUntilMs = 0L
    @Volatile private var window: Pair<String, Long>? = null
    @Volatile private var perp: PerpQuote? = null
    @Volatile private var poly: PolyQuote? = null
    @Volatile private var hourly: List<VenueQuote> = emptyList()
    @Volatile private var alts: Map<String, VenueQuote> = emptyMap()
    @Volatile private var pairReturns: Map<String, Pair<DoubleArray, DoubleArray>> = emptyMap()
    @Volatile private var hourVol: Pair<DoubleArray, Double>? = null
    @Volatile private var nowcast: Pair<Double, Long>? = null
    @Volatile private var forecast: Pair<Array<DoubleArray>, Long>? = null
    @Volatile private var regime: Pair<Double, String>? = null
    @Volatile private var lastView: FleetView? = null
    private val trades = ArrayDeque<Triple<Long, Double, Double>>()
    private val polyTokens = HashMap<String, Triple<String, String, Double>>()
    private var loops: Job? = null

    private fun touch(ticker: String, closeMs: Long, nowMs: Long) {
        activeUntilMs = nowMs + 60_000L
        window = ticker to closeMs
        if (loops?.isActive != true) start()
    }

    override fun index(ticker: String, closeMs: Long, nowMs: Long): IndexView? {
        touch(ticker, closeMs, nowMs)
        return super.index(ticker, closeMs, nowMs)
    }

    override fun external(ticker: String, closeMs: Long, nowMs: Long): FleetExternal {
        val hour = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("UTC")).hour
        val hv = hourVol?.let { (omega, s24) -> omega[hour].takeIf { it.isFinite() }?.let { it to s24 } }
        val vw = synchronized(trades) { FleetParsers.vwapPerSecond(trades, nowMs / 1000, 60) }
        val altSpot = alts.mapNotNull { (coin, q) ->
            val open = q.strike ?: return@mapNotNull null
            val now = cf.fresh(coin, nowMs)?.value ?: open
            coin to (now to open)
        }.toMap()
        return FleetExternal(
            perp = perp, poly = poly?.takeIf { it.slug == polySlug(closeMs) },
            hourly = hourly.filter { it.closeMs == closeMs },
            alts = alts.filterValues { it.closeMs == closeMs }, pairReturns = pairReturns, altSpot = altSpot,
            lastMacro = MacroCalendar.last(nowMs), nowcast = nowcast, vwap1s = vw,
            forecast = forecast, regime = regime, hourVol = hv
        )
    }

    override fun onView(view: FleetView) {
        super.onView(view)
        lastView = view
    }

    override fun onBehaviourChanged() {
        val arr = JSONArray()
        for (c in behaviour.snapshot()) arr.put(JSONArray(c.toList()))
        runCatching { prefs.edit().putString(KEY_BEHAVIOUR, arr.toString()).apply() }
    }

    // ------------------------------------------------------------ pollers

    private fun start() {
        loops = scope.launch {
            launch { loop(1_000L) { pollVenueBooks() } }
            launch { loop(3_000L) { pollKalshiAndPerp() } }
            launch { loop(3_000L) { pollPolymarket() } }
            launch { loop(15 * 60_000L) { refreshCandles() } }
            launch { loop(6 * 3_600_000L) { refreshHourVol() } }
            launch { loop(10_000L) { runForecast() } }
            launch { loop(60_000L) { runRegime() } }
            while (isActive && System.currentTimeMillis() < activeUntilMs) delay(5_000L)
            // Idle: the pollers stop; the leg settler keeps its own loop.
            coroutineContext[Job]?.children?.forEach { it.cancel() }
        }
    }

    private suspend fun loop(everyMs: Long, body: suspend () -> Unit) {
        while (true) {
            runCatching { body() }
            delay(everyMs)
        }
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "kalshi-odds-app").build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()

    private fun pollVenueBooks() {
        val books = ArrayList<Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>>>()
        get("https://api.exchange.coinbase.com/products/BTC-USD/book?level=2")?.let { runCatching { books += FleetParsers.coinbaseBook(it) } }
        get("https://api.kraken.com/0/public/Depth?pair=XBTUSD&count=100")?.let { runCatching { books += FleetParsers.krakenBook(it) } }
        get("https://api.gemini.com/v1/book/btcusd?limit_bids=50&limit_asks=50")?.let { runCatching { books += FleetParsers.geminiBook(it) } }
        val nc = FleetMath.cfNowcast(books)
        if (nc != null) nowcast = nc to System.currentTimeMillis()
        note("Exchange books", nc != null, "${books.size} of 3 venues" + (nc?.let { " · nowcast ${"%.1f".format(it)}" } ?: ""))
        val fresh = ArrayList<Triple<Long, Double, Double>>()
        get("https://api.exchange.coinbase.com/products/BTC-USD/trades?limit=100")?.let { runCatching { fresh += FleetParsers.coinbaseTrades(it) } }
        get("https://api.kraken.com/0/public/Trades?pair=XBTUSD&count=100")?.let { runCatching { fresh += FleetParsers.krakenTrades(it) } }
        val cut = System.currentTimeMillis() - 120_000L
        synchronized(trades) {
            val have = trades.mapTo(HashSet()) { it }
            for (t in fresh.sortedBy { it.first }) if (t.first >= cut && have.add(t)) trades.addLast(t)
            while (trades.isNotEmpty() && trades.first().first < cut) trades.removeFirst()
        }
    }

    private fun pollKalshiAndPerp() {
        val now = System.currentTimeMillis()
        get("$MARGIN/markets/KXBTCPERP")?.let { body -> FleetParsers.perpMarket(body, now)?.let { perp = it } }
        note("KXBTCPERP", perp?.let { now - it.updatedMs < 10_000L } == true, perp?.let { "bid ${it.bid} · ask ${it.ask}" } ?: "no quote")
        val got = HashMap<String, VenueQuote>()
        for ((coin, series) in ALT_SERIES) {
            get("$KALSHI/markets?series_ticker=$series&status=open&limit=5")?.let { body ->
                FleetParsers.kalshiMarkets(body).minByOrNull { it.closeMs ?: Long.MAX_VALUE }?.let { got[coin] = it }
            }
        }
        alts = got
        note("Altcoin 15m markets", got.isNotEmpty(), got.keys.sorted().joinToString())
        val close = window?.second ?: return
        // The hourly market settling on this window's close (only windows that close on the hour).
        if (Instant.ofEpochMilli(close).atZone(ZoneId.of("UTC")).minute == 0) {
            val ev = "KXBTCD-" + HOURLY_FMT.format(Instant.ofEpochMilli(close).atZone(ZoneId.of("America/New_York"))).uppercase(Locale.US)
            get("$KALSHI/markets?event_ticker=$ev&limit=200")?.let { body ->
                val spot = cf.fresh("BTC", now)?.value
                hourly = FleetParsers.kalshiMarkets(body).filter { q -> spot == null || q.strike == null || kotlin.math.abs(q.strike / spot - 1) < 0.01 }
                    .sortedBy { q -> spot?.let { s -> kotlin.math.abs((q.strike ?: s) - s) } ?: 0.0 }
            }
            note("Hourly KXBTCD", hourly.isNotEmpty(), "${hourly.size} strikes near the price · $ev")
        }
    }

    private fun polySlug(closeMs: Long) = "btc-updown-15m-${closeMs / 1000 - 900}"

    private fun pollPolymarket() {
        val close = window?.second ?: return
        val slug = polySlug(close)
        val tokens = polyTokens[slug] ?: get("https://gamma-api.polymarket.com/events?slug=$slug")?.let { FleetParsers.polyEvent(it) }?.also {
            polyTokens.clear(); polyTokens[slug] = it
        } ?: run { note("Polymarket", false, "no market $slug"); return }
        val up = get("$CLOB/book?token_id=${tokens.first}")?.let { FleetParsers.polyBestAsk(it) }
        val down = get("$CLOB/book?token_id=${tokens.second}")?.let { FleetParsers.polyBestAsk(it) }
        poly = PolyQuote(slug, up?.first, down?.first, up?.second, down?.second, tokens.third, System.currentTimeMillis())
        note("Polymarket", up != null || down != null, "Up ${up?.first ?: "–"} · Down ${down?.first ?: "–"}")
    }

    private fun candles(product: String, granularity: Int, startSec: Long? = null, endSec: Long? = null): List<Pair<Long, Double>> {
        val range = if (startSec != null && endSec != null) "&start=${Instant.ofEpochSecond(startSec)}&end=${Instant.ofEpochSecond(endSec)}" else ""
        return get("https://api.exchange.coinbase.com/products/$product/candles?granularity=$granularity$range")
            ?.let { runCatching { FleetParsers.coinbaseCandles(it) }.getOrNull() }.orEmpty()
    }

    private fun refreshCandles() {
        val btc = FleetSeries.windowReturns(candles("BTC-USD", 900))
        if (btc.isEmpty()) { note("15m candles", false, "Coinbase did not answer"); return }
        val out = HashMap<String, Pair<DoubleArray, DoubleArray>>()
        for (coin in ALT_SERIES.keys) {
            val alt = FleetSeries.windowReturns(candles("$coin-USD", 900))
            val (b, a) = FleetSeries.aligned(btc, alt)
            if (b.size >= 36) out[coin] = b to a
        }
        pairReturns = out
        note("15m candles", out.isNotEmpty(), "${out.size} coins × ${out.values.firstOrNull()?.first?.size ?: 0} windows")
    }

    private fun refreshHourVol() {
        val end = System.currentTimeMillis() / 1000 / 300 * 300
        val all = ArrayList<Pair<Long, Double>>()
        for (i in 3 downTo 0) {
            val e = end - i * 300L * 290
            all += candles("BTC-USD", 300, e - 300L * 290, e)
        }
        val hv = FleetSeries.hourVol(all.distinctBy { it.first }.sortedBy { it.first })
        if (hv != null) hourVol = hv
        note("Hour-of-day volatility", hv != null, hv?.let { "${all.size} five-minute bars" } ?: "not enough bars")
    }

    private fun runForecast() {
        val f = forecaster ?: return
        if (!f.ready) { note("TimesFM", false, "model not downloaded (AI → On-phone models)"); return }
        val now = System.currentTimeMillis()
        val series = cf.series("BTC", now, 512)
        if (series.count { it.isFinite() } < 300) { note("TimesFM", false, "waiting for 5 minutes of index"); return }
        val t0 = System.nanoTime()
        val q = f.forecast(series) ?: run { note("TimesFM", false, "forecast failed"); return }
        forecast = q to now
        note("TimesFM", true, "${f.name} · ${(System.nanoTime() - t0) / 1_000_000} ms · 60 s median ${"%.1f".format(q.getOrNull(59)?.get(4) ?: Double.NaN)}")
    }

    /** The slow loop: market quality 0–100 from the numbers, or from the LLM when one is set up. */
    private suspend fun runRegime() {
        val v = lastView ?: return
        val quant = quantRegime(v)
        val llm = askLlm
        if (llm != null && forecast != null) {
            val prompt = "Score Kalshi Bitcoin 15-minute market quality for a short-term forecaster, 0 (do not trade) to 100. " +
                "Reply with JSON {\"score\": n} only.\n" +
                "spread ${v.yesAsk?.let { a -> v.yesBid?.let { b -> "%.3f".format(a - b) } }}, bid size ${v.yesBidQty}, ask size ${v.yesAskQty}, " +
                "VPIN ${v.vpin}, seconds left ${v.tte.toInt()}, index sigma per s ${v.sigma1sLog}, seconds between trades ${v.tradeGap}, " +
                "numeric score ${(quant * 100).toInt()}."
            val reply = runCatching { llm(prompt) }.getOrNull()
            val score = reply?.second?.let { Regex("\"score\"\\s*:\\s*(\\d+(\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            if (score != null) {
                regime = (score / 100.0).coerceIn(0.0, 1.0) to "LLM ${reply.first}"
                note("Regime loop", true, "LLM ${reply.first}: ${score.toInt()} (numbers ${(quant * 100).toInt()})")
                return
            }
        }
        regime = quant to "numbers"
        note("Regime loop", true, "numbers: ${(quant * 100).toInt()}")
    }

    /** 0..1: tight spread, real size on the book, a fresh index, trades flowing, no toxic flow. */
    fun quantRegime(v: FleetView): Double {
        val spread = v.yesAsk?.let { a -> v.yesBid?.let { b -> a - b } } ?: return 0.0
        val sSpread = (1.0 - (spread - 0.01) / 0.04).coerceIn(0.0, 1.0)
        val depth = ((v.yesBidQty ?: 0.0) + (v.yesAskQty ?: 0.0))
        val sDepth = (depth / 2000.0).coerceIn(0.0, 1.0)
        val sIndex = if (v.spot != null && v.sigma1sLog != null) 1.0 else 0.0
        val sFlow = v.tradeGap?.let { (1.0 - (it - 1.0) / 9.0).coerceIn(0.0, 1.0) } ?: 0.0
        val sToxic = v.vpin?.let { (1.0 - (it - 0.4) / 0.4).coerceIn(0.0, 1.0) } ?: 0.7
        return (sSpread + sDepth + sIndex + sFlow + sToxic) / 5.0
    }

    // ------------------------------------------------------------ off-venue paper legs

    private data class Stored(val id: Long, val strategy: String, val leg: OffBookLeg, val openedMs: Long, val note: String)

    private val legs: MutableList<Stored> = loadLegs()
    private var settler: Job? = null

    override fun openOffBook(strategy: ScalpStrategy, leg: OffBookLeg, note: String, nowMs: Long) {
        val id = nowMs * 100 + (synchronized(legs) { legs.size } % 100)
        val stored = Stored(id, strategy.name, leg, nowMs, note)
        synchronized(legs) { legs += stored; saveLegs() }
        val order = orderOf(stored, strategy)
        onLegEvents?.invoke(listOf(ScalpEvent.Posted(order), ScalpEvent.Filled(order, nowMs)))
        ensureSettler()
    }

    private fun orderOf(s: Stored, st: ScalpStrategy) = ScalpOrder(
        id = s.id, strategy = st, ticker = venueTicker(s.leg), side = s.leg.side, price = s.leg.price, contracts = s.leg.contracts,
        entryFeeUsd = s.leg.feeUsd, queueAhead = 0.0, queueKnown = true, postedAtMs = s.openedMs,
        predictionCents = 0.0, expectedCents = 0.0, tookAsk = true
    )

    private fun venueTicker(leg: OffBookLeg) = when (leg.venue) {
        OffBookLeg.Venue.KALSHI -> leg.market
        OffBookLeg.Venue.POLYMARKET -> "POLY:${leg.market}"
        OffBookLeg.Venue.PERP -> "PERP:${leg.market}"
    }

    /** Legs still open, for the screens. */
    fun openLegs(): List<Pair<String, OffBookLeg>> = synchronized(legs) { legs.map { it.strategy to it.leg } }

    private fun ensureSettler() {
        if (settler?.isActive == true) return
        settler = scope.launch {
            while (isActive) {
                val due = synchronized(legs) { legs.filter { System.currentTimeMillis() >= it.leg.closeMs } }
                if (synchronized(legs) { legs.isEmpty() }) break
                for (s in due) runCatching { trySettle(s) }
                delay(5_000L)
            }
        }
    }

    private fun trySettle(s: Stored) {
        val st = runCatching { ScalpStrategy.valueOf(s.strategy) }.getOrNull() ?: return drop(s)
        val now = System.currentTimeMillis()
        val leg = s.leg
        val (exit, fee) = when (leg.venue) {
            OffBookLeg.Venue.KALSHI -> {
                val r = get("$KALSHI/markets/${leg.market}")?.let { FleetParsers.kalshiResult(it) } ?: return
                (if (r.equals(leg.side, true)) 1.0 else 0.0) to 0.0
            }
            OffBookLeg.Venue.POLYMARKET -> {
                val r = get("https://gamma-api.polymarket.com/events?slug=${leg.market}")?.let { FleetParsers.polyResult(it) } ?: return
                (if (r.equals(leg.side, true)) 1.0 else 0.0) to 0.0
            }
            OffBookLeg.Venue.PERP -> {
                // Closed at the window's close: sell a long at the bid, buy back a short at the ask.
                val p = get("$MARGIN/markets/${leg.market}")?.let { FleetParsers.perpMarket(it, now) } ?: return
                val px = if (leg.side == "YES") p.bid else p.ask
                px to px * leg.contracts * p.takerFeeRate
            }
        }
        val sign = if (leg.venue == OffBookLeg.Venue.PERP && leg.side == "NO") -1.0 else 1.0
        val pnl = sign * (exit - leg.price) * leg.contracts - leg.feeUsd - fee
        val closed = ClosedScalp(orderOf(s, st), exit, ExitKind.SETTLED, fee, Math.round(pnl * 10_000.0) / 10_000.0, s.openedMs, now)
        onLegEvents?.invoke(listOf(ScalpEvent.Closed(closed)))
        drop(s)
    }

    private fun drop(s: Stored) = synchronized(legs) { legs.removeAll { it.id == s.id }; saveLegs() }

    private fun saveLegs() {
        val arr = JSONArray()
        for (s in legs) arr.put(JSONObject().apply {
            put("id", s.id); put("strategy", s.strategy); put("opened", s.openedMs); put("note", s.note)
            put("venue", s.leg.venue.name); put("market", s.leg.market); put("side", s.leg.side); put("price", s.leg.price)
            put("contracts", s.leg.contracts); put("fee", s.leg.feeUsd); put("close", s.leg.closeMs)
        })
        runCatching { prefs.edit().putString(KEY_LEGS, arr.toString()).apply() }
    }

    private fun loadLegs(): MutableList<Stored> {
        val raw = prefs.getString(KEY_LEGS, null) ?: return ArrayList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Stored(
                    o.getLong("id"), o.getString("strategy"),
                    OffBookLeg(OffBookLeg.Venue.valueOf(o.getString("venue")), o.getString("market"), o.getString("side"), o.getDouble("price"),
                        o.getInt("contracts"), o.getDouble("fee"), o.getLong("close")),
                    o.getLong("opened"), o.optString("note")
                )
            }.toMutableList()
        }.getOrDefault(ArrayList()).also { if (it.isNotEmpty()) scope.launch { delay(2_000L); ensureSettler() } }
    }

    companion object {
        private const val PREFS = "fleet_hub"
        private const val KEY_LEGS = "off_book_legs"
        private const val KEY_BEHAVIOUR = "behaviour_table"
        const val KALSHI = "https://api.elections.kalshi.com/trade-api/v2"
        const val MARGIN = "https://external-api.kalshi.com/trade-api/v2/margin"
        const val CLOB = "https://clob.polymarket.com"
        val ALT_SERIES = linkedMapOf(
            "ETH" to "KXETH15M", "SOL" to "KXSOL15M", "XRP" to "KXXRP15M", "DOGE" to "KXDOGE15M", "BNB" to "KXBNB15M", "HYPE" to "KXHYPE15M"
        )
        private val HOURLY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMMddHH", Locale.US)

        private fun loadBehaviour(context: Context): BehaviourTable {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BEHAVIOUR, null)
                ?: return BehaviourTable()
            return runCatching {
                val a = JSONArray(raw)
                BehaviourTable((0 until a.length()).map { i -> a.getJSONArray(i).let { r -> DoubleArray(r.length()) { r.getDouble(it) } } })
            }.getOrDefault(BehaviourTable())
        }
    }
}
