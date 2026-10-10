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
        MAKER_DIP("mkdip", "Maker dip-rebound", "maker-first: post a bid after a fast ask drop (fee $0 maker), sell the rebound resting at the ask; queue-modelled fills")
    }

    val ALL: List<Model> = Model.values().toList()
    const val COUNT = 6

    /** Sixth model: the dip-hunter seed run maker-first (resting bid entry, resting ask exit). */
    val MAKER_DIP_PARAMS: ScalpParams = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER).copy(maker = true)

    fun sliceUsd(bankrollUsd: Double): Double = (bankrollUsd.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0) / COUNT

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
        val noFills: Int
    )

    fun row(model: Model, trades: List<ScalpTrade>, bidFor: (ScalpTrade) -> Double? = { null }, resamples: Int = 400): Row {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
        val wins = closed.filter { it.netUsd!! > 0.0 }
        val losses = closed.filter { it.netUsd!! <= 0.0 }
        val cost = closed.sumOf { it.entryCostUsd }
        val net = closed.sumOf { it.netUsd!! }
        val active = trades.filter { it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY }
        val ci = ClusteredBootstrap.meanCi(
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
            noFills = trades.count { it.state == ScalpState.NO_FILL }
        )
    }

    /** Leaderboard (all six models, best net first). [coin] null = all coins; [windowKey] null = all-time. */
    fun leaderboard(trades: List<ScalpTrade>, coin: String? = null, windowKey: String? = null, bidFor: (ScalpTrade) -> Double? = { null }): List<Row> {
        val byModel = trades.asSequence()
            .filter { coin == null || it.coin == coin }
            .filter { windowKey == null || it.windowKey == windowKey }
            .mapNotNull { t -> modelOf(t)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
        return ALL.map { m -> row(m, byModel[m].orEmpty(), bidFor) }
            .sortedWith(compareByDescending<Row> { it.netUsd + it.unrealizedUsd }.thenByDescending { it.roundTrips })
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
