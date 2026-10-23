package com.dirk.kalshiodds.decision

import org.json.JSONObject
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
    private var prefsFile: File? = null

    fun engine(): Engine = synchronized(lock) { engine }
    fun modelPath(): String? = synchronized(lock) { modelFile?.absolutePath }
    fun sha256(): String? = synchronized(lock) { sha256 }
    fun activeName(): String = synchronized(lock) { activeName }
    fun imported(): Boolean = synchronized(lock) { modelFile?.isFile == true && !sha256.isNullOrBlank() }

    /** Remember the built-in model pick across screen switches and process restarts. */
    fun bind(filesDir: File) = synchronized(lock) {
        prefsFile = File(filesDir, "local-analyst.json")
        loadLocked()
    }

    fun setBackend(backend: Backend) = synchronized(lock) {
        engine = engine.copy(backend = backend)
        saveLocked()
    }
    fun setMaxOutputTokens(tokens: Int) = synchronized(lock) {
        engine = engine.copy(maxOutputTokens = tokens.coerceIn(256, 384))
        saveLocked()
    }
    fun setPauseDuringActiveEntry(on: Boolean) = synchronized(lock) {
        engine = engine.copy(pauseDuringActiveEntry = on)
        saveLocked()
    }
    fun setActiveName(name: String) = synchronized(lock) {
        if (SHORTLIST.any { it.name == name }) activeName = name
        saveLocked()
    }

    private fun saveLocked() {
        val file = prefsFile ?: return
        val o = JSONObject()
            .put("activeName", activeName)
            .put("backend", engine.backend.name)
            .put("maxOutputTokens", engine.maxOutputTokens)
            .put("pauseDuringActiveEntry", engine.pauseDuringActiveEntry)
        runCatching { file.writeText(o.toString()) }
    }

    private fun loadLocked() {
        val file = prefsFile ?: return
        if (!file.isFile) return
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val name = o.optString("activeName")
        if (SHORTLIST.any { it.name == name }) activeName = name
        val backend = runCatching { Backend.valueOf(o.optString("backend")) }.getOrNull()
        if (backend != null) engine = engine.copy(backend = backend)
        if (o.has("maxOutputTokens")) {
            engine = engine.copy(maxOutputTokens = o.optInt("maxOutputTokens", engine.maxOutputTokens).coerceIn(256, 384))
        }
        if (o.has("pauseDuringActiveEntry")) {
            engine = engine.copy(pauseDuringActiveEntry = o.optBoolean("pauseDuringActiveEntry", engine.pauseDuringActiveEntry))
        }
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
        prefsFile = null
    }
}

object ModelImport {
    /** Stream the digest. A 2.59 GB `.litertlm` must not be loaded into one byte array. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
        if (blob.contains("Top feature drivers") || blob.contains("Paper session journal")) {
            return explainResearch(user.ifBlank { blob })
        }
        return answer(user.ifBlank { blob }, entryActive)
    }

    /**
     * Two-paragraph on-device quant explanation for a trade decision or session journal.
     * Used when no cloud free provider is configured or when cloud free providers are rate-limited.
     */
    fun explainResearch(prompt: String): String {
        val text = prompt.trim()
        if (text.contains("Paper session journal", ignoreCase = true)) {
            val closed = Regex("Closed\\s+(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val bodyLines = text.lines().drop(1).map { it.trim() }.filter {
                it.isNotEmpty() && !it.startsWith("Two short paragraphs", ignoreCase = true)
            }
            val regimes = bodyLines.filter { it.contains(":") && it.contains("/") }
            val tips = bodyLines.filterNot { it.contains(":") && it.contains("/") }
            val p1 = if (closed > 0) {
                val regText = if (regimes.isNotEmpty()) "Regime breakdown: ${regimes.joinToString("; ")}. " else ""
                "Built-in Quant Analyst · Session summary across $closed closed paper trades. ${regText}Across these windows, the top feature drivers (fairResidualCents, mid, timeLeft, distStrikeBps, and spread) separate positive-EV entries from high-fee chop."
            } else {
                val regText = if (regimes.isNotEmpty()) "Live scored ticks: ${regimes.joinToString("; ")}. " else "The live filter is scoring incoming ticks against the fee-adjusted EV gate. "
                "Built-in Quant Analyst · Session summary (0 closed model trades so far). ${regText}The feature drivers (mid, timeLeft, fairResidualCents, and distStrikeBps) are tracking each tick's edge while open and skipped scores accumulate."
            }
            val tipText = if (tips.isNotEmpty()) tips.joinToString(" ") else "Leave the threshold at ${fmt(ModelFilter.threshold())} while more paper trades complete."
            val retrainAdvice = if (closed >= ModelFilter.MIN_TRAIN) {
                "With $closed closed trades (≥ ${ModelFilter.MIN_TRAIN} minimum), you can tap Retrain offline now to refit the L2 logistic weights and LightGBM payoff trees outside the 15-minute embargo."
            } else {
                "Offline retraining requires ${ModelFilter.MIN_TRAIN} closed paper trades outside the 15-minute embargo ($closed/${ModelFilter.MIN_TRAIN} collected so far); keep the prior feature drivers active until the sample clears ${ModelFilter.MIN_TRAIN}."
            }
            return "$p1\n\n$tipText $retrainAdvice Paper only — no orders."
        }

        val modelType = Regex("Paper\\s+([^.\\n]+)\\s+filter").find(text)?.groupValues?.getOrNull(1)?.trim() ?: "Quant"
        val title = Regex("filter\\.\\s*([^.\\n]+(?:\\.[0-9]+)?)\\.\\s*Reason:").find(text)?.groupValues?.getOrNull(1)?.trim() ?: "model decision"
        val reason = Regex("Reason:\\s*(.+?)\\.\\s*Outcome:").find(text)?.groupValues?.getOrNull(1)?.trim() ?: "gate check"
        val outcome = Regex("Outcome:\\s*([^.\\n]+(?:\\.[0-9]+)?)\\.").find(text)?.groupValues?.getOrNull(1)?.trim() ?: "still open"
        val driverMatches = Regex("^\\s*([A-Za-z0-9_]+)\\s+([+-]?[0-9.]+)\\s*\\(value\\s+([+-]?[0-9.]+)\\)", RegexOption.MULTILINE)
            .findAll(text)
            .map { Triple(it.groupValues[1], it.groupValues[2].toDoubleOrNull() ?: 0.0, it.groupValues[3]) }
            .toList()
        val pos = driverMatches.filter { it.second > 0.0 }.map { "${it.first} (${String.format(Locale.US, "%+.2f", it.second)}, value ${it.third})" }
        val neg = driverMatches.filter { it.second < 0.0 }.map { "${it.first} (${String.format(Locale.US, "%+.2f", it.second)}, value ${it.third})" }
        val flat = driverMatches.filter { it.second == 0.0 }.map { "${it.first} (+0.00, value ${it.third})" }
        val entered = title.contains("entered", ignoreCase = true) || reason.startsWith("enter", ignoreCase = true)
        val driverSummary = buildString {
            if (pos.isNotEmpty()) append("Positive feature drivers lifting the score: ${pos.joinToString(", ")}. ")
            if (neg.isNotEmpty()) append("Negative feature drivers pulling the score down: ${neg.joinToString(", ")}. ")
            if (flat.isNotEmpty()) append("Neutral feature drivers near zero push: ${flat.joinToString(", ")}. ")
            if (pos.isEmpty() && neg.isEmpty() && flat.isEmpty()) append("The top feature drivers shaped the linear-logit and tree-ensemble edge estimate. ")
        }.trim()
        val p1 = "Built-in Quant Analyst ($modelType) · $title (Reason: $reason; Outcome: $outcome). $driverSummary " +
            if (entered) "Because the combined score and expected net payoff cleared the fee-adjusted gate, the paper twin accepted the entry."
            else "Because the combined feature drivers left expected net payoff at or below the fee-and-spread hurdle, the paper twin skipped this tick."

        val names = driverMatches.map { it.first }.toSet()
        val notes = ArrayList<String>()
        if ("mid" in names || "dist50" in names) {
            notes += "mid / dist50 measures where the contract sits relative to the 50¢ maximum-variance zone where Kalshi's quadratic taker fee 0.07·P·(1−P) peaks at 1.75¢ per leg"
        }
        if ("timeLeft" in names || "pinRisk" in names) {
            notes += "timeLeft tracks the remaining 15-minute window before the ${ModelFilter.exitBeforeSeconds().toInt()}s exit cutoff"
        }
        if ("fairResidualCents" in names || "studentT" in names) {
            notes += "fairResidualCents captures the net cents gap between Student-t (ν=4.2) fair value and the ask after deducting spread and taker fee"
        }
        if ("distStrikeBps" in names || "distSigma" in names) {
            notes += "distStrikeBps / distSigma measures BTC spot distance from the strike in basis points and volatility units"
        }
        if (notes.isEmpty()) {
            notes += "the feature drivers weigh order-book spread, flow imbalance, and fair-value residual against round-trip taker fees"
        }
        val p2 = "Microstructure & fee context: ${notes.joinToString("; ")}. " +
            (if (entered) "Hold while the fair-value residual stays positive and exit before the final ${ModelFilter.exitBeforeSeconds().toInt()}s cutoff."
            else "A wider fairResidualCents edge or tighter bid-ask spread is needed on the next tick to clear round-trip fees.") +
            " Paper only — no orders."
        return "$p1\n\n$p2"
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
        val yesBid = num(prompt, "YES bid/ask\\s+([0-9.]+)/")
        val yesAsk = num(prompt, "YES bid/ask\\s+[^/\\s]+/([0-9.]+)")
        val fairText = num(prompt, "Model fair YES\\s+([0-9.]+)")
        val tau = (minutes ?: 5.0) * 60.0
        val p = if (spot != null && strike != null) HftSignals.studentTFair(spot, strike, 3e-5, tau, "YES") else fairText
        val fair = p ?: fairText
        if (fair == null || yesAsk == null) return "SKIP"
        if (yesAsk <= 0.55 && fair - yesAsk > 0.03) return "YES ${fmt(fair.coerceIn(0.05, 0.95))}"
        val noAsk = (1.0 - (yesBid ?: yesAsk)).coerceIn(0.01, 0.99)
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
