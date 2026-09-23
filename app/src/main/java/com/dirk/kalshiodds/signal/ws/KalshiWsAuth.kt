package com.dirk.kalshiodds.signal.ws

import java.io.StringReader
import java.security.Security
import java.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.signers.PSSSigner
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

/**
 * Kalshi WS / REST request signing.
 *
 * Pre-sign text: `timestamp + "GET" + "/trade-api/ws/v2"`
 * RSA keys: RSA-PSS + SHA-256 (MGF1-SHA256, salt = digest length).
 * Ed25519 keys: RFC 8032 over the raw UTF-8 bytes.
 *
 * Never logs the PEM.
 */
object KalshiWsAuth {
    const val WS_METHOD = "GET"
    const val WS_PATH = "/trade-api/ws/v2"
    const val HEADER_KEY = "KALSHI-ACCESS-KEY"
    const val HEADER_TIMESTAMP = "KALSHI-ACCESS-TIMESTAMP"
    const val HEADER_SIGNATURE = "KALSHI-ACCESS-SIGNATURE"

    const val PRIMARY_WS_URL = "wss://external-api-ws.kalshi.com/trade-api/ws/v2"
    const val ELECTIONS_WS_URL = "wss://api.elections.kalshi.com/trade-api/ws/v2"

    val WS_URLS: List<String> = listOf(PRIMARY_WS_URL, ELECTIONS_WS_URL)

    init {
        ensureProvider()
    }

    fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class HandshakeHeaders(
        val keyId: String,
        val timestampMs: String,
        val signature: String
    ) {
        fun asMap(): Map<String, String> = mapOf(
            HEADER_KEY to keyId,
            HEADER_TIMESTAMP to timestampMs,
            HEADER_SIGNATURE to signature
        )
    }

    sealed class ParsedKey {
        data class Rsa(val params: RSAKeyParameters) : ParsedKey()
        data class Ed25519(val params: Ed25519PrivateKeyParameters) : ParsedKey()
    }

    fun parsePrivateKey(pem: String): ParsedKey {
        val trimmed = pem.trim()
        require(trimmed.contains("BEGIN") && trimmed.contains("PRIVATE")) {
            "Not a PEM private key"
        }
        PEMParser(StringReader(trimmed)).use { parser ->
            val obj = parser.readObject()
                ?: throw IllegalArgumentException("Empty PEM")
            val keyParam: AsymmetricKeyParameter = when (obj) {
                is PEMKeyPair -> PrivateKeyFactory.createKey(obj.privateKeyInfo)
                is PrivateKeyInfo -> PrivateKeyFactory.createKey(obj)
                else -> {
                    // Last resort: JCA conversion then BC params
                    val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    val jca = when (obj) {
                        is org.bouncycastle.openssl.X509TrustedCertificateBlock ->
                            throw IllegalArgumentException("PEM is a certificate, not a private key")
                        else -> runCatching {
                            // PKCS8Encrypted not supported without password
                            converter.getPrivateKey(obj as? PrivateKeyInfo ?: error("Unsupported PEM object ${obj.javaClass.simpleName}"))
                        }.getOrElse {
                            throw IllegalArgumentException("Unsupported PEM object ${obj.javaClass.simpleName}")
                        }
                    }
                    PrivateKeyFactory.createKey(PrivateKeyInfo.getInstance(jca.encoded))
                }
            }
            return when (keyParam) {
                is Ed25519PrivateKeyParameters -> ParsedKey.Ed25519(keyParam)
                is RSAPrivateCrtKeyParameters -> ParsedKey.Rsa(keyParam)
                is RSAKeyParameters -> ParsedKey.Rsa(keyParam)
                else -> throw IllegalArgumentException("Unsupported key type ${keyParam.javaClass.simpleName}")
            }
        }
    }

    fun sign(key: ParsedKey, messageUtf8: String): String {
        val bytes = messageUtf8.toByteArray(Charsets.UTF_8)
        val sig = when (key) {
            is ParsedKey.Ed25519 -> {
                val signer = Ed25519Signer()
                signer.init(true, key.params)
                signer.update(bytes, 0, bytes.size)
                signer.generateSignature()
            }
            is ParsedKey.Rsa -> {
                val signer = PSSSigner(RSAEngine(), SHA256Digest(), SHA256Digest(), 32)
                signer.init(true, key.params)
                signer.update(bytes, 0, bytes.size)
                signer.generateSignature()
            }
        }
        return encodeBase64(sig)
    }

    fun signWsHandshake(key: ParsedKey, timestampMs: String): String =
        sign(key, timestampMs + WS_METHOD + WS_PATH)

    /**
     * REST pre-sign text: `timestamp + METHOD + path`.
     * [path] must be the full URL path from the API root with no query string
     * (e.g. `/trade-api/v2/portfolio/events/orders`).
     */
    fun signRest(key: ParsedKey, timestampMs: String, method: String, path: String): String {
        val cleanPath = path.substringBefore('?')
        return sign(key, timestampMs + method.uppercase() + cleanPath)
    }

    fun handshakeHeaders(keyId: String, pem: String, timestampMs: Long = System.currentTimeMillis()): HandshakeHeaders {
        val key = parsePrivateKey(pem)
        val ts = timestampMs.toString()
        return HandshakeHeaders(
            keyId = keyId.trim(),
            timestampMs = ts,
            signature = signWsHandshake(key, ts)
        )
    }

    fun encodeBase64(data: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            Base64.getEncoder().encodeToString(data)
        }
    }
}
