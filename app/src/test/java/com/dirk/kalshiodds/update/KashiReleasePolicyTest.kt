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
        assertEquals("kashi", chosen?.targetCommitish)
        assertFalse(KashiReleasePolicy.RELEASES_URL.contains("/releases/latest"))
        assertTrue(KashiReleasePolicy.RELEASES_URL.contains("Strobingn/kalshi-odds-app"))
        assertNull(KashiReleasePolicy.choose(KashiReleasePolicy.parse(json), "0.3.22"))
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
        assertTrue(gradle.contains("versionName = \"0.3.25\""))
        assertTrue(gradle.contains("versionCode = 40"))
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
