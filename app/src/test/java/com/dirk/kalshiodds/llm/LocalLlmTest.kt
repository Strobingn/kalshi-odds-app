package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.signal.scalper.ClosedScalp
import com.dirk.kalshiodds.signal.scalper.DailyReview
import com.dirk.kalshiodds.signal.scalper.ExitKind
import com.dirk.kalshiodds.signal.scalper.ScalpOrder
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalpTradeLog
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The on-phone LLM's manager, the writer that picks local or cloud, and the daily review's runner. No native code. */
class LocalLlmTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MemPrefs(override var selected: String? = null, override var preferLocal: Boolean = true) : LocalLlm.Prefs

    /** A pretend llama.cpp: answers [pieces] as UTF-8, counts loads and frees. */
    private class FakeEngine(var pieces: List<String> = listOf("Hel", "lo"), var available: Boolean = true) : LocalLlm.Engine {
        var loads = 0
        var frees = 0
        var lastUser = ""
        override fun available() = available
        override fun unavailableReason() = "no such library"
        override fun load(path: String, nCtx: Int, nThreads: Int): Long { loads++; return 42L }
        override fun describe(handle: Long) = "fake 1B"
        override fun lastError() = "boom"
        override fun generate(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, sink: LlamaNative.ByteSink): Int {
            lastUser = user
            for (p in pieces) if (!sink.onBytes(p.toByteArray(Charsets.UTF_8))) break
            return pieces.size
        }
        override fun free(handle: Long) { frees++ }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun gguf(name: String): File = File(tmp.root, name).apply { writeBytes("GGUF".toByteArray() + ByteArray(64)) }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test
    fun utf8ThatArrivesInPiecesIsNeverSplitMidCharacter() {
        val text = "Profit é 🚀 done"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (cut in 1 until bytes.size) {
            val acc = Utf8Accumulator()
            acc.add(bytes.copyOfRange(0, cut))
            assertFalse("no replacement character mid-stream", acc.text().contains('�'))
            acc.add(bytes.copyOfRange(cut, bytes.size))
            assertEquals(text, acc.finish())
        }
        val one = Utf8Accumulator()
        for (b in bytes) one.add(byteArrayOf(b))
        assertEquals(text, one.finish())
    }

    @Test
    fun itAnswersOnlyWithAModelAndLoadsItOnceUntilIdle() = runBlocking {
        val dir = tmp.newFolder("llm")
        val engine = FakeEngine()
        val llm = LocalLlm(dir, engine, scope, MemPrefs(), idleUnloadMs = 200L)
        val none = llm.generate("sys", "hi")
        assertTrue(none.exceptionOrNull()!!.message!!.contains("no on-phone model"))

        llm.import("my model.gguf", 68L) { gguf("src.gguf").inputStream() }
        waitFor("the import") { llm.state.value.installed != null }
        assertEquals("my_model.gguf", llm.state.value.installed)

        val seen = ArrayList<String>()
        val a = llm.generate("sys", "hi") { seen += it }.getOrThrow()
        assertEquals("Hello", a.text)
        assertEquals(listOf("Hel", "Hello"), seen)
        assertEquals(2, a.tokens)
        assertTrue(llm.state.value.lastSpeed > 0)
        llm.generate("sys", "again").getOrThrow()
        assertEquals("loaded once for both", 1, engine.loads)
        assertTrue(llm.state.value.loaded)
        waitFor("the idle unload") { engine.frees == 1 }
        assertFalse(llm.state.value.loaded)
        llm.generate("sys", "third").getOrThrow()
        assertEquals("reloaded after the unload", 2, engine.loads)
    }

    @Test
    fun aMissingLibraryOrANonGgufFileIsReportedNotCrashed() = runBlocking {
        val dir = tmp.newFolder("llm")
        val engine = FakeEngine(available = false)
        val llm = LocalLlm(dir, engine, scope, MemPrefs())
        llm.import("notes.txt", 10L) { "hello world".byteInputStream() }
        waitFor("the refusal") { llm.state.value.message.isNotBlank() }
        assertTrue(llm.state.value.message.contains("not a GGUF"))
        assertTrue("nothing kept", dir.listFiles()!!.isEmpty())
        llm.import("ok.gguf", 68L) { gguf("x.gguf").inputStream() }
        waitFor("the import") { llm.isInstalled }
        val r = llm.generate("s", "u")
        assertTrue(r.exceptionOrNull()!!.message!!.contains("no such library"))
    }

    private fun serving(body: ByteArray, seen: MutableList<String?>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val range = chain.request().header("Range")
            seen += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (range != null) 206 else 200).message("OK")
                .body(body.copyOfRange(from, body.size).toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }).build()

    @Test
    fun aDownloadResumesAndIsCheckedBeforeItIsUsed() {
        val dir = tmp.newFolder("llm")
        val body = "GGUF".toByteArray() + ByteArray(5000) { (it % 251).toByte() }
        val sha = File(tmp.root, "b").apply { writeBytes(body) }.let { LocalLlm.sha256(it) }
        val m = LocalModel("t", "Test model (x)", "test.gguf", "https://example.invalid/test.gguf", body.size.toLong(), sha)
        File(dir, "test.gguf.part").writeBytes(body.copyOfRange(0, 1200))
        val ranges = ArrayList<String?>()
        val prefs = MemPrefs()
        val llm = LocalLlm(dir, FakeEngine(), scope, prefs, http = serving(body, ranges))
        llm.download(m)
        waitFor("the download") { llm.state.value.message.isNotBlank() }
        assertEquals("Test model is installed", llm.state.value.message)
        assertEquals(listOf<String?>("bytes=1200-"), ranges)
        assertTrue(File(dir, "test.gguf").readBytes().contentEquals(body))
        assertEquals("test.gguf", prefs.selected)

        // A file that does not match its hash is thrown away.
        val dir2 = tmp.newFolder("llm2")
        val bad = LocalLlm(dir2, FakeEngine(), scope, MemPrefs(), http = serving(body, ArrayList()))
        bad.download(m.copy(sha256 = "00"))
        waitFor("the check") { bad.state.value.message.isNotBlank() }
        assertTrue(bad.state.value.message.startsWith("Download failed"))
        assertFalse(File(dir2, "test.gguf").exists())
        assertFalse(File(dir2, "test.gguf.part").exists())
    }

    @Test
    fun theWriterUsesThePhoneFirstAndTheCloudAsFallback() = runBlocking {
        val dir = tmp.newFolder("llm")
        val engine = FakeEngine(pieces = listOf("on ", "phone"))
        val prefs = MemPrefs()
        val llm = LocalLlm(dir, engine, scope, prefs)
        var cloudCalls = 0
        var key = true
        val w = AiWriter(llm, { cloudCalls++; "Groq m" to "from the cloud" }, { "" }, { key })

        assertEquals("no model yet: the cloud", "from the cloud", w.write("s", "u", 50).getOrThrow().text)
        llm.import("m.gguf", 68L) { gguf("m1.gguf").inputStream() }
        waitFor("the import") { llm.isInstalled }
        val local = w.write("s", "u", 50).getOrThrow()
        assertEquals("on phone", local.text)
        assertTrue(local.onPhone && local.source.startsWith("On-phone"))
        assertEquals(1, cloudCalls)

        engine.pieces = emptyList()
        val fallback = w.write("s", "u", 50).getOrThrow()
        assertEquals("from the cloud", fallback.text)
        assertTrue(fallback.source.contains("on-phone model"))

        llm.setPreferLocal(false)
        engine.pieces = listOf("x")
        assertFalse(w.write("s", "u", 50).getOrThrow().onPhone)

        key = false
        assertTrue("no key: the phone after all", w.write("s", "u", 50).getOrThrow().onPhone)
        val nothing = AiWriter(LocalLlm(tmp.newFolder("empty"), engine, scope, MemPrefs()), { null }, { "" }, { false })
        assertTrue(nothing.write("s", "u", 5).exceptionOrNull()!!.message!!.contains("install an on-phone model"))
    }

    @Test
    fun theDailyReviewRunsOnceForYesterdayWhenItHadTrades() {
        val direct = Executor { it.run() }
        val log = ScalpTradeLog(tmp.newFolder("scalper"), run = 1L, io = direct)
        val now = java.time.ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val store = DailyReview.Store(File(tmp.root, "reviews.json"))
        val engine = FakeEngine(pieces = listOf("A good ", "day."))
        val llm = LocalLlm(tmp.newFolder("llm"), engine, scope, MemPrefs())
        llm.import("m.gguf", 68L) { gguf("m2.gguf").inputStream() }
        waitFor("the import") { llm.isInstalled }
        val writer = AiWriter(llm, { null }, { "" }, { false })
        var auto = true
        val runner = ReviewRunner(scope, writer, log, store, { auto }, nowMs = { now })

        assertFalse("no trades yesterday", runner.maybeAuto())
        val yesterdayMs = now - 86_400_000L
        val o = ScalpOrder(1L, ScalpStrategy.LATE_EDGE, "KXBTC15M-26OCT221215-15", "YES", 0.9, 5, queueAhead = 0.0, queueKnown = false,
            postedAtMs = yesterdayMs, predictionCents = 0.0, expectedCents = 0.0)
        val c = ClosedScalp(o, 1.0, ExitKind.SETTLED, 0.0, 0.5, yesterdayMs, yesterdayMs + 1000)
        log.add(listOf(ScalpTrade.of(c, 1L)), emptyList())
        assertTrue(log.flushNow())

        auto = false
        assertFalse("switched off", runner.maybeAuto())
        auto = true
        assertTrue(runner.maybeAuto())
        waitFor("the review") { runner.reviews.value.isNotEmpty() }
        val r = runner.reviews.value.single()
        assertEquals("2026-10-22", r.day)
        assertEquals("A good day.", r.text)
        assertFalse(r.partial)
        assertEquals(1, r.stats.trades)
        assertTrue("the prompt carries the day's numbers", engine.lastUser.contains("1 trades, 100% won, net +$0.50"))
        assertFalse("done for that day", runner.maybeAuto())
    }
}
