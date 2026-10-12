package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt a user writes must reach the agent exactly as previewed, and an empty setting must change
 * nothing at all — otherwise every existing conversation would suddenly carry unfamiliar blocks.
 */
class PromptOverlayTest {

    @Test
    fun `without guidance the prompt is unchanged`() {
        val request = "Fix the failing build and tell me why it failed."
        assertEquals(request, PromptOverlay.compose(request, AgentGuidance()))
        assertTrue(AgentGuidance().isEmpty)
    }

    @Test
    fun `whitespace-only fields add nothing`() {
        val request = "Add a test."
        val guidance = AgentGuidance(instructions = "   \n\n  ", personality = "\t")
        assertEquals(request, PromptOverlay.compose(request, guidance))
        assertTrue(PromptOverlay.effective(guidance).isEmpty)
    }

    @Test
    fun `project rules come before the request`() {
        val composed = PromptOverlay.compose(
            "Add offline caching.",
            AgentGuidance(instructions = "Keep minSdk at 28."),
        )
        assertEquals(
            "<project_instructions>\nKeep minSdk at 28.\n</project_instructions>\n\nAdd offline caching.",
            composed,
        )
    }

    @Test
    fun `working style becomes its own block`() {
        val composed = PromptOverlay.compose(
            "Add offline caching.",
            AgentGuidance(personality = "Answer in short technical German."),
        )
        assertEquals(
            "<working_style>\nAnswer in short technical German.\n</working_style>\n\nAdd offline caching.",
            composed,
        )
    }

    @Test
    fun `rules are read before the style and both before the request`() {
        val composed = PromptOverlay.compose(
            "Ship it.",
            AgentGuidance(instructions = "Never touch secrets.", personality = "Be terse."),
        )
        val instructionsAt = composed.indexOf("<project_instructions>")
        val styleAt = composed.indexOf("<working_style>")
        val requestAt = composed.indexOf("Ship it.")
        assertTrue(instructionsAt in 0 until styleAt)
        assertTrue(styleAt < requestAt)
        assertTrue(composed.contains("Never touch secrets."))
        assertTrue(composed.contains("Be terse."))
    }

    @Test
    fun `the request stays last so appended attachments stay adjacent to it`() {
        val composed = PromptOverlay.compose("Look at this.", AgentGuidance(instructions = "Rule."))
        assertTrue(composed.endsWith("Look at this."))
    }

    @Test
    fun `three or more blank lines collapse and edges are trimmed`() {
        val normalized = PromptOverlay.normalize("\n\nFirst line.\n\n\n\nSecond line.\n\n ", PromptOverlay.MAX_INSTRUCTIONS)
        assertEquals("First line.\n\nSecond line.", normalized)
    }

    @Test
    fun `an overlong field is cut at the cap and marked as shortened`() {
        val long = "x".repeat(PromptOverlay.MAX_INSTRUCTIONS + 500)
        val normalized = PromptOverlay.normalize(long, PromptOverlay.MAX_INSTRUCTIONS)
        assertTrue(normalized.length <= PromptOverlay.MAX_INSTRUCTIONS)
        assertTrue(normalized.endsWith(PromptOverlay.TRUNCATION_MARKER))
    }

    @Test
    fun `text exactly at the cap is not marked`() {
        val exact = "y".repeat(PromptOverlay.MAX_INSTRUCTIONS)
        val normalized = PromptOverlay.normalize(exact, PromptOverlay.MAX_INSTRUCTIONS)
        assertEquals(PromptOverlay.MAX_INSTRUCTIONS, normalized.length)
        assertFalse(normalized.contains(PromptOverlay.TRUNCATION_MARKER))
    }

    @Test
    fun `each field is capped on its own`() {
        val effective = PromptOverlay.effective(
            AgentGuidance(
                instructions = "i".repeat(PromptOverlay.MAX_INSTRUCTIONS + 10),
                personality = "p".repeat(PromptOverlay.MAX_PERSONALITY + 10),
            ),
        )
        assertTrue(effective.instructions.length <= PromptOverlay.MAX_INSTRUCTIONS)
        assertTrue(effective.personality.length <= PromptOverlay.MAX_PERSONALITY)
    }

    @Test
    fun `the preview and the sent prompt are the same call`() {
        val guidance = AgentGuidance(instructions = "Rule.", personality = "Style.")
        assertEquals(
            PromptOverlay.compose("Do it.", guidance),
            PromptOverlay.compose("Do it.", guidance),
        )
    }

    @Test
    fun `no workspace document is written without guidance`() {
        assertNull(PromptOverlay.workspaceDocument(AgentGuidance()))
        assertNull(PromptOverlay.workspaceDocument(AgentGuidance(instructions = "  ")))
    }

    @Test
    fun `the workspace document carries the same text and names its origin`() {
        val document = PromptOverlay.workspaceDocument(
            AgentGuidance(instructions = "Keep minSdk at 28.", personality = "Be terse."),
        ) ?: error("a document is expected for non-empty guidance")
        assertTrue(document.contains("# Project guidance for coding agents"))
        assertTrue(document.contains("## Project rules"))
        assertTrue(document.contains("Keep minSdk at 28."))
        assertTrue(document.contains("## Working style"))
        assertTrue(document.contains("Be terse."))
        // Honesty: editing the file does not change what the app sends.
        assertTrue(document.contains("does not change the app's copy"))
        assertTrue(document.startsWith("# Project guidance"))
        assertEquals(".mobileharness/project-guidance.md", PromptOverlay.WORKSPACE_DOCUMENT_PATH)
    }
}
