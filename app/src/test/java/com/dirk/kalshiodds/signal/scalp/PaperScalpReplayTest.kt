package com.dirk.kalshiodds.signal.scalp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PaperScalpReplayTest {
    private fun q(at: Long, bid: Double = .39) = PaperDepth("KXBTC15M-REPLAY", at, at, 900000,
        listOf(bid to 1000.0), listOf((.99 - bid) to 1000.0))
    private fun frames(): List<PaperReplayFrame> {
        val frames = mutableListOf<PaperReplayFrame>()
        val lab = AdaptivePaperScalp(eventSink = { frames += it })
        (1000L..31000L step 1000).forEach { lab.onBook(q(it, .62)) }
        lab.onBook(q(32000, .35)); lab.onBook(q(33000)); lab.onBook(q(33300))
        lab.setPaused(true)
        lab.onBook(q(33600, .35)); lab.onBook(q(33900, .34))
        lab.settle("KXBTC15M-REPLAY", true, 900000)
        return frames
    }
    private fun start(f: PaperReplayFrame) = PaperReplayEvent("checkpoint", f.event.atMs, checkpoint = f.before, history = f.history)
    @Test fun fullRecordingMatchesEveryVirtualLedgerAfterJsonRoundTrip() {
        val frames = frames()
        val json = Json { encodeDefaults = true }
        val events = listOf(start(frames.first())) + frames.map { it.event }
        val decoded = events.map { json.decodeFromString<PaperReplayEvent>(json.encodeToString(it)) }
        val report = PaperScalpReplay.run(decoded.asSequence())
        assertEquals(frames.size, report.checkedEvents)
        assertTrue(report.accounts.getValue("cheap-100").closed > 0)
    }
    @Test fun rotatedSegmentCanRestartAtPendingDecisionWithExactHistoryAndDepth() {
        val frames = frames()
        val startAt = frames.indexOfFirst { it.before.accounts.any { a -> a.pending.isNotEmpty() } }
        assertTrue(startAt >= 0)
        val selected = frames.drop(startAt)
        val report = PaperScalpReplay.run((listOf(start(selected.first())) + selected.map { it.event }).asSequence())
        assertEquals(selected.size, report.checkedEvents)
    }
    @Test fun tamperedLedgerFailsInsteadOfReportingFabricatedPerformance() {
        val frames = frames(); val first = frames.first()
        val bad = first.event.copy(totals = first.event.totals.mapValues { (_, v) -> v.copy(cash = v.cash + 1) })
        assertThrows(IllegalStateException::class.java) { PaperScalpReplay.run(sequenceOf(start(first), bad)) }
    }
    @Test fun replayRequiresCheckpointAndMatchingPolicy() {
        assertThrows(IllegalArgumentException::class.java) { PaperScalpReplay.run(sequenceOf(PaperReplayEvent("book", 1, book = q(1)))) }
        assertThrows(IllegalArgumentException::class.java) { PaperScalpReplay.run(sequenceOf(PaperReplayEvent("checkpoint", 1, policy = 99))) }
    }
    @Test fun externalRecordedSegmentsCanBeCheckedWithoutInventingData() {
        val path = System.getenv("PAPER_REPLAY_PATH")
        org.junit.Assume.assumeNotNull(path)
        val json = Json { ignoreUnknownKeys = true }
        java.io.File(requireNotNull(path)).useLines { lines ->
            val report = PaperScalpReplay.run(lines.filter { it.isNotBlank() }.map { json.decodeFromString<PaperReplayEvent>(it) })
            assertTrue(report.checkedEvents > 0)
            println("Observed replay checked ${report.checkedEvents} events: ${report.accounts}")
        }
    }
}
