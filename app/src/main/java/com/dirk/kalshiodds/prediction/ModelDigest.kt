package com.dirk.kalshiodds.prediction

import java.security.MessageDigest

/** SHA-256 of the model file bytes. Must match `ml/publish_model.py`. */
object ModelDigest {
    fun sha256Hex(utf8: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(utf8.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) {
            sb.append("%02x".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }

    fun matches(expected: String, utf8: String): Boolean =
        expected.isNotBlank() && expected.equals(sha256Hex(utf8), ignoreCase = true)
}
