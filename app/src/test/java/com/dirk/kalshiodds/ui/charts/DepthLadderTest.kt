package com.dirk.kalshiodds.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

/**
 * The BTC 9:45 PM book: UP bids at 3.4–3.8¢, DOWN bids at 95.7–96.1¢.
 * Each side stays on the price Kalshi quotes. Whole-cent books stay on 1¢ rows.
 */
class DepthLadderTest {
    private val upDollars = listOf(
        0.038 to 10577.0,
        0.037 to 9853.0,
        0.036 to 971.0,
        0.035 to 729.0,
        0.034 to 2.0
    )
    private val downDollars = listOf(
        0.961 to 1925.0,
        0.960 to 1739.0,
        0.959 to 15.0,
        0.958 to 670.0,
        0.957 to 60.0
    )
    private val upCents = listOf(
        3.8 to 10577.0,
        3.7 to 9853.0,
        3.6 to 971.0,
        3.5 to 729.0,
        3.4 to 2.0
    )
    private val downCents = listOf(
        96.1 to 1925.0,
        96.0 to 1739.0,
        95.9 to 15.0,
        95.8 to 670.0,
        95.7 to 60.0
    )

    @Test fun dollarsAndCentsAndSubCentTicksDoNotFloorToZero() {
        assertEquals(38, ChartShapes.levelDeci(0.038))
        assertEquals(38, ChartShapes.levelDeci(3.8))
        assertEquals(961, ChartShapes.levelDeci(0.961))
        assertEquals(961, ChartShapes.levelDeci(96.1))
        assertEquals(740, ChartShapes.levelDeci(0.74))
        assertEquals(740, ChartShapes.levelDeci(74.0))
        assertEquals(250, ChartShapes.levelDeci(0.25))
        assertEquals(250, ChartShapes.levelDeci(25.0))
        // 38¢ is a cent price. 380 is the deci-cent integer for that same 38.0¢.
        assertEquals(380, ChartShapes.levelDeci(38.0))
        assertEquals(380, ChartShapes.levelDeci(0.38))
        assertEquals(380, ChartShapes.levelDeci(380.0))
        assertEquals(1, ChartShapes.levelDeci(0.001))
        assertEquals(4, ChartShapes.levelDeci(0.004))
        assertEquals("0.1¢", ChartShapes.centLabel(0.1))
        assertEquals("0.4¢", ChartShapes.centLabel(0.4))
        assertEquals("3.8¢", ChartShapes.centLabel(3.8))
        assertEquals("74¢", ChartShapes.centLabel(74.0))
        assertEquals("96.1¢", ChartShapes.centLabel(96.1))
        assertNull(ChartShapes.levelDeci(0.0))
        assertNull(ChartShapes.levelDeci(1.0))
        assertNull(ChartShapes.levelDeci(10577.0))
        assertNull(ChartShapes.levelDeci(Double.NaN))
    }

    @Test fun eachSideKeepsItsOwnQuotedPriceAndTenthCentRows() {
        val fromDollars = ChartShapes.depthRungs(upDollars, downDollars)
        val fromCents = ChartShapes.depthRungs(upCents, downCents)
        assertEquals(decis(fromDollars.up), decis(fromCents.up))
        assertEquals(decis(fromDollars.down), decis(fromCents.down))
        assertEquals(fromDollars.up.map { it.size }, fromCents.up.map { it.size })
        assertEquals(fromDollars.down.map { it.size }, fromCents.down.map { it.size })

        val ladder = fromDollars
        assertEquals(listOf("3.8¢", "3.7¢", "3.6¢", "3.5¢", "3.4¢"), ladder.up.map { it.label() })
        assertEquals(listOf("96.1¢", "96¢", "95.9¢", "95.8¢", "95.7¢"), ladder.down.map { it.label() })
        assertTrue(ladder.up.none { it.label() == "4¢" || it.label() == "0¢" })
        assertTrue(ladder.down.none { it.label() == "3.9¢" || it.label() == "4¢" })
        for (i in 0 until ladder.up.lastIndex) {
            assertEquals(0.1, ladder.up[i].cents - ladder.up[i + 1].cents, 1e-9)
        }
        for (i in 0 until ladder.down.lastIndex) {
            assertEquals(0.1, ladder.down[i].cents - ladder.down[i + 1].cents, 1e-9)
        }

        assertEquals(10577.0, ladder.up.first { round(it.cents * 10.0).toInt() == 38 }.size, 0.0)
        assertEquals(2.0, ladder.up.first { round(it.cents * 10.0).toInt() == 34 }.size, 0.0)
        val down961 = ladder.down.first { round(it.cents * 10.0).toInt() == 961 }
        assertEquals(1925.0, down961.size, 0.0)
        assertEquals("DOWN 96.1¢ · 1,925", ChartShapes.depthReadout(false, down961))
        assertEquals("UP 3.8¢ · 10,577", ChartShapes.depthReadout(true, ladder.up.first()))
        assertFalse(ChartShapes.depthReadout(false, down961).contains("3.9"))
        assertFalse(ChartShapes.DEPTH_LADDER_CAPTION.contains("100¢ minus"))
        assertFalse(ChartShapes.DEPTH_LADDER_CAPTION.contains("YES price"))

        assertEquals(1.0, ChartShapes.depthBarFraction(10577.0, ladder), 1e-12)
        val downFrac = ChartShapes.depthBarFraction(1925.0, ladder)
        assertEquals(1925.0 / 10577.0, downFrac, 1e-12)
        assertTrue(downFrac > 0.15)
        val tiny = ChartShapes.depthBarFraction(60.0, ladder)
        assertTrue(tiny > 0.0)
        assertTrue(tiny < downFrac)
    }

    @Test fun downBidsAreNeverDrawnAtOneHundredMinusThePrice() {
        val cheap = ChartShapes.depthRungs(emptyList(), listOf(0.029 to 2611.0))
        assertEquals(listOf("2.9¢"), cheap.down.map { it.label() })
        assertTrue(cheap.up.isEmpty())
        assertEquals("DOWN 2.9¢ · 2,611", ChartShapes.depthReadout(false, cheap.down.single()))
        assertTrue(cheap.down.none { it.label() == "97.1¢" })

        val rich = ChartShapes.depthRungs(emptyList(), listOf(0.961 to 1925.0))
        assertEquals(listOf("96.1¢"), rich.down.map { it.label() })
        assertTrue(rich.down.none { it.label() == "3.9¢" })

        val screenshot = ChartShapes.depthRungs(
            yes = listOf(0.966 to 326.0, 0.965 to 12.0),
            no = listOf(0.029 to 2611.0, 0.022 to 800.0)
        )
        assertEquals(listOf("96.6¢", "96.5¢"), screenshot.up.map { it.label() })
        assertEquals(
            listOf("2.9¢", "2.8¢", "2.7¢", "2.6¢", "2.5¢", "2.4¢", "2.3¢", "2.2¢"),
            screenshot.down.map { it.label() }
        )
        val labels = (screenshot.up + screenshot.down).map { it.label() }
        assertTrue(labels.none { it == "97.1¢" || it == "97.8¢" || it == "3.4¢" })
        assertEquals(2611.0, screenshot.down.first().size, 0.0)
        assertEquals(800.0, screenshot.down.last().size, 0.0)
        assertEquals(0.0, screenshot.down[1].size, 0.0)
    }

    @Test fun aSizeWallAwayFromTheTouchDoesNotSwallowTheLadder() {
        val yes = upDollars + listOf(0.001 to 9_999_999.0)
        val no = downDollars + listOf(0.50 to 9_999_999.0)
        val ladder = ChartShapes.depthRungs(yes, no)
        assertEquals(listOf("3.8¢", "3.7¢", "3.6¢", "3.5¢", "3.4¢"), ladder.up.map { it.label() })
        assertEquals(listOf("96.1¢", "96¢", "95.9¢", "95.8¢", "95.7¢"), ladder.down.map { it.label() })
        assertEquals(10577.0, ChartShapes.depthMaxSize(ladder), 0.0)
        assertTrue(ladder.up.none { it.size > 20_000.0 })
        assertTrue(ladder.down.none { it.size > 20_000.0 })
    }

    @Test fun wholeCentBookStaysOnWholeCentRows() {
        val fromDollars = ChartShapes.depthRungs(
            yes = listOf(0.74 to 100.0, 0.73 to 40.0),
            no = listOf(0.25 to 80.0, 0.24 to 20.0)
        )
        val fromCents = ChartShapes.depthRungs(
            yes = listOf(74.0 to 100.0, 73.0 to 40.0),
            no = listOf(25.0 to 80.0, 24.0 to 20.0)
        )
        assertEquals(listOf(74.0, 73.0), fromDollars.up.map { it.cents })
        assertEquals(listOf(25.0, 24.0), fromDollars.down.map { it.cents })
        assertEquals(fromDollars.up.map { it.cents }, fromCents.up.map { it.cents })
        assertEquals(fromDollars.down.map { it.cents }, fromCents.down.map { it.cents })
        assertEquals(listOf("74¢", "73¢"), fromDollars.up.map { it.label() })
        assertEquals(listOf("25¢", "24¢"), fromDollars.down.map { it.label() })
        assertEquals(100.0, fromDollars.up.first { it.cents == 74.0 }.size, 0.0)
        assertEquals(40.0, fromDollars.up.first { it.cents == 73.0 }.size, 0.0)
        assertEquals(80.0, fromDollars.down.first { it.cents == 25.0 }.size, 0.0)
        assertEquals(20.0, fromDollars.down.first { it.cents == 24.0 }.size, 0.0)
        assertEquals("DOWN 25¢ · 80", ChartShapes.depthReadout(false, fromDollars.down.first()))
        assertTrue(fromDollars.down.none { it.cents == 75.0 || it.cents == 76.0 })
        for (i in 0 until fromDollars.up.lastIndex) {
            assertEquals(1.0, fromDollars.up[i].cents - fromDollars.up[i + 1].cents, 0.0)
        }
        for (i in 0 until fromDollars.down.lastIndex) {
            assertEquals(1.0, fromDollars.down[i].cents - fromDollars.down[i + 1].cents, 0.0)
        }
    }

    @Test fun deepWholeCentBookStaysAroundTheTouch() {
        val yes = (1..74).map { it / 100.0 to 1.0 }
        val no = (1..25).map { it / 100.0 to 1.0 }
        val ladder = ChartShapes.depthRungs(yes, no)
        assertEquals((74 downTo 67).map { it.toDouble() }, ladder.up.map { it.cents })
        assertEquals((25 downTo 18).map { it.toDouble() }, ladder.down.map { it.cents })
        assertEquals(1.0, ladder.up.first { it.cents == 74.0 }.size, 0.0)
        assertEquals(1.0, ladder.down.first { it.cents == 25.0 }.size, 0.0)
        assertTrue(ladder.up.none { it.cents < 67.0 || it.cents > 74.0 })
        assertTrue(ladder.down.none { it.cents > 25.0 || it.cents < 18.0 })
        for (i in 0 until ladder.up.lastIndex) {
            assertEquals(1.0, ladder.up[i].cents - ladder.up[i + 1].cents, 0.0)
        }
    }

    @Test fun subCentGapInsideTheTouchIsFilled() {
        val ladder = ChartShapes.depthRungs(
            yes = listOf(0.604 to 1.0, 0.60 to 12.0),
            no = emptyList()
        )
        assertEquals(listOf("60.4¢", "60.3¢", "60.2¢", "60.1¢", "60¢"), ladder.up.map { it.label() })
        assertTrue(ladder.down.isEmpty())
        assertEquals(1.0, ladder.up.first().size, 0.0)
        assertEquals(12.0, ladder.up.last().size, 0.0)
        assertEquals(0.0, ladder.up[1].size, 0.0)
    }

    @Test fun tenthOnOneSideDoesNotRoundThatSide() {
        val ladder = ChartShapes.depthRungs(
            yes = listOf(0.604 to 5.0, 0.60 to 1.0),
            no = listOf(0.40 to 7.0, 0.39 to 2.0)
        )
        assertEquals(listOf("60.4¢", "60.3¢", "60.2¢", "60.1¢", "60¢"), ladder.up.map { it.label() })
        assertEquals(listOf("40¢", "39¢"), ladder.down.map { it.label() })
    }

    @Test fun dragReadsTheTopRowFirst() {
        assertEquals(0, ChartShapes.depthRowAt(0f, 280f, 10))
        assertEquals(5, ChartShapes.depthRowAt(140f, 280f, 10))
        assertEquals(9, ChartShapes.depthRowAt(280f, 280f, 10))
        assertEquals(0, ChartShapes.depthRowAt(-20f, 280f, 10))
        assertEquals(0, ChartShapes.depthRowAt(10f, 0f, 10))
    }

    @Test fun barsStayProportionalAndATinyRowStaysVisible() {
        val max = 10577.0
        val lane = 200f
        val floor = 4f
        assertEquals(0f, ChartShapes.depthBarPx(0.0, max, lane, floor), 0f)
        assertEquals(lane, ChartShapes.depthBarPx(max, max, lane, floor), 0.01f)
        val mid = ChartShapes.depthBarPx(1925.0, max, lane, floor)
        val tiny = ChartShapes.depthBarPx(60.0, max, lane, floor)
        val speck = ChartShapes.depthBarPx(2.0, max, lane, floor)
        assertEquals(lane * 1925.0 / max, mid.toDouble(), 0.05)
        assertTrue(mid > tiny)
        assertTrue(tiny >= floor)
        assertTrue(speck >= floor)
        assertTrue(speck < mid)
        assertTrue(tiny < lane)
    }

    @Test fun priceLabelsAndBarsUseSeparateLanes() {
        val wide = ChartShapes.depthColumnLayout(columnPx = 400f, labelPx = 80f, sizePx = 70f, minBarPx = 28f, gapPx = 4f)
        assertEquals(80f, wide.labelWidth, 0.01f)
        assertTrue(wide.showsSize)
        assertTrue(wide.barLeft >= wide.labelWidth)
        assertTrue(wide.sizeLeft >= wide.barLeft + wide.barWidth - 0.01f)
        assertEquals(400f, wide.sizeLeft + wide.sizeWidth, 0.05f)
        assertTrue(wide.barWidth >= 28f)

        val narrow = ChartShapes.depthColumnLayout(columnPx = 140f, labelPx = 80f, sizePx = 70f, minBarPx = 28f, gapPx = 4f)
        assertFalse(narrow.showsSize)
        assertEquals(80f, narrow.labelWidth, 0.01f)
        assertTrue(narrow.barLeft >= narrow.labelWidth)
        assertTrue(narrow.barWidth > 0f)
        assertEquals(140f, narrow.barLeft + narrow.barWidth, 0.05f)
    }

    @Test fun cumulativeBidsUseQuotedPricesAndAsksStayAsks() {
        val series = ChartGallery.cumulativeDepth(upDollars, downDollars)
        val same = ChartGallery.cumulativeDepth(upCents, downCents)
        assertEquals(listOf("UP bid", "UP ask", "DOWN bid", "DOWN ask"), series.map { it.name })
        series.zip(same).forEach { (a, b) ->
            assertEquals(a.cents.size, b.cents.size)
            a.cents.zip(b.cents).forEach { (x, y) -> assertEquals(x, y, 1e-9) }
            a.size.zip(b.size).forEach { (x, y) -> assertEquals(x, y, 1e-9) }
        }

        val upBid = series.first { it.name == "UP bid" }
        assertEquals(listOf(3.8, 3.7, 3.6, 3.5, 3.4), upBid.cents)
        assertEquals(listOf(10577.0, 20430.0, 21401.0, 22130.0, 22132.0), upBid.size)
        assertTrue(upBid.cents.none { it == 0.0 || it == 4.0 })

        val upAsk = series.first { it.name == "UP ask" }
        assertEquals(listOf(3.9, 4.0, 4.1, 4.2, 4.3), upAsk.cents)
        assertEquals(1925.0, upAsk.size.first(), 0.0)
        assertEquals(4409.0, upAsk.size.last(), 0.0)

        val downBid = series.first { it.name == "DOWN bid" }
        assertEquals(listOf(96.1, 96.0, 95.9, 95.8, 95.7), downBid.cents)
        assertEquals(1925.0, downBid.size.first(), 0.0)
        assertTrue(downBid.cents.none { abs(it - 3.9) < 0.05 })

        val downAsk = series.first { it.name == "DOWN ask" }
        assertEquals(listOf(96.2, 96.3, 96.4, 96.5, 96.6), downAsk.cents)
        assertEquals(10577.0, downAsk.size.first(), 0.0)
        assertEquals(22132.0, downAsk.size.last(), 0.0)

        val quoted = ChartGallery.cumulativeDepth(
            yes = listOf(0.966 to 326.0, 0.90 to 10.0),
            no = listOf(0.029 to 2611.0, 0.022 to 100.0)
        )
        assertEquals(listOf(96.6, 90.0), quoted.first { it.name == "UP bid" }.cents)
        assertEquals(listOf(2.9, 2.2), quoted.first { it.name == "DOWN bid" }.cents)
        val summary = ChartGallery.depthSummary(quoted)
        assertTrue(summary.contains("UP bid 96.6¢"))
        assertTrue(summary.contains("DOWN bid 2.9¢"))
        assertTrue(summary.contains("UP ask 97.1¢"))
        assertTrue(summary.contains("DOWN ask 3.4¢"))
        assertFalse(summary.contains("DOWN bid 97.1"))
        assertFalse(summary.contains("DOWN bid 97.8"))
    }

    @Test fun cumulativeScaleSeparatesSubCentClustersFromZero() {
        val cents = ChartGallery.cumulativeDepth(upDollars, downDollars).flatMap { it.cents }
        val scale = ChartGallery.depthScale(cents)
        assertTrue(scale.split)
        val at34 = ChartGallery.depthFraction(3.4, scale)
        val at38 = ChartGallery.depthFraction(3.8, scale)
        val at961 = ChartGallery.depthFraction(96.1, scale)
        assertTrue(at34 > 0f)
        assertTrue(at38 - at34 > 0.05f)
        assertTrue(at38 < 0.5f)
        assertTrue(at961 > 0.5f)
        assertTrue(at961 < 1f)

        val single = ChartGallery.depthScale(listOf(0.1))
        val mid = ChartGallery.depthFraction(0.1, single)
        assertTrue(mid > 0.2f && mid < 0.8f)

        val normal = ChartGallery.depthScale(listOf(40.0, 50.0, 60.0))
        assertTrue(!normal.split)
        val f40 = ChartGallery.depthFraction(40.0, normal)
        val f60 = ChartGallery.depthFraction(60.0, normal)
        assertTrue(f60 - f40 > 0.5f)
    }

    @Test fun pointFourCentsIsNotAZeroRow() {
        val ladder = ChartShapes.depthRungs(listOf(0.004 to 50.0), listOf(0.995 to 10.0))
        assertEquals(listOf("0.4¢"), ladder.up.map { it.label() })
        assertEquals(listOf("99.5¢"), ladder.down.map { it.label() })
        assertEquals(50.0, ladder.up.single().size, 0.0)
        assertEquals(10.0, ladder.down.single().size, 0.0)
        assertTrue(ladder.up.none { it.label() == "0¢" || it.label() == "1¢" })
        assertTrue(ladder.down.none { it.label() == "0.5¢" || it.label() == "1¢" })
    }

    private fun decis(levels: List<ChartShapes.DepthLevel>) = levels.map { round(it.cents * 10.0).toInt() }
}
