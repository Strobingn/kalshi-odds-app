package com.dirk.kalshiodds.signal.config

/**
 * Kalshi private-key PEM as users actually paste it.
 *
 * Official docs (https://docs.kalshi.com/getting_started/api_keys):
 * - RSA keys Kalshi generates: `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1)
 * - Ed25519 / `openssl genpkey`: `-----BEGIN PRIVATE KEY-----` (PKCS#8)
 *
 * Phone paste often: CRLF, leading/trailing whitespace, the whole PEM as
 * one line with spaces instead of newlines, or the base64 body without
 * header lines.
 */
object PemNormalizer {

    private val PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----"
    private val PKCS1_END = "-----END RSA PRIVATE KEY-----"
    private val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private val PKCS8_END = "-----END PRIVATE KEY-----"
    private val PKCS8_ENC_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----"
    private val PKCS8_ENC_END = "-----END ENCRYPTED PRIVATE KEY-----"

    fun normalize(raw: String): String {
        val trimmed = raw.replace("\uFEFF", "").trim()
        if (trimmed.isEmpty()) return ""
        val unified = trimmed.replace("\r\n", "\n").replace("\r", "\n")
        extract(unified, "RSA PRIVATE KEY")?.let { return wrap("RSA PRIVATE KEY", it) }
        extract(unified, "ENCRYPTED PRIVATE KEY")?.let { return wrap("ENCRYPTED PRIVATE KEY", it) }
        extract(unified, "PRIVATE KEY")?.let { return wrap("PRIVATE KEY", it) }
        val body = unwrapBase64(unified)
        if (body.length >= 64 && body.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            return wrap("RSA PRIVATE KEY", body)
        }
        return unified.trim()
    }

    fun looksLikePem(raw: String): Boolean {
        val n = normalize(raw)
        if (n.length < 80) return false
        val hasBegin = n.contains(PKCS1_BEGIN) || n.contains(PKCS8_BEGIN) || n.contains(PKCS8_ENC_BEGIN)
        val hasEnd = n.contains(PKCS1_END) || n.contains(PKCS8_END) || n.contains(PKCS8_ENC_END)
        return hasBegin && hasEnd && n.contains("PRIVATE")
    }

    private fun extract(text: String, type: String): String? {
        val begin = "-----BEGIN $type-----"
        val end = "-----END $type-----"
        val start = text.indexOf(begin, ignoreCase = true)
        val stop = text.indexOf(end, ignoreCase = true)
        if (start < 0 || stop < 0 || stop <= start) return null
        val inner = text.substring(start + begin.length, stop)
        val body = unwrapBase64(inner)
        return body.takeIf { it.length >= 64 }
    }

    private fun unwrapBase64(raw: String): String =
        raw.filter { !it.isWhitespace() }

    private fun wrap(type: String, body: String): String {
        val lines = body.chunked(64)
        return buildString {
            append("-----BEGIN ").append(type).append("-----\n")
            lines.forEach { append(it).append('\n') }
            append("-----END ").append(type).append("-----")
        }
    }
}
