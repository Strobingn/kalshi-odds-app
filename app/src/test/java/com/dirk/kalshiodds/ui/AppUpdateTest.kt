package com.dirk.kalshiodds.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    private fun release(
        tag: String,
        apk: String?,
        body: String = "",
        draft: Boolean = false
    ): String {
        val assets = if (apk == null) "[]" else
            """[{"name":"notes.txt","browser_download_url":"https://github.com/x/notes.txt"},
               {"name":"$apk","browser_download_url":"https://github.com/Strobingn/kalshi-odds-app/releases/download/$tag/$apk","size":123}]"""
        return """{"tag_name":"$tag","draft":$draft,"prerelease":true,"html_url":"https://github.com/Strobingn/kalshi-odds-app/releases/tag/$tag",
            "published_at":"2026-10-05T01:02:03Z","body":"$body","assets":$assets}"""
    }

    private val sample = "[" + listOf(
        release("edge-model-latest", null),
        release("arb-v1.0-Mis_bitcoin", "ArbHunter-v1.0-Mis_bitcoin-12.apk"),
        release("v1.3-Claude", "DipHunter-v1.3-Claude-61.apk", "Claude Bitcoin v1.3 (34) from `Claude` @ abc1234, run #61."),
        release("v1.2-Claude", "DipHunter-v1.2-Claude-58.apk", "Claude Bitcoin v1.2 (33) from `Claude` @ 1180cc9, run #58."),
        release("v0.3.15-main", "DipHunter-v0.3.15-main-99.apk", "DipHunter v0.3.15 (31) from `main` @ d34495b, run #99."),
        release("v1.4-Claude", "DipHunter-v1.4-Claude-70.apk", "draft", draft = true),
        release("v1.8.0-mis.1-Mis_bitcoin", "MisBitcoin-v1.8.0-mis.1-Mis_bitcoin-140.apk", "Mis Bitcoin v1.8.0-mis.1 (44) from `Mis_bitcoin` @ b5d0de6, run #140."),
        release(
            "v1.9.0-arena-claude-strategy-arena",
            "StrategyArena-v1.9.0-arena-claude-strategy-arena-150.apk",
            "Strategy Arena v1.9.0-arena (52) from `claude-strategy-arena` @ 0000000, run #150."
        ),
        release("v1.9.0-Claude", "ClaudeBitcoin-v1.9.0-Claude-230.apk", "Claude Bitcoin v1.9.0 (52) from `Claude` @ b8b8fa7, run #230.")
    ).joinToString(",") + "]"

    @Test
    fun parsesOnlyClaudeBranchAppReleasesWithAnApk() {
        val r = AppUpdate.parse(sample)
        assertEquals(listOf("v1.3-Claude", "v1.2-Claude", "v1.9.0-Claude"), r.map { it.tag })
        val newest = AppUpdate.latest(r)!!
        assertEquals("1.9.0", newest.versionName)
        assertEquals(230, newest.runNumber)
        assertEquals(52, newest.versionCode)
        assertEquals("b8b8fa7", newest.sha)
        assertEquals("ClaudeBitcoin-v1.9.0-Claude-230.apk", newest.apkName)
        assertEquals(
            "https://github.com/Strobingn/kalshi-odds-app/releases/download/v1.9.0-Claude/ClaudeBitcoin-v1.9.0-Claude-230.apk",
            newest.apkUrl
        )
        assertEquals("v1.9.0 build #230", newest.label)
    }

    @Test
    fun dashesInTheBranchOrTheVersionDoNotConfuseTheMatch() {
        // A plain version next to a dashed branch: `v1.3-claude-strategy-arena` is 1.3 on claude-strategy-arena.
        val json = "[" + release("v1.3-claude-strategy-arena", "StrategyArena-v1.3-claude-strategy-arena-7.apk") + "]"
        assertEquals("1.3", AppUpdate.parse(json, branch = "claude-strategy-arena").single().versionName)
        assertTrue("another branch's release is not ours", AppUpdate.parse(json).isEmpty())
        assertEquals(listOf("1.8.0-mis.1"), AppUpdate.parse(sample, branch = "Mis_bitcoin").map { it.versionName })
        assertEquals(listOf("1.9.0-arena"), AppUpdate.parse(sample, branch = "claude-strategy-arena").map { it.versionName })
    }

    @Test
    fun mainBranchAndOtherAppsAreNeverOffered() {
        val tags = AppUpdate.parse(sample).map { it.tag }
        assertTrue(tags.none { it.endsWith("-main") })
        assertTrue(tags.none { it.startsWith("arb-") })
        assertEquals(listOf("v0.3.15-main"), AppUpdate.parse(sample, branch = "main").map { it.tag })
    }

    @Test
    fun latestIsTheHighestRunNumber() {
        val latest = AppUpdate.latest(AppUpdate.parse(sample))!!
        assertEquals(230, latest.runNumber)
        assertNull(AppUpdate.latest(emptyList()))
    }

    @Test
    fun newerRunIsAnUpdateEvenAtTheSameVersion() {
        val latest = AppUpdate.latest(AppUpdate.parse(sample))
        assertTrue(AppUpdate.decide(latest, currentRun = 229, currentCode = 34) is AppUpdate.Status.Available)
        assertTrue(AppUpdate.decide(latest, currentRun = 230, currentCode = 34) is AppUpdate.Status.UpToDate)
        assertTrue(AppUpdate.decide(latest, currentRun = 231, currentCode = 34) is AppUpdate.Status.UpToDate)
    }

    @Test
    fun localBuildFallsBackToVersionCode() {
        val latest = AppUpdate.latest(AppUpdate.parse(sample))
        assertTrue(AppUpdate.decide(latest, currentRun = 0, currentCode = 51) is AppUpdate.Status.Available)
        assertTrue(AppUpdate.decide(latest, currentRun = 0, currentCode = 52) is AppUpdate.Status.UpToDate)
    }

    @Test
    fun nothingFoundOrBadJsonFailsWithAReason() {
        assertTrue(AppUpdate.parse("not json").isEmpty())
        assertTrue(AppUpdate.parse("""{"message":"API rate limit exceeded"}""").isEmpty())
        val status = AppUpdate.decide(null)
        assertTrue(status is AppUpdate.Status.Failed)
        assertEquals("Could not check: No Claude build with an APK was found on GitHub", AppUpdate.statusLine(status))
    }

    @Test
    fun runNumberFallsBackToTheReleaseNotes() {
        val json = "[" + release("v1.3-Claude", "ClaudeBitcoin.apk", "Claude Bitcoin v1.3 (34) from `Claude` @ abc1234, run #77.") + "]"
        assertEquals(77, AppUpdate.parse(json).single().runNumber)
    }

    @Test
    fun nonHttpsDownloadLinksAreDropped() {
        val json = """[{"tag_name":"v1.3-Claude","draft":false,"body":"","assets":[{"name":"a-5.apk","browser_download_url":"http://example.com/a-5.apk"}]}]"""
        assertTrue(AppUpdate.parse(json).isEmpty())
    }

    @Test
    fun copyIsPlain() {
        val latest = AppUpdate.latest(AppUpdate.parse(sample))!!
        assertEquals("Update available: v1.9.0 build #230", AppUpdate.statusLine(AppUpdate.Status.Available(latest)))
        assertEquals("Up to date. Newest on GitHub: v1.9.0 build #230", AppUpdate.statusLine(AppUpdate.Status.UpToDate(latest)))
        assertEquals("Checking GitHub…", AppUpdate.statusLine(AppUpdate.Status.Checking))
        assertEquals("Build #61 · abc1234", AppUpdate.buildLabel(61, "abc1234"))
        assertEquals("Local build (not from GitHub)", AppUpdate.buildLabel(0, "local"))
    }

    @Test
    fun checkerTurnsNetworkErrorsIntoAStatus() = runBlocking {
        val ok = AppUpdateChecker(fetch = { sample }).check()
        assertTrue(ok is AppUpdate.Status.Available || ok is AppUpdate.Status.UpToDate)
        val failed = AppUpdateChecker(fetch = { throw IllegalStateException("GitHub answered HTTP 500") }).check()
        assertEquals("Could not check: GitHub answered HTTP 500", AppUpdate.statusLine(failed))
    }
}
