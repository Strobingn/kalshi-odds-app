package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.signal.ws.KalshiWsAuth
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Kalshi REST auth headers. Credentials come from
 * EncryptedSharedPreferences via [credentials]. PEM is never logged.
 *
 * Pre-sign: `timestamp + METHOD + path` where path is the full URL path
 * from the API root without query (e.g. `/trade-api/v2/portfolio/events/orders`).
 */
class KalshiAuthInterceptor(
    private val credentials: () -> Pair<String, String>
) : Interceptor {

    @Volatile
    private var cachedPem: String? = null

    @Volatile
    private var cachedKey: KalshiWsAuth.ParsedKey? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val (keyId, pem) = credentials()
        if (keyId.isBlank() || pem.isBlank()) {
            throw IllegalStateException("Kalshi API key missing — add Key ID + PEM in Settings")
        }
        val key = parsedKey(pem)
        val request = chain.request()
        val timestamp = System.currentTimeMillis().toString()
        val path = request.url.encodedPath
        val signature = KalshiWsAuth.signRest(key, timestamp, request.method, path)
        val signed = request.newBuilder()
            .header(KalshiWsAuth.HEADER_KEY, keyId.trim())
            .header(KalshiWsAuth.HEADER_TIMESTAMP, timestamp)
            .header(KalshiWsAuth.HEADER_SIGNATURE, signature)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        return chain.proceed(signed)
    }

    private fun parsedKey(pem: String): KalshiWsAuth.ParsedKey {
        val cached = cachedKey
        if (cached != null && cachedPem == pem) return cached
        val parsed = KalshiWsAuth.parsePrivateKey(pem)
        cachedPem = pem
        cachedKey = parsed
        return parsed
    }
}
