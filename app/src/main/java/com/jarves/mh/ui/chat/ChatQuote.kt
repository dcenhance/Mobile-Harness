package com.jarves.mh.ui.chat

import com.jarves.mh.model.ChatMessage

/**
 * Bounded quote of a message for the composer.
 *
 * Quoting only prepares an editable draft: it never dispatches a request, so the reader decides
 * what actually leaves the device and can shorten it first.
 */
object ChatQuote {
    const val MAX_QUOTE_LINES = 6
    const val MAX_QUOTE_CHARS = 400
    const val MAX_DRAFT_CHARS = 20000

    /** Markdown quote block for [text], or an empty string when there is nothing to quote. */
    fun quote(text: String): String {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty).take(MAX_QUOTE_LINES)
        if (lines.isEmpty()) return ""
        val quoted = lines.joinToString("\n") { "> $it" }
        val bounded = if (quoted.length <= MAX_QUOTE_CHARS) quoted else quoted.take(MAX_QUOTE_CHARS).trimEnd() + "…"
        return "$bounded\n\n"
    }

    fun quote(message: ChatMessage): String = quote(message.text)

    /** Appends [quote] to a draft without discarding what the reader already typed. */
    fun append(draft: String, quote: String): String {
        if (quote.isEmpty()) return draft
        val combined = when {
            draft.isEmpty() -> quote
            draft.endsWith("\n") -> draft + quote
            else -> "$draft\n$quote"
        }
        return if (combined.length <= MAX_DRAFT_CHARS) combined else combined.take(MAX_DRAFT_CHARS)
    }
}
