package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.ws.KalshiWsAuth
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.StringWriter
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey

class KalshiWsAuthTest {

    @Test
    fun rsaPssSignsWsHandshakeAndVerifies() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pem = toPkcs1Pem(pair.private as RSAPrivateCrtKey)
        val parsed = KalshiWsAuth.parsePrivateKey(pem)
        assertTrue(parsed is KalshiWsAuth.ParsedKey.Rsa)
        val ts = "1703123456789"
        val sigB64 = KalshiWsAuth.signWsHandshake(parsed, ts)
        val sig = Base64.getDecoder().decode(sigB64)
        val verifier = PSSSigner(RSAEngine(), SHA256Digest(), SHA256Digest(), 32)
        val pub = org.bouncycastle.crypto.params.RSAKeyParameters(
            false,
            (pair.public as java.security.interfaces.RSAPublicKey).modulus,
            (pair.public as java.security.interfaces.RSAPublicKey).publicExponent
        )
        verifier.init(false, pub)
        val msg = (ts + KalshiWsAuth.WS_METHOD + KalshiWsAuth.WS_PATH).toByteArray(Charsets.UTF_8)
        verifier.update(msg, 0, msg.size)
        assertTrue(verifier.verifySignature(sig))
        val headers = KalshiWsAuth.handshakeHeaders("key-id-1", pem, 1703123456789L)
        assertEquals("key-id-1", headers.keyId)
        assertEquals(ts, headers.timestampMs)
        assertTrue(headers.signature.isNotBlank())
    }

    @Test
    fun ed25519SignsAndVerifies() {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters
        val pkcs8 = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(priv).encoded
        val pem = toPem("PRIVATE KEY", pkcs8)
        val parsed = KalshiWsAuth.parsePrivateKey(pem)
        assertTrue(parsed is KalshiWsAuth.ParsedKey.Ed25519)
        val msg = "1703123456789GET/trade-api/ws/v2"
        val sig = Base64.getDecoder().decode(KalshiWsAuth.sign(parsed, msg))
        val v = Ed25519Signer()
        v.init(false, pub)
        val bytes = msg.toByteArray()
        v.update(bytes, 0, bytes.size)
        assertTrue(v.verifySignature(sig))
    }

    @Test
    fun signRestUsesMethodAndPathWithoutQuery() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pem = toPkcs1Pem(pair.private as RSAPrivateCrtKey)
        val parsed = KalshiWsAuth.parsePrivateKey(pem)
        val ts = "1703123456789"
        val path = "/trade-api/v2/portfolio/events/orders"
        val a = KalshiWsAuth.signRest(parsed, ts, "POST", path)
        val b = KalshiWsAuth.signRest(parsed, ts, "POST", "$path?market_ticker=X")
        assertTrue(a.isNotBlank())
        assertTrue(b.isNotBlank())
        val pub = org.bouncycastle.crypto.params.RSAKeyParameters(
            false,
            (pair.public as java.security.interfaces.RSAPublicKey).modulus,
            (pair.public as java.security.interfaces.RSAPublicKey).publicExponent
        )
        val msg = (ts + "POST" + path).toByteArray(Charsets.UTF_8)
        fun verify(sigB64: String): Boolean {
            val verifier = PSSSigner(RSAEngine(), SHA256Digest(), SHA256Digest(), 32)
            verifier.init(false, pub)
            verifier.update(msg, 0, msg.size)
            return verifier.verifySignature(Base64.getDecoder().decode(sigB64))
        }
        // RSA-PSS is non-deterministic; both must verify the query-stripped path.
        assertTrue(verify(a))
        assertTrue(verify(b))
    }

    @Test
    fun wsUrlsIncludePrimaryAndElections() {
        assertEquals("wss://external-api-ws.kalshi.com/trade-api/ws/v2", KalshiWsAuth.PRIMARY_WS_URL)
        assertTrue(KalshiWsAuth.WS_URLS.contains(KalshiWsAuth.ELECTIONS_WS_URL))
    }

    private fun toPkcs1Pem(key: RSAPrivateCrtKey): String {
        val params = PrivateKeyFactory.createKey(key.encoded)
        val info = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(params)
        // PKCS#1 RSA PRIVATE KEY
        val rsa = org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(info.parsePrivateKey())
        return toPem("RSA PRIVATE KEY", rsa.encoded)
    }

    private fun toPem(type: String, der: ByteArray): String {
        val sw = StringWriter()
        JcaPEMWriter(sw).use { w -> w.writeObject(PemObject(type, der)) }
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
