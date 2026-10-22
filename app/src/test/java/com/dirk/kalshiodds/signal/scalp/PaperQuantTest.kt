package com.dirk.kalshiodds.signal.scalp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaperQuantTest {
    private val x = List(PaperQuant.N) { 0.0 }
    private fun book(at: Long, bid: Double = .39, spot: PaperQuantSpot? = null) = PaperDepth(
        "KXBTC15M-QUANT", at, at, 900_000, listOf(bid to 1000.0), listOf((.99 - bid) to 500.0), spot = spot)
    private fun row(i: Int, net: Double = if (i % 2 == 0) .08 else -.06) = PaperQuantObservation(
        "KXBTC15M-$i", "cheap", "YES", i * 900_000L + 100_000, (i + 1) * 900_000L,
        i * 900_000L + 200_000, x.toMutableList().apply { this[2] = if (net > 0) 1.0 else -1.0 }, net)
    @Test fun featuresAreAsOfEntryAndSpotHasExplicitMissingMasks() {
        val history = (1000L..31000L step 1000).map { book(it, .39) }
        val model = PaperQuantModel()
        val entry = requireNotNull(PaperQuant.entry(book(31000), history, "YES", "cheap", model))
        val withFuture = PaperQuant.entry(book(31000), history + book(32000, .90), "YES", "cheap", model)
        assertEquals(entry, withFuture)
        assertEquals(20, entry.x.size)
        assertEquals(List(8) { 0.0 }, entry.x.takeLast(8))
        val proxy = PaperQuantSpot(30000, 101.0, .001, .002, .003, 100.0, "test proxy")
        val present = requireNotNull(PaperQuant.entry(book(31000, spot = proxy), history, "YES", "cheap", model))
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), listOf(13, 15, 17, 19).map { present.x[it] })
        for (invalid in listOf(proxy.copy(fetchedAtMs = 32000), proxy.copy(fetchedAtMs = -100000))) {
            assertEquals(entry, PaperQuant.entry(book(31000, spot = invalid), history, "YES", "cheap", model))
        }
        assertNull(PaperQuant.entry(book(31000), history.takeLast(10), "YES", "cheap", model))
    }
    @Test fun onlyCanonicalUnfilteredControlClosesBecomeDeduplicatedExamples() {
        val e = PaperQuantEntry(x)
        val result = PaperScalpResult("one", "KXBTC15M-ONE", "cheap", "YES", 10, .40,
            .80, .40, .10, .04, "target", 200000, 3, e, 100000, 900000)
        val accounts = listOf(
            PaperScalpAccount("cheap-15", results = listOf(result, result.copy(id = "repeat", atMs = 300000))),
            PaperScalpAccount("cheap-100", results = listOf(result.copy(ticker = "KXBTC15M-COPY"))),
            PaperScalpAccount("adaptive-15", results = listOf(result.copy(ticker = "KXBTC15M-ADAPTIVE"))))
        val learned = PaperQuant.observe(PaperQuantState(), accounts)
        assertEquals(1, learned.observations.size)
        assertEquals(.08, learned.observations.single().netPerContract, 1e-9)
        assertEquals(learned, PaperQuant.observe(learned, accounts))
        assertTrue(PaperQuant.observe(PaperQuantState(), listOf(PaperScalpAccount("cheap-15",
            results = listOf(result.copy(quantEntry = null), result.copy(quantEntry = e.copy(schema = 99)))))).observations.isEmpty())
    }
    @Test fun labelsWaitUntilBothOutcomeAndWindowHavePassedTheEmbargo() {
        val r = row(0).copy(outcomeMs = 1_000_000)
        assertTrue(PaperQuant.eligible(listOf(r), 1_090_000).isEmpty())
        assertEquals(listOf(r), PaperQuant.eligible(listOf(r), 1_090_001))
        assertTrue(PaperQuant.eligible(listOf(r, r.copy(setup = "dip", outcomeMs = 1_100_000)), 1_090_001).isEmpty())
        assertTrue(PaperQuant.eligible(listOf(r.copy(x = listOf(Double.NaN))), 9_000_000).isEmpty())
    }
    @Test fun forwardFoldsNeverShareWindowsOrOverlappingOutcomeLabels() {
        val rows = (0..69).flatMap { listOf(row(it), row(it).copy(setup = "dip", openedMs = it * 900_000L + 300_000)) }
        val folds = PaperQuant.folds(rows)
        assertEquals(3, folds.size)
        for ((train, test) in folds) {
            val cutoff = test.flatten().minOf { it.openedMs }
            assertEquals(10, test.size)
            assertTrue(train.map { it.ticker }.intersect(test.flatten().map { it.ticker }.toSet()).isEmpty())
            assertTrue(train.all { maxOf(it.closeMs, it.outcomeMs) + PaperQuant.EMBARGO_MS < cutoff })
        }
        // A very late outcome is purged even when its contract was earlier.
        val delayed = rows.map { if (it.ticker == "KXBTC15M-0") it.copy(outcomeMs = 99_000_000) else it }
        assertTrue(PaperQuant.folds(delayed).all { f -> f.train.none { it.ticker == "KXBTC15M-0" } })
    }
    @Test fun modelLearnsNetOutcomeAndCannotValidateByJustSelectingEveryTrade() {
        val rows = (0..69).map { row(it) }
        val fit = PaperQuant.fit(rows.take(40), 50_000_000)
        assertTrue(requireNotNull(PaperQuant.probability(fit, row(2).x)) > requireNotNull(PaperQuant.probability(fit, row(3).x)))
        val result = PaperQuant.validate(rows, PaperQuant.fit(rows, 70_000_000))
        assertEquals(30, result.testWindows)
        assertTrue(result.filtered > result.baseline) // synthetic learnability test, not observed performance
        for (net in listOf(.08, -.08)) {
            val same = (0..69).map { row(it, net) }
            assertFalse(PaperQuant.validate(same, PaperQuant.fit(same, 70_000_000)).ready)
        }
        assertFalse(PaperQuantModel(weights = List(21) { Double.NaN }, testWindows = 30,
            selectedWindows = 30, incrementalLower = 1.0, filteredLower = 1.0).ready)
    }
    @Test fun windowWeightsIgnoreDuplicateSizingCopiesAndRefreshIsRateLimited() {
        val rows = (0..39).map { row(it) }
        PaperQuant.fit(rows, 1).weights.zip(PaperQuant.fit(rows.flatMap { listOf(it, it) }, 1).weights).forEach { (a, b) -> assertEquals(a, b, 1e-12) }
        val first = PaperQuant.update(PaperQuantState(observations = rows), emptyList(), 50_000_000)
        assertEquals(first, PaperQuant.update(first, emptyList(), 50_000_001))
        assertEquals(first, Json.decodeFromString<PaperQuantState>(Json.encodeToString(first)))
    }
    @Test fun activeFilterBlocksNewEntriesButControlBooksAndProtectiveExitsContinue() {
        // Synthetic ready model injected only to exercise routing, not to claim validation on observed data.
        val reject = PaperQuantModel(weights = listOf(-20.0) + x, win = .08, loss = .08,
            testWindows = 30, selectedWindows = 10, filteredLower = .01, incrementalLower = .01)
        val seed = (1000L..31000L step 1000).map { book(it, .62) } + book(32000, .35)
        val initial = PaperScalpState(quant = PaperQuantState(true, model = reject, lastFitMs = 32000))
        val filtered = AdaptivePaperScalp(initial, historySeed = seed)
        filtered.onBook(book(33000)); filtered.onBook(book(33300))
        assertTrue(filtered.snapshot().accounts.first { it.id == "cheap-15" }.positions.isNotEmpty())
        assertTrue(filtered.snapshot().accounts.first { it.id == "cheap-100" }.positions.isEmpty())
        val baseline = AdaptivePaperScalp(initial.copy(quant = initial.quant.copy(filterRequested = false)), historySeed = seed)
        baseline.onBook(book(33000)); baseline.onBook(book(33300))
        assertTrue(baseline.snapshot().accounts.first { it.id == "cheap-100" }.positions.isNotEmpty())
        baseline.setQuantFilter(true)
        baseline.onBook(book(33600, .34)); baseline.onBook(book(33900, .33))
        assertEquals(1, baseline.snapshot().accounts.first { it.id == "cheap-100" }.closed)
    }
    @Test fun shadowAndFilterControlsSurviveRestartAndReplayWithoutChangingStops() {
        val frames = mutableListOf<PaperReplayFrame>()
        val lab = AdaptivePaperScalp(eventSink = { frames += it })
        (1000L..31000L step 1000).forEach { lab.onBook(book(it, .62)) }
        lab.onBook(book(32000, .35)); lab.onBook(book(33000)); lab.onBook(book(33300))
        val before = lab.snapshot().accounts.flatMap { it.positions }.map { it.stop }
        assertTrue(before.isNotEmpty())
        lab.setQuantFilter(true)
        assertEquals(before, lab.snapshot().accounts.flatMap { it.positions }.map { it.stop })
        assertTrue(AdaptivePaperScalp(lab.snapshot()).snapshot().quant.filterRequested)
        lab.onBook(book(33600, .34)); lab.onBook(book(33900, .33))
        val checkpoint = PaperReplayEvent("checkpoint", 0, checkpoint = frames.first().before)
        val report = PaperScalpReplay.run((listOf(checkpoint) + frames.map { it.event }).asSequence())
        assertEquals(frames.size, report.checkedEvents)
    }
}
