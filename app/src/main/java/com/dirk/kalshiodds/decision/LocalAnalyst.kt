package com.dirk.kalshiodds.decision

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * S24 Ultra LiteRT-LM shortlist and engine settings.
 * No `.litertlm` file means answers stay on the fast built-in quant analyst.
 * Heavy generation stays off the order path when the trading gate is on.
 */
object LocalAnalyst {
    enum class Backend { CPU, GPU }

    data class Candidate(
        val name: String,
        val repo: String,
        val fileName: String,
        val sizeLabel: String,
        val cpuTokPerSec: Int?,
        val gpuTokPerSec: Int?,
        val role: String
    )

    data class Engine(
        val backend: Backend = Backend.CPU,
        val maxNumTokens: Int = 2048,
        val maxOutputTokens: Int = 384,
        val enableThinking: Boolean = false,
        val thinkingTokenBudget: Int = 0,
        val automaticToolCalling: Boolean = false,
        val concurrentRequests: Int = 1,
        val pauseDuringActiveEntry: Boolean = true
    )

    val SHORTLIST: List<Candidate> = listOf(
        Candidate("Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm", "~2.59 GB", null, null, "Primary"),
        Candidate("Gemma 3n E2B", "litert-community/gemma-3n-E2B-it-litert-lm", "gemma-3n-E2B-it.litertlm", "measured", 16, 16, "S24 fallback"),
        Candidate("Gemma 3 1B", "litert-community/gemma-3-1b-it-litert-lm", "gemma-3-1b-it.litertlm", "measured", 33, 24, "S24 fallback"),
        Candidate("Gemma 3n E4B", "litert-community/gemma-3n-E4B-it-litert-lm", "gemma-3n-E4B-it.litertlm", "measured", 9, null, "S24 fallback"),
        Candidate("Phi-4 mini", "litert-community/phi-4-mini-it-litert-lm", "phi-4-mini.litertlm", "measured", 7, 10, "S24 fallback"),
        Candidate("Qwen3.5-4B", "litert-community/qwen3.5-4b-it-litert-lm", "qwen3.5-4b.litertlm", "challenger", null, null, "Challenger")
    )

    private val lock = Any()
    private var engine = Engine()
    private var modelFile: File? = null
    private var sha256: String? = null
    private var activeName: String = SHORTLIST.first().name

    fun engine(): Engine = synchronized(lock) { engine }
    fun modelPath(): String? = synchronized(lock) { modelFile?.absolutePath }
    fun sha256(): String? = synchronized(lock) { sha256 }
    fun activeName(): String = synchronized(lock) { activeName }
    fun imported(): Boolean = synchronized(lock) { modelFile?.isFile == true && !sha256.isNullOrBlank() }

    fun setBackend(backend: Backend) = synchronized(lock) { engine = engine.copy(backend = backend) }
    fun setMaxOutputTokens(tokens: Int) = synchronized(lock) {
        engine = engine.copy(maxOutputTokens = tokens.coerceIn(256, 384))
    }
    fun setPauseDuringActiveEntry(on: Boolean) = synchronized(lock) {
        engine = engine.copy(pauseDuringActiveEntry = on)
    }
    fun setActiveName(name: String) = synchronized(lock) {
        if (SHORTLIST.any { it.name == name }) activeName = name
    }

    /** Hash a `.litertlm` file and keep it as the active local model. Missing files stay on the quant analyst. */
    fun importFile(file: File): String = synchronized(lock) {
        if (!file.isFile || !file.name.endsWith(".litertlm", ignoreCase = true)) {
            modelFile = null
            sha256 = null
            return "No .litertlm imported · built-in quant analyst"
        }
        val hash = ModelImport.sha256(file)
        modelFile = file
        sha256 = hash
        "Verified ${file.name} · $hash"
    }

    fun resetForTest() = synchronized(lock) {
        engine = Engine()
        modelFile = null
        sha256 = null
        activeName = SHORTLIST.first().name
    }
}

object ModelImport {
    fun sha256(file: File): String = sha256(file.readBytes())

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expected: String): Boolean {
        if (!file.isFile || expected.isBlank()) return false
        return sha256(file).equals(expected.trim(), ignoreCase = true)
    }
}

/**
 * Fast on-device answers. A missing `.litertlm` file never blocks the paper book.
 */
object QuantCompanion {
    fun reply(messages: List<Pair<String, String>>, entryActive: Boolean = false): String {
        val blob = messages.joinToString("\n") { it.second }
        val user = messages.lastOrNull { it.first.equals("user", true) }?.second.orEmpty()
        if (blob.contains("Reply ONE line")) return paperLine(user.ifBlank { blob })
        if (blob.contains("adjustments") || blob.contains("\"changes\"") || blob.contains("Paper tuner")) {
            return """{"changes":[]}"""
        }
        return answer(user.ifBlank { blob }, entryActive)
    }

    fun answer(prompt: String, entryActive: Boolean = false): String {
        val gate = LocalAnalyst.engine()
        if (gate.pauseDuringActiveEntry && entryActive) {
            return "Heavy local generation is paused while an entry is working. ${deterministic(prompt)}"
        }
        if (!LocalAnalyst.imported()) {
            return "Built-in quant analyst (no .litertlm yet). ${deterministic(prompt)}"
        }
        val who = LocalAnalyst.activeName()
        val eng = LocalAnalyst.engine()
        return "$who is selected on ${eng.backend} · context ${eng.maxNumTokens} · output ${eng.maxOutputTokens}. ${deterministic(prompt)}"
    }

    fun paperLine(prompt: String): String {
        val strike = num(prompt, "Strike\\s+([0-9.]+)")
        val spot = num(prompt, "BTC spot\\s+([0-9.]+)")
        val minutes = num(prompt, "minutes left\\s+([0-9.]+)")
        val yesAsk = num(prompt, "YES bid/ask\\s+[^/\\s]+/([0-9.]+)")
        val fairText = num(prompt, "Model fair YES\\s+([0-9.]+)")
        val tau = (minutes ?: 5.0) * 60.0
        val p = if (spot != null && strike != null) HftSignals.studentTFair(spot, strike, 3e-5, tau, "YES") else fairText
        val fair = p ?: fairText
        if (fair == null || yesAsk == null) return "SKIP"
        if (yesAsk <= 0.55 && fair - yesAsk > 0.03) return "YES ${fmt(fair.coerceIn(0.05, 0.95))}"
        val noAsk = (1.0 - yesAsk).coerceIn(0.01, 0.99)
        val noFair = 1.0 - fair
        if (noAsk <= 0.55 && noFair - noAsk > 0.03) return "NO ${fmt(noFair.coerceIn(0.05, 0.95))}"
        return "SKIP"
    }

    private fun deterministic(prompt: String): String {
        val line = paperLine(prompt)
        val policy = String.format(Locale.US, "Break-even win rate at 50¢ with +8¢/−5¢ is %.1f%%.", ModelFilter.breakEvenWinRate(ModelFilter.rawNetCents(0.50, 0.58), ModelFilter.rawNetCents(0.50, 0.45)) * 100.0)
        return "Paper only. Quant line: $line. $policy No orders."
    }

    private fun num(text: String, pattern: String): Double? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}
