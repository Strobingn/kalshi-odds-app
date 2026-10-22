package com.dirk.kalshiodds.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round

/**
 * The BTC 9:45 PM book: UP bids at 3.4–3.8¢, DOWN bids at 95.7–96.1¢.
 * Whole-cent books (74¢ / 25¢) stay on 1¢ rows.
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

    @Test fun subCentBookMapsBothSidesOntoOneContiguousYesAxis() {
        val fromDollars = ChartShapes.depthRungs(upDollars, downDollars)
        val fromCents = ChartShapes.depthRungs(upCents, downCents)
        assertEquals(decis(fromDollars), decis(fromCents))
        assertEquals(fromDollars.map { it.upSize }, fromCents.map { it.upSize })
        assertEquals(fromDollars.map { it.downSize }, fromCents.map { it.downSize })

        val rungs = fromDollars
        assertEquals(
            listOf("4.3¢", "4.2¢", "4.1¢", "4¢", "3.9¢", "3.8¢", "3.7¢", "3.6¢", "3.5¢", "3.4¢"),
            rungs.map { it.label() }
        )
        assertTrue(rungs.none { abs(it.cents) < 0.05 })
        assertTrue(rungs.none { it.label() == "0¢" || it.label() == "89¢" })
        for (i in 0 until rungs.lastIndex) {
            assertEquals(0.1, rungs[i].cents - rungs[i + 1].cents, 1e-9)
        }

        val up38 = rungs.first { round(it.cents * 10.0).toInt() == 38 }
        assertEquals(10577.0, up38.upSize, 0.0)
        assertEquals(0.0, up38.downSize, 0.0)
        val up34 = rungs.first { round(it.cents * 10.0).toInt() == 34 }
        assertEquals(2.0, up34.upSize, 0.0)
        val down961 = rungs.first { round(it.cents * 10.0).toInt() == 39 }
        assertEquals(1925.0, down961.downSize, 0.0)
        assertEquals(0.0, down961.upSize, 0.0)
        assertEquals("3.9¢ · DOWN 1925 (96.1¢ bid)", ChartShapes.depthReadout(down961))
        assertEquals("3.8¢ · UP 10577", ChartShapes.depthReadout(up38))

        assertEquals(1.0, ChartShapes.depthBarFraction(10577.0, rungs), 1e-12)
        val downFrac = ChartShapes.depthBarFraction(1925.0, rungs)
        assertEquals(1925.0 / 10577.0, downFrac, 1e-12)
        assertTrue(downFrac > 0.15)
        assertTrue(ChartShapes.depthBarFraction(60.0, rungs) > 0.0)
        assertTrue(ChartShapes.depthBarFraction(60.0, rungs) < downFrac)
    }

    @Test fun aSizeWallAwayFromTheTouchDoesNotSwallowTheLadder() {
        val yes = upDollars + listOf(0.001 to 9_999_999.0)
        val no = downDollars + listOf(0.50 to 9_999_999.0)
        val rungs = ChartShapes.depthRungs(yes, no)
        assertEquals(
            listOf("4.3¢", "4.2¢", "4.1¢", "4¢", "3.9¢", "3.8¢", "3.7¢", "3.6¢", "3.5¢", "3.4¢"),
            rungs.map { it.label() }
        )
        assertEquals(10577.0, ChartShapes.depthMaxSize(rungs), 0.0)
        assertTrue(rungs.none { it.upSize > 20_000.0 || it.downSize > 20_000.0 })
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
        assertEquals(listOf(76.0, 75.0, 74.0, 73.0), fromDollars.map { it.cents })
        assertEquals(fromDollars.map { it.cents }, fromCents.map { it.cents })
        assertEquals(listOf("76¢", "75¢", "74¢", "73¢"), fromDollars.map { it.label() })
        assertEquals(100.0, fromDollars.first { it.cents == 74.0 }.upSize, 0.0)
        assertEquals(0.0, fromDollars.first { it.cents == 74.0 }.downSize, 0.0)
        assertEquals(80.0, fromDollars.first { it.cents == 75.0 }.downSize, 0.0)
        assertEquals(20.0, fromDollars.first { it.cents == 76.0 }.downSize, 0.0)
        assertEquals(40.0, fromDollars.first { it.cents == 73.0 }.upSize, 0.0)
        assertEquals("75¢ · DOWN 80 (25¢ bid)", ChartShapes.depthReadout(fromDollars.first { it.cents == 75.0 }))
        for (i in 0 until fromDollars.lastIndex) {
            assertEquals(1.0, fromDollars[i].cents - fromDollars[i + 1].cents, 0.0)
        }
    }

    @Test fun deepWholeCentBookStaysAroundTheTouch() {
        val yes = (1..74).map { it / 100.0 to 1.0 }
        val no = (1..25).map { it / 100.0 to 1.0 }
        val rungs = ChartShapes.depthRungs(yes, no)
        assertEquals(16, rungs.size)
        assertEquals(82.0, rungs.first().cents, 0.0)
        assertEquals(67.0, rungs.last().cents, 0.0)
        assertEquals(1.0, rungs.first { it.cents == 74.0 }.upSize, 0.0)
        assertEquals(1.0, rungs.first { it.cents == 75.0 }.downSize, 0.0)
        assertTrue(rungs.none { it.cents < 67.0 || it.cents > 82.0 })
        for (i in 0 until rungs.lastIndex) {
            assertEquals(1.0, rungs[i].cents - rungs[i + 1].cents, 0.0)
        }
    }

    @Test fun subCentGapInsideTheTouchIsFilled() {
        val rungs = ChartShapes.depthRungs(
            yes = listOf(0.604 to 1.0, 0.60 to 12.0),
            no = emptyList()
        )
        assertEquals(listOf("60.4¢", "60.3¢", "60.2¢", "60.1¢", "60¢"), rungs.map { it.label() })
        assertEquals(1.0, rungs.first().upSize, 0.0)
        assertEquals(12.0, rungs.last().upSize, 0.0)
        assertEquals(0.0, rungs[1].upSize, 0.0)
    }

    @Test fun dragReadsTheTopRowFirst() {
        assertEquals(0, ChartShapes.depthRowAt(0f, 280f, 10))
        assertEquals(5, ChartShapes.depthRowAt(140f, 280f, 10))
        assertEquals(9, ChartShapes.depthRowAt(280f, 280f, 10))
        assertEquals(0, ChartShapes.depthRowAt(-20f, 280f, 10))
        assertEquals(0, ChartShapes.depthRowAt(10f, 0f, 10))
    }

    @Test fun cumulativeDepthKeepsSubCentTicksAndComplements() {
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

        val downAsk = series.first { it.name == "DOWN ask" }
        assertEquals(listOf(96.2, 96.3, 96.4, 96.5, 96.6), downAsk.cents)
        assertEquals(10577.0, downAsk.size.first(), 0.0)
        assertEquals(22132.0, downAsk.size.last(), 0.0)
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
        val rungs = ChartShapes.depthRungs(listOf(0.004 to 50.0), listOf(0.995 to 10.0))
        assertEquals(listOf("0.5¢", "0.4¢"), rungs.map { it.label() })
        assertEquals(50.0, rungs.last().upSize, 0.0)
        assertEquals(10.0, rungs.first().downSize, 0.0)
        assertTrue(rungs.none { it.label() == "0¢" || it.label() == "1¢" })
    }

    private fun decis(rungs: List<ChartShapes.DepthRung>) = rungs.map { round(it.cents * 10.0).toInt() }
}
