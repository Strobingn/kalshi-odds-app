package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.ui.HomeMarkets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring audit: every production path that sets or displays
 * [TicketBuilder.WINDOW_CLOSED] / [TicketBuilder.MARKET_CLOSED].
 */
class ClosedWindowPathAuditTest {

    @Test
    fun everyWindowOrMarketClosedPathIsAccountedFor() {
        val roots = listOf(
            File("app/src/main/java"),
            File("src/main/java")
        )
        val srcRoot = roots.first { it.isDirectory }
        val hits = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { idx, line ->
                    if (
                        line.contains("WINDOW_CLOSED") ||
                        line.contains("MARKET_CLOSED") ||
                        line.contains("\"Window closed\"") ||
                        line.contains("\"Market closed\"")
                    ) {
                        val rel = file.relativeTo(srcRoot).path
                        PathHit(rel, idx + 1, line.trim())
                    } else {
                        null
                    }
                }
            }
            .toList()

        val expected = setOf(
            "com/dirk/kalshiodds/signal/trade/TicketBuilder.kt",
            "com/dirk/kalshiodds/signal/trade/TicketSession.kt",
            "com/dirk/kalshiodds/signal/trade/LastOrderErrorOnce.kt",
            "com/dirk/kalshiodds/signal/trade/BetCall.kt",
            "com/dirk/kalshiodds/signal/trade/LiveOrderGates.kt",
            "com/dirk/kalshiodds/data/api/KalshiTradeClient.kt",
            "com/dirk/kalshiodds/ui/OddsViewModel.kt",
            // Scalp swarm: force-exit at window close (chip state) and the
            // settings chips that surface it. Both are display-only; scalp
            // exits never build order tickets.
            "com/dirk/kalshiodds/signal/scalp/ScalpEngine.kt",
            "com/dirk/kalshiodds/ui/SettingsScreen.kt"
        )
        // Windows walks yield '\' separators; normalize so the audit is platform-neutral.
        val files = hits.map { it.path.replace('\\', '/') }.toSet()
        assertEquals(
            "Unexpected WINDOW_CLOSED / MARKET_CLOSED files: ${files - expected}",
            expected,
            files
        )

        val session = read("com/dirk/kalshiodds/signal/trade/TicketSession.kt")
        assertTrue(session.contains("WINDOW_CLOSED_NOTICE"))
        assertTrue(session.contains("voidHoldMs"))
        assertTrue(session.contains("announcedVoidIds"))
        assertTrue(session.contains("TicketPhase.Submitting"))

        val once = read("com/dirk/kalshiodds/signal/trade/LastOrderErrorOnce.kt")
        assertTrue(once.contains("isNotAnOrderError"))
        assertTrue(once.contains("shouldClearPersisted"))
        assertTrue(once.contains("WINDOW_CLOSED_NOTICE"))
        assertTrue(once.contains("MARKET_CLOSED"))
        assertTrue(once.contains("NEXT_WINDOW_LOADING"))

        val vm = read("com/dirk/kalshiodds/ui/OddsViewModel.kt")
        assertTrue(vm.contains("LastOrderErrorOnce.accept"))
        assertTrue(vm.contains("LastOrderErrorOnce.isNotAnOrderError"))
        assertTrue(vm.contains("resolveActionWindow"))
        assertTrue(vm.contains("NEXT_WINDOW_LOADING"))
        assertTrue(vm.contains("voidTickers"))
        assertTrue(vm.contains("replaceProposals"))
        assertTrue(
            "sellPosition still uses Market closed only when no market row exists",
            vm.contains("failSoft(\"Market closed\")")
        )

        val app = read("com/dirk/kalshiodds/KalshiOddsApp.kt")
        assertTrue(app.contains("clearStaleLifecycleNotice"))

        val store = read("com/dirk/kalshiodds/signal/trade/LastOrderErrorStore.kt")
        assertTrue(store.contains("clearStaleLifecycleNotice"))
        assertTrue(store.contains("LastOrderErrorOnce.isNotAnOrderError"))

        val builder = read("com/dirk/kalshiodds/signal/trade/TicketBuilder.kt")
        assertTrue(builder.contains("const val MARKET_CLOSED"))
        assertTrue(builder.contains("const val WINDOW_CLOSED"))
        assertTrue(builder.contains("return blocked(market, want, ctx, MARKET_CLOSED"))

        val bet = read("com/dirk/kalshiodds/signal/trade/BetCall.kt")
        assertTrue(bet.contains("TicketBuilder.MARKET_CLOSED"))

        val gates = read("com/dirk/kalshiodds/signal/trade/LiveOrderGates.kt")
        assertTrue(gates.contains("\"Market closed\""))

        val client = read("com/dirk/kalshiodds/data/api/KalshiTradeClient.kt")
        assertTrue(client.contains("ticket.blockedReason ?: \"Market closed\""))

        val home = read("com/dirk/kalshiodds/ui/HomeMarkets.kt")
        assertEquals(HomeMarkets.NEXT_WINDOW_LOADING, "Next window loading")
        assertTrue(home.contains("NEXT_WINDOW_LOADING"))

        val display = read("com/dirk/kalshiodds/ui/components/TradeTicketCard.kt")
        assertTrue(display.contains("tickets.lastError"))
        assertTrue(display.contains("ticket.blockedReason"))

        val settings = read("com/dirk/kalshiodds/ui/SettingsScreen.kt")
        assertTrue(settings.contains("lastOrderError"))

        hits.forEach { hit ->
            assertFalse(
                "TODO/stub at ${hit.path}:${hit.line}",
                hit.text.contains("TODO") || hit.text.contains("FIXME")
            )
        }
    }

    private data class PathHit(val path: String, val line: Int, val text: String)

    private fun read(rel: String): String {
        val files = listOf(
            File("app/src/main/java/$rel"),
            File("src/main/java/$rel")
        )
        return files.first { it.isFile }.readText()
    }
}
