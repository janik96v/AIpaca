package com.aipaca.app.agent.memory

import com.aipaca.app.data.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionSummarizerTest {

    @Test
    fun `takes the first usable line`() {
        assertEquals(
            "Discussed k-medoids versus GNN for post office siting",
            SessionSummarizer.clean("\n\nDiscussed k-medoids versus GNN for post office siting\n\nAnything else?")
        )
    }

    @Test
    fun `strips the decorations small models add`() {
        assertEquals("Fixed the gradle build", SessionSummarizer.clean("- \"Fixed the gradle build\""))
        assertEquals("Fixed the gradle build", SessionSummarizer.clean("Summary: Fixed the gradle build"))
        assertEquals("Fixed the gradle build", SessionSummarizer.clean("* Fixed the gradle build"))
    }

    @Test
    fun `truncates to the index budget`() {
        val long = "word ".repeat(200)
        assertEquals(SessionIndexStore.MAX_SUMMARY_CHARS, SessionSummarizer.clean(long).length)
    }

    @Test
    fun `empty output stays empty`() {
        assertEquals("", SessionSummarizer.clean(""))
        assertEquals("", SessionSummarizer.clean("   \n  \n"))
    }
}

class SessionViewToolTest {

    private fun message(index: Int) = MessageEntity(
        id = "m$index",
        sessionId = "s1",
        role = if (index % 2 == 0) "user" else "assistant",
        content = "message body $index",
        timestamp = index.toLong(),
        sessionTitle = "title"
    )

    @Test
    fun `short conversations render in full`() {
        val rendered = SessionViewTool.render((1..6).map { message(it) })
        (1..6).forEach { assertTrue(rendered.contains("message body $it")) }
        assertTrue(!rendered.contains("messages ..."))
    }

    @Test
    fun `long conversations keep the goal and the resolution and mark the gap`() {
        val rendered = SessionViewTool.render((1..40).map { message(it) })
        assertTrue(rendered.contains("message body 1"), "the goal must survive")
        assertTrue(rendered.contains("message body 40"), "the outcome must survive")
        assertTrue(rendered.contains("messages ..."), "the gap must be visible, not silent")
        assertTrue(!rendered.contains("message body 20"))
    }

    @Test
    fun `empty conversations render as nothing`() {
        assertEquals("", SessionViewTool.render(emptyList()))
    }
}
