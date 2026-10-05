package com.dirk.kalshiodds.update

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipInputStream

/**
 * Reads the package id and signing cert out of an APK zip before any
 * install prompt. Package id comes from the manifest string pool (or a
 * plain XML manifest in tests). The cert is the JAR signer cert when the
 * APK has one, otherwise the APK Signature Scheme v2/v3 certificate.
 * Debug builds from AGP 8 with minSdk 26 are v2-only.
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
        val cert = certBytes ?: apkSigningCert(bytes)
        return Facts(
            packageName = manifest?.let { packageName(it) },
            certSha256 = cert?.let { certSha256(it) }
        )
    }

    fun packageName(manifest: ByteArray): String? {
        val fromXml = textPackage(manifest)
        if (fromXml != null) return fromXml
        return resolvePackage(axmlStrings(manifest))
    }

    /**
     * Binary manifests pool the application id next to class names such as
     * `com.dirk.kalshiodds.KalshiOddsApp` and `com.dirk.kalshiodds.MainActivity`.
     * Those used to match the same pattern; more than one match returned null
     * and the install gate rejected every real Kashi APK.
     *
     * Prefer [KashiReleasePolicy.PACKAGE_ID] when it is in the pool. Otherwise
     * accept a single package-id-shaped string (lowercase segments only) so
     * Activity and Application class names are never treated as the package.
     */
    internal fun resolvePackage(strings: Collection<String>): String? {
        if (KashiReleasePolicy.PACKAGE_ID in strings) return KashiReleasePolicy.PACKAGE_ID
        val packages = strings.filter { it.matches(PACKAGE_ID) }.toSet()
        return packages.singleOrNull()
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

    /**
     * Application-id shape under this app's namespace. Each extra segment
     * starts with a lowercase letter, so CamelCase class FQCNs do not match.
     */
    private val PACKAGE_ID = Regex("^com\\.dirk\\.kalshiodds(\\.[a-z][a-z0-9_]*)*$")

    private fun apkSigningCert(apk: ByteArray): ByteArray? =
        runCatching { readApkSigningCert(apk) }.getOrNull()

    /**
     * First X.509 certificate from the APK Signing Block (v2, else v3).
     * The block sits immediately before the ZIP central directory.
     */
    private fun readApkSigningCert(apk: ByteArray): ByteArray? {
        val eocd = findEocd(apk)
        if (eocd < 0) return null
        val cdOffset = u32l(apk, eocd + 16)
        if (cdOffset < 32 || cdOffset > apk.size) return null
        val cd = cdOffset.toInt()
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        if (!regionEquals(apk, cd - magic.size, magic)) return null
        val size = u64(apk, cd - 24)
        if (size < 32 || size > apk.size) return null
        val blockStart = cd - size.toInt() - 8
        if (blockStart < 0 || u64(apk, blockStart) != size) return null
        var pos = blockStart + 8
        val end = cd - 24
        var fallback: ByteArray? = null
        while (pos + 12 <= end) {
            val pairLen = u64(apk, pos)
            if (pairLen < 4 || pairLen > (end - pos - 8)) return fallback
            val id = u32l(apk, pos + 8)
            val valueStart = pos + 12
            val valueEnd = (pos + 8 + pairLen).toInt()
            if (id == APK_SIG_V2 || id == APK_SIG_V3) {
                val cert = firstCertificate(apk, valueStart, valueEnd)
                if (id == APK_SIG_V2 && cert != null) return cert
                if (cert != null && fallback == null) fallback = cert
            }
            pos = valueEnd
        }
        return fallback
    }

    private fun firstCertificate(apk: ByteArray, start: Int, end: Int): ByteArray? {
        if (start < 0 || end > apk.size || start + 4 > end) return null
        val seqLen = u32l(apk, start)
        if (seqLen < 4 || start + 4L + seqLen > end) return null
        val seqEnd = (start + 4 + seqLen).toInt()
        if (start + 8 > seqEnd) return null
        val signerLen = u32l(apk, start + 4)
        if (signerLen < 4 || start + 8L + signerLen > seqEnd) return null
        val signerStart = start + 8
        val signerEnd = (signerStart + signerLen).toInt()
        if (signerStart + 4 > signerEnd) return null
        val signedLen = u32l(apk, signerStart)
        if (signedLen < 8 || signerStart + 4L + signedLen > signerEnd) return null
        val signedStart = signerStart + 4
        val signedEnd = (signedStart + signedLen).toInt()
        val digestsLen = u32l(apk, signedStart)
        if (digestsLen < 0 || signedStart + 4L + digestsLen > signedEnd) return null
        val certsLenPos = (signedStart + 4 + digestsLen).toInt()
        if (certsLenPos + 4 > signedEnd) return null
        val certsLen = u32l(apk, certsLenPos)
        if (certsLen < 4 || certsLenPos + 4L + certsLen > signedEnd) return null
        val certsStart = certsLenPos + 4
        val certsEnd = (certsStart + certsLen).toInt()
        val certLen = u32l(apk, certsStart)
        val certStart = certsStart + 4
        if (certLen <= 0 || certStart + certLen > certsEnd) return null
        return apk.copyOfRange(certStart, (certStart + certLen).toInt())
    }

    private fun findEocd(apk: ByteArray): Int {
        val min = maxOf(0, apk.size - 22 - 65535)
        var i = apk.size - 22
        while (i >= min) {
            if (apk[i] == 0x50.toByte() &&
                i + 3 < apk.size &&
                apk[i + 1] == 0x4b.toByte() &&
                apk[i + 2] == 0x05.toByte() &&
                apk[i + 3] == 0x06.toByte()
            ) {
                val commentLen = u16(apk, i + 20)
                if (i + 22 + commentLen == apk.size) return i
            }
            i--
        }
        return -1
    }

    private fun regionEquals(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || offset + expected.size > bytes.size) return false
        for (i in expected.indices) {
            if (bytes[offset + i] != expected[i]) return false
        }
        return true
    }

    private fun u32l(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > bytes.size) return -1L
        return (bytes[at].toLong() and 0xFFL) or
            ((bytes[at + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[at + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[at + 3].toLong() and 0xFFL) shl 24)
    }

    private fun u64(bytes: ByteArray, at: Int): Long {
        if (at < 0 || at + 8 > bytes.size) return -1L
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[at + i].toLong() and 0xFFL) shl (8 * i))
        }
        return value
    }

    private const val APK_SIG_V2 = 0x7109871aL
    private const val APK_SIG_V3 = 0xf05368c0L
}
