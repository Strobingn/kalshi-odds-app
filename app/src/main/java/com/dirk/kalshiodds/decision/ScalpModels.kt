package com.dirk.kalshiodds.decision

/**
 * 0.3.50 Scalp tab: the SIX paper scalp models that run side by side on every BTC/ETH/SOL 15-minute window, each with
 * its own bankroll slice and ledger, plus the pure leaderboard / per-window / ladder math the tab renders.
 * PAPER ONLY — no model has an order path; no LLM is in the decision path.
 */
object ScalpModels {
    enum class Model(val code: String, val label: String, val blurb: String) {
        FAIR_GAP("fair", "Fair-gap", ScalpStrategy.FAIR_GAP.blurb),
        DIP_HUNTER("dip", "Dip-hunter", ScalpStrategy.DIP_HUNTER.blurb),
        MOMENTUM("momo", "Momentum-sniper", ScalpStrategy.MOMENTUM.blurb),
        EXTREME_REVERSION("xrev", "Extreme-reversion", ScalpStrategy.EXTREME_REVERSION.blurb),
        CF_REPRICE("cfr", "CF-reprice", ScalpStrategy.CF_REPRICE.blurb),
        MAKER_DIP("mkdip", "Maker dip-rebound", "maker-first: post a bid after a fast ask drop (fee $0 maker), sell the rebound resting at the ask; queue-modelled fills"),
        /** 0.3.53: buy the side the short-horizon forecaster says will rise, near 50¢; exit when the forecast turns. */
        FORECAST_RIDE("fcride", "Forecast ride", "near 50¢ (40–60¢), buy the side the deterministic short-horizon forecast says rises by more than spread + both fees; hold while it keeps pointing up"),
        /** 0.3.53: exact mirror of Forecast ride — trades against the forecast with the same band, exits, sizing and fees. */
        FORECAST_FADE("fcfade", "Forecast fade", "mirror of Forecast ride: same band, exits, sizing and fees, but takes the side the forecast says falls"),
        // 0.3.57 sf57: twenty shock-lag / late-favourite paper scalpers (see ShockFav).
        SF01("sf01", "sf01 " + ShockFav.VARIANTS[0].label, ShockFav.VARIANTS[0].summary()),
        SF02("sf02", "sf02 " + ShockFav.VARIANTS[1].label, ShockFav.VARIANTS[1].summary()),
        SF03("sf03", "sf03 " + ShockFav.VARIANTS[2].label, ShockFav.VARIANTS[2].summary()),
        SF04("sf04", "sf04 " + ShockFav.VARIANTS[3].label, ShockFav.VARIANTS[3].summary()),
        SF05("sf05", "sf05 " + ShockFav.VARIANTS[4].label, ShockFav.VARIANTS[4].summary()),
        SF06("sf06", "sf06 " + ShockFav.VARIANTS[5].label, ShockFav.VARIANTS[5].summary()),
        SF07("sf07", "sf07 " + ShockFav.VARIANTS[6].label, ShockFav.VARIANTS[6].summary()),
        SF08("sf08", "sf08 " + ShockFav.VARIANTS[7].label, ShockFav.VARIANTS[7].summary()),
        SF09("sf09", "sf09 " + ShockFav.VARIANTS[8].label, ShockFav.VARIANTS[8].summary()),
        SF10("sf10", "sf10 " + ShockFav.VARIANTS[9].label, ShockFav.VARIANTS[9].summary()),
        SF11("sf11", "sf11 " + ShockFav.VARIANTS[10].label, ShockFav.VARIANTS[10].summary()),
        SF12("sf12", "sf12 " + ShockFav.VARIANTS[11].label, ShockFav.VARIANTS[11].summary()),
        SF13("sf13", "sf13 " + ShockFav.VARIANTS[12].label, ShockFav.VARIANTS[12].summary()),
        SF14("sf14", "sf14 " + ShockFav.VARIANTS[13].label, ShockFav.VARIANTS[13].summary()),
        SF15("sf15", "sf15 " + ShockFav.VARIANTS[14].label, ShockFav.VARIANTS[14].summary()),
        SF16("sf16", "sf16 " + ShockFav.VARIANTS[15].label, ShockFav.VARIANTS[15].summary()),
        SF17("sf17", "sf17 " + ShockFav.VARIANTS[16].label, ShockFav.VARIANTS[16].summary()),
        SF18("sf18", "sf18 " + ShockFav.VARIANTS[17].label, ShockFav.VARIANTS[17].summary()),
        SF19("sf19", "sf19 " + ShockFav.VARIANTS[18].label, ShockFav.VARIANTS[18].summary()),
        SF20("sf20", "sf20 " + ShockFav.VARIANTS[19].label, ShockFav.VARIANTS[19].summary()),
        SF21("sf21", "sf21 " + ShockFav.VARIANTS[20].label, ShockFav.VARIANTS[20].summary()),
        SF22("sf22", "sf22 " + ShockFav.VARIANTS[21].label, ShockFav.VARIANTS[21].summary()),
        SF23("sf23", "sf23 " + ShockFav.VARIANTS[22].label, ShockFav.VARIANTS[22].summary()),
        SF24("sf24", "sf24 " + ShockFav.VARIANTS[23].label, ShockFav.VARIANTS[23].summary()),
        SF27("sf27", "sf27 " + ShockFav.VARIANTS[24].label, ShockFav.VARIANTS[24].summary()),
        SF28("sf28", "sf28 " + ShockFav.VARIANTS[25].label, ShockFav.VARIANTS[25].summary()),
        SF29("sf29", "sf29 " + ShockFav.VARIANTS[26].label, ShockFav.VARIANTS[26].summary()),
        SF30("sf30", "sf30 " + ShockFav.VARIANTS[27].label, ShockFav.VARIANTS[27].summary()),
        SF31("sf31", "sf31 " + ShockFav.VARIANTS[28].label, ShockFav.VARIANTS[28].summary())
    }

    val ALL: List<Model> = Model.values().toList()
    val COUNT: Int get() = ALL.size
    private val SF_BY_CODE: Map<String, Model> by lazy { ALL.filter { it.code.startsWith(ShockFav.PREFIX) }.associateBy { it.code } }
    val SF_MODELS: List<Model> get() = ALL.filter { it.code.startsWith(ShockFav.PREFIX) }
    /** 0.3.59: true for sf variants with no positive BTC 2 s-delay test CI. */
    fun noEdge2s(m: Model): Boolean = ShockFav.byVariantId(m.code)?.noEdge2s == true
    /** Card order: 2 s-delay survivors first, then legacy models, then no-edge sf variants (fixed order within groups). */
    val CARD_ORDER: List<Model> by lazy {
        ALL.sortedBy { m -> val p = ShockFav.byVariantId(m.code); when { p == null -> 1; !p.noEdge2s -> 0; else -> 2 } }
    }
    /** One combined leaderboard: survivors first, then by net. */
    fun combinedBoard(rows: Map<Model, Row>): List<Row> = ALL.mapNotNull { rows[it] }
        .sortedWith(compareBy<Row> { if (noEdge2s(it.model)) 1 else 0 }.thenByDescending { it.netUsd + it.unrealizedUsd }.thenByDescending { it.roundTrips })

    /** Sixth model: the dip-hunter seed run maker-first (resting bid entry, resting ask exit). */
    val MAKER_DIP_PARAMS: ScalpParams = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER).copy(maker = true)

    /** 0.3.54: no per-model split — every scalper draws from the whole shared free paper bankroll. */
    fun sliceUsd(bankrollUsd: Double): Double = (bankrollUsd.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0)

    fun modelOf(p: ScalpParams): Model =
        if (p.maker && p.strategy == ScalpStrategy.DIP_HUNTER) Model.MAKER_DIP else modelOf(p.strategy)

    fun modelOf(s: ScalpStrategy): Model = when (s) {
        ScalpStrategy.FAIR_GAP -> Model.FAIR_GAP
        ScalpStrategy.DIP_HUNTER -> Model.DIP_HUNTER
        ScalpStrategy.MOMENTUM -> Model.MOMENTUM
        ScalpStrategy.EXTREME_REVERSION -> Model.EXTREME_REVERSION
        ScalpStrategy.CF_REPRICE -> Model.CF_REPRICE
    }

    /** Model of a primary trade; null for shadow tuning variants. */
    fun modelOf(t: ScalpTrade): Model? {
        if (!t.isPrimary) return null
        if (t.variantId.startsWith(ForecastScalp.RIDE_PREFIX)) return Model.FORECAST_RIDE
        if (t.variantId.startsWith(ForecastScalp.FADE_PREFIX)) return Model.FORECAST_FADE
        if (ShockFav.isShockFav(t.variantId)) return SF_BY_CODE[t.variantId.take(4)]
        val p = ScalpParams.byId(t.variantId) ?: return Model.FAIR_GAP
        return modelOf(p)
    }

    data class Row(
        val model: Model,
        val netUsd: Double,
        val roundTrips: Int,
        val wins: Int,
        val winRate: Double?,
        val avgWinUsd: Double?,
        val avgLossUsd: Double?,
        /** Net / entry cost over closed round trips. */
        val roi: Double?,
        val roiCi: ClusteredBootstrap.Ci?,
        val open: Int,
        val unrealizedUsd: Double,
        val noFills: Int,
        /** 0.3.52 algorithm cards. */
        val grossWinUsd: Double = 0.0,
        val grossLossUsd: Double = 0.0,
        val losses: Int = 0,
        val openContracts: Int = 0,
        val feesUsd: Double = 0.0
    ) {
        val holding: Boolean get() = openContracts > 0
    }

    fun row(model: Model, trades: List<ScalpTrade>, bidFor: (ScalpTrade) -> Double? = { null }, resamples: Int = 400): Row {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
        val wins = closed.filter { it.netUsd!! > 0.0 }
        val losses = closed.filter { it.netUsd!! <= 0.0 }
        val cost = closed.sumOf { it.entryCostUsd }
        val net = closed.sumOf { it.netUsd!! }
        val active = trades.filter { it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY }
        val ci = if (resamples <= 0) null else ClusteredBootstrap.meanCi(
            closed.filter { it.entryCostUsd > 0.0 }.map { it.windowKey to it.netUsd!! / it.entryCostUsd },
            resamples = resamples
        )
        return Row(
            model = model,
            netUsd = net,
            roundTrips = closed.size,
            wins = wins.size,
            winRate = if (closed.isEmpty()) null else wins.size.toDouble() / closed.size,
            avgWinUsd = wins.takeIf { it.isNotEmpty() }?.map { it.netUsd!! }?.average(),
            avgLossUsd = losses.takeIf { it.isNotEmpty() }?.map { it.netUsd!! }?.average(),
            roi = if (cost > 0.0) net / cost else null,
            roiCi = ci,
            open = active.size,
            unrealizedUsd = active.sumOf { t -> t.unrealizedUsd(bidFor(t))?.takeIf { it.isFinite() } ?: 0.0 },
            noFills = trades.count { it.state == ScalpState.NO_FILL },
            grossWinUsd = wins.sumOf { it.netUsd!! },
            grossLossUsd = losses.filter { it.netUsd!! < 0.0 }.sumOf { it.netUsd!! },
            losses = losses.count { it.netUsd!! < 0.0 },
            openContracts = active.filter { it.state != ScalpState.PENDING_ENTRY }.sumOf { it.remaining.coerceAtLeast(0) },
            feesUsd = closed.sumOf { it.entryFeeUsd + it.exitFeeUsd }
        )
    }

    /** Leaderboard (all six models, best net first). [coin] null = all coins; [windowKey] null = all-time. */
    fun leaderboard(trades: List<ScalpTrade>, coin: String? = null, windowKey: String? = null, bidFor: (ScalpTrade) -> Double? = { null }, sinceMs: Long? = null, withCi: Boolean = true): List<Row> {
        val byModel = trades.asSequence()
            .filter { sinceMs == null || (it.closedAtMs ?: it.signalAtMs) >= sinceMs }
            .filter { coin == null || it.coin == coin }
            .filter { windowKey == null || it.windowKey == windowKey }
            .mapNotNull { t -> modelOf(t)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
        return ALL.map { m -> row(m, byModel[m].orEmpty(), bidFor, if (withCi) 400 else 0) }
            .sortedWith(compareByDescending<Row> { it.netUsd + it.unrealizedUsd }.thenByDescending { it.roundTrips })
    }

    /** All trades (primary) of one model, newest first. */
    fun tradesOf(model: Model, trades: List<ScalpTrade>, limit: Int = 300): List<ScalpTrade> =
        trades.asSequence().filter { modelOf(it) == model && it.state != ScalpState.NO_FILL }.take(limit).toList()

    /** Cumulative net after fees over closed round trips, oldest → newest (bounded). */
    fun equity(model: Model, trades: List<ScalpTrade>, maxPoints: Int = 400): List<Double> {
        val closed = trades.filter { modelOf(it) == model && it.state == ScalpState.CLOSED && it.netUsd?.isFinite() == true }
            .sortedBy { it.closedAtMs ?: 0L }
        var acc = 0.0
        val pts = closed.map { acc += it.netUsd!!; acc }
        if (pts.size <= maxPoints) return pts
        val step = pts.size.toDouble() / maxPoints
        return (0 until maxPoints).map { pts[(it * step).toInt()] } + pts.last()
    }

    /** Best model = highest net with at least one round trip (null when nobody has traded). */
    fun best(rows: List<Row>): Model? = rows.filter { it.roundTrips > 0 }.maxByOrNull { it.netUsd }?.model

    data class WindowRow(val windowKey: String, val closeMs: Long?, val netByModel: Map<Model, Double>, val tripsByModel: Map<Model, Int>, val best: Model?)

    /** Per 15-minute window: each model's net, and which did best. Newest first, at most [limit] windows. */
    fun perWindow(trades: List<ScalpTrade>, coin: String? = null, limit: Int = 48): List<WindowRow> {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null && (coin == null || it.coin == coin) }
        return closed.groupBy { it.windowKey }.map { (w, rows) ->
            val tagged = rows.mapNotNull { t -> modelOf(t)?.let { it to t } }
            val net = tagged.groupBy({ it.first }, { it.second.netUsd!! }).mapValues { it.value.sum() }
            val n = tagged.groupBy({ it.first }, { it.second }).mapValues { it.value.size }
            WindowRow(w, rows.firstNotNullOfOrNull { ScalpTicker.closeMs(it.ticker) }, net, n, net.maxByOrNull { it.value }?.key)
        }.sortedByDescending { it.closeMs ?: 0L }.take(limit)
    }

    /** Start of today in ET (for "today" P&L). */
    fun startOfTodayEtMs(nowMs: Long): Long =
        java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
            .atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    /** "BTC 15m · 11:00 AM" (window end time, ET). */
    fun chipLabel(ticker: String, closeMs: Long?): String {
        val coin = ScalpParams.coinOf(ticker)
        val end = (closeMs ?: ScalpTicker.closeMs(ticker))?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("America/New_York"))
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
        }
        return if (end == null) "$coin 15m" else "$coin 15m · $end"
    }

    /** Current-window key of a ticker (same cluster as [ScalpTrade.windowKey]). */
    fun windowKeyOf(ticker: String): String = ticker.uppercase().substringAfter('-').substringBefore('-')
}

/**
 * 0.3.50 price ladder for one side of one market (paper). Rows at every whole cent 1..99, highest first:
 * resting bids (our side), asks (1 − other side's bids), and our open paper orders at that price.
 */
object PriceLadder {
    data class Row(val cents: Int, val bidSize: Double, val askSize: Double, val myBuyQty: Int, val mySellQty: Int, val myOrderIds: List<String>) {
        val price: Double get() = cents / 100.0
    }

    data class Order(val id: String, val buy: Boolean, val limitPrice: Double, val remaining: Int)

    fun rows(
        side: String,
        yesBids: List<Pair<Double, Double>>,
        noBids: List<Pair<Double, Double>>,
        myOrders: List<Order> = emptyList()
    ): List<Row> {
        val own = if (side.equals("NO", true)) noBids else yesBids
        val other = if (side.equals("NO", true)) yesBids else noBids
        val bids = HashMap<Int, Double>()
        own.forEach { (p, s) -> if (p.isFinite() && s > 0.0) cents(p)?.let { bids[it] = (bids[it] ?: 0.0) + s } }
        val asks = HashMap<Int, Double>()
        other.forEach { (p, s) -> if (p.isFinite() && s > 0.0) cents(1.0 - p)?.let { asks[it] = (asks[it] ?: 0.0) + s } }
        val mine = myOrders.groupBy { cents(it.limitPrice) ?: -1 }
        return (99 downTo 1).map { c ->
            val m = mine[c].orEmpty()
            Row(c, bids[c] ?: 0.0, asks[c] ?: 0.0, m.filter { it.buy }.sumOf { it.remaining }, m.filter { !it.buy }.sumOf { it.remaining }, m.map { it.id })
        }
    }

    /** Rows around the touch (best bid/ask ± [pad] cents) so the ladder opens where the action is. */
    fun focusIndex(rows: List<Row>): Int {
        val bestAsk = rows.filter { it.askSize > 0 }.minOfOrNull { it.cents }
        val bestBid = rows.filter { it.bidSize > 0 }.maxOfOrNull { it.cents }
        val mid = when {
            bestAsk != null && bestBid != null -> (bestAsk + bestBid) / 2
            else -> bestAsk ?: bestBid ?: 50
        }
        return (99 - mid).coerceIn(0, rows.lastIndex.coerceAtLeast(0))
    }

    fun cents(p: Double): Int? = if (!p.isFinite()) null else Math.round(p * 100).toInt().takeIf { it in 1..99 }

    /** "Sell what I own at +X¢": limit = entry + X, clamped to 1..99¢. Null when nothing is held. */
    fun exitPrice(entry: Double?, plusCents: Int): Double? {
        val e = entry?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val c = (Math.round(e * 100).toInt() + plusCents).coerceIn(1, 99)
        return c / 100.0
    }

    /** Drag repricing: rows moved → new limit, clamped to 1..99¢. */
    fun repriced(limit: Double, rowsMoved: Int): Double = ((Math.round(limit * 100).toInt() - rowsMoved).coerceIn(1, 99)) / 100.0

    val PRESET_SIZES = listOf(1, 5, 10, 25, 50)
    val EXIT_PRESETS_CENTS = listOf(1, 2, 3, 5, 10)
}


/** 0.3.53 Forecast ride / fade rule (paper only). Pure decisions; [ScalpBook.onForecastQuote] applies them. */
object ForecastScalp {
    const val RIDE_PREFIX = "fcride"
    const val FADE_PREFIX = "fcfade"
    const val BAND_LO = 0.40
    const val BAND_HI = 0.60
    const val MAX_SPREAD = 0.02
    const val MIN_TAU_ENTRY_S = 60.0
    const val EXIT_BEFORE_CLOSE_S = 30.0
    const val TAKE_PROFIT = 0.90
    const val HARD_FLIP = -0.03
    const val ENTRY_LIMIT_TTL_MS = 2_000L
    const val SAFETY = 0.005

    fun isForecast(t: ScalpTrade): Boolean = t.variantId.startsWith(RIDE_PREFIX) || t.variantId.startsWith(FADE_PREFIX)
    fun variantId(ride: Boolean, horizonS: Int) = (if (ride) RIDE_PREFIX else FADE_PREFIX) + "-h" + (if (horizonS < 0) "close" else horizonS.toString())

    /** Entry: band, spread, edge > spread + entry fee + exit fee (1-ct rounded) + 0.5¢. */
    /** 0.3.54: predicted move must beat the full round trip (spread + entry fee + exit fee) by [marginUsd]. */
    fun wantsEntry(mid: Double, spread: Double, ask: Double, move: Double, tauS: Double, marginUsd: Double = SAFETY): Boolean =
        mid in BAND_LO..BAND_HI && spread <= MAX_SPREAD + 1e-9 && tauS >= MIN_TAU_ENTRY_S &&
            move > spread + 2 * ScalpRule.orderFee(1, ask) + marginUsd

    /** Exit when the forecast for the held side turns (below one exit fee), flips hard, TP, horizon reached, or close. */
    /** [sideForecast] NaN = no fresh forecast: forecast-based exits are skipped (hold until fresh data). */
    fun wantsExit(sideForecast: Double, bid: Double, heldS: Double, horizonS: Int, tauS: Double): String? = when {
        bid >= TAKE_PROFIT - 1e-9 -> "take profit ≥ 90¢"
        !sideForecast.isNaN() && sideForecast < HARD_FLIP -> "forecast flipped hard"
        !sideForecast.isNaN() && sideForecast < ScalpRule.orderFee(1, bid) -> "forecast turned (move < exit fee)"
        !sideForecast.isNaN() && horizonS > 0 && heldS >= horizonS -> "horizon reached"
        tauS < EXIT_BEFORE_CLOSE_S -> "exit before close"
        else -> null
    }

    fun horizonNote(): String = LeadForecaster.note()
}
