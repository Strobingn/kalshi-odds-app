package com.dirk.kalshiodds.data.api

import android.util.Log
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.KalshiErrorEnvelope
import com.dirk.kalshiodds.data.dto.MarketPositionDto
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
    private val demoPrimary: KalshiTradeApi? = null,
    private val demoFallback: KalshiTradeApi? = null,
    private val credentials: () -> Pair<String, String>,
    private val useDemo: () -> Boolean = { false }
) {
    constructor(
        api: KalshiTradeApi,
        credentials: () -> Pair<String, String>
    ) : this(primary = api, fallback = null, credentials = credentials)

    private fun activePrimary(): KalshiTradeApi =
        if (useDemo()) demoPrimary ?: primary else primary

    private fun activeFallback(): KalshiTradeApi? =
        if (useDemo()) demoFallback ?: fallback else fallback

    suspend fun createLimit(ticket: TradeTicket, clientOrderId: String): PlacedOrder {
        if (!ticket.canApprove) {
            throw IllegalStateException(ticket.blockedReason ?: "Market closed")
        }
        val sized = enforceLiveCap(ticket)
        ensureKeys()
        val body = v2Body(sized, clientOrderId)
        return try {
            val first = activePrimary().createOrderV2(body)
            val chosen = chooseHost(first) { activeFallback()?.createOrderV2(body) }
            mapV2(sized, clientOrderId, chosen)
        } catch (e: Exception) {
            throw softFailure(e)
        }
    }

    suspend fun cancel(order: PlacedOrder): PlacedOrder {
        ensureKeys()
        val id = order.orderId ?: throw IllegalStateException("No order id to cancel")
        return try {
            val ticker = order.ticket.ticker
            val first = activePrimary().cancelOrderV2(id, marketTicker = ticker, exchangeIndex = -1)
            val chosen = chooseHost(first) {
                activeFallback()?.cancelOrderV2(id, marketTicker = ticker, exchangeIndex = -1)
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

    /**
     * Available cash for Live Approve sizing. Never logs the body.
     * Returns null if the key cannot read `portfolio/balance`.
     */
    suspend fun getCashUsd(): Double? = getBalanceResult().cashUsd

    /**
     * Settings "Test connection" — GET /portfolio/balance.
     * Returns the Kalshi body verbatim on 4xx/5xx.
     */
    suspend fun getBalanceResult(): BalanceProbe {
        val (id, pem) = credentials()
        if (id.isBlank() && pem.isBlank()) {
            return BalanceProbe(false, null, "Add Kalshi API Key ID + PEM in Settings")
        }
        if (com.dirk.kalshiodds.signal.config.PemNormalizer.onlyKeyIdSaved(id, pem)) {
            return BalanceProbe(false, null, com.dirk.kalshiodds.signal.trade.LiveOrderGates.PEM_ONLY_KEY_ID)
        }
        return try {
            val first = activePrimary().getBalance()
            val chosen = chooseHost(first) { activeFallback()?.getBalance() }
            if (!chosen.isSuccessful) {
                val raw = chosen.errorBody()?.string().orEmpty()
                return BalanceProbe(false, null, verbatimHttp(chosen.code(), raw))
            }
            val cash = chosen.body()?.cashUsd()
            BalanceProbe(
                ok = cash != null,
                cashUsd = cash,
                detail = if (cash != null) {
                    String.format(java.util.Locale.US, "GET /portfolio/balance · cash $%.2f", cash)
                } else {
                    "GET /portfolio/balance succeeded but cash was missing"
                }
            )
        } catch (e: Exception) {
            BalanceProbe(false, null, e.message ?: "Balance request failed")
        }
    }

    suspend fun listMarketPositions(): List<MarketPositionDto> {
        ensureKeys()
        return try {
            val first = activePrimary().getPositions(countFilter = "position", limit = 200)
            val chosen = chooseHost(first) { activeFallback()?.getPositions(countFilter = "position", limit = 200) }
            if (!chosen.isSuccessful) throw httpFailure(chosen.code(), chosen.errorBody()?.string())
            chosen.body()?.marketPositions.orEmpty()
        } catch (e: Exception) {
            throw softFailure(e)
        }
    }

    private fun v2Body(ticket: TradeTicket, clientOrderId: String): CreateOrderV2Request =
        CreateOrderV2Request(
            ticker = ticket.ticker,
            side = ticket.bookSide,
            count = String.format(Locale.US, "%.2f", ticket.contracts.toDouble()),
            price = com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(ticket.yesLimitPrice)
                ?: String.format(Locale.US, "%.4f", ticket.yesLimitPrice),
            clientOrderId = clientOrderId,
            reduceOnly = ticket.reduceOnly || ticket.isSell
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

    private fun enforceLiveCap(ticket: TradeTicket): TradeTicket {
        if (ticket.isSell) return ticket
        val clip = com.dirk.kalshiodds.signal.trade.LiveOrderSizer.enforce(ticket)
        if (!clip.ok) {
            throw IllegalStateException(clip.refusedReason ?: "Cannot size a live order under the $5 all-in cap")
        }
        if (clip.allInUsd > com.dirk.kalshiodds.signal.trade.LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-9) {
            throw IllegalStateException(
                String.format(
                    java.util.Locale.US,
                    "Live order all-in $%.2f exceeds the $%.2f cap",
                    clip.allInUsd,
                    com.dirk.kalshiodds.signal.trade.LiveOrderSizer.LIVE_ALL_IN_CAP_USD
                )
            )
        }
        val yesLimit = if (ticket.side.equals("NO", true)) {
            com.dirk.kalshiodds.domain.KalshiPrice.clipLimit(1.0 - clip.price)
        } else {
            com.dirk.kalshiodds.domain.KalshiPrice.clipLimit(clip.price)
        }
        return ticket.copy(
            contracts = clip.count,
            limitPrice = clip.price,
            yesLimitPrice = yesLimit,
            stakeUsd = clip.allInUsd,
            estimatedFillUsd = clip.allInUsd,
            estimatedAvgFill = clip.price,
            feeUsd = clip.feeUsd,
            allInUsd = clip.allInUsd,
            profitIfWinUsd = clip.profitIfWinUsd,
            maxPayoutUsd = clip.count * com.dirk.kalshiodds.signal.config.SignalConstants.CONTRACT_SETTLEMENT_USD
        )
    }

    private fun ensureKeys() {
        val (id, pem) = credentials()
        if (com.dirk.kalshiodds.signal.config.PemNormalizer.onlyKeyIdSaved(id, pem)) {
            throw IllegalStateException(com.dirk.kalshiodds.signal.trade.LiveOrderGates.PEM_ONLY_KEY_ID)
        }
        if (id.isBlank() || pem.isBlank()) {
            throw IllegalStateException("Kalshi API key missing — add Key ID + PEM in Settings")
        }
        if (!com.dirk.kalshiodds.signal.config.PemNormalizer.looksLikePem(pem)) {
            throw IllegalStateException(com.dirk.kalshiodds.signal.trade.LiveOrderGates.PEM_ONLY_KEY_ID)
        }
    }

    private fun httpFailure(code: Int, rawBody: String?): IllegalStateException {
        warn("trade API $code")
        return IllegalStateException(verbatimHttp(code, rawBody))
    }

    companion object {
        private const val TAG = "DipHunterTrade"
        private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Documented V2 write path — never POST `/portfolio/orders`. */
        const val V2_CREATE_PATH = "/trade-api/v2/portfolio/events/orders"
        const val LEGACY_CREATE_PATH = "/trade-api/v2/portfolio/orders"

        fun verbatimHttp(code: Int, rawBody: String?): String {
            val body = rawBody
                ?.replace(Regex("(?i)-----BEGIN[\\s\\S]+?-----END[\\s\\S]+?-----"), "[redacted PEM]")
                ?.replace(Regex("(?i)BEGIN [A-Z ]*PRIVATE[A-Z ]*"), "[redacted]")
                ?.trim()
                .orEmpty()
            return if (body.isBlank()) "HTTP $code" else "HTTP $code\n$body"
        }
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
}

data class BalanceProbe(
    val ok: Boolean,
    val cashUsd: Double?,
    val detail: String
)
