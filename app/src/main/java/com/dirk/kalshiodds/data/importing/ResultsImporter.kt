package com.dirk.kalshiodds.data.importing

import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.ScorecardRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Streamed restore of DipHunter CSV/JSON exports and Kalshi fill-history CSVs.
 * Dedupes by id / ticker+timestamp against [SeenKeys] (existing SQLite rows).
 */
object ResultsImporter {
    const val MAX_ERROR_SAMPLES = 8
    const val JSON_OBJECT_CAP_BYTES = 4 * 1024 * 1024

    data class Parsed(
        val batch: ImportBatch,
        val summary: ImportSummary
    )

    fun parse(reader: Reader, seen: SeenKeys = SeenKeys(), peek: String? = null): Parsed {
        val first = peek?.trim().orEmpty().ifEmpty {
            val buf = if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader, 8 * 1024)
            val line = buf.readLine() ?: return Parsed(ImportBatch(), ImportSummary("empty", message = "File was empty"))
            return parse(buf, seen, line)
        }
        return when {
            first.startsWith("{") || first.startsWith("[") -> parseJson(reader, first, seen)
            else -> parseCsv(reader, first, seen)
        }
    }

    fun parseCsv(reader: Reader, firstLine: String, seen: SeenKeys): Parsed {
        val snapshots = ArrayList<ScoredSnapshotRow>()
        val alerts = ArrayList<AlertRow>()
        val scorecards = ArrayList<ScorecardRow>()
        val tickets = ArrayList<TicketAttemptRow>()
        val fills = ArrayList<ImportedFill>()
        var imported = 0
        var skipped = 0
        var minTs: Long? = null
        var maxTs: Long? = null
        val errors = ArrayList<String>()
        var header: Map<String, Int>? = null
        var mode = CsvMode.UNKNOWN

        fun noteTs(ts: Long?) {
            if (ts == null || ts <= 0L) return
            minTs = minTs?.let { minOf(it, ts) } ?: ts
            maxTs = maxTs?.let { maxOf(it, ts) } ?: ts
        }

        fun consume(line: String, lineNumber: Int) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return
            val fields = StreamCsvParser.parseLine(trimmed)
            val first = fields.firstOrNull()?.lowercase().orEmpty()
            when {
                first == "kind" || looksLikeAppHeader(fields) -> {
                    header = StreamCsvParser.headerIndex(fields)
                    mode = detectAppMode(fields)
                }
                looksLikeKalshiHeader(fields) -> {
                    header = StreamCsvParser.headerIndex(fields)
                    mode = CsvMode.KALSHI_FILLS
                }
                header == null && looksLikeKalshiHeader(fields) -> {
                    header = StreamCsvParser.headerIndex(fields)
                    mode = CsvMode.KALSHI_FILLS
                }
                header == null -> {
                    // First data-ish row without a header: try Kalshi-ish positional, else skip.
                    skipped += 1
                    if (errors.size < MAX_ERROR_SAMPLES) {
                        errors.add("line $lineNumber: no header yet")
                    }
                }
                else -> {
                    val idx = header!!
                    when (mode) {
                        CsvMode.SNAPSHOT -> ingestSnapshot(fields, idx, seen, snapshots)?.also {
                            imported += 1
                            noteTs(it.createdAtMs)
                        } ?: run { skipped += 1 }
                        CsvMode.ALERT -> ingestAlert(fields, idx, seen, alerts)?.also {
                            imported += 1
                            noteTs(it.createdAtMs)
                        } ?: run { skipped += 1 }
                        CsvMode.SCORECARD -> ingestScorecard(fields, idx, seen, scorecards)?.also {
                            imported += 1
                            noteTs(it.createdAtMs)
                        } ?: run { skipped += 1 }
                        CsvMode.TICKET -> ingestTicket(fields, idx, seen, tickets)?.also {
                            imported += 1
                            noteTs(it.createdAtMs)
                        } ?: run { skipped += 1 }
                        CsvMode.KALSHI_FILLS -> ingestFill(fields, idx, seen, fills)?.also {
                            imported += 1
                            noteTs(it.createdAtMs)
                        } ?: run { skipped += 1 }
                        CsvMode.UNKNOWN -> {
                            when (StreamCsvParser.field(fields, idx, "kind")?.lowercase()) {
                                "snapshot" -> ingestSnapshot(fields, idx, seen, snapshots)?.also {
                                    imported += 1
                                    noteTs(it.createdAtMs)
                                } ?: run { skipped += 1 }
                                "alert" -> ingestAlert(fields, idx, seen, alerts)?.also {
                                    imported += 1
                                    noteTs(it.createdAtMs)
                                } ?: run { skipped += 1 }
                                "scorecard" -> ingestScorecard(fields, idx, seen, scorecards)?.also {
                                    imported += 1
                                    noteTs(it.createdAtMs)
                                } ?: run { skipped += 1 }
                                "ticket" -> ingestTicket(fields, idx, seen, tickets)?.also {
                                    imported += 1
                                    noteTs(it.createdAtMs)
                                } ?: run { skipped += 1 }
                                else -> ingestFill(fields, idx, seen, fills)?.also {
                                    imported += 1
                                    noteTs(it.createdAtMs)
                                } ?: run { skipped += 1 }
                            }
                        }
                    }
                }
            }
        }

        consume(firstLine, 1)
        val buf = if (reader is java.io.BufferedReader) reader else java.io.BufferedReader(reader, 8 * 1024)
        var n = 1
        while (true) {
            val line = buf.readLine() ?: break
            n += 1
            consume(line, n)
        }

        val kind = when {
            fills.isNotEmpty() && snapshots.isEmpty() && alerts.isEmpty() -> "kalshi-fills"
            else -> "diphunter-export"
        }
        return Parsed(
            batch = ImportBatch(snapshots, alerts, scorecards, tickets, fills),
            summary = ImportSummary(
                kind = kind,
                imported = imported,
                skipped = skipped,
                snapshots = snapshots.size,
                alerts = alerts.size,
                scorecards = scorecards.size,
                tickets = tickets.size,
                fills = fills.size,
                minTsMs = minTs,
                maxTsMs = maxTs,
                errors = errors,
                message = "$imported imported, $skipped skipped"
            )
        )
    }

    fun parseJson(reader: Reader, firstLine: String, seen: SeenKeys): Parsed {
        val body = buildString {
            append(firstLine)
            val buf = CharArray(8 * 1024)
            var total = firstLine.length
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                if (total > JSON_OBJECT_CAP_BYTES) {
                    return Parsed(
                        ImportBatch(),
                        ImportSummary(
                            kind = "json",
                            errors = listOf("JSON larger than 4MB — export CSV for streamed restore"),
                            message = "JSON too large"
                        )
                    )
                }
                appendRange(buf, 0, n)
            }
        }
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            val wrapped = JSONObject().put("fills", JSONArray(trimmed)).toString()
            return parseJsonObject(wrapped, seen)
        }
        // NDJSON: one object per line
        if (trimmed.contains('\n') && trimmed.lineSequence().all { it.isBlank() || it.trim().startsWith("{") }) {
            return parseNdjson(trimmed, seen)
        }
        return parseJsonObject(trimmed, seen)
    }

    private fun parseNdjson(body: String, seen: SeenKeys): Parsed {
        val snapshots = ArrayList<ScoredSnapshotRow>()
        val alerts = ArrayList<AlertRow>()
        val scorecards = ArrayList<ScorecardRow>()
        val tickets = ArrayList<TicketAttemptRow>()
        val fills = ArrayList<ImportedFill>()
        var imported = 0
        var skipped = 0
        var minTs: Long? = null
        var maxTs: Long? = null
        body.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val o = runCatching { JSONObject(t) }.getOrNull() ?: return@forEach
            if (o.has("format")) return@forEach
            when (o.optString("kind").lowercase()) {
                "snapshot" -> jsonSnapshot(o)?.takeIf { seen.snapshots.add(seen.snapshotKey(it.ticker, it.createdAtMs)) }?.also {
                    snapshots.add(it); imported += 1; minTs = minOfTs(minTs, it.createdAtMs); maxTs = maxOfTs(maxTs, it.createdAtMs)
                } ?: run { skipped += 1 }
                "alert" -> jsonAlert(o)?.takeIf { seen.alerts.add(seen.alertKey(it.alertId, it.ticker, it.createdAtMs)) }?.also {
                    alerts.add(it); imported += 1; minTs = minOfTs(minTs, it.createdAtMs); maxTs = maxOfTs(maxTs, it.createdAtMs)
                } ?: run { skipped += 1 }
                "scorecard" -> jsonScorecard(o)?.takeIf { seen.scorecards.add("${it.ticker}|${it.createdAtMs}") }?.also {
                    scorecards.add(it); imported += 1; minTs = minOfTs(minTs, it.createdAtMs); maxTs = maxOfTs(maxTs, it.createdAtMs)
                } ?: run { skipped += 1 }
                "ticket" -> jsonTicket(o)?.takeIf { seen.tickets.add(seen.ticketKey(it.clientOrderId, it.ticker, it.createdAtMs)) }?.also {
                    tickets.add(it); imported += 1; minTs = minOfTs(minTs, it.createdAtMs); maxTs = maxOfTs(maxTs, it.createdAtMs)
                } ?: run { skipped += 1 }
                "fill" -> jsonFill(o)?.takeIf { seen.fills.add(it.id) }?.also {
                    fills.add(it); imported += 1; minTs = minOfTs(minTs, it.createdAtMs); maxTs = maxOfTs(maxTs, it.createdAtMs)
                } ?: run { skipped += 1 }
                else -> skipped += 1
            }
        }
        return Parsed(
            ImportBatch(snapshots, alerts, scorecards, tickets, fills),
            ImportSummary(
                kind = "diphunter-ndjson",
                imported = imported,
                skipped = skipped,
                snapshots = snapshots.size,
                alerts = alerts.size,
                scorecards = scorecards.size,
                tickets = tickets.size,
                fills = fills.size,
                minTsMs = minTs,
                maxTsMs = maxTs,
                message = "$imported imported, $skipped skipped"
            )
        )
    }

    private fun parseJsonObject(body: String, seen: SeenKeys): Parsed {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return Parsed(ImportBatch(), ImportSummary("json", errors = listOf(it.message ?: "bad json"), message = "Invalid JSON"))
        }
        val snapshots = ArrayList<ScoredSnapshotRow>()
        val alerts = ArrayList<AlertRow>()
        val scorecards = ArrayList<ScorecardRow>()
        val tickets = ArrayList<TicketAttemptRow>()
        val fills = ArrayList<ImportedFill>()
        val settingsChanges = ArrayList<com.dirk.kalshiodds.data.local.history.SettingsChange>()
        var imported = 0
        var skipped = 0
        var minTs: Long? = null
        var maxTs: Long? = null
        fun addSnap(o: JSONObject) {
            val row = jsonSnapshot(o) ?: run { skipped += 1; return }
            if (!seen.snapshots.add(seen.snapshotKey(row.ticker, row.createdAtMs))) { skipped += 1; return }
            snapshots.add(row); imported += 1
            minTs = minOfTs(minTs, row.createdAtMs); maxTs = maxOfTs(maxTs, row.createdAtMs)
        }
        jsonArray(root, "snapshots").forEachObj(::addSnap)
        jsonArray(root, "alerts").forEachObj { o ->
            val row = jsonAlert(o) ?: run { skipped += 1; return@forEachObj }
            if (!seen.alerts.add(seen.alertKey(row.alertId, row.ticker, row.createdAtMs))) { skipped += 1; return@forEachObj }
            alerts.add(row); imported += 1
            minTs = minOfTs(minTs, row.createdAtMs); maxTs = maxOfTs(maxTs, row.createdAtMs)
        }
        jsonArray(root, "scorecards").forEachObj { o ->
            val row = jsonScorecard(o) ?: run { skipped += 1; return@forEachObj }
            if (!seen.scorecards.add("${row.ticker}|${row.createdAtMs}")) { skipped += 1; return@forEachObj }
            scorecards.add(row); imported += 1
            minTs = minOfTs(minTs, row.createdAtMs); maxTs = maxOfTs(maxTs, row.createdAtMs)
        }
        jsonArray(root, "tickets").forEachObj { o ->
            val row = jsonTicket(o) ?: run { skipped += 1; return@forEachObj }
            if (!seen.tickets.add(seen.ticketKey(row.clientOrderId, row.ticker, row.createdAtMs))) { skipped += 1; return@forEachObj }
            tickets.add(row); imported += 1
            minTs = minOfTs(minTs, row.createdAtMs); maxTs = maxOfTs(maxTs, row.createdAtMs)
        }
        jsonArray(root, "fills").forEachObj { o ->
            val row = jsonFill(o) ?: run { skipped += 1; return@forEachObj }
            if (!seen.fills.add(row.id)) { skipped += 1; return@forEachObj }
            fills.add(row); imported += 1
            minTs = minOfTs(minTs, row.createdAtMs); maxTs = maxOfTs(maxTs, row.createdAtMs)
        }
        jsonArray(root, "settings_changes").forEachObj { o ->
            val ts = o.optLong("created_at_ms", 0L)
            val key = o.optString("key")
            if (key.isBlank()) { skipped += 1; return@forEachObj }
            settingsChanges.add(
                com.dirk.kalshiodds.data.local.history.SettingsChange(
                    createdAtMs = ts,
                    key = key,
                    oldValue = o.optString("old_value"),
                    newValue = o.optString("new_value"),
                    snapshotJson = o.optString("snapshot_json").takeIf { it.isNotBlank() }
                )
            )
            imported += 1
            minTs = minOfTs(minTs, ts); maxTs = maxOfTs(maxTs, ts)
        }
        return Parsed(
            ImportBatch(snapshots, alerts, scorecards, tickets, fills, settingsChanges = settingsChanges),
            ImportSummary(
                kind = "diphunter-json",
                imported = imported,
                skipped = skipped,
                snapshots = snapshots.size,
                alerts = alerts.size,
                scorecards = scorecards.size,
                tickets = tickets.size,
                fills = fills.size,
                minTsMs = minTs,
                maxTsMs = maxTs,
                message = "$imported imported, $skipped skipped"
            )
        )
    }

    private fun jsonArray(root: JSONObject, key: String): JSONArray? = root.optJSONArray(key)

    private fun JSONArray?.forEachObj(block: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            block(o)
        }
    }

    private enum class CsvMode { UNKNOWN, SNAPSHOT, ALERT, SCORECARD, TICKET, KALSHI_FILLS }

    private fun detectAppMode(fields: List<String>): CsvMode {
        val joined = fields.joinToString(",").lowercase()
        return when {
            joined.contains("alert_id") -> CsvMode.ALERT
            joined.contains("policy_roi") || joined.contains("outcome") && joined.contains("brier") -> CsvMode.SCORECARD
            joined.contains("stake_usd") || joined.contains("client_order_id") -> CsvMode.TICKET
            joined.contains("fair_pp") -> CsvMode.SNAPSHOT
            else -> CsvMode.UNKNOWN
        }
    }

    private fun looksLikeAppHeader(fields: List<String>): Boolean {
        val j = fields.joinToString(",").lowercase()
        return j.contains("fair_pp") || j.contains("alert_id") || j.contains("stake_usd") || j.contains("policy_roi")
    }

    private fun looksLikeKalshiHeader(fields: List<String>): Boolean {
        val keys = fields.map { StreamCsvParser.normalizeHeader(it) }.toSet()
        val hasTicker = keys.any { it == "ticker" || it == "market_ticker" }
        val hasFill = keys.any { it.contains("fill") || it == "trade_id" || it == "id" }
        val hasSide = keys.contains("side") || keys.contains("yes_no")
        val hasCount = keys.any { it == "count" || it == "contracts" || it == "quantity" || it == "qty" }
        return hasTicker && (hasFill || (hasSide && hasCount))
    }

    private fun ingestSnapshot(
        fields: List<String>,
        idx: Map<String, Int>,
        seen: SeenKeys,
        out: MutableList<ScoredSnapshotRow>
    ): ScoredSnapshotRow? {
        val ticker = StreamCsvParser.field(fields, idx, "ticker") ?: return null
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val ts = parseTs(StreamCsvParser.field(fields, idx, "created_at_ms", "created_time", "timestamp")) ?: return null
        if (!seen.snapshots.add(seen.snapshotKey(ticker, ts))) return null
        val row = ScoredSnapshotRow(
            ticker = ticker,
            series = StreamCsvParser.field(fields, idx, "series") ?: CryptoMarkets.inferSeries(ticker),
            side = StreamCsvParser.field(fields, idx, "side") ?: "YES",
            edgePp = num(StreamCsvParser.field(fields, idx, "edge_pp")) ?: 0.0,
            fairPp = num(StreamCsvParser.field(fields, idx, "fair_pp")) ?: 0.0,
            marketPp = num(StreamCsvParser.field(fields, idx, "market_pp")) ?: 0.0,
            regime = StreamCsvParser.field(fields, idx, "regime"),
            uncertainty = num(StreamCsvParser.field(fields, idx, "uncertainty")),
            createdAtMs = ts,
            confidence = num(StreamCsvParser.field(fields, idx, "confidence")),
            tte = StreamCsvParser.field(fields, idx, "tte"),
            heavyMl = StreamCsvParser.field(fields, idx, "heavy_ml") == "1",
            note = StreamCsvParser.field(fields, idx, "note")
        )
        out.add(row)
        return row
    }

    private fun ingestAlert(
        fields: List<String>,
        idx: Map<String, Int>,
        seen: SeenKeys,
        out: MutableList<AlertRow>
    ): AlertRow? {
        val ticker = StreamCsvParser.field(fields, idx, "ticker") ?: return null
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val ts = parseTs(StreamCsvParser.field(fields, idx, "created_at_ms", "created_time")) ?: 0L
        val id = StreamCsvParser.field(fields, idx, "alert_id", "id") ?: "$ticker|$ts"
        if (!seen.alerts.add(seen.alertKey(id, ticker, ts))) return null
        val row = AlertRow(
            alertId = id,
            ticker = ticker,
            series = StreamCsvParser.field(fields, idx, "series") ?: CryptoMarkets.inferSeries(ticker),
            side = StreamCsvParser.field(fields, idx, "side") ?: "YES",
            edgePp = num(StreamCsvParser.field(fields, idx, "edge_pp")) ?: 0.0,
            fairPp = num(StreamCsvParser.field(fields, idx, "fair_pp")) ?: 0.0,
            marketPp = num(StreamCsvParser.field(fields, idx, "market_pp")) ?: 0.0,
            reason = StreamCsvParser.field(fields, idx, "reason") ?: "",
            regime = StreamCsvParser.field(fields, idx, "regime"),
            createdAtMs = ts
        )
        out.add(row)
        return row
    }

    private fun ingestScorecard(
        fields: List<String>,
        idx: Map<String, Int>,
        seen: SeenKeys,
        out: MutableList<ScorecardRow>
    ): ScorecardRow? {
        val ticker = StreamCsvParser.field(fields, idx, "ticker") ?: return null
        val ts = parseTs(StreamCsvParser.field(fields, idx, "created_at_ms")) ?: 0L
        if (!seen.scorecards.add("${ticker}|$ts")) return null
        val row = ScorecardRow(
            ticker = ticker,
            series = StreamCsvParser.field(fields, idx, "series") ?: CryptoMarkets.inferSeries(ticker),
            outcome = StreamCsvParser.field(fields, idx, "outcome") ?: "",
            score = StreamCsvParser.field(fields, idx, "score")?.toIntOrNull(),
            brier = num(StreamCsvParser.field(fields, idx, "brier")),
            edgePp = num(StreamCsvParser.field(fields, idx, "edge_pp")),
            policyRoi = num(StreamCsvParser.field(fields, idx, "policy_roi")),
            createdAtMs = ts,
            note = StreamCsvParser.field(fields, idx, "note")
        )
        out.add(row)
        return row
    }

    private fun ingestTicket(
        fields: List<String>,
        idx: Map<String, Int>,
        seen: SeenKeys,
        out: MutableList<TicketAttemptRow>
    ): TicketAttemptRow? {
        val ticker = StreamCsvParser.field(fields, idx, "ticker") ?: return null
        val ts = parseTs(StreamCsvParser.field(fields, idx, "created_at_ms")) ?: 0L
        val coid = StreamCsvParser.field(fields, idx, "client_order_id")
        if (!seen.tickets.add(seen.ticketKey(coid, ticker, ts))) return null
        val row = TicketAttemptRow(
            ticker = ticker,
            side = StreamCsvParser.field(fields, idx, "side") ?: "YES",
            stakeUsd = num(StreamCsvParser.field(fields, idx, "stake_usd")) ?: 0.0,
            approved = StreamCsvParser.field(fields, idx, "approved") == "1",
            result = StreamCsvParser.field(fields, idx, "result") ?: "",
            createdAtMs = ts,
            clientOrderId = coid,
            note = StreamCsvParser.field(fields, idx, "note")
        )
        out.add(row)
        return row
    }

    private fun ingestFill(
        fields: List<String>,
        idx: Map<String, Int>,
        seen: SeenKeys,
        out: MutableList<ImportedFill>
    ): ImportedFill? {
        val ticker = StreamCsvParser.field(fields, idx, "ticker", "market_ticker", "market") ?: return null
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val ts = parseTs(
            StreamCsvParser.field(
                fields, idx,
                "created_time", "created_at", "created_at_ms", "timestamp", "time", "fill_time"
            )
        ) ?: return null
        val id = StreamCsvParser.field(fields, idx, "fill_id", "trade_id", "id", "order_id")
            ?: "$ticker|$ts|${StreamCsvParser.field(fields, idx, "count", "contracts")}"
        if (!seen.fills.add(id)) return null
        val count = num(StreamCsvParser.field(fields, idx, "count", "contracts", "quantity", "qty")) ?: return null
        val price = num(
            StreamCsvParser.field(fields, idx, "yes_price", "price", "yes_price_dollars", "fill_price")
        )
        val row = ImportedFill(
            id = id,
            ticker = ticker,
            side = (StreamCsvParser.field(fields, idx, "side", "yes_no") ?: "yes").uppercase(),
            action = StreamCsvParser.field(fields, idx, "action"),
            count = count,
            price = price,
            createdAtMs = ts,
            source = "kalshi-csv"
        )
        out.add(row)
        return row
    }

    private fun jsonSnapshot(o: JSONObject): ScoredSnapshotRow? {
        val ticker = o.optString("ticker").ifBlank { return null }
        val ts = o.optLong("created_at_ms", o.optLong("createdAtMs", 0L))
        return ScoredSnapshotRow(
            ticker = ticker,
            series = o.optString("series", CryptoMarkets.inferSeries(ticker)),
            side = o.optString("side", "YES"),
            edgePp = o.optDouble("edge_pp", o.optDouble("edgePp", 0.0)),
            fairPp = o.optDouble("fair_pp", o.optDouble("fairPp", 0.0)),
            marketPp = o.optDouble("market_pp", o.optDouble("marketPp", 0.0)),
            regime = o.optString("regime").ifBlank { null },
            uncertainty = o.optDouble("uncertainty").takeIf { o.has("uncertainty") },
            createdAtMs = ts,
            confidence = o.optDouble("confidence").takeIf { o.has("confidence") },
            tte = o.optString("tte").ifBlank { null },
            heavyMl = o.optBoolean("heavy_ml", o.optBoolean("heavyMl", false)),
            note = o.optString("note").ifBlank { null }
        )
    }

    private fun jsonAlert(o: JSONObject): AlertRow? {
        val ticker = o.optString("ticker").ifBlank { return null }
        val ts = o.optLong("created_at_ms", o.optLong("createdAtMs", 0L))
        return AlertRow(
            alertId = o.optString("alert_id", o.optString("alertId", "$ticker|$ts")),
            ticker = ticker,
            series = o.optString("series", CryptoMarkets.inferSeries(ticker)),
            side = o.optString("side", "YES"),
            edgePp = o.optDouble("edge_pp", o.optDouble("edgePp", 0.0)),
            fairPp = o.optDouble("fair_pp", o.optDouble("fairPp", 0.0)),
            marketPp = o.optDouble("market_pp", o.optDouble("marketPp", 0.0)),
            reason = o.optString("reason"),
            regime = o.optString("regime").ifBlank { null },
            createdAtMs = ts
        )
    }

    private fun jsonScorecard(o: JSONObject): ScorecardRow? {
        val ticker = o.optString("ticker").ifBlank { return null }
        return ScorecardRow(
            ticker = ticker,
            series = o.optString("series", CryptoMarkets.inferSeries(ticker)),
            outcome = o.optString("outcome"),
            score = if (o.has("score") && !o.isNull("score")) o.optInt("score") else null,
            brier = o.optDouble("brier").takeIf { o.has("brier") },
            edgePp = o.optDouble("edge_pp", o.optDouble("edgePp", Double.NaN)).takeIf { it.isFinite() },
            policyRoi = o.optDouble("policy_roi").takeIf { o.has("policy_roi") || o.has("policyRoi") },
            createdAtMs = o.optLong("created_at_ms", o.optLong("createdAtMs", 0L)),
            note = o.optString("note").ifBlank { null }
        )
    }

    private fun jsonTicket(o: JSONObject): TicketAttemptRow? {
        val ticker = o.optString("ticker").ifBlank { return null }
        return TicketAttemptRow(
            ticker = ticker,
            side = o.optString("side", "YES"),
            stakeUsd = o.optDouble("stake_usd", o.optDouble("stakeUsd", 0.0)),
            approved = o.optBoolean("approved"),
            result = o.optString("result"),
            createdAtMs = o.optLong("created_at_ms", o.optLong("createdAtMs", 0L)),
            clientOrderId = o.optString("client_order_id", o.optString("clientOrderId", "")).ifBlank { null },
            note = o.optString("note").ifBlank { null }
        )
    }

    private fun jsonFill(o: JSONObject): ImportedFill? {
        val ticker = o.optString("ticker").ifBlank { return null }
        val ts = o.optLong("created_at_ms", o.optLong("createdAtMs", 0L))
        val id = o.optString("id", o.optString("fill_id", "$ticker|$ts"))
        return ImportedFill(
            id = id,
            ticker = ticker,
            side = o.optString("side", "YES"),
            action = o.optString("action").ifBlank { null },
            count = o.optDouble("count", 0.0),
            price = o.optDouble("price").takeIf { o.has("price") },
            createdAtMs = ts,
            source = o.optString("source", "json")
        )
    }

    fun parseTs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        t.toLongOrNull()?.let { n ->
            return if (n < 1_000_000_000_000L) n * 1000L else n
        }
        t.toDoubleOrNull()?.let { d ->
            val n = d.toLong()
            return if (n < 1_000_000_000_000L) n * 1000L else n
        }
        return try {
            Instant.parse(t.replace(" ", "T").let { if (it.endsWith("Z") || it.contains("+")) it else it + "Z" })
                .toEpochMilli()
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun num(raw: String?): Double? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun minOfTs(a: Long?, b: Long): Long = a?.let { minOf(it, b) } ?: b
    private fun maxOfTs(a: Long?, b: Long): Long = a?.let { maxOf(it, b) } ?: b
}
