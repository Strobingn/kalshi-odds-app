package com.dirk.kalshiodds.update

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.util.Date

class KashiReleasePolicyTest {
    @Test
    fun onlyKashiDebugAssetIsEligible() {
        val json = """
            [
              {"tag_name":"v0.3.22-debug","target_commitish":"kashi","draft":false,
                "body":"app-id: com.dirk.kalshiodds.kashi",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.22-debug/DipHunter-debug.apk"}]},
              {"tag_name":"v0.3.22-debug","target_commitish":"chat-GTP","draft":false,
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/chat/DipHunter-debug.apk"}]},
              {"tag_name":"v0.3.22","target_commitish":"main","draft":false,
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.22/DipHunter-debug.apk"}]},
              {"tag_name":"v0.3.99-debug","target_commitish":"grokbot","draft":false,
                "assets":[{"name":"app-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/x/app-debug.apk"}]},
              {"tag_name":"edge-model-latest","target_commitish":"kashi","draft":false,
                "assets":[{"name":"edge_model.json","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/edge/edge_model.json"}]}
            ]
        """.trimIndent()
        val chosen = KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.21")
        assertEquals("v0.3.22-debug", chosen?.tag)
        assertEquals(
            KashiReleasePolicy.assetUrl("v0.3.22-debug"),
            chosen?.asset?.downloadUrl
        )
        assertFalse(KashiReleasePolicy.RELEASES_URL.contains("/releases/latest"))
        assertTrue(KashiReleasePolicy.RELEASES_URL.contains("Strobingn/kalshi-odds-app"))
        assertNull(KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.22"))
    }

    @Test
    fun onlyV03DebugTagsBeatTheInstalledVersion() {
        val json = """
            [
              {"tag_name":"v0.3.25-debug","target_commitish":"d72b4442e221b9356368bfd60b371f4a2cfa88da","draft":false,
                "body":"app-id: com.dirk.kalshiodds.kashi",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://example.invalid/nope.apk"}]},
              {"tag_name":"v0.3.24-debug","target_commitish":"9da31fc9728900272f2ff195a63fc83341e501dc","draft":false,
                "body":"app-id: com.dirk.kalshiodds.kashi",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.24-debug/DipHunter-debug.apk"}]},
              {"tag_name":"v1.2-Claude","target_commitish":"e028133b","draft":false,
                "assets":[{"name":"DipHunter-v1.2-Claude-55.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v1.2-Claude/DipHunter-v1.2-Claude-55.apk"}]},
              {"tag_name":"gtp-v1.2-grokbot","target_commitish":"4b5a292c","draft":false,
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/gtp-v1.2-grokbot/DipHunter-debug.apk"}]},
              {"tag_name":"v1.0-grokbot-claude","target_commitish":"db810921","draft":false,
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v1.0-grokbot-claude/DipHunter-debug.apk"}]},
              {"tag_name":"v0.4.0-debug","target_commitish":"kashi","draft":false,
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.4.0-debug/DipHunter-debug.apk"}]}
            ]
        """.trimIndent()
        val parsed = KashiReleasePolicy.parse(json)
        assertEquals(1, parsed.count { KashiReleasePolicy.eligible(it) && it.tag == "v0.3.25-debug" })
        assertTrue(parsed.none { KashiReleasePolicy.eligible(it) && !it.tag.startsWith("v0.3.") })
        val chosen = KashiReleasePolicy.choose(parsed, "0.3.24")
        assertEquals("v0.3.25-debug", chosen?.tag)
        assertEquals(KashiReleasePolicy.assetUrl("v0.3.25-debug"), chosen?.asset?.downloadUrl)
        assertNull(KashiReleasePolicy.choose(parsed, "0.3.25"))
        assertNull(KashiReleasePolicy.choose(parsed, "0.3.26"))
        assertEquals(1, KashiReleasePolicy.compareVersions("0.3.10", "0.3.9"))
        assertEquals(-1, KashiReleasePolicy.compareVersions("0.3.26", "0.3.27"))
        assertEquals(0, KashiReleasePolicy.compareVersions("v0.3.26-debug", "0.3.26"))
        assertTrue(com.dirk.kalshiodds.update.UpdateCheckSchedule.due(0L, 1L))
        assertFalse(com.dirk.kalshiodds.update.UpdateCheckSchedule.due(1_000L, 1_000L + 6L * 60 * 60 * 1000 - 1))
        assertTrue(com.dirk.kalshiodds.update.UpdateCheckSchedule.due(1_000L, 1_000L + com.dirk.kalshiodds.update.UpdateCheckSchedule.INTERVAL_MS))
    }

    @Test
    fun installGateRequiresPackageAndCert() {
        assertTrue(
            ApkInstallGate.decide(KashiReleasePolicy.PACKAGE_ID, KashiReleasePolicy.CERT_SHA256)
                is ApkInstallDecision.Allow
        )
        assertTrue(
            ApkInstallGate.decide("com.dirk.kalshiodds", KashiReleasePolicy.CERT_SHA256)
                is ApkInstallDecision.Reject
        )
        assertTrue(
            ApkInstallGate.decide(KashiReleasePolicy.PACKAGE_ID, "ab".repeat(32))
                is ApkInstallDecision.Reject
        )
    }

    @Test
    fun archiveInspectorReadsPackageAndCert() {
        val cert = selfSigned()
        val digest = ApkArchiveInspector.certSha256(cert.encoded)
        val apk = zip(
            "AndroidManifest.xml" to """<manifest package="com.dirk.kalshiodds.kashi"/>""".toByteArray(),
            "META-INF/CERT.RSA" to cert.encoded
        )
        val facts = ApkArchiveInspector.inspect(apk)
        assertEquals(KashiReleasePolicy.PACKAGE_ID, facts.packageName)
        assertEquals(digest, facts.certSha256)
        assertTrue(ApkInstallGate.decide(facts.packageName, facts.certSha256) is ApkInstallDecision.Reject ||
            facts.certSha256 != KashiReleasePolicy.CERT_SHA256)
        val foreign = zip(
            "AndroidManifest.xml" to """<manifest package="com.dirk.kalshiodds"/>""".toByteArray(),
            "META-INF/CERT.RSA" to cert.encoded
        )
        assertEquals("com.dirk.kalshiodds", ApkArchiveInspector.inspect(foreign).packageName)
    }

    @Test
    fun stringPoolPrefersKashiIdOverClassNames() {
        val manifest = axmlPool(
            "android.permission.INTERNET",
            "com.dirk.kalshiodds.KalshiOddsApp",
            "com.dirk.kalshiodds.MainActivity",
            "com.dirk.kalshiodds.kashi",
            "com.dirk.kalshiodds.kashi.fileprovider"
        )
        assertEquals(KashiReleasePolicy.PACKAGE_ID, ApkArchiveInspector.packageName(manifest))
        assertEquals(
            KashiReleasePolicy.PACKAGE_ID,
            ApkArchiveInspector.resolvePackage(
                listOf(
                    "com.dirk.kalshiodds.KalshiOddsApp",
                    "com.dirk.kalshiodds.MainActivity",
                    "com.dirk.kalshiodds.kashi"
                )
            )
        )
    }

    @Test
    fun camelCaseClassNamesAreNotAcceptedAsPackage() {
        assertNull(
            ApkArchiveInspector.packageName(
                axmlPool(
                    "com.dirk.kalshiodds.KalshiOddsApp",
                    "com.dirk.kalshiodds.MainActivity"
                )
            )
        )
        assertNull(ApkArchiveInspector.packageName(axmlPool("com.dirk.kalshiodds.KalshiOddsApp")))
        assertNull(
            ApkArchiveInspector.resolvePackage(
                listOf("com.dirk.kalshiodds.KalshiOddsApp", "com.dirk.kalshiodds.MainActivity")
            )
        )
        assertEquals(
            "com.dirk.kalshiodds",
            ApkArchiveInspector.packageName(axmlPool("com.dirk.kalshiodds"))
        )
        assertNull(
            ApkArchiveInspector.packageName(
                axmlPool("com.dirk.kalshiodds", "com.dirk.kalshiodds.other")
            )
        )
    }

    @Test
    fun realV03030ManifestAndDebugCertPassTheInstallGate() {
        val manifest = resource("update/v0.3.30-AndroidManifest.xml")
        val cert = resource("update/v0.3.30-debug-cert.der")
        assertEquals(KashiReleasePolicy.PACKAGE_ID, ApkArchiveInspector.packageName(manifest))
        val apk = zipWithApkSignature("AndroidManifest.xml" to manifest, cert)
        val facts = ApkArchiveInspector.inspect(apk)
        assertEquals(KashiReleasePolicy.PACKAGE_ID, facts.packageName)
        assertEquals(KashiReleasePolicy.CERT_SHA256, facts.certSha256)
        assertTrue(ApkInstallGate.decide(facts.packageName, facts.certSha256) is ApkInstallDecision.Allow)
    }

    @Test
    fun v2CertThatDoesNotMatchDebugKeyIsRejected() {
        val cert = selfSigned()
        val apk = zipWithApkSignature(
            "AndroidManifest.xml" to axmlPool(
                "com.dirk.kalshiodds.KalshiOddsApp",
                "com.dirk.kalshiodds.MainActivity",
                KashiReleasePolicy.PACKAGE_ID
            ),
            cert.encoded
        )
        val facts = ApkArchiveInspector.inspect(apk)
        assertEquals(KashiReleasePolicy.PACKAGE_ID, facts.packageName)
        assertTrue(facts.certSha256 != null && facts.certSha256 != KashiReleasePolicy.CERT_SHA256)
        assertTrue(ApkInstallGate.decide(facts.packageName, facts.certSha256) is ApkInstallDecision.Reject)
    }

    @Test
    fun realDipHunterDebugApkWhenProvided() {
        val path = System.getenv("DIPHUNTER_APK")
        val apk = path?.let { java.io.File(it) }
        assumeTrue("set DIPHUNTER_APK to check a full debug apk", apk != null && apk.isFile)
        val facts = ApkArchiveInspector.inspect(apk!!.readBytes())
        assertEquals(KashiReleasePolicy.PACKAGE_ID, facts.packageName)
        assertEquals(KashiReleasePolicy.CERT_SHA256, facts.certSha256)
        assertTrue(ApkInstallGate.decide(facts.packageName, facts.certSha256) is ApkInstallDecision.Allow)
    }

    @Test
    fun downloadOfForeignPackageIsRejected() {
        val cert = selfSigned()
        val apk = zip(
            "AndroidManifest.xml" to """<manifest package="com.dirk.kalshiodds"/>""".toByteArray(),
            "META-INF/CERT.RSA" to cert.encoded
        )
        val client = KashiUpdateClient(
            fetchText = { error("not used") },
            fetchBytes = { apk }
        )
        val release = KashiReleasePolicy.Release(
            tag = "v0.3.22-debug",
            targetCommitish = "kashi",
            draft = false,
            notes = KashiReleasePolicy.APP_ID_MARKER,
            asset = KashiReleasePolicy.Asset(
                "DipHunter-debug.apk",
                "https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.22-debug/DipHunter-debug.apk"
            )
        )
        val result = client.downloadVerified(release)
        assertTrue(result is DownloadResult.Rejected)
    }

    @Test
    fun ciRunsUnitTestsOnKashiOnly() {
        val root = repoRoot()
        val yml = java.io.File(root, ".github/workflows/kashi-unit-tests.yml").readText()
        assertTrue(yml.contains("testDebugUnitTest"))
        assertTrue(yml.contains("branches: [kashi]"))
        assertFalse(yml.contains("assembleRelease"))
        val gradle = java.io.File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains(KashiReleasePolicy.CERT_SHA256))
        assertTrue(gradle.contains("versionName = \"0.3.35\""))
        assertTrue(gradle.contains("versionCode = 50"))
    }

    @Test
    fun higherVersionWithoutMarkerIsSkippedAndLowerKashiReleaseIsOffered() {
        val json = """
            [
              {"tag_name":"v1.2.0-debug","draft":false,"body":"other branch",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v1.2.0-debug/DipHunter-debug.apk"}]},
              {"tag_name":"v0.3.28-debug","draft":false,"body":"app-id: com.dirk.kalshiodds.kashi",
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/v0.3.28-debug/DipHunter-debug.apk"}]}
            ]
        """.trimIndent()
        val chosen = KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.27")
        assertEquals("v0.3.28-debug", chosen?.tag)
        assertNull(KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.28"))
    }

    @Test
    fun tagParsingAcceptsV0328AndV040() {
        assertEquals(Triple(0, 3, 28), KashiReleasePolicy.versionOf("v0.3.28-debug"))
        assertEquals(Triple(0, 4, 0), KashiReleasePolicy.versionOf("v0.4.0"))
        assertEquals(1, KashiReleasePolicy.compareVersions("v0.4.0-debug", "0.3.28"))
        assertTrue(KashiReleasePolicy.TAG.matches("v0.3.28-debug"))
        assertTrue(KashiReleasePolicy.TAG.matches("v0.4.0-debug"))
        assertFalse(KashiReleasePolicy.TAG.matches("v1.2-debug"))
    }

    @Test
    fun downloadedApkWithWrongPackageOrCertIsRefused() {
        val installed = KashiReleasePolicy.CERT_SHA256
        assertTrue(
            InstalledApkCheck.decide(KashiReleasePolicy.PACKAGE_ID, installed, KashiReleasePolicy.PACKAGE_ID, installed)
                is ApkInstallDecision.Allow
        )
        val wrongPkg = InstalledApkCheck.decide(
            "com.dirk.kalshiodds",
            installed,
            KashiReleasePolicy.PACKAGE_ID,
            installed
        )
        assertTrue(wrongPkg is ApkInstallDecision.Reject)
        assertTrue((wrongPkg as ApkInstallDecision.Reject).reason.contains("com.dirk.kalshiodds.kashi"))
        val wrongCert = InstalledApkCheck.decide(
            KashiReleasePolicy.PACKAGE_ID,
            "ab".repeat(32),
            KashiReleasePolicy.PACKAGE_ID,
            installed
        )
        assertTrue(wrongCert is ApkInstallDecision.Reject)
        assertTrue((wrongCert as ApkInstallDecision.Reject).reason.contains("certificate"))
    }

    private fun repoRoot(): java.io.File {
        var dir = java.io.File(".").absoluteFile
        for (i in 0 until 6) {
            if (java.io.File(dir, "settings.gradle.kts").isFile &&
                java.io.File(dir, ".github/workflows/kashi-unit-tests.yml").isFile
            ) {
                return dir
            }
            dir = dir.parentFile ?: break
        }
        error("repo root not found from ${java.io.File(".").absolutePath}")
    }

    private fun selfSigned(): X509Certificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test"),
            BigInteger.ONE,
            Date(now - 10_000),
            Date(now + 86_400_000),
            X500Name("CN=Test"),
            keys.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun resource(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: error("missing test resource $path")
        return stream.use { it.readBytes() }
    }

    /** UTF-16 binary XML string pool, the same encoding as aapt manifests. */
    private fun axmlPool(vararg strings: String): ByteArray {
        val encoded = strings.map { utf16(it) }
        val stringsStart = 28 + strings.size * 4
        val offsets = IntArray(strings.size)
        var dataLen = 0
        encoded.forEachIndexed { i, bytes ->
            offsets[i] = dataLen
            dataLen += bytes.size
        }
        val chunkSize = stringsStart + dataLen
        val file = ByteArray(8 + chunkSize)
        putU16(file, 0, 0x0003)
        putU16(file, 2, 8)
        putU32(file, 4, file.size)
        val base = 8
        putU16(file, base, 0x0001)
        putU16(file, base + 2, 28)
        putU32(file, base + 4, chunkSize)
        putU32(file, base + 8, strings.size)
        putU32(file, base + 12, 0)
        putU32(file, base + 16, 0)
        putU32(file, base + 20, stringsStart)
        putU32(file, base + 24, 0)
        var cursor = base + 28
        offsets.forEach { off ->
            putU32(file, cursor, off)
            cursor += 4
        }
        encoded.forEach { bytes ->
            bytes.copyInto(file, cursor)
            cursor += bytes.size
        }
        return file
    }

    private fun utf16(value: String): ByteArray {
        val out = ByteArray(2 + value.length * 2 + 2)
        putU16(out, 0, value.length)
        var pos = 2
        value.forEach { ch ->
            putU16(out, pos, ch.code)
            pos += 2
        }
        return out
    }

    private fun zipWithApkSignature(entry: Pair<String, ByteArray>, certDer: ByteArray): ByteArray {
        val plain = zip(entry)
        val eocd = plain.size - 22
        check(plain[eocd] == 0x50.toByte() && plain[eocd + 1] == 0x4b.toByte())
        val cdOffset = u32at(plain, eocd + 16)
        val block = apkSigBlock(certDer)
        val out = ByteArray(plain.size + block.size)
        plain.copyInto(out, 0, 0, cdOffset)
        block.copyInto(out, cdOffset)
        plain.copyInto(out, cdOffset + block.size, cdOffset, plain.size)
        putU32(out, eocd + block.size + 16, cdOffset + block.size)
        return out
    }

    /** Minimal APK Signature Scheme v2 block containing [certDer] and no signature bytes. */
    private fun apkSigBlock(certDer: ByteArray): ByteArray {
        val certs = ByteArray(4 + certDer.size)
        putU32(certs, 0, certDer.size)
        certDer.copyInto(certs, 4)
        val signed = ByteArray(4 + 4 + certs.size + 4)
        putU32(signed, 0, 0)
        putU32(signed, 4, certs.size)
        certs.copyInto(signed, 8)
        putU32(signed, 8 + certs.size, 0)
        val signer = ByteArray(4 + signed.size + 8)
        putU32(signer, 0, signed.size)
        signed.copyInto(signer, 4)
        putU32(signer, 4 + signed.size, 0)
        putU32(signer, 8 + signed.size, 0)
        val scheme = ByteArray(8 + signer.size)
        putU32(scheme, 0, 4 + signer.size)
        putU32(scheme, 4, signer.size)
        signer.copyInto(scheme, 8)
        val pairLen = 4 + scheme.size
        val pairs = ByteArray(8 + pairLen)
        putU64(pairs, 0, pairLen.toLong())
        putU32(pairs, 8, 0x7109871a)
        scheme.copyInto(pairs, 12)
        val size = (pairs.size + 8 + 16).toLong()
        val block = ByteArray(8 + pairs.size + 8 + 16)
        putU64(block, 0, size)
        pairs.copyInto(block, 8)
        putU64(block, 8 + pairs.size, size)
        "APK Sig Block 42".toByteArray(Charsets.US_ASCII).copyInto(block, 8 + pairs.size + 8)
        return block
    }

    private fun putU16(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putU32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        out[at + 2] = ((value shr 16) and 0xFF).toByte()
        out[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putU64(out: ByteArray, at: Int, value: Long) {
        var v = value
        for (i in 0 until 8) {
            out[at + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
    }

    private fun u32at(bytes: ByteArray, at: Int): Int {
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}
