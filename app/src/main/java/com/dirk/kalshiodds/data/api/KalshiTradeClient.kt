package com.dirk.kalshiodds.data.api

import android.util.Log
import com.dirk.kalshiodds.data.dto.CreateOrderLegacyRequest
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TradeTicket
import java.util.Locale
import retrofit2.Response

/**
 * Minimal authenticated create-order + cancel.
 *
 * Verified against public Kalshi docs (2026): REST under
 * `https://…/trade-api/v2`. Primary write path is
 * `POST /portfolio/events/orders` (V2 bid/ask + fixed-point dollars).
 * Legacy `POST /portfolio/orders` is used only when V2 returns 404.
 *
 * Limit orders only — never market. Fail-soft with a clear message.
 * PEM / secrets are never written to logs.
 */
class KalshiTradeClient(
    private val api: KalshiTradeApi,
    private val credentials: () -> Pair<String, String>
) {
    suspend fun createLimit(ticket: TradeTicket, clientOrderId: String): PlacedOrder {
        ensureKeys()
        val v2 = CreateOrderV2Request(
            ticker = ticket.ticker,
            side = ticket.bookSide,
            count = String.format(Locale.US, "%.2f", ticket.contracts.toDouble()),
            price = String.format(Locale.US, "%.4f", ticket.yesLimitPrice),
            clientOrderId = clientOrderId
        )
        return try {
            val response = api.createOrderV2(v2)
            if (response.code() == 404) {
                createLegacy(ticket, clientOrderId)
            } else {
                mapV2(ticket, clientOrderId, response)
            }
        } catch (e: Exception) {
            throw softFailure(e)
        }
    }

    suspend fun cancel(order: PlacedOrder): PlacedOrder {
        ensureKeys()
        val id = order.orderId ?: throw IllegalStateException("No order id to cancel")
        return try {
            val response = api.cancelOrderV2(id, marketTicker = order.ticket.ticker, exchangeIndex = -1)
            if (response.code() == 404) {
                val legacy = api.cancelOrderLegacy(id)
                if (!legacy.isSuccessful) throw httpFailure(legacy.code(), legacy.errorBody()?.string())
                order.copy(error = "cancelled")
            } else {
                if (!response.isSuccessful) throw httpFailure(response.code(), response.errorBody()?.string())
                val reduced = response.body()?.reducedBy
                order.copy(error = "cancelled" + (reduced?.let { " (−$it)" } ?: ""))
            }
        } catch (e: Exception) {
            throw softFailure(e)
        }
    }

    private suspend fun createLegacy(ticket: TradeTicket, clientOrderId: String): PlacedOrder {
        val body = CreateOrderLegacyRequest(
            ticker = ticket.ticker,
            side = ticket.side.lowercase(Locale.US),
            action = "buy",
            count = ticket.contracts,
            yesPriceDollars = if (ticket.side.equals("YES", true)) {
                String.format(Locale.US, "%.4f", ticket.limitPrice)
            } else {
                null
            },
            noPriceDollars = if (ticket.side.equals("NO", true)) {
                String.format(Locale.US, "%.4f", ticket.limitPrice)
            } else {
                null
            },
            clientOrderId = clientOrderId
        )
        val response = api.createOrderLegacy(body)
        if (!response.isSuccessful) throw httpFailure(response.code(), response.errorBody()?.string())
        val order = response.body()?.order
        return PlacedOrder(
            ticket = ticket,
            clientOrderId = clientOrderId,
            orderId = order?.orderId,
            fillCount = order?.fillCountFp.toDoubleOrZero(),
            remainingCount = order?.remainingCountFp.toDoubleOrZero(),
            averageFillPrice = order?.yesPriceDollars.toDoubleOrNullSafe(),
            placedAtMs = System.currentTimeMillis()
        )
    }

    private fun mapV2(
        ticket: TradeTicket,
        clientOrderId: String,
        response: Response<com.dirk.kalshiodds.data.dto.CreateOrderV2Response>
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
        val hint = when (code) {
            401 -> "Unauthorized — check Key ID + PEM in Settings"
            400 -> "Rejected — check price / size / market status"
            403 -> "Forbidden — this API key may not trade"
            409 -> "Duplicate client_order_id — not re-sent"
            429 -> "Rate limited — wait and Approve again"
            503 -> "Kalshi unavailable — try again shortly"
            else -> "HTTP $code"
        }
        // Log status only. Never log PEM, signatures, or raw credential bodies.
        Log.w(TAG, "trade API $code")
        val detail = rawBody
            ?.replace(Regex("(?i)BEGIN [A-Z ]*PRIVATE[A-Z ]*"), "[redacted]")
            ?.take(160)
            ?.trim()
            .orEmpty()
        val suffix = if (detail.isNotBlank()) " — $detail" else ""
        return IllegalStateException("$hint$suffix")
    }

    private fun softFailure(e: Exception): Exception {
        if (e is IllegalStateException) return e
        Log.w(TAG, "trade API failed (${e.javaClass.simpleName})")
        return IllegalStateException(
            e.message?.takeIf { !it.contains("PRIVATE", ignoreCase = true) }
                ?: "Trade request failed — check network and Settings keys"
        )
    }

    private fun String?.toDoubleOrZero(): Double = this.toDoubleOrNullSafe() ?: 0.0

    private fun String?.toDoubleOrNullSafe(): Double? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    companion object {
        private const val TAG = "DipHunterTrade"
    }
}
