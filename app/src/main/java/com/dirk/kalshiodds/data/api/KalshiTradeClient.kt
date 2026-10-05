package com.dirk.kalshiodds.data.api

import android.util.Log
import com.dirk.kalshiodds.data.dto.CreateOrderV2Request
import com.dirk.kalshiodds.data.dto.CreateOrderV2Response
import com.dirk.kalshiodds.data.dto.KalshiErrorEnvelope
import com.dirk.kalshiodds.data.dto.MarketPositionDto
import com.dirk.kalshiodds.data.dto.PortfolioOrderDto
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.signal.trade.PlacedOrder
import com.dirk.kalshiodds.signal.trade.TicketKind
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
 *
 * Live buys are clipped at the $10 all-in cap ([LiveOrderSizer.enforce])
 * immediately before the V2 body is built, so a leftover $50 win-target
 * cannot resize a live order above $10.
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

    /**
     * Orders already accepted under [clientOrderId]. Used before an Approve
     * retry so a timeout cannot create a second real order.
     *
     * Side, action, price, count, and ticker come from the Kalshi order.
     * The lookup walks [MAX_ORDER_LOOKUP_PAGES] of the orders cursor and,
     * when [ticker] is set, asks Kalshi to filter to that market.
     */
    suspend fun findByClientOrderId(clientOrderId: String, ticker: String? = null): PlacedOrder? {
        if (clientOrderId.isBlank()) return null
        ensureKeys()
        return try {
            val filter = ticker?.takeIf { it.isNotBlank() }
            var cursor: String? = null
            repeat(MAX_ORDER_LOOKUP_PAGES) {
                val first = activePrimary().getOrders(limit = 100, cursor = cursor, ticker = filter)
                val chosen = chooseHost(first) {
                    activeFallback()?.getOrders(limit = 100, cursor = cursor, ticker = filter)
                }
                if (!chosen.isSuccessful) {
                    if (chosen.code() == 429) {
                        throw httpFailure(chosen.code(), chosen.errorBody()?.string())
                    }
                    return null
                }
                val body = chosen.body()
                val hit = body?.orders.orEmpty().firstOrNull { it.clientOrderId == clientOrderId }
                if (hit != null) return placedFromExisting(hit, clientOrderId)
                val next = body?.cursor?.takeIf { it.isNotBlank() && it != cursor } ?: return null
                cursor = next
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun placedFromExisting(hit: PortfolioOrderDto, clientOrderId: String): PlacedOrder {
        val outcome = when (hit.side?.trim()?.uppercase()) {
            "NO", "DOWN" -> "NO"
            else -> "YES"
        }
        val isSell = hit.action?.trim()?.equals("sell", ignoreCase = true) == true
        val yesPx = hit.yesPriceDollars.toDoubleOrNullSafe()
        val noPx = hit.noPriceDollars.toDoubleOrNullSafe()
            ?: yesPx?.let { (1.0 - it).takeIf { px -> px > 0.0 } }
        val limit = when (outcome) {
            "NO" -> noPx ?: yesPx ?: 0.0
            else -> yesPx ?: noPx?.let { 1.0 - it } ?: 0.0
        }
        val yesLimit = when (outcome) {
            "NO" -> yesPx ?: (1.0 - limit)
            else -> yesPx ?: limit
        }.let { KalshiPrice.clipLimit(it.coerceIn(0.0, 1.0)) }
        val contracts = contractsOf(hit)
        val stake = (contracts * limit).coerceAtLeast(0.0)
        val bookSide = when {
            isSell && outcome == "YES" -> "ask"
            isSell -> "bid"
            outcome == "YES" -> "bid"
            else -> "ask"
        }
        val kind = if (isSell) TicketKind.SELL else TicketKind.MANUAL
        return PlacedOrder(
            ticket = TradeTicket(
                id = clientOrderId,
                ticker = hit.ticker.orEmpty(),
                side = outcome,
                bookSide = bookSide,
                stakeUsd = stake,
                limitPrice = limit,
                yesLimitPrice = yesLimit,
                contracts = contracts,
                estimatedFillUsd = stake,
                maxPayoutUsd = if (isSell) stake else contracts * SignalConstants.CONTRACT_SETTLEMENT_USD,
                estimatedAvgFill = limit,
                sizingNote = if (isSell) "existing sell" else "existing order",
                kind = kind,
                reduceOnly = isSell,
                clientOrderId = clientOrderId
            ),
            clientOrderId = clientOrderId,
            orderId = hit.orderId,
            fillCount = (hit.fillCountFp ?: hit.fillCount).toDoubleOrNullSafe() ?: 0.0,
            remainingCount = (hit.remainingCountFp ?: hit.remainingCount).toDoubleOrNullSafe() ?: 0.0,
            averageFillPrice = limit.takeIf { it > 0.0 },
            placedAtMs = System.currentTimeMillis()
        )
    }

    private fun contractsOf(hit: PortfolioOrderDto): Int {
        val fill = (hit.fillCountFp ?: hit.fillCount).toDoubleOrNullSafe() ?: 0.0
        val remaining = (hit.remainingCountFp ?: hit.remainingCount).toDoubleOrNullSafe() ?: 0.0
        val total = hit.initialCountFp.toDoubleOrNullSafe()
            ?: hit.countFp.toDoubleOrNullSafe()
            ?: (fill + remaining)
        return kotlin.math.round(total).toInt().coerceAtLeast(0)
    }

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
            if (chosen.code() == 409) {
                findByClientOrderId(clientOrderId, sized.ticker)?.let { return it }
            }
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
    suspend fun getCashUsd(): Double? {
        return when (val r = testConnection()) {
            is ConnectionTestResult.Ok -> r.cashUsd
            is ConnectionTestResult.Fail -> null
        }
    }

    /**
     * Settings → Test connection. GET /portfolio/balance with the stored key.
     * https://docs.kalshi.com/api-reference/portfolio/get-balance
     * https://docs.kalshi.com/getting_started/quick_start_authenticated_requests
     */
    suspend fun testConnection(): ConnectionTestResult {
        val (id, pem) = credentials()
        if (id.isBlank() && pem.isBlank()) {
            return ConnectionTestResult.Fail("No Key ID or PEM stored — paste both in Settings")
        }
        if (id.isBlank()) {
            return ConnectionTestResult.Fail(com.dirk.kalshiodds.signal.config.CredentialWriteGuard.REJECT_PEM_ONLY)
        }
        if (pem.isBlank() || !com.dirk.kalshiodds.signal.config.PemNormalizer.looksLikePem(pem)) {
            return ConnectionTestResult.Fail(com.dirk.kalshiodds.signal.config.CredentialWriteGuard.REJECT_KEY_ONLY)
        }
        val parseErr = runCatching {
            com.dirk.kalshiodds.signal.ws.KalshiWsAuth.parsePrivateKey(pem)
        }.exceptionOrNull()
        if (parseErr != null) {
            return ConnectionTestResult.Fail(
                "PEM parse failed — ${parseErr.message ?: "unsupported key"}. " +
                    "Kalshi RSA keys use BEGIN RSA PRIVATE KEY; openssl/Ed25519 use BEGIN PRIVATE KEY."
            )
        }
        val host = if (useDemo()) {
            KalshiApi.DEMO_TRADE_BASE_URL
        } else {
            KalshiApi.TRADE_BASE_URL
        }
        return try {
            val first = activePrimary().getBalance()
            val chosen = chooseHost(first) { activeFallback()?.getBalance() }
            if (!chosen.isSuccessful) {
                val raw = chosen.errorBody()?.string()
                return ConnectionTestResult.Fail(
                    reason = balanceFailure(chosen.code(), raw),
                    httpCode = chosen.code(),
                    rawBody = raw
                )
            }
            val cash = chosen.body()?.cashUsd()
                ?: return ConnectionTestResult.Fail("Balance response had no cash field")
            ConnectionTestResult.Ok(
                cashUsd = cash,
                host = host,
                rawSummary = String.format(
                    java.util.Locale.US,
                    "GET /portfolio/balance ok · $%.2f available · %s",
                    cash,
                    host.trimEnd('/')
                )
            )
        } catch (e: Exception) {
            ConnectionTestResult.Fail(
                e.message?.takeIf { !it.contains("PRIVATE", ignoreCase = true) }
                    ?: "Trade request failed — check network and Settings keys"
            )
        }
    }

    private fun balanceFailure(code: Int, rawBody: String?): String {
        val parsed = parseError(rawBody)
        val codeName = parsed?.code.orEmpty()
        val hint = when {
            code == 401 && codeName.equals("INCORRECT_API_KEY_SIGNATURE", ignoreCase = true) ->
                "401 INCORRECT_API_KEY_SIGNATURE — Key ID / PEM mismatch, or phone clock skew (timestamp must be Unix ms). See https://docs.kalshi.com/getting_started/api_keys"
            code == 401 ->
                "401 Unauthorized — check Key ID + PEM, signing path /trade-api/v2/portfolio/balance, and that the phone clock is correct"
            else -> "HTTP $code"
        }
        val detail = listOfNotNull(parsed?.code, parsed?.message, parsed?.details)
            .joinToString(" · ")
            .ifBlank { rawBody?.trim().orEmpty() }
        return if (detail.isNotBlank()) "$hint — $detail" else hint
    }

    suspend fun listRestingOrders(): List<com.dirk.kalshiodds.signal.trade.RestingOrder> {
        ensureKeys()
        return try {
            val first = activePrimary().getOrders(limit = 50)
            val chosen = chooseHost(first) { activeFallback()?.getOrders(limit = 50) }
            if (!chosen.isSuccessful) return emptyList()
            chosen.body()?.orders.orEmpty().mapNotNull { dto ->
                val status = dto.status?.trim()?.lowercase().orEmpty()
                val remaining = (dto.remainingCountFp ?: dto.remainingCount).toDoubleOrNullSafe() ?: 0.0
                val filled = (dto.fillCountFp ?: dto.fillCount).toDoubleOrNullSafe() ?: 0.0
                val open = status == "resting" || status == "pending" ||
                    (remaining > 0.0 && status != "canceled" && status != "cancelled" && status != "executed")
                if (!open) return@mapNotNull null
                val yes = dto.yesPriceDollars.toDoubleOrNullSafe()
                val no = dto.noPriceDollars.toDoubleOrNullSafe()
                val side = when (dto.side?.trim()?.uppercase()) {
                    "NO", "DOWN" -> "NO"
                    else -> "YES"
                }
                com.dirk.kalshiodds.signal.trade.RestingOrder(
                    orderId = dto.orderId.orEmpty(),
                    ticker = dto.ticker.orEmpty(),
                    side = side,
                    remaining = remaining,
                    filled = filled,
                    price = if (side == "NO") no ?: yes else yes ?: no,
                    status = status.ifBlank { "resting" }
                )
            }
        } catch (_: Exception) {
            emptyList()
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

    private fun v2Body(ticket: TradeTicket, clientOrderId: String): CreateOrderV2Request {
        val reduceOnly = ticket.reduceOnly || ticket.isSell
        // Official Create Order V2: reduce_only is rejected unless TIF is IoC.
        // https://docs.kalshi.com/api-reference/orders/create-order-v2
        val timeInForce = if (reduceOnly) {
            CreateOrderV2Request.TIME_IN_FORCE_IOC
        } else {
            CreateOrderV2Request.TIME_IN_FORCE_GTC
        }
        return CreateOrderV2Request(
            ticker = ticket.ticker,
            side = ticket.bookSide,
            count = String.format(Locale.US, "%.2f", ticket.contracts.toDouble()),
            price = com.dirk.kalshiodds.domain.KalshiPrice.toWireDollars(ticket.yesLimitPrice)
                ?: String.format(Locale.US, "%.4f", ticket.yesLimitPrice),
            timeInForce = timeInForce,
            clientOrderId = clientOrderId,
            postOnly = ticket.postOnly,
            reduceOnly = reduceOnly
        )
    }

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
        if (ticket.kellyAutopilot) {
            if (ticket.contracts < 1 || com.dirk.kalshiodds.domain.KalshiPrice.usable(ticket.limitPrice) == null) {
                throw IllegalStateException("Autopilot order has no Kelly size")
            }
            return ticket
        }
        val feeRate = if (ticket.postOnly || ticket.kind == com.dirk.kalshiodds.signal.trade.TicketKind.D3) {
            0.0
        } else {
            com.dirk.kalshiodds.signal.config.SignalConstants.DEFAULT_FEE_RATE
        }
        val clip = LiveOrderSizer.enforce(ticket, feeRate = feeRate)
        if (!clip.ok) {
            throw IllegalStateException(clip.refusedReason ?: "Cannot size a live order under the $10 all-in cap")
        }
        if (clip.allInUsd > LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-9) {
            throw IllegalStateException(
                String.format(
                    Locale.US,
                    "Live order all-in $%.2f exceeds the $%.2f cap",
                    clip.allInUsd,
                    LiveOrderSizer.LIVE_ALL_IN_CAP_USD
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
        return IllegalStateException(verbatimHttp(code, rawBody, hint))
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
        /** Newest-100 was not enough; stop after this many order pages. */
        const val MAX_ORDER_LOOKUP_PAGES = 5
        private val errorJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Documented V2 write path — never POST `/portfolio/orders`. */
        const val V2_CREATE_PATH = "/trade-api/v2/portfolio/events/orders"
        const val LEGACY_CREATE_PATH = "/trade-api/v2/portfolio/orders"

        fun verbatimHttp(code: Int, rawBody: String?, hint: String? = null): String {
            val body = rawBody
                ?.replace(Regex("(?is)-----BEGIN[^-]*PRIVATE[^-]*-----.*?-----END[^-]*PRIVATE[^-]*-----"), "[redacted-pem]")
                ?.replace(Regex("(?i)BEGIN [A-Z ]*PRIVATE[A-Z ]*"), "[redacted]")
                ?.trim()
                .orEmpty()
            val prefix = hint?.takeIf { it.isNotBlank() } ?: "HTTP $code"
            val extra = when {
                code == 404 && prefix.contains("not falling back").not() ->
                    " — not falling back to deprecated v1 /portfolio/orders"
                code == 410 && prefix.contains("V2").not() ->
                    " — this build submits V2 POST /portfolio/events/orders only"
                else -> ""
            }
            return if (body.isBlank()) "$prefix$extra" else "$prefix\n$body$extra"
        }
    }
}
