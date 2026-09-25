package com.dirk.kalshiodds.signal.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PemNormalizerTest {

    @Test
    fun crlfOneLineAndWhitespaceNormalizeToPkcs1() {
        val body = "A".repeat(200)
        val crlf = "-----BEGIN RSA PRIVATE KEY-----\r\n$body\r\n-----END RSA PRIVATE KEY-----\r\n"
        val oneLine = "-----BEGIN RSA PRIVATE KEY-----$body-----END RSA PRIVATE KEY-----"
        val messy = "  -----BEGIN RSA PRIVATE KEY-----  \n  $body  \n -----END RSA PRIVATE KEY-----  "
        for (raw in listOf(crlf, oneLine, messy)) {
            val n = PemNormalizer.normalize(raw)
            assertTrue(n, n.startsWith(PemNormalizer.PKCS1_BEGIN))
            assertTrue(n, n.contains(PemNormalizer.PKCS1_END))
            assertTrue(PemNormalizer.looksLikePem(n))
        }
    }

    @Test
    fun pkcs8HeaderIsKept() {
        val body = "B".repeat(200)
        val n = PemNormalizer.normalize("-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----")
        assertTrue(n.startsWith(PemNormalizer.PKCS8_BEGIN))
        assertTrue(PemNormalizer.looksLikePem(n))
    }

    @Test
    fun keyIdOnlyIsNotPem() {
        assertTrue(PemNormalizer.onlyKeyIdSaved("abc-key-id", ""))
        assertFalse(PemNormalizer.looksLikePem("abc-key-id"))
        assertTrue(PemNormalizer.MISSING_PEM.contains("PEM"))
    }
}
