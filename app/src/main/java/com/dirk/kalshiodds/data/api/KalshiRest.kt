package com.dirk.kalshiodds.data.api

/**
 * The single process-wide Kalshi REST budget. Every OkHttp client that
 * talks to Kalshi must use [gate].
 */
object KalshiRest {
    val bucket: KalshiTokenBucket = KalshiTokenBucket()
    val gate: KalshiHttpGate = KalshiHttpGate(bucket)
}
