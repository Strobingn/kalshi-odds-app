package com.dirk.kalshiodds.signal.config

import com.dirk.kalshiodds.signal.ws.KalshiWsAuth
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PemNormalizerTest {

    @Test
    fun pkcs1CrlfOneLineAndSpacesAllParse() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pem = toPkcs1Pem(pair.private as RSAPrivateCrtKey)
        assertTrue(pem.contains("BEGIN RSA PRIVATE KEY"))

        val crlf = pem.replace("\n", "\r\n")
        val oneLine = pem.replace("\n", " ")
        val padded = "  \n$pem\n  "
        val missingNl = pem.replace("\n", "")

        for (variant in listOf(pem, crlf, oneLine, padded, missingNl)) {
            assertTrue(variant, PemNormalizer.looksLikePem(variant))
            val parsed = KalshiWsAuth.parsePrivateKey(variant)
            assertTrue(parsed is KalshiWsAuth.ParsedKey.Rsa)
        }
    }

    @Test
    fun pkcs8HeaderParses() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pkcs8 = toPem("PRIVATE KEY", pair.private.encoded)
        assertTrue(pkcs8.contains("BEGIN PRIVATE KEY"))
        assertTrue(PemNormalizer.looksLikePem(pkcs8.replace("\n", " ")))
        assertTrue(KalshiWsAuth.parsePrivateKey(pkcs8.replace("\n", " ")) is KalshiWsAuth.ParsedKey.Rsa)
    }

    @Test
    fun missingHeaderLinesStillParseAsPkcs1() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val pem = toPkcs1Pem(pair.private as RSAPrivateCrtKey)
        val body = pem.lines().filter { !it.contains("BEGIN") && !it.contains("END") && it.isNotBlank() }
            .joinToString("")
        assertFalse(body.contains("BEGIN"))
        assertTrue(PemNormalizer.looksLikePem(body))
        assertTrue(KalshiWsAuth.parsePrivateKey(body) is KalshiWsAuth.ParsedKey.Rsa)
    }

    @Test
    fun keyIdAloneIsNotPem() {
        assertFalse(PemNormalizer.looksLikePem("a952bcbe-ec3b-4b5b-b8f9-11dae589608c"))
        assertFalse(SecureCredentialStore.looksLikePem("a952bcbe-ec3b-4b5b-b8f9-11dae589608c"))
        assertEquals(
            CredentialWriteGuard.REJECT_KEY_ONLY,
            CredentialWriteGuard.rejectReason("a952bcbe-ec3b-4b5b-b8f9-11dae589608c", "")
        )
        assertEquals(
            CredentialWriteGuard.REJECT_PEM_ONLY,
            CredentialWriteGuard.rejectReason("", "-----BEGIN RSA PRIVATE KEY-----\n${"A".repeat(80)}\n-----END RSA PRIVATE KEY-----")
        )
    }

    private fun toPkcs1Pem(key: RSAPrivateCrtKey): String {
        val params = PrivateKeyFactory.createKey(key.encoded)
        val info = org.bouncycastle.crypto.util.PrivateKeyInfoFactory.createPrivateKeyInfo(params)
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
