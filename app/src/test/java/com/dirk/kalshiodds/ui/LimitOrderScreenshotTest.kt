package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.limit.LimitHost
import com.dirk.kalshiodds.signal.limit.LimitOrder
import com.dirk.kalshiodds.signal.limit.PaperLimitOrder
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.signal.trade.TradeTicket
import com.dirk.kalshiodds.ui.components.LimitOrderDialog
import com.dirk.kalshiodds.ui.components.WorkingLimitsCard
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test

/** JVM screenshots of the limit-order editor and the resting-orders card. */
class LimitOrderScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false, screenWidth = 411, screenHeight = 900, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    private val now = 1_791_550_800_000L
    private val ticker = "KXBTC15M-26OCT091315-15"

    private val buy = TradeTicket(
        id = "t1", ticker = ticker, side = "YES", bookSide = "bid", stakeUsd = 4.77, limitPrice = 0.53, yesLimitPrice = 0.53,
        contracts = 9, estimatedFillUsd = 4.77, maxPayoutUsd = 9.0, estimatedAvgFill = 0.53, sizingNote = "", kind = TicketKind.MANUAL
    )

    private val host = object : LimitHost {
        override fun quote(ticket: TradeTicket) = LimitOrder.Quote(0.50, 0.53, 4_210.0, 1_100.0)
        override fun queueAhead(ticket: TradeTicket, price: Double): Double? = if (price > 0.505) 0.0 else 4_210.0
        override fun check(ticket: TradeTicket, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean): String? =
            LimitOrder.build(ticket, price, contracts, quote(ticket), cancelAfterMs, now + 600_000L, now, paper).exceptionOrNull()?.message
        override fun place(ticketId: String, price: Double, contracts: Int, cancelAfterMs: Long, paper: Boolean) = Unit
        override fun cancelPaper(orderId: String) = Unit
        override val tradeFeedOn = true
    }

    @Test
    fun editorRealBuy() {
        paparazzi.snapshot(name = "limit_editor_real_buy") {
            KalshiOddsTheme(darkTheme = true) {
                LimitOrderDialog(buy, host, credentialsConfigured = true, paperTradingEnabled = false, onBack = {}, onDismiss = {})
            }
        }
    }

    @Test
    fun editorPaperSell() {
        val sell = buy.copy(id = "s1", kind = TicketKind.SELL, bookSide = "ask", heldContracts = 9, paperOnly = true, reduceOnly = true)
        paparazzi.snapshot(name = "limit_editor_paper_sell") {
            KalshiOddsTheme(darkTheme = true) {
                LimitOrderDialog(sell, host, credentialsConfigured = false, paperTradingEnabled = true, onBack = {}, onDismiss = {})
            }
        }
    }

    @Test
    fun restingCard() {
        val real = LimitOrder.build(buy, 0.51, 9, host.quote(buy), 120_000L, now + 600_000L, now, paper = false).getOrThrow()
        val paper = LimitOrder.build(buy.copy(side = "NO", bookSide = "ask"), 0.46, 20, LimitOrder.Quote(0.47, 0.50), 300_000L, now + 600_000L, now, paper = true).getOrThrow()
        paparazzi.snapshot(name = "limit_resting_card") {
            KalshiOddsTheme(darkTheme = true) {
                WorkingLimitsCard(
                    live = listOf(PlacedOrder(real, "c", "ord-1", 0.0, 9.0, null, now)),
                    paper = listOf(PaperLimitOrder("p1", paper, 3_100.0, true, now, now + 300_000L, needed = 1_240.0)),
                    nowMs = now + 20_000L,
                    onCancelLive = {},
                    onCancelPaper = {}
                )
            }
        }
    }
}
