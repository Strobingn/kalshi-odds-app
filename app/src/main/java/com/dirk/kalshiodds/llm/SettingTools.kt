package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.LateWindowScalper
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpStrategySettings
import java.util.Locale

/**
 * 1.0.4: the AI chat's only write tool, `set_setting(key, value)`, limited to PAPER scalping settings
 * (global stops/limits, research params incl. Kelly bankroll, per-strategy params and cooldowns).
 *
 * Safety order: (1) anything touching real money — live arming, Approve / REAL MONEY gates, API keys,
 * order placement, cash — is hard-blocked before any lookup; (2) the key must be on the allowlist;
 * (3) the value must parse and sit inside the documented range (no silent clamping). A valid proposal is
 * only a [Proposal]; nothing changes until Dirk taps Apply on the confirm card and [apply] runs.
 */
object SettingTools {
    sealed class Result {
        data class Proposal(val key: String, val oldValue: String, val newValue: String) : Result()
        data class Rejected(val key: String, val reason: String, val realMoneyBlocked: Boolean = false) : Result()
    }

    enum class Kind { INT, DOUBLE, BOOL }
    data class Spec(val kind: Kind, val min: Double = 0.0, val max: Double = 0.0, val help: String = "")

    /** Lower-cased fragments that mark a real-money / credential / order key. Checked first, always. */
    private val BLOCKED = listOf("live", "real", "money", "approve", "apikey", "api_key", "api.", "secret", "token", "password",
        "private", "pem", "order", "place", "withdraw", "deposit", "cash", "arming", "armed", "armlive", "wallet", "kalshikey")

    fun isRealMoneyKey(key: String): Boolean {
        val k = key.trim().lowercase(Locale.US)
        return BLOCKED.any { it in k } || k == "key" || k.endsWith(".key") || k.startsWith("key.") || k.endsWith("key") && !k.endsWith("monkey")
    }

    private fun i(min: Int, max: Int, help: String = "") = Spec(Kind.INT, min.toDouble(), max.toDouble(), help)
    private fun d(min: Double, max: Double, help: String = "") = Spec(Kind.DOUBLE, min, max, help)
    private val b = Spec(Kind.BOOL)

    val GLOBAL: Map<String, Spec> = linkedMapOf(
        "enabled" to b, "killSwitch" to b, "adaptive" to b, "manual" to b,
        "maxAskCents" to i(1, 55), "stopCents" to i(1, 99), "takeProfitCents" to i(0, 99), // trailCents omitted: spawnV2 hard-codes the 3¢ trail
        "contracts" to i(1, 1000), "greenContracts" to i(1, 500),
        "maxStakeUsd" to d(0.0, 100_000.0), "maxHoldSeconds" to i(0, 3600), "maxTradesPerHour" to i(0, 10_000),
        "maxDailyLossUsd" to d(0.0, 100_000.0), "maxOpen" to i(0, 1000), "windowSeconds" to i(10, 600),
        "dipCents" to d(0.0, 30.0), "stopLossPercent" to i(1, 99), // peak-trail knobs omitted: execution hard-codes the 3¢ trail
        "research.edgeCents" to d(0.0, 20.0), "research.lateMinS" to i(30, 600), "research.lateMaxS" to i(30, 900),
        "research.lateLoCents" to i(50, 98), "research.lateHiCents" to i(50, 99), "research.makerEdgeCents" to d(0.0, 20.0),
        "research.kellyFraction" to d(0.0, 1.0), "research.windowCapUsd" to d(0.0, 100_000.0), "research.clip" to i(1, 500),
        "research.kellyBankrollUsd" to d(10.0, 1_000_000.0, "paper bankroll"))

    /** Per-strategy fields: `strategy.<code>.<field>`. cadenceSeconds is the strategy's re-entry cooldown. */
    val STRATEGY: Map<String, Spec> = linkedMapOf(
        "enabled" to b, "aiLearns" to b, "contracts" to i(1, 1000), "minEntryCents" to i(1, 99), "maxEntryCents" to i(1, 99),
        "stopLossPercent" to i(1, 99), "trailCents" to i(1, 20), "stopCents" to i(1, 99), "takeProfitCents" to i(0, 99),
        "maxHoldSeconds" to i(0, 3600), "untilMinute" to i(1, 14), "cadenceSeconds" to i(0, 30, "cooldown"),
        "maxOpenPerSide" to i(0, 20), "edgeCents" to i(0, 30))

    private val ALIASES = mapOf("bankroll" to "research.kellyBankrollUsd", "kellyBankrollUsd" to "research.kellyBankrollUsd")

    private val codes: Set<String> by lazy { ScalpModels.ALL.map { it.code }.toSet() }

    /**
     * Per-family fields actually consumed by the scalper (Scalp.kt), so an applied card always changes behaviour:
     * originals — price range / until-minute entry filter + stop % / take profit / max hold exits (no enable switch:
     * original entries never consult disabledStrategies); V2 — permits(), plan(), cadence, side cap, exits;
     * research — permits(), entry range, clip, edge. trailCents is never exposed (3¢ trail is a hard rule).
     */
    private val ORIGINAL_FIELDS = setOf("aiLearns", "minEntryCents", "maxEntryCents", "untilMinute", "stopLossPercent", "takeProfitCents", "maxHoldSeconds")
    private val V2_FIELDS = setOf("enabled", "aiLearns", "contracts", "minEntryCents", "maxEntryCents", "stopCents", "stopLossPercent",
        "takeProfitCents", "maxHoldSeconds", "untilMinute", "cadenceSeconds", "maxOpenPerSide")
    private val RESEARCH_FIELDS = setOf("enabled", "aiLearns", "contracts", "minEntryCents", "maxEntryCents", "edgeCents")

    fun fieldsFor(code: String): Set<String> {
        val m = ScalpModels.ALL.firstOrNull { it.code == code } ?: return emptySet()
        if (m == ScalpModels.Model.V2_LLM || LateWindowScalper.matches(m.code)) return emptySet()
        return when { ScalpModels.isResearch(m) -> RESEARCH_FIELDS; ScalpModels.isV2(m) -> V2_FIELDS; else -> ORIGINAL_FIELDS }
    }

    /** Fully normalized view, including research params (ScalpControls.normalized() leaves those raw). */
    private fun norm(c: ScalpControls) = c.normalized().let { it.copy(research = it.research.normalized()) }

    private fun snapshot(c: ScalpControls, code: String?): Map<String, String> =
        GLOBAL.keys.associateWith { get(c, it) } + (code?.let { cd -> fieldsFor(cd).associate { f -> "strategy.$cd.$f" to get(c, "strategy.$cd.$f") } } ?: emptyMap())

    fun specFor(key: String): Spec? {
        val k = ALIASES[key] ?: key
        GLOBAL[k]?.let { return it }
        val parts = k.split('.')
        if (parts.size == 3 && parts[0] == "strategy" && parts[1] in codes && parts[2] in fieldsFor(parts[1])) return STRATEGY[parts[2]]
        return null
    }

    fun propose(c: ScalpControls, rawKey: String, rawValue: String): Result {
        val key = ALIASES[rawKey.trim()] ?: rawKey.trim()
        if (isRealMoneyKey(key) || isRealMoneyKey(rawValue))
            return Result.Rejected(key, "blocked: the AI can never touch real money (live arming, Approve/REAL MONEY gates, API keys, orders)", true)
        val spec = specFor(key) ?: return Result.Rejected(key, "not an allowed setting")
        val v = rawValue.trim().removeSuffix("¢").removePrefix("$").trim()
        val parsed: Any = when (spec.kind) {
            Kind.BOOL -> when (v.lowercase(Locale.US)) { "true", "on", "yes", "1" -> true; "false", "off", "no", "0" -> false
                else -> return Result.Rejected(key, "expected true/false, got '$rawValue'") }
            Kind.INT -> v.toIntOrNull() ?: return Result.Rejected(key, "expected a whole number, got '$rawValue'")
            Kind.DOUBLE -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Result.Rejected(key, "expected a number, got '$rawValue'")
        }
        if (parsed is Number) {
            val x = parsed.toDouble()
            if (x < spec.min || x > spec.max) return Result.Rejected(key, "out of range: ${fmt(spec, spec.min)}…${fmt(spec, spec.max)}")
        }
        val newValue = parsed.toString()
        val next = runCatching { set(c, key, newValue) }.getOrElse { return Result.Rejected(key, it.message ?: "invalid") }
        // Cross-field rules (lateMaxS ≥ lateMinS, maxEntry ≥ minEntry, …) are enforced by normalization at trade time.
        // Refuse if normalizing would change the requested value OR any other setting, so the card is exactly what runs.
        val code = key.split('.').takeIf { it.size == 3 }?.get(1)
        val want = snapshot(norm(c), code) + (key to newValue)
        val got = snapshot(norm(next), code)
        val drift = want.keys.filter { want[it] != got[it] }
        if (drift.isNotEmpty()) return Result.Rejected(key, "conflicts with related settings: " + drift.joinToString { "$it would become ${got[it]}" })
        val old = get(c, key)
        if (old == newValue) return Result.Rejected(key, "already $old (no change)")
        return Result.Proposal(key, old, newValue)
    }

    /**
     * 1.0.12 window watcher: same re-validation as [apply], then the write, without [ScalpStrategySettings.manual].
     * manual() is the user-confirm path (it restarts "AI learns"). The watcher is not a user edit.
     */
    fun applyAutomatic(c: ScalpControls, p: Result.Proposal): ScalpControls {
        val again = propose(c, p.key, p.newValue)
        require(again is Result.Proposal) { (again as Result.Rejected).reason }
        return set(c, p.key, p.newValue).let { it.copy(research = it.research.normalized()) }.normalized()
    }

    /** Applies a proposal confirmed by Dirk. Re-validated against the current controls first. */
    fun apply(c: ScalpControls, p: Result.Proposal): ScalpControls {
        val again = propose(c, p.key, p.newValue)
        require(again is Result.Proposal) { (again as Result.Rejected).reason }
        val next = set(c, p.key, p.newValue).let { it.copy(research = it.research.normalized()) }
        if (!p.key.startsWith("strategy.") || p.key.endsWith(".enabled")) return next.normalized()
        // Like the settings screen: any manual per-strategy change (incl. the AI-learns toggle) restarts the learner.
        val code = p.key.split('.')[1]
        return next.copy(strategySettings = next.strategySettings + (code to next.settings(code).manual())).normalized()
    }

    private fun fmt(s: Spec, x: Double) = if (s.kind == Kind.INT) x.toInt().toString() else x.toString()

    fun get(c: ScalpControls, key: String): String {
        val r = c.research
        return when (key) {
            "enabled" -> c.enabled; "killSwitch" -> c.killSwitch; "adaptive" -> c.adaptive; "manual" -> c.manual
            "peakTrailEnabled" -> c.peakTrailEnabled; "maxAskCents" -> c.maxAskCents; "stopCents" -> c.stopCents
            "takeProfitCents" -> c.takeProfitCents; "trailCents" -> c.trailCents; "armCents" -> c.armCents; "contracts" -> c.contracts
            "greenContracts" -> c.greenContracts; "maxStakeUsd" -> c.maxStakeUsd; "maxHoldSeconds" -> c.maxHoldSeconds
            "maxTradesPerHour" -> c.maxTradesPerHour; "maxDailyLossUsd" -> c.maxDailyLossUsd; "maxOpen" -> c.maxOpen
            "windowSeconds" -> c.windowSeconds; "dipCents" -> c.dipCents; "peakTrailCents" -> c.peakTrailCents
            "stopLossPercent" -> c.stopLossPercent
            "research.edgeCents" -> r.edgeCents; "research.lateMinS" -> r.lateMinS; "research.lateMaxS" -> r.lateMaxS
            "research.lateLoCents" -> r.lateLoCents; "research.lateHiCents" -> r.lateHiCents; "research.makerEdgeCents" -> r.makerEdgeCents
            "research.kellyFraction" -> r.kellyFraction; "research.windowCapUsd" -> r.windowCapUsd; "research.clip" -> r.clip
            "research.kellyBankrollUsd" -> r.kellyBankrollUsd
            else -> {
                val (_, code, f) = key.split('.').also { require(it.size == 3 && it[0] == "strategy") { "unknown key $key" } }
                if (f == "enabled") return (code !in c.disabledStrategies).toString()
                val s = c.settings(code)
                when (f) {
                    "aiLearns" -> s.aiLearns; "contracts" -> s.contracts; "minEntryCents" -> s.minEntryCents; "maxEntryCents" -> s.maxEntryCents
                    "stopLossPercent" -> s.stopLossPercent; "trailCents" -> s.trailCents; "stopCents" -> s.stopCents
                    "takeProfitCents" -> s.takeProfitCents; "maxHoldSeconds" -> s.maxHoldSeconds; "untilMinute" -> s.untilMinute
                    "cadenceSeconds" -> s.cadenceSeconds; "maxOpenPerSide" -> s.maxOpenPerSide; "edgeCents" -> s.edgeCents
                    else -> error("unknown key $key")
                } ?: "default"
            }
        }.toString()
    }

    private fun set(c: ScalpControls, key: String, v: String): ScalpControls {
        val r = c.research
        fun bo() = v.toBooleanStrict()
        fun io() = v.toInt()
        fun db() = v.toDouble()
        return when (key) {
            "enabled" -> c.copy(enabled = bo()); "killSwitch" -> c.copy(killSwitch = bo()); "adaptive" -> c.copy(adaptive = bo())
            "manual" -> c.copy(manual = bo()); "peakTrailEnabled" -> c.copy(peakTrailEnabled = bo())
            "maxAskCents" -> c.copy(maxAskCents = io()); "stopCents" -> c.copy(stopCents = io()); "takeProfitCents" -> c.copy(takeProfitCents = io())
            "trailCents" -> c.copy(trailCents = io()); "armCents" -> c.copy(armCents = io()); "contracts" -> c.copy(contracts = io())
            "greenContracts" -> c.copy(greenContracts = io()); "maxStakeUsd" -> c.copy(maxStakeUsd = db()); "maxHoldSeconds" -> c.copy(maxHoldSeconds = io())
            "maxTradesPerHour" -> c.copy(maxTradesPerHour = io()); "maxDailyLossUsd" -> c.copy(maxDailyLossUsd = db()); "maxOpen" -> c.copy(maxOpen = io())
            "windowSeconds" -> c.copy(windowSeconds = io()); "dipCents" -> c.copy(dipCents = db()); "peakTrailCents" -> c.copy(peakTrailCents = io())
            "stopLossPercent" -> c.copy(stopLossPercent = io())
            "research.edgeCents" -> c.copy(research = r.copy(edgeCents = db())); "research.lateMinS" -> c.copy(research = r.copy(lateMinS = io()))
            "research.lateMaxS" -> c.copy(research = r.copy(lateMaxS = io())); "research.lateLoCents" -> c.copy(research = r.copy(lateLoCents = io()))
            "research.lateHiCents" -> c.copy(research = r.copy(lateHiCents = io())); "research.makerEdgeCents" -> c.copy(research = r.copy(makerEdgeCents = db()))
            "research.kellyFraction" -> c.copy(research = r.copy(kellyFraction = db())); "research.windowCapUsd" -> c.copy(research = r.copy(windowCapUsd = db()))
            "research.clip" -> c.copy(research = r.copy(clip = io())); "research.kellyBankrollUsd" -> c.copy(research = r.copy(kellyBankrollUsd = db()))
            else -> {
                val (_, code, f) = key.split('.').also { require(it.size == 3 && it[0] == "strategy") { "unknown key $key" } }
                if (f == "enabled") return c.copy(disabledStrategies = if (bo()) c.disabledStrategies - code else c.disabledStrategies + code)
                val s: ScalpStrategySettings = c.settings(code)
                val n = when (f) {
                    "aiLearns" -> s.copy(aiLearns = bo()); "contracts" -> s.copy(contracts = io()); "minEntryCents" -> s.copy(minEntryCents = io())
                    "maxEntryCents" -> s.copy(maxEntryCents = io()); "stopLossPercent" -> s.copy(stopLossPercent = io()); "trailCents" -> s.copy(trailCents = io())
                    "stopCents" -> s.copy(stopCents = io()); "takeProfitCents" -> s.copy(takeProfitCents = io()); "maxHoldSeconds" -> s.copy(maxHoldSeconds = io())
                    "untilMinute" -> s.copy(untilMinute = io()); "cadenceSeconds" -> s.copy(cadenceSeconds = io()); "maxOpenPerSide" -> s.copy(maxOpenPerSide = io())
                    "edgeCents" -> s.copy(edgeCents = io())
                    else -> error("unknown key $key")
                }
                c.copy(strategySettings = c.strategySettings + (code to n))
            }
        }
    }

    // 1.0.6: never start a character class with "[:" — Android's ICU regex parses it as a POSIX set ([:alpha:])
    // and throws PatternSyntaxException at class init (ExceptionInInitializerError); the JVM accepts it.
    /** Tool calls in the model's reply: one per line, `SET_SETTING <key> = <value>` (or JSON-ish key/value). */
    private val CALL = Regex("""SET_SETTING\s*[(:]?\s*["']?([A-Za-z0-9_.\-]+)["']?\s*[=,:]\s*["']?([^"')\n]+?)["']?\s*\)?\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    fun parseCalls(reply: String): List<Pair<String, String>> = CALL.findAll(reply).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    fun stripCalls(reply: String): String = reply.lines().filterNot { CALL.containsMatchIn(it) }.joinToString("\n").trim()

    /** Tool description for the system prompt: current values and ranges for the allowlist. */
    fun describe(c: ScalpControls): String = buildString {
        appendLine("Tool set_setting (PAPER settings only). To propose a change write a line exactly: SET_SETTING <key> = <value>")
        appendLine("Dirk must tap Apply before anything changes. Real money, live arming, Approve/REAL MONEY gates, API keys and orders are blocked and must not be attempted.")
        appendLine("Global keys (current value, range):")
        GLOBAL.forEach { (k, s) -> appendLine("$k=${get(c, k)} ${range(s)}") }
        appendLine("Per-strategy keys strategy.<code>.<field> with field in: " + STRATEGY.entries.joinToString { (k, s) -> "$k ${range(s)}" })
        appendLine("Fields by family — original strategies: ${ORIGINAL_FIELDS.joinToString()}; v2 strategies: ${V2_FIELDS.joinToString()}; research strategies: ${RESEARCH_FIELDS.joinToString()}.")
        appendLine("cadenceSeconds is the strategy's cooldown between entries. 'bankroll' is research.kellyBankrollUsd.")
    }

    private fun range(s: Spec) = if (s.kind == Kind.BOOL) "(true/false)" else "(${fmt(s, s.min)}..${fmt(s, s.max)})"
}
