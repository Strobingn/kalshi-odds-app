package com.dirk.kalshiodds.signal.scalp

import android.app.Application
import android.net.Uri
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PaperScalpRecorderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun cleanRecordingFolder() { File(context.filesDir, "paper-replays-v3").deleteRecursively() }
    private fun q(at: Long) = PaperDepth("KXBTC15M-RECORD", at, at, 900000,
        listOf(.39 to 1000.0), listOf(.60 to 1000.0))

    @Test fun exportedZipReplaysActualRecordedEventsAndKeepsFeedsSeparate() {
        val recorder = PaperScalpRecorder(context)
        var last = PaperScalpState()
        for (source in listOf("production", "demo")) {
            val lab = AdaptivePaperScalp(PaperScalpState(source = source), eventSink = { recorder.append(source, it) })
            lab.onBook(q(1000)); lab.setPaused(true); lab.onBook(q(2000)); last = lab.snapshot()
        }
        val file = File(context.cacheDir, "paper-roundtrip.zip")
        recorder.export(Uri.fromFile(file), last)
        ZipFile(file).use { zip ->
            val segments = zip.entries().asSequence().filter { it.name.endsWith(".jsonl") }.toList()
            assertEquals(setOf("production", "demo"), segments.map { it.name.substringBefore('/') }.toSet())
            for (segment in segments) {
                zip.getInputStream(segment).bufferedReader().useLines { lines ->
                    val events = lines.map { Json.decodeFromString<PaperReplayEvent>(it) }
                    val report = PaperScalpReplay.run(events)
                    assertEquals(3, report.checkedEvents)
                }
            }
            assertNotNull(zip.getEntry("paper-state.json"))
        }
    }
    @Test fun recordingFailureIsVisibleAndRecoveryStartsFromCurrentLedger() {
        val root = File(context.filesDir, "paper-replays-v3").apply { writeText("blocked directory") }
        val recorder = PaperScalpRecorder(context)
        val lab = AdaptivePaperScalp(eventSink = { recorder.append("production", it) })
        lab.setPaused(true)
        assertTrue(lab.snapshot().paused); assertTrue(recorder.note.contains("error"))
        assertTrue(root.delete())
        lab.setPaused(false)
        assertTrue(recorder.note.contains("recording on"))
        val file = File(context.cacheDir, "recovered.zip")
        recorder.export(Uri.fromFile(file), lab.snapshot())
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".jsonl") }
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                assertEquals(1, PaperScalpReplay.run(lines.map { Json.decodeFromString<PaperReplayEvent>(it) }).checkedEvents)
            }
        }
    }
    @Test fun emptyRecordingFailsClearlyBeforeWritingOutput() {
        val recorder = PaperScalpRecorder(context)
        val file = File(context.cacheDir, "empty-export.zip")
        assertThrows(IllegalArgumentException::class.java) { recorder.export(Uri.fromFile(file), PaperScalpState()) }
    }
}
