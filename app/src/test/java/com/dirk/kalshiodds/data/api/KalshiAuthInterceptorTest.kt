package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.signal.ws.KalshiWsAuth
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Independent JCA verifier for the documented Kalshi RSA-PSS parameters:
 * SHA-256, MGF1-SHA256, salt = digest length (32).
 * https://docs.kalshi.com/getting_started/api_keys
 */
class KalshiAuthInterceptorTest {

    @Test
    fun appSignatureVerifiesWithIndependentRsassaPss() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pem = toPkcs1Pem(pair.private as RSAPrivateCrtKey)
        val parsed = KalshiWsAuth.parsePrivateKey(pem)
        val ts = "1703123456789"
        val path = "/trade-api/v2/portfolio/events/orders"
        val sigB64 = KalshiWsAuth.signRest(parsed, ts, "POST", path)
        val message = (ts + "POST" + path).toByteArray(Charsets.UTF_8)
        assertTrue(verifyJca(pair.public, message, sigB64))
        assertEquals(KalshiWsAuth.HEADER_KEY, "KALSHI-ACCESS-KEY")
        assertEquals(KalshiWsAuth.HEADER_TIMESTAMP, "KALSHI-ACCESS-TIMESTAMP")
        assertEquals(KalshiWsAuth.HEADER_SIGNATURE, "KALSHI-ACCESS-SIGNATURE")
    }

    @Test
    fun interceptorSignsEncodedPathWithoutQueryMatchingDocs() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pem = toPkcs1Pem(pair.private as RSAPrivateCrtKey)
        val interceptor = KalshiAuthInterceptor { "key-id-1" to pem }
        val request = Request.Builder()
            .url("https://external-api.kalshi.com/trade-api/v2/portfolio/events/orders?limit=5")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        assertEquals("/trade-api/v2/portfolio/events/orders", request.url.encodedPath)

        var captured: Request? = null
        val chain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(req: Request): Response {
                captured = req
                return Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            override fun connection() = null
            override fun call() = error("unused")
            override fun connectTimeoutMillis() = 0
            override fun readTimeoutMillis() = 0
            override fun writeTimeoutMillis() = 0
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }
        interceptor.intercept(chain)
        val signed = captured ?: error("interceptor did not proceed")
        assertEquals("key-id-1", signed.header(KalshiWsAuth.HEADER_KEY))
        val ts = signed.header(KalshiWsAuth.HEADER_TIMESTAMP)!!
        val sig = signed.header(KalshiWsAuth.HEADER_SIGNATURE)!!
        assertTrue(ts.toLong() > 1_000_000_000_000L)
        val path = signed.url.encodedPath
        assertEquals("/trade-api/v2/portfolio/events/orders", path)
        val message = (ts + "POST" + path).toByteArray(Charsets.UTF_8)
        assertTrue(verifyJca(pair.public, message, sig))
        assertEquals(KalshiTradeClient.V2_CREATE_PATH, path)
    }

    @Test
    fun balancePathIsTradeApiV2() {
        val url = "https://api.elections.kalshi.com/trade-api/v2/portfolio/balance".toHttpUrl()
        assertEquals("/trade-api/v2/portfolio/balance", url.encodedPath)
    }

    private fun verifyJca(publicKey: PublicKey, message: ByteArray, sigB64: String): Boolean {
        val sig = Signature.getInstance("RSASSA-PSS")
        sig.setParameter(
            PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
        )
        sig.initVerify(publicKey)
        sig.update(message)
        return sig.verify(Base64.getDecoder().decode(sigB64))
    }

    private fun toPkcs1Pem(key: RSAPrivateCrtKey): String {
        val params = PrivateKeyFactory.createKey(key.encoded)
        val info = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(params)
        val rsa = org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(info.parsePrivateKey())
        val sw = StringWriter()
        JcaPEMWriter(sw).use { w -> w.writeObject(PemObject("RSA PRIVATE KEY", rsa.encoded)) }
        return sw.toString()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun provider() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
