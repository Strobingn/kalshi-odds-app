package com.dirk.kalshiodds.update

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipInputStream

/**
 * Reads the package id and signing cert out of an APK zip before any
 * install prompt. Package id comes from the manifest string pool (or a
 * plain XML manifest in tests). The cert is the META-INF signer cert.
 */
object ApkArchiveInspector {
    data class Facts(val packageName: String?, val certSha256: String?)

    fun inspect(bytes: ByteArray): Facts {
        var manifest: ByteArray? = null
        var certBytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    name == "AndroidManifest.xml" -> manifest = zip.readBytes()
                    name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA", true) || name.endsWith(".DSA", true) || name.endsWith(".EC", true)) ->
                        certBytes = zip.readBytes()
                }
            }
        }
        return Facts(
            packageName = manifest?.let { packageName(it) },
            certSha256 = certBytes?.let { certSha256(it) }
        )
    }

    fun packageName(manifest: ByteArray): String? {
        val fromXml = textPackage(manifest)
        if (fromXml != null) return fromXml
        val strings = axmlStrings(manifest)
        val packages = strings.filter { it.matches(PACKAGE) }.toSet()
        if (packages.isEmpty()) return null
        if (packages.size == 1) return packages.single()
        return null
    }

    fun certSha256(bytes: ByteArray): String? {
        val cert = readCert(bytes) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun textPackage(manifest: ByteArray): String? {
        if (manifest.size >= 4 && manifest[0] == 0x03.toByte()) return null
        val text = runCatching { manifest.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val match = Regex("package\\s*=\\s*\"([^\"]+)\"").find(text) ?: return null
        return match.groupValues[1]
    }

    private fun readCert(bytes: ByteArray): X509Certificate? {
        runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(ByteArrayInputStream(bytes))
            if (cert is X509Certificate) return cert
        }
        return runCatching {
            val cms = org.bouncycastle.cms.CMSSignedData(bytes)
            val selector = object : org.bouncycastle.util.Selector<org.bouncycastle.cert.X509CertificateHolder> {
                override fun match(obj: org.bouncycastle.cert.X509CertificateHolder?): Boolean = true
                override fun clone(): Any = this
            }
            val holder = cms.certificates.getMatches(selector).firstOrNull() ?: return null
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        }.getOrNull()
    }

    /**
     * Minimal Android binary XML string pool. Enough to recover the
     * manifest package attribute, which is stored as a pooled string.
     */
    internal fun axmlStrings(manifest: ByteArray): List<String> {
        if (manifest.size < 8) return emptyList()
        var offset = 8
        val out = ArrayList<String>()
        while (offset + 8 <= manifest.size) {
            val type = u16(manifest, offset)
            val headerSize = u16(manifest, offset + 2)
            val size = u32(manifest, offset + 4)
            if (size < 8 || offset + size > manifest.size) break
            if (type == 0x0001 && headerSize >= 28) {
                out += readPool(manifest, offset, size)
            }
            offset += size
        }
        return out
    }

    private fun readPool(bytes: ByteArray, start: Int, size: Int): List<String> {
        val stringCount = u32(bytes, start + 8)
        val flags = u32(bytes, start + 16)
        val stringsStart = u32(bytes, start + 20)
        if (stringCount <= 0 || stringCount > 10_000) return emptyList()
        val utf8 = flags and 0x100 != 0
        val offsets = IntArray(stringCount)
        var cursor = start + 28
        for (i in 0 until stringCount) {
            if (cursor + 4 > start + size) return emptyList()
            offsets[i] = u32(bytes, cursor)
            cursor += 4
        }
        val base = start + stringsStart
        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val at = base + offsets[i]
            if (at < start || at >= start + size) continue
            out += if (utf8) readUtf8(bytes, at, start + size) else readUtf16(bytes, at, start + size)
        }
        return out
    }

    private fun readUtf16(bytes: ByteArray, at: Int, end: Int): String {
        if (at + 2 > end) return ""
        var len = u16(bytes, at)
        var pos = at + 2
        if (len and 0x8000 != 0) {
            if (pos + 2 > end) return ""
            len = ((len and 0x7FFF) shl 16) or u16(bytes, pos)
            pos += 2
        }
        val chars = CharArray(len)
        for (i in 0 until len) {
            if (pos + 2 > end) return String(chars, 0, i)
            chars[i] = ((bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)).toChar()
            pos += 2
        }
        return String(chars)
    }

    private fun readUtf8(bytes: ByteArray, at: Int, end: Int): String {
        var pos = at
        if (pos >= end) return ""
        var charLen = bytes[pos].toInt() and 0xFF
        pos += 1
        if (charLen and 0x80 != 0) {
            if (pos >= end) return ""
            charLen = ((charLen and 0x7F) shl 8) or (bytes[pos].toInt() and 0xFF)
            pos += 1
        }
        if (pos >= end) return ""
        var byteLen = bytes[pos].toInt() and 0xFF
        pos += 1
        if (byteLen and 0x80 != 0) {
            if (pos >= end) return ""
            byteLen = ((byteLen and 0x7F) shl 8) or (bytes[pos].toInt() and 0xFF)
            pos += 1
        }
        if (pos + byteLen > end) return ""
        return String(bytes, pos, byteLen, Charsets.UTF_8)
    }

    private fun u16(bytes: ByteArray, at: Int): Int {
        if (at + 1 >= bytes.size) return 0
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, at: Int): Int {
        if (at + 3 >= bytes.size) return 0
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }

    private val PACKAGE = Regex("^com\\.dirk\\.kalshiodds(\\.[A-Za-z0-9_]+)?$")
}
