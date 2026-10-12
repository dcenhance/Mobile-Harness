package com.jarves.mh.text

/**
 * Literal, case-insensitive occurrence ranges of [query] inside [text].
 *
 * Matching walks the text with [String.regionMatches] and never rewrites it, so every returned
 * index addresses the original string. A `lowercase()` comparison can change the character count
 * in some locales and would silently shift the offsets of everything after the fold.
 *
 * Matches never overlap: after a hit the scan continues behind it.
 */
fun literalMatchRanges(text: String, query: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty() || limit <= 0) return emptyList()
    val ranges = ArrayList<IntRange>()
    val lastStart = text.length - needle.length
    var index = 0
    while (index <= lastStart && ranges.size < limit) {
        if (text.regionMatches(index, needle, 0, needle.length, ignoreCase = true)) {
            ranges += index until index + needle.length
            index += needle.length
        } else {
            index++
        }
    }
    return ranges
}
