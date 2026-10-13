package com.jarves.mh.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One compiled search is used for finding *and* replacing, so these cases pin both: the same ranges a
 * highlight would draw are the ranges a replacement rewrites.
 */
class SearchQueryTest {

    private fun query(source: String, options: SearchOptions = SearchOptions()) =
        SearchQuery.compile(source, options) ?: error("pattern did not compile: $source")

    @Test
    fun `literal matching ignores case by default and keeps offsets`() {
        val q = query("hello")
        val ranges = q.findAll("Hello there, hello!")
        assertEquals(listOf(0 until 5, 13 until 18), ranges)
        assertEquals("Hello", "Hello there, hello!".substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test
    fun `literal matching can be case sensitive`() {
        val q = query("Hello", SearchOptions(caseSensitive = true))
        assertEquals(listOf(0 until 5), q.findAll("Hello there, hello!"))
    }

    @Test
    fun `matches never overlap`() {
        val q = query("aa")
        assertEquals(listOf(0 until 2, 2 until 4), q.findAll("aaaa"))
    }

    @Test
    fun `whole word only accepts standalone occurrences`() {
        val q = query("cat", SearchOptions(wholeWord = true))
        assertEquals(listOf(0 until 3, 16 until 19), q.findAll("cat catalog the cat."))
        // An underscore is a word character, so `cat_9` is not a standalone word.
        assertEquals(emptyList<IntRange>(), q.findAll("cat_9"))
        // The word sits after the bracket, so its own offsets must say so.
        assertEquals(listOf(1 until 4), q.findAll("(cat)"))
        assertEquals(emptyList<IntRange>(), q.findAll("bobcat"))
    }

    @Test
    fun `a word search can still be case sensitive`() {
        val q = query("Cat", SearchOptions(wholeWord = true, caseSensitive = true))
        assertEquals(emptyList<IntRange>(), q.findAll("cat"))
        assertEquals(listOf(0 until 3), q.findAll("Cat!"))
    }

    @Test
    fun `an empty unit is refused instead of matching everywhere`() {
        assertNull(SearchQuery.compile(""))
        assertNull(SearchQuery.compile("", SearchOptions(wholeWord = true)))
        val q = query("x")
        assertEquals(emptyList<IntRange>(), q.findAll(""))
    }

    @Test
    fun `a pattern reports its groups and offsets`() {
        val q = query("v(\\d+)\\.(\\d+)", SearchOptions(regex = true))
        val text = "version v12.4 and v9.0"
        val ranges = q.findAll(text)
        assertEquals(listOf(8 until 13, 18 until 22), ranges)
        assertEquals("v12.4", text.substring(8, 13))
        assertEquals("v9.0", text.substring(18, 22))
    }

    @Test
    fun `a pattern search is case insensitive unless asked otherwise`() {
        assertTrue(query("fun", SearchOptions(regex = true)).findAll("FUN").isNotEmpty())
        assertTrue(query("fun", SearchOptions(regex = true, caseSensitive = true)).findAll("FUN").isEmpty())
    }

    @Test
    fun `an unusable pattern is reported as such`() {
        assertNull(SearchQuery.compile("([unclosed", SearchOptions(regex = true)))
        assertNull(SearchQuery.compile(""))
        assertNull(SearchQuery.compile("x".repeat(SearchQuery.MAX_SOURCE_LENGTH + 1)))
        assertNotNull(SearchQuery.compile("([unclosed"))
        // A literal search never has to be valid regex.
        assertEquals(listOf(0 until 10), SearchQuery.compile("([unclosed")!!.findAll("([unclosed text"))
    }

    @Test
    fun `a literal replacement does not rescan what it wrote`() {
        val q = query("a")
        assertEquals("aaaa", q.replaceInLine("aa", "aa").text)
        assertEquals(2, q.replaceInLine("aa", "aa").count)
    }

    @Test
    fun `a literal replacement keeps the case of the text it replaces`() {
        val q = query("size")
        val result = q.replaceInLine("SIZE size Size", "length")
        assertEquals("length length length", result.text)
        assertEquals(3, result.count)
    }

    @Test
    fun `a word replacement leaves the parts of a word alone`() {
        val q = query("id", SearchOptions(wholeWord = true))
        val result = q.replaceInLine("id = user_id + id", "identifier")
        assertEquals("identifier = user_id + identifier", result.text)
        assertEquals(2, result.count)
    }

    @Test
    fun `a pattern replacement expands group references`() {
        val q = query("(\\w+)@(\\w+)", SearchOptions(regex = true))
        val result = q.replaceInLine("mail a@b and c@d", "$2:$1")
        assertEquals("mail b:a and d:c", result.text)
        assertEquals(2, result.count)
    }

    @Test
    fun `an unusable group reference is refused rather than written`() {
        val q = query("(\\w+)", SearchOptions(regex = true))
        val failed = runCatching { q.replaceInLine("text", "$9") }.isFailure
        assertTrue("a missing group must not be written as a literal", failed)
    }

    @Test
    fun `a line without a match is returned unchanged`() {
        val q = query("needle")
        val result = q.replaceInLine("no match here", "x")
        assertFalse(result.changed)
        assertEquals("no match here", result.text)
        assertEquals(0, result.count)
    }

    @Test
    fun `whole word boundaries hold for patterns too`() {
        val q = query("fun", SearchOptions(regex = true, wholeWord = true))
        assertEquals(listOf(0 until 3), q.findAll("fun function"))
        assertEquals(1, q.replaceInLine("fun function", "joy").count)
    }
}
