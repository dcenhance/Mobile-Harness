package com.jarves.mh.text

import java.util.regex.Pattern

/** How a search string is read. Defaults match what a phone user expects: insensitive, plain text. */
data class SearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/**
 * One compiled search, used for both finding and replacing so that what is highlighted is exactly what
 * would be replaced.
 *
 * Matching and replacing share one compiled pattern: for a whole-word search the word boundaries are
 * built *into* the pattern as lookarounds, which keeps the user's own capture groups intact (`$1` in the
 * replacement still means the user's first group) and makes an accepted match an accepted replacement.
 * A literal search walks with [String.regionMatches] and never rewrites the text, so every index stays
 * valid, and a replacement never re-scans the text it just wrote.
 */
class SearchQuery private constructor(
    val source: String,
    val options: SearchOptions,
    private val literalNeedle: String?,
    private val pattern: Pattern?,
) {

    companion object {
        const val MAX_SOURCE_LENGTH = 400

        /** Word characters for the whole-word rule; kept identical for literal and pattern searches. */
        private const val WORD_BEFORE = "(?<![\\p{L}\\p{N}_])"
        private const val WORD_AFTER = "(?![\\p{L}\\p{N}_])"

        /** `null` when the source is unusable (empty, too long) or a pattern does not compile. */
        fun compile(source: String, options: SearchOptions = SearchOptions()): SearchQuery? {
            if (source.isEmpty() || source.length > MAX_SOURCE_LENGTH) return null
            if (!options.regex) return SearchQuery(source, options, source, null)
            return runCatching {
                val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                val body = if (options.wholeWord) "$WORD_BEFORE(?:$source)$WORD_AFTER" else source
                SearchQuery(source, options, null, Pattern.compile(body, flags))
            }.getOrNull()
        }

        /** The word-character rule as a plain function, so a literal search can apply it per hit. */
        fun isWordCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character == '_'

        fun isWholeWord(text: String, start: Int, length: Int): Boolean {
            val before = start - 1
            val after = start + length
            if (before >= 0 && isWordCharacter(text[before])) return false
            return !(after < text.length && isWordCharacter(text[after]))
        }
    }

    val isRegex: Boolean get() = options.regex

    /** Occurrences in one line, in order, never overlapping and never empty. */
    fun findAll(line: String, limit: Int = Int.MAX_VALUE): List<IntRange> {
        if (line.isEmpty() || limit <= 0) return emptyList()
        val needle = literalNeedle
        if (needle != null) {
            if (needle.length > line.length) return emptyList()
            val ranges = ArrayList<IntRange>()
            var index = 0
            val lastStart = line.length - needle.length
            while (index <= lastStart && ranges.size < limit) {
                val hit = line.regionMatches(index, needle, 0, needle.length, ignoreCase = !options.caseSensitive) &&
                    (!options.wholeWord || isWholeWord(line, index, needle.length))
                if (hit) {
                    ranges += index until index + needle.length
                    index += needle.length
                } else {
                    index++
                }
            }
            return ranges
        }
        val matcher = pattern!!.matcher(line)
        val ranges = ArrayList<IntRange>()
        while (ranges.size < limit && matcher.find()) {
            if (matcher.end() > matcher.start()) ranges += matcher.start() until matcher.end()
        }
        return ranges
    }

    /** Replaces every occurrence in one line and reports how many were replaced. */
    fun replaceInLine(line: String, replacement: String): LineReplacement {
        val needle = literalNeedle
        if (needle != null) return replaceLiteral(line, needle, replacement)
        val matcher = pattern!!.matcher(line)
        if (!matcher.find()) return LineReplacement(line, 0)
        // appendReplacement copies the text between matches itself and expands $1 / \\ in the
        // replacement, so the builder must start empty; appendTail writes the rest of the line.
        val builder = StringBuilder(line.length + replacement.length * 2)
        var count = 0
        do {
            matcher.appendReplacement(builder, replacement)
            count++
        } while (matcher.find())
        matcher.appendTail(builder)
        return LineReplacement(builder.toString(), count)
    }

    private fun replaceLiteral(line: String, needle: String, replacement: String): LineReplacement {
        if (line.isEmpty() || needle.isEmpty() || needle.length > line.length) return LineReplacement(line, 0)
        val builder = StringBuilder(line.length)
        var index = 0
        var copied = 0
        var count = 0
        val lastStart = line.length - needle.length
        while (index <= lastStart) {
            val hit = line.regionMatches(index, needle, 0, needle.length, ignoreCase = !options.caseSensitive) &&
                (!options.wholeWord || isWholeWord(line, index, needle.length))
            if (hit) {
                builder.append(line, copied, index).append(replacement)
                index += needle.length
                copied = index
                count++
            } else {
                index++
            }
        }
        if (count == 0) return LineReplacement(line, 0)
        builder.append(line, copied, line.length)
        return LineReplacement(builder.toString(), count)
    }
}

/** One line after replacing, with the number of replacements it took. */
data class LineReplacement(val text: String, val count: Int) {
    val changed: Boolean get() = count > 0
}
