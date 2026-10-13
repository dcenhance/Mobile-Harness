package com.jarves.mh.runtime

/**
 * Standing project rules and a working style the user can write, plus how they reach the agent.
 *
 * Mobile Harness already composes the prompt that goes to the agent (request text plus an
 * `<attached_files>` block), but the user had no way to add standing guidance and no way to see the
 * composed result. [compose] is used for both the real prompt and the preview the UI shows, so what is
 * previewed is exactly what is sent — one function, not two that can drift apart.
 */
data class AgentGuidance(
    val instructions: String = "",
    val personality: String = "",
) {
    /** True when there is nothing to add and the prompt must stay byte-identical to a plain request. */
    val isEmpty: Boolean get() = instructions.isBlank() && personality.isBlank()
}

object PromptOverlay {

    /** Ceilings, so a pasted document cannot flood every turn. */
    const val MAX_INSTRUCTIONS = 4000
    const val MAX_PERSONALITY = 2000

    /** Appended when a field was shortened, so a truncated rule is never mistaken for the whole rule. */
    const val TRUNCATION_MARKER = "[shortened by Mobile Harness]"

    /**
     * Trims noise without changing meaning: leading/trailing whitespace, runs of three or more blank
     * lines (a pasted document often brings them), and nothing else. Overlong text is cut at the cap
     * and marked.
     */
    fun normalize(text: String, cap: Int): String {
        val collapsed = text.replace(Regex("\n{3,}"), "\n\n").trim()
        if (collapsed.length <= cap) return collapsed
        val marker = "\n$TRUNCATION_MARKER"
        val keep = (cap - marker.length).coerceAtLeast(0)
        return collapsed.take(keep).trimEnd() + marker
    }

    fun effective(guidance: AgentGuidance): AgentGuidance = AgentGuidance(
        instructions = normalize(guidance.instructions, MAX_INSTRUCTIONS),
        personality = normalize(guidance.personality, MAX_PERSONALITY),
    )

    /**
     * The prompt for one turn: standing rules and working style first (a model reads them before the
     * request), then the request verbatim, then whatever the caller appends (attachments). With no
     * guidance the request is returned unchanged.
     *
     * [deviceAccessNote] is the one-line hint about the read-only device bridge; it is only passed while
     * that bridge actually runs, so an agent is never told about a command that does not exist.
     */
    fun compose(request: String, guidance: AgentGuidance, deviceAccessNote: String? = null): String {
        val effective = effective(guidance)
        val blocks = buildString {
            if (effective.instructions.isNotBlank()) {
                appendLine("<project_instructions>")
                appendLine(effective.instructions)
                appendLine("</project_instructions>")
            }
            if (effective.personality.isNotBlank()) {
                appendLine("<working_style>")
                appendLine(effective.personality)
                appendLine("</working_style>")
            }
            if (!deviceAccessNote.isNullOrBlank()) {
                appendLine("<device_access>")
                appendLine(deviceAccessNote.trim())
                appendLine("</device_access>")
            }
        }
        if (blocks.isBlank()) return request
        return blocks + "\n" + request
    }

    /**
     * What the agent reads outside the prompt: the same text as a file in the workspace, so a person can
     * open it and a later agent turn can still find it. Null when both fields are empty (no file is
     * written then).
     */
    fun workspaceDocument(guidance: AgentGuidance, deviceAccessNote: String? = null): String? {
        val effective = effective(guidance)
        if (effective.isEmpty && deviceAccessNote.isNullOrBlank()) return null
        return buildString {
            appendLine("# Project guidance for coding agents")
            appendLine()
            appendLine("Written by Mobile Harness from the app's project guidance screen and added to every")
            appendLine("prompt this project sends. Editing this file alone does not change the app's copy.")
            appendLine()
            if (effective.instructions.isNotBlank()) {
                appendLine("## Project rules")
                appendLine()
                appendLine(effective.instructions)
                appendLine()
            }
            if (effective.personality.isNotBlank()) {
                appendLine("## Working style")
                appendLine()
                appendLine(effective.personality)
                appendLine()
            }
            if (!deviceAccessNote.isNullOrBlank()) {
                appendLine("## Device access")
                appendLine()
                appendLine(deviceAccessNote.trim())
                appendLine()
            }
        }
    }

    /** Workspace-relative path of the mirror file; app-owned, so no user file is ever overwritten. */
    const val WORKSPACE_DOCUMENT_PATH = ".mobileharness/project-guidance.md"
}
