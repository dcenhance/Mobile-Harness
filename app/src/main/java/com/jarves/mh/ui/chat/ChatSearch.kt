package com.jarves.mh.ui.chat

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.text.literalMatchRanges

/** One literal hit of the active find query, addressed in the transcript. */
data class ChatSearchHit(
    val messageIndex: Int,
    val messageId: String,
    /** 0-based occurrence of the query inside this message. */
    val occurrenceInMessage: Int,
    val start: Int,
    val end: Int,
)

/**
 * Result of a conversation-wide find: every occurrence, in transcript order.
 *
 * The searched text is the saved [ChatMessage] list, so a reply that is still streaming is not
 * searchable until it is appended to the conversation. The count is literal and never rounded.
 */
data class ChatSearchResult(
    val query: String,
    val hits: List<ChatSearchHit>,
    /** True when the transcript holds more matches than [ChatSearch.MAX_HITS]. */
    val truncated: Boolean,
) {
    /** 1-based position for display, or 0 when no hit is selected. */
    fun position(currentIndex: Int): Int =
        if (hits.isEmpty()) 0 else currentIndex.coerceIn(0, hits.size - 1) + 1

    fun currentHit(currentIndex: Int): ChatSearchHit? = hits.getOrNull(currentIndex)

    /** Transcript index of the hit to reveal, or null when there is nothing to reveal. */
    fun hitMessageIndex(currentIndex: Int): Int? = currentHit(currentIndex)?.messageIndex

    /** Next/previous hit with wrap-around in both directions. Null when nothing matches. */
    fun step(currentIndex: Int, forward: Boolean): Int? {
        if (hits.isEmpty()) return null
        val base = currentIndex.coerceIn(-1, hits.size - 1)
        return when {
            base < 0 -> if (forward) 0 else hits.size - 1
            hits.size == 1 -> 0
            forward -> (base + 1) % hits.size
            else -> (base - 1 + hits.size) % hits.size
        }
    }

    /**
     * Occurrence of the active hit inside [messageIndex], or null when the active hit sits in
     * another message. The rendered text of a markdown reply can differ from its source, so a
     * caller must only rely on this for text it renders verbatim.
     */
    fun occurrenceInMessage(hit: ChatSearchHit?, messageIndex: Int): Int? =
        hit?.takeIf { it.messageIndex == messageIndex }?.occurrenceInMessage
}

object ChatSearch {
    /** Upper bound for one find. Truncation is reported instead of hidden. */
    const val MAX_HITS = 2000

    fun find(messages: List<ChatMessage>, query: String): ChatSearchResult {
        val needle = query.trim()
        if (needle.isEmpty()) return ChatSearchResult("", emptyList(), truncated = false)
        val hits = ArrayList<ChatSearchHit>()
        for ((messageIndex, message) in messages.withIndex()) {
            val ranges = literalMatchRanges(message.text, needle, MAX_HITS + 1 - hits.size)
            ranges.forEachIndexed { occurrence, range ->
                hits += ChatSearchHit(messageIndex, message.id, occurrence, range.first, range.last + 1)
            }
            if (hits.size > MAX_HITS) {
                while (hits.size > MAX_HITS) hits.removeAt(hits.lastIndex)
                return ChatSearchResult(needle, hits, truncated = true)
            }
        }
        return ChatSearchResult(needle, hits, truncated = false)
    }
}
