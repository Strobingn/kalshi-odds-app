package com.dirk.kalshiodds.signal.config

/**
 * Normalize a pasted Kalshi private key so PKCS#1 (`BEGIN RSA PRIVATE KEY`)
 * and PKCS#8 (`BEGIN PRIVATE KEY`) both parse after CRLF, one-line paste,
 * or extra whitespace.
 *
 * Docs: https://docs.kalshi.com/getting_started/api_keys
 * Kalshi RSA PEMs are PKCS#1; `openssl genpkey` PKCS#8 also starts with
 * `BEGIN PRIVATE KEY`.
 */
object PemNormalizer {

    const val PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----"
    const val PKCS1_END = "-----END RSA PRIVATE KEY-----"
    const val PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"
    const val PKCS8_END = "-----END PRIVATE KEY-----"

    const val MISSING_PEM =
        "Key ID is saved but the private key PEM is missing — paste a BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY block"

    fun normalize(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (t.isEmpty()) return ""
        t = t.replace(Regex("[ \t]+"), " ")
        val header = when {
            t.contains("BEGIN RSA PRIVATE KEY", ignoreCase = true) -> PKCS1_BEGIN
            t.contains("BEGIN PRIVATE KEY", ignoreCase = true) -> PKCS8_BEGIN
            else -> null
        }
        val footer = when (header) {
            PKCS1_BEGIN -> PKCS1_END
            PKCS8_BEGIN -> PKCS8_END
            else -> null
        }
        if (header == null || footer == null) {
            val compact = t.replace(Regex("\\s+"), "")
            if (compact.length >= 80 && compact.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
                return wrap(PKCS8_BEGIN, PKCS8_END, compact)
            }
            return t
        }
        val body = t
            .replace(Regex("(?i)-----BEGIN [A-Z ]*PRIVATE[A-Z ]*KEY-----"), "")
            .replace(Regex("(?i)-----END [A-Z ]*PRIVATE[A-Z ]*KEY-----"), "")
            .replace(Regex("\\s+"), "")
        if (body.isEmpty()) return "$header\n$footer\n"
        return wrap(header, footer, body)
    }

    fun looksLikePem(raw: String): Boolean {
        val n = normalize(raw)
        val hasBegin = n.contains(PKCS1_BEGIN) || n.contains(PKCS8_BEGIN)
        val hasEnd = n.contains(PKCS1_END) || n.contains(PKCS8_END)
        val body = n.lineSequence()
            .filter { !it.startsWith("-----") }
            .joinToString("")
            .trim()
        return hasBegin && hasEnd && body.length > 80
    }

    fun onlyKeyIdSaved(keyId: String, pem: String): Boolean =
        keyId.isNotBlank() && !looksLikePem(pem)

    private fun wrap(begin: String, end: String, body: String): String {
        val lines = body.chunked(64)
        return buildString {
            append(begin).append('\n')
            lines.forEach { append(it).append('\n') }
            append(end).append('\n')
        }
    }
}
