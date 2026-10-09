package com.dirk.kalshiodds.data.importing

import java.io.BufferedReader
import java.io.Reader

/**
 * Line-at-a-time CSV. Does not buffer the whole file.
 * Supports quoted fields with commas and escaped quotes.
 */
object StreamCsvParser {
    data class Row(val lineNumber: Int, val fields: List<String>)

    fun forEachRow(reader: Reader, onRow: (Row) -> Boolean) {
        val buf = if (reader is BufferedReader) reader else BufferedReader(reader, 8 * 1024)
        var n = 0
        while (true) {
            val line = buf.readLine() ?: break
            n += 1
            if (line.isBlank()) continue
            val fields = parseLine(line)
            if (!onRow(Row(n, fields))) break
        }
    }

    fun parseLine(line: String): List<String> {
        val out = ArrayList<String>(16)
        val sb = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i += 1
                    } else {
                        inQuotes = false
                    }
                }
                !inQuotes && c == '"' -> inQuotes = true
                !inQuotes && (c == ',' || c == '\t') -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i += 1
        }
        out.add(sb.toString())
        return out
    }

    fun headerIndex(header: List<String>): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        header.forEachIndexed { idx, raw ->
            val key = normalizeHeader(raw)
            if (key.isNotEmpty() && key !in map) map[key] = idx
        }
        return map
    }

    fun normalizeHeader(raw: String): String =
        raw.trim().lowercase()
            .replace(' ', '_')
            .replace('-', '_')
            .replace('/', '_')
            .replace("\"", "")

    fun field(row: List<String>, idx: Map<String, Int>, vararg aliases: String): String? {
        for (a in aliases) {
            val i = idx[normalizeHeader(a)] ?: continue
            if (i in row.indices) {
                val v = row[i].trim()
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }
}
