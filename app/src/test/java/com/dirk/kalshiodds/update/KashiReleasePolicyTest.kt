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
import org.junit.Test
import java.math.BigInteger
import java.util.Date

class KashiReleasePolicyTest {
    @Test
    fun onlyKashiDebugAssetIsEligible() {
        val json = """
            [
              {"tag_name":"v0.3.22-debug","target_commitish":"kashi","draft":false,
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
                "assets":[{"name":"DipHunter-debug.apk","browser_download_url":"https://example.invalid/nope.apk"}]},
              {"tag_name":"v0.3.24-debug","target_commitish":"9da31fc9728900272f2ff195a63fc83341e501dc","draft":false,
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
        assertTrue(gradle.contains("versionName = \"0.3.27\""))
        assertTrue(gradle.contains("versionCode = 42"))
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
}
