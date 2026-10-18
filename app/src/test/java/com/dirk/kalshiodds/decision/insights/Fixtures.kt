package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade

internal fun settled(id: String, net: Double, at: Long, contracts: Int = 10, sold: Int = 10, fees: Double = 0.0, state: ScalpState = ScalpState.CLOSED) = ScalpTrade(
    id = id, ticker = "KXBTC15M-26OCT181700-00", side = "YES", state = state, signalAtMs = at - 60_000, signalAsk = 0.4, fairAtSignal = 0.5,
    contracts = contracts, entryPrice = 0.4, entryFeeUsd = fees / 2, exitFeeUsd = fees / 2, soldContracts = sold, proceedsUsd = 0.45 * sold,
    closedAtMs = at, netUsd = net)

internal fun series(prefix: String, nets: List<Double>, start: Long = 1_000_000L) = nets.mapIndexed { i, n -> settled("$prefix$i", n, start + i * 1000L) }
