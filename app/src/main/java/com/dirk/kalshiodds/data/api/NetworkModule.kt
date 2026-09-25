package com.dirk.kalshiodds.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val okHttp: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    val api: KalshiApi by lazy { publicClient(KalshiApi.BASE_URL) }

    /** Unauthenticated demo market data — https://demo-api.kalshi.co/trade-api/v2 */
    val demoApi: KalshiApi by lazy { publicClient(KalshiApi.DEMO_SHARED_BASE_URL) }

    fun publicApi(demo: Boolean): KalshiApi = if (demo) demoApi else api

    private fun publicClient(baseUrl: String): KalshiApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KalshiApi::class.java)

    /**
     * Authenticated trade client. [credentials] returns (keyId, pem) from
     * EncryptedSharedPreferences. PEM is never logged (BASIC logging only).
     */
    fun tradeApi(
        credentials: () -> Pair<String, String>,
        baseUrl: String = KalshiApi.TRADE_BASE_URL
    ): KalshiTradeApi {
        val client = okHttp.newBuilder()
            .addInterceptor(KalshiAuthInterceptor(credentials))
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KalshiTradeApi::class.java)
    }

    const val USER_AGENT = "DipHunter/0.3.8 (Android; Dirk Diggler)"
}
