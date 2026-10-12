package com.jarves.mh.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.jarves.mh.text.literalMatchRanges

/**
 * Marks every literal occurrence of [query] inside the *rendered* text of this value.
 *
 * Counting and stepping come from [com.jarves.mh.ui.chat.ChatSearch] over the message source; this
 * marker works on what is actually drawn, so a markdown reply is marked where the reader sees its
 * words even though the renderer drops markers such as `**`. [currentOccurrence] receives the
 * stronger style; pass null when only the message, not one occurrence, is the active hit.
 */
fun AnnotatedString.withLiteralHighlight(
    query: String,
    currentOccurrence: Int?,
    matchStyle: SpanStyle,
    currentStyle: SpanStyle = matchStyle,
): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return this
    val ranges = literalMatchRanges(text, query)
    if (ranges.isEmpty()) return this
    val builder = AnnotatedString.Builder(this)
    ranges.forEachIndexed { occurrence, range ->
        builder.addStyle(
            if (occurrence == currentOccurrence) currentStyle else matchStyle,
            range.first,
            range.last + 1,
        )
    }
    return builder.toAnnotatedString()
}
