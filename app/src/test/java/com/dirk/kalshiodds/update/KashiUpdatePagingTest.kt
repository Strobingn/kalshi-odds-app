package com.dirk.kalshiodds.update

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KashiUpdatePagingTest {
    private val marker = KashiReleasePolicy.APP_ID_MARKER

    private fun rel(tag: String, body: String = "$marker\n\nnotes", asset: String = KashiReleasePolicy.ASSET_NAME, draft: Boolean = false) =
        JSONObject().put("tag_name", tag).put("draft", draft).put("body", body)
            .put("assets", JSONArray().put(JSONObject().put("name", asset)))

    private fun pages(vararg p: List<JSONObject>): (String) -> String = { url ->
        val n = Regex("page=(\\d+)").find(url.substringAfter("per_page="))?.groupValues?.get(1)?.toInt() ?: 1
        JSONArray(p.getOrElse(n - 1) { emptyList() }).toString()
    }

    private fun filler(prefix: String, n: Int) = (1..n).map { rel("$prefix-$it", body = "other branch") }

    @Test fun urlIsPagedHundred() {
        assertTrue(KashiReleasePolicy.RELEASES_URL.endsWith("releases?per_page=100"))
        assertEquals("${KashiReleasePolicy.RELEASES_URL}&page=2", KashiReleasePolicy.pageUrl(2))
        assertEquals(3, KashiReleasePolicy.MAX_PAGES)
    }

    @Test fun findsOurReleaseOnPageTwoBehindOtherBranches() {
        val urls = ArrayList<String>()
        val p1 = filler("swarm", 100)
        val p2 = listOf(rel("v0.3.48-debug")) + filler("swarm2", 20)
        val c = KashiUpdateClient(fetchText = { urls += it; pages(p1, p2)(it) })
        val r = c.check("0.3.47")
        assertTrue(r.toString(), r is UpdateCheck.Available && r.release.tag == "v0.3.48-debug")
        assertEquals(2, urls.size) // short page 2 stops paging
    }

    @Test fun stopsAtThreePages() {
        val urls = ArrayList<String>()
        val full = filler("x", 100)
        val c = KashiUpdateClient(fetchText = { urls += it; JSONArray(full).toString() })
        assertEquals(UpdateCheck.UpToDate, c.check("0.3.47"))
        assertEquals(3, urls.size)
    }

    @Test fun filtersByTagPatternAndKeepsSafetyChecks() {
        val list = listOf(
            rel("v9.9.9"),                                  // not -debug
            rel("v0.3.99-debug", body = "no marker"),       // missing app-id
            rel("v0.3.98-debug", asset = "other.apk"),      // wrong asset
            rel("v0.3.97-debug", draft = true),             // draft
            rel("v0.3.47-debug"),                           // not newer
            rel("v0.3.48-debug")
        )
        val c = KashiUpdateClient(fetchText = { JSONArray(list).toString() })
        val all = c.listReleases()!!
        assertTrue(all.all { KashiReleasePolicy.TAG.matches(it.tag) })
        val r = c.check("0.3.47")
        assertTrue(r is UpdateCheck.Available && r.release.tag == "v0.3.48-debug")
        assertEquals(UpdateCheck.UpToDate, c.check("0.3.49"))
    }

    @Test fun laterPageFailureKeepsEarlierResultsFirstPageFailureFails() {
        val p1 = listOf(rel("v0.3.48-debug")) + filler("y", 99)
        val c = KashiUpdateClient(fetchText = { if (it.endsWith("page=1")) JSONArray(p1).toString() else throw IOException("boom") })
        assertTrue(c.check("0.3.47") is UpdateCheck.Available)
        val bad = KashiUpdateClient(fetchText = { throw IOException("down") })
        assertTrue(bad.check("0.3.47") is UpdateCheck.Failed)
    }

    @Test fun releaseScriptAndGuardEnforceAppIdFirstLine() {
        fun f(p: String) = listOf(File(p), File("../$p")).first { it.exists() }.readText()
        val script = f("scripts/kashi-release.sh")
        assertTrue(script.contains("MARKER=\"$marker\""))
        assertTrue(script.contains("does not start with"))
        assertTrue(script.contains("prerelease"))
        val guard = f(".github/workflows/kashi-release-guard.yml")
        assertTrue(guard.contains(marker) && guard.contains("exit 1") && guard.contains("release:"))
    }
}
