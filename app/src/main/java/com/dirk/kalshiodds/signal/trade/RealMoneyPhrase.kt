package com.dirk.kalshiodds.signal.trade

/** The typed confirm for real-money actions (except the one-tap Stop). Exact, case-sensitive. */
object RealMoneyPhrase {
    const val PHRASE = "REAL MONEY"

    fun matches(typed: String?): Boolean = typed?.trim() == PHRASE
}
