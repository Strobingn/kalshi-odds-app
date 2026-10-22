package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat: two separate threads; setting changes only through Apply and the supervisor's bounds. */
class AiChatTest {
    private val st = ScalpStrategy.KASHI_FAV_BASE

    private fun chat(tuning: ScalpTuning, reply: String) =
        AiChat(ask = { "gemini" to reply }, tuning = tuning, context = { "ctx" })

    @Test
    fun aProposalChangesNothingUntilApplied() {
        val t = ScalpTuning()
        val c = chat(t, "Wider stop.\nSET ${st.name} stopCents 25")
        c.send(AiChat.SCALPING, "tune it")
        val p = c.thread(AiChat.SCALPING).single { it.role == "proposal" }
        assertTrue(t.knobs(st).isDefault)
        assertEquals("pending", p.status)
        c.apply(p.id)
        assertTrue(!t.knobs(st).isDefault)
        assertEquals("applied", c.thread(AiChat.SCALPING).single { it.role == "proposal" }.status)
    }

    @Test
    fun cancelAndAnUnknownSettingLeaveTheSettingsAlone() {
        val t = ScalpTuning()
        val c = chat(t, "SET ${st.name} stopCents 25\nSET NOT_A_STRATEGY stopCents 5")
        c.send(AiChat.SCALPING, "x")
        val ps = c.thread(AiChat.SCALPING).filter { it.role == "proposal" }
        c.cancel(ps[0].id)
        c.apply(ps[1].id)
        assertTrue(t.knobs(st).isDefault)
        assertEquals("rejected", c.thread(AiChat.SCALPING).first { it.id == ps[1].id }.status)
    }

    @Test
    fun thePaperThreadNeverProposesAndThreadsAreSeparate() {
        val t = ScalpTuning()
        val c = chat(t, "SET ${st.name} stopCents 25\nI bet YES.")
        c.send(AiChat.PAPER, "why?")
        assertTrue(c.thread(AiChat.PAPER).none { it.role == "proposal" })
        assertTrue(c.thread(AiChat.SCALPING).isEmpty())
        assertTrue(AiChat.system(AiChat.SCALPING, "").contains("SCALPING AI"))
        assertTrue(AiChat.system(AiChat.PAPER, "").contains("PAPER AI"))
    }

    @Test
    fun noKeyShowsAnErrorBubble() {
        val c = AiChat(ask = { null }, tuning = ScalpTuning(), context = { "" })
        c.send(AiChat.PAPER, "hi")
        assertEquals("error", c.thread(AiChat.PAPER).last().role)
    }
}
