package com.dirk.kalshiodds.signal.ws

import com.dirk.kalshiodds.data.api.KalshiTradeClient
import com.dirk.kalshiodds.signal.config.PemNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

/**
 * Independent java.security RSASSA-PSS verifier for the documented
 * timestamp+METHOD+path message. Signed path must equal the HTTP path
 * (`/trade-api/v2/portfolio/events/orders`) with the query excluded.
 */
class KalshiRsaPssVerifierTest {

    @Test
    fun javaSecurityVerifierAcceptsPkcs1AndPkcs8Signatures() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pkcs8 = pem("PRIVATE KEY", pair.private.encoded)
        val ts = "1710000000000"
        val method = "POST"
        val path = KalshiTradeClient.V2_CREATE_PATH
        val withQuery = "$path?foo=1"
        assertEquals(path, withQuery.substringBefore('?'))
        assertEquals("/trade-api/v2/portfolio/events/orders", path)

        val parsed = KalshiWsAuth.parsePrivateKey(pkcs8)
        val sig = KalshiWsAuth.signRest(parsed, ts, method, withQuery)
        val message = ts + method + path
        assertTrue(verifyPss(pair.public, message, sig))

        val oneLine = pkcs8.replace("\n", "")
        val crlf = pkcs8.replace("\n", "\r\n")
        for (raw in listOf(oneLine, crlf, PemNormalizer.normalize(pkcs8))) {
            val again = KalshiWsAuth.signRest(KalshiWsAuth.parsePrivateKey(raw), ts, method, path)
            assertTrue(verifyPss(pair.public, message, again))
        }
    }

    @Test
    fun headerNamesMatchDocs() {
        assertEquals("KALSHI-ACCESS-KEY", KalshiWsAuth.HEADER_KEY)
        assertEquals("KALSHI-ACCESS-TIMESTAMP", KalshiWsAuth.HEADER_TIMESTAMP)
        assertEquals("KALSHI-ACCESS-SIGNATURE", KalshiWsAuth.HEADER_SIGNATURE)
    }

    private fun verifyPss(pub: java.security.PublicKey, message: String, b64: String): Boolean {
        val sig = Signature.getInstance("RSASSA-PSS")
        sig.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        sig.initVerify(pub)
        sig.update(message.toByteArray(Charsets.UTF_8))
        return sig.verify(Base64.getDecoder().decode(b64))
    }

    private fun pem(type: String, der: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(der)
        return buildString {
            append("-----BEGIN ").append(type).append("-----\n")
            b64.chunked(64).forEach { append(it).append('\n') }
            append("-----END ").append(type).append("-----\n")
        }
    }
}
