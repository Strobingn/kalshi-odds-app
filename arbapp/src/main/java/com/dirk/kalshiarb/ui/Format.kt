package com.dirk.kalshiarb.ui

import java.util.Locale
import kotlin.math.abs

object Format {

    fun cents(c: Long): String {
        val sign = if (c < 0) "-" else ""
        val a = abs(c)
        return String.format(Locale.US, "%s$%d.%02d", sign, a / 100, a % 100)
    }

    /** E4 price as cents, e.g. 4550 → "45.5¢". */
    fun priceE4(p: Double): String {
        val c = p / 100.0
        return if (abs(c - Math.round(c)) < 1e-6) {
            String.format(Locale.US, "%d¢", Math.round(c))
        } else {
            String.format(Locale.US, "%.2f¢", c).replace(Regex("0+¢$"), "¢")
        }
    }

    fun feeE4(f: Long): String = String.format(Locale.US, "$%.4f", f / 10_000.0).let {
        if (it.endsWith("00")) it.dropLast(2) else it
    }

    fun pct(p: Double): String = String.format(Locale.US, "%.2f%%", p)

    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        }
    }
}
