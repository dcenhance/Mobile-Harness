package com.jarves.mh.ui.chat

/**
 * Folding policy for long replies.
 *
 * Only an *older* assistant reply folds: the newest message always stays open, because that is the
 * one the reader is following. A folded reply can always be reopened, and the reopening control
 * states the real line count of the message instead of a guess.
 */
object ReplyFoldPolicy {
    /** Message-text lines above which an older reply is folded on first display. */
    const val FOLD_LINES = 24

    /** The line count the fold control states: the message's own text lines. */
    fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    /** True when this reply is a candidate for folding at all, regardless of its current state. */
    fun isFoldable(text: String, fromUser: Boolean): Boolean = !fromUser && lineCount(text) > FOLD_LINES

    fun shouldFold(text: String, fromUser: Boolean, isNewestMessage: Boolean, expanded: Boolean): Boolean =
        isFoldable(text, fromUser) && !isNewestMessage && !expanded
}
