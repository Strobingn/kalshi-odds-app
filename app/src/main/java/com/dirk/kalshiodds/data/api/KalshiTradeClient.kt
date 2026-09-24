package com.dirk.kalshiodds.data.api

import android.util.Log
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.KalshiErrorEnvelope
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * Authenticated create-order + cancel using **V2 only**.
 *
 * Evidence of the 0.3.4 410: [createLimit] used to POST
 * `/portfolio/events/orders` and, on HTTP 404, fall back to
 * `POST /portfolio/orders`. Kalshi now returns HTTP 410
 * `deprecated_v1_order_endpoint` on that legacy write. This client never
 * calls `/portfolio/orders`.
 *
 * Primary host: [KalshiApi.TRADE_BASE_URL] (`external-api.kalshi.com`).
 * Fallback host: [KalshiApi.BASE_URL] (elections) — still V2.
 *
 * Limit orders only — never market. Fail-soft with a clear message.
 * PEM / secrets are never written to logs.
 */
class KalshiTradeClient(
    private val primary: KalshiTradeApi,
    private val fallback: KalshiTradeApi? = null,
    private val credentials: () -> Pair<String, String>
) {
    constructor(
        api: KalshiTradeApi,
        credentials: () -> Pair<String, String>
    ) : this(primary = api, fallback = null, credentials = credentials)

    suspend fun createLimit(ticket: TradeTicket, clientOrderId: String): PlacedOrder {
        ensureKeys()
        val body = v2Body(ticket, clientOrderId)
        return try {
            val first = primary.createOrderV2(body)
            val chosen = chooseHost(first) { fallback?.createOrderV2(body) }
            mapV2(ticket, clientOrderId, chosen)
        } catch (e: Exception) {
            throw softFailure(e)
        }
    }

    suspend fun cancel(order: PlacedOrder): PlacedOrder {
        ensureKeys()
        val id = order.orderId ?: throw IllegalStateException("No order id to cancel")
        return try {
            val ticker = order.ticket.ticker
            val first = primary.cancelOrderV2(id, marketTicker = ticker, exchangeIndex = -1)
            val chosen = chooseHost(first) {
                fallback?.cancelOrderV2(id, marketTicker = ticker, exchangeIndex = -1)
            }
            if (!chosen.isSuccessful) throw httpFailure(chosen.code(), chosen.errorBody()?.string())
            val reduced = chosen.body()?.reducedBy
            order.copy(error = "cancelled" + (reduced?.let { " (−$it)" } ?: ""))
        } catch (e: Exception) {
            throw softFailure(e)
        }
    }

    private suspend fun <T> chooseHost(
        first: Response<T>,
        retry: suspend () -> Response<T>?
    ): Response<T> {
        if (first.isSuccessful || !shouldRetryOtherHost(first.code())) return first
        val second = retry() ?: return first
        return when {
            second.isSuccessful -> second
            // Keep a 4xx from the working host over a 404/410 miss on the other.
            first.code() in 400..499 && first.code() != 404 && first.code() != 410 -> first
            else -> second
        }
    }

    private fun shouldRetryOtherHost(code: Int): Boolean =
        code == 404 || code == 410 || code >= 500

    private fun v2Body(ticket: TradeTicket, clientOrderId: String): CreateOrderV2Request =
        CreateOrderV2Request(
            ticker = ticket.ticker,
            side = ticket.bookSide,
            count = String.format(Locale.US, "%.2f", ticket.contracts.toDouble()),
            price = String.format(Locale.US, "%.4f", ticket.yesLimitPrice),
            clientOrderId = clientOrderId
        )

    private fun mapV2(
        ticket: TradeTicket,
        clientOrderId: String,
        response: Response<CreateOrderV2Response>
    ): PlacedOrder {
        if (!response.isSuccessful) throw httpFailure(response.code(), response.errorBody()?.string())
        val body = response.body() ?: throw IllegalStateException("Empty create-order response")
        return PlacedOrder(
            ticket = ticket,
            clientOrderId = body.clientOrderId ?: clientOrderId,
            orderId = body.orderId,
            fillCount = body.fillCount.toDoubleOrZero(),
            remainingCount = body.remainingCount.toDoubleOrZero(),
            averageFillPrice = body.averageFillPrice.toDoubleOrNullSafe(),
            placedAtMs = body.tsMs ?: System.currentTimeMillis()
        )
    }

    private fun ensureKeys() {
        val (id, pem) = credentials()
        if (id.isBlank() || pem.isBlank()) {
            throw IllegalStateException("Kalshi API key missing — add Key ID + PEM in Settings")
        }
    }

    private fun httpFailure(code: Int, rawBody: String?): IllegalStateException {
        val parsed = parseError(rawBody)
        val deprecated = code == 410 ||
            parsed?.code.equals("deprecated_v1_order_endpoint", ignoreCase = true) ||
            parsed?.message?.contains("switch to the V2", ignoreCase = true) == true
        val hint = when {
            deprecated ->
                "Kalshi retired the v1 order API (HTTP $code). This build submits V2 POST /portfolio/events/orders only — tap Live Approve again."
            code == 401 -> "Unauthorized — check Key ID + PEM in Settings"
            code == 400 -> "Rejected — check price / size / market status"
            code == 403 -> "Forbidden — this API key may not trade"
            code == 404 -> "V2 create-order not found on Kalshi hosts — not falling back to deprecated v1 /portfolio/orders"
            code == 409 -> "Duplicate client_order_id — not re-sent"
            code == 429 -> "Rate limited — wait and Approve again"
            code == 503 -> "Kalshi unavailable — try again shortly"
            else -> "HTTP $code"
        }
        warn("trade API $code")
        val detail = listOfNotNull(parsed?.code, parsed?.message)
            .joinToString(" · ")
            .ifBlank {
                rawBody
                    ?.replace(Regex("(?i)BEGIN [A-Z ]*PRIVATE[A-Z ]*"), "[redacted]")
                    ?.take(160)
                    ?.trim()
                    .orEmpty()
            }
        val suffix = if (detail.isNotBlank() && !hint.contains(detail.take(24))) " — $detail" else ""
        return IllegalStateException("$hint$suffix")
    }

    private fun parseError(rawBody: String?): com.dirk.kalshiodds.data.dto.KalshiErrorBody? {
        if (rawBody.isNullOrBlank()) return null
        return runCatching { errorJson.decodeFromString(KalshiErrorEnvelope.serializer(), rawBody).error }
            .getOrNull()
            ?: runCatching {
                errorJson.decodeFromString(
                    com.dirk.kalshiodds.data.dto.KalshiErrorBody.serializer(),
                    rawBody
                )
            }.getOrNull()
    }

    private fun softFailure(e: Exception): Exception {
        if (e is IllegalStateException) return e
        warn("trade API failed (${e.javaClass.simpleName})")
        return IllegalStateException(
            e.message?.takeIf { !it.contains("PRIVATE", ignoreCase = true) }
                ?: "Trade request failed — check network and Settings keys"
        )
    }

    private fun warn(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    private fun String?.toDoubleOrZero(): Double = this.toDoubleOrNullSafe() ?: 0.0

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    companion object {
        private const val TAG = "DipHunterTrade"
        private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Documented V2 write path — never POST `/portfolio/orders`. */
        const val V2_CREATE_PATH = "/trade-api/v2/portfolio/events/orders"
        const val LEGACY_CREATE_PATH = "/trade-api/v2/portfolio/orders"
    }
}
