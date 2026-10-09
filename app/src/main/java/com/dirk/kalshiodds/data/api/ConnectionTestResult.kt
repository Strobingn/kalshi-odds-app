package com.dirk.kalshiodds.data.api

/**
 * Result of Settings → Test connection (GET /portfolio/balance).
 * https://docs.kalshi.com/api-reference/portfolio/get-balance
 */
sealed class ConnectionTestResult {
    data class Ok(
        val cashUsd: Double,
        val host: String,
        val rawSummary: String
    ) : ConnectionTestResult()

    data class Fail(
        val reason: String,
        val httpCode: Int? = null,
        val rawBody: String? = null
    ) : ConnectionTestResult() {
        val display: String
            get() = buildString {
                append(reason)
                if (!rawBody.isNullOrBlank() && !reason.contains(rawBody.take(40))) {
                    append("\n")
                    append(rawBody)
                }
            }
    }
}
