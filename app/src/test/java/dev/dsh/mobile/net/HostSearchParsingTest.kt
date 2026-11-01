package dev.dsh.mobile.net

import dev.dsh.mobile.data.HOST_SEARCH_LIMIT
import dev.dsh.mobile.data.HOST_SEARCH_MAX_QUERY_CHARS
import dev.dsh.mobile.data.HOST_SEARCH_MIN_QUERY_CHARS
import dev.dsh.mobile.data.hostSearchHighlightRanges
import dev.dsh.mobile.data.hostSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the host-wide search contract: GET /search decode (optional session title/time,
 * truncated/supported flags) and the pure query + snippet-highlight helpers.
 */
class HostSearchParsingTest {

    @Test
    fun `search response decodes hits with optional fields`() {
        val json = """
            {"supported":true,"truncated":true,"hits":[
              {"sessionId":"s_1","sessionTitle":"Release train","messageId":"m_1",
               "role":"assistant","snippet":"…deploy the release…",
               "createdAt":"2026-11-01T09:00:00Z"},
              {"sessionId":"s_2","messageId":"m_2","role":"user","snippet":"hello"}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(SearchResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.truncated)
        assertEquals(2, response.hits.size)
        assertEquals("Release train", response.hits[0].sessionTitle)
        assertEquals("2026-11-01T09:00:00Z", response.hits[0].createdAt)
        assertEquals("deploy the release", response.hits[0].snippet.substring(1).substringBeforeLast("…"))
        assertNull("sessionTitle is optional", response.hits[1].sessionTitle)
        assertNull("createdAt is optional", response.hits[1].createdAt)
    }

    @Test
    fun `unsupported search decodes to an empty result`() {
        val response = DshJson.decodeFromString(
            SearchResponse.serializer(),
            """{"supported":false,"hits":[]}""",
        )
        assertTrue(!response.supported)
        assertTrue(response.hits.isEmpty())
        assertTrue(!response.truncated)
    }

    @Test
    fun `search defaults are supported and not truncated`() {
        val response = DshJson.decodeFromString(
            SearchResponse.serializer(),
            """{"hits":[]}""",
        )
        assertTrue(response.supported)
        assertTrue(!response.truncated)
        // Old hosts omit pagination: no further pages, first page defaults.
        assertEquals(0, response.offset)
        assertNull(response.nextOffset)
        assertFalse(response.hasMore)
        // Old hosts also omit scan coverage: default is complete/unknown, no notice.
        assertFalse(response.scanIncomplete)
        assertEquals(0, response.scannedSessions)
        assertEquals(0, response.totalSessions)
    }

    @Test
    fun `scan coverage fields decode for host 0_2_7`() {
        val response = DshJson.decodeFromString(
            SearchResponse.serializer(),
            """{"supported":true,"scanIncomplete":true,"scannedSessions":12,
               "totalSessions":40,"hits":[]}""",
        )
        assertTrue(response.scanIncomplete)
        assertEquals(12, response.scannedSessions)
        assertEquals(40, response.totalSessions)
    }

    @Test
    fun `paged search response decodes offset and hasMore`() {
        val response = DshJson.decodeFromString(
            SearchResponse.serializer(),
            """{"supported":true,"offset":50,"nextOffset":100,"hasMore":true,
               "hits":[{"sessionId":"s","messageId":"m","role":"user","snippet":"x"}]}""",
        )
        assertEquals(50, response.offset)
        assertEquals(100, response.nextOffset)
        assertTrue(response.hasMore)
        assertEquals(1, response.hits.size)
    }

    @Test
    fun `offset is only sent for later pages`() {
        assertEquals("deploy", hostSearchQueryParams("deploy")["q"])
        assertNull("first page stays identical for old hosts", hostSearchQueryParams("deploy")["offset"])
        assertEquals("50", hostSearchQueryParams("deploy", offset = 50)["offset"])
        assertEquals("500", hostSearchQueryParams("deploy", limit = 50, offset = 500)["offset"])
        assertEquals("assistant", hostSearchQueryParams("x", role = "assistant")["role"])
    }

    @Test
    fun `queries shorter than two chars are not sent`() {
        assertNull(hostSearchQuery(""))
        assertNull(hostSearchQuery(" "))
        assertNull(hostSearchQuery("a"))
        assertNull(hostSearchQuery(" a "))
        assertEquals("ab", hostSearchQuery("  ab  "))
        assertEquals("deploy", hostSearchQuery("deploy"))
        assertEquals(HOST_SEARCH_MIN_QUERY_CHARS, 2)
    }

    @Test
    fun `long queries are capped`() {
        val long = "x".repeat(HOST_SEARCH_MAX_QUERY_CHARS + 50)
        val query = hostSearchQuery(long)
        assertEquals(HOST_SEARCH_MAX_QUERY_CHARS, query?.length)
        assertEquals(50, HOST_SEARCH_LIMIT)
    }

    @Test
    fun `highlight ranges are case-insensitive and non-overlapping`() {
        assertEquals(listOf(0 until 5, 6 until 11), hostSearchHighlightRanges("Hello hello world", "hello"))
        assertEquals(emptyList<IntRange>(), hostSearchHighlightRanges("hello", "world"))
        assertEquals(emptyList<IntRange>(), hostSearchHighlightRanges("hello", " "))
        assertEquals(listOf(0 until 2), hostSearchHighlightRanges("aaa", "aa"))
        assertEquals(listOf(3 until 6), hostSearchHighlightRanges("xx YES yy", " yes "))
        assertEquals(listOf(0 until 2), hostSearchHighlightRanges("PRIORITY queue", "pr"))
    }

    @Test
    fun `highlight handles unicode text`() {
        assertEquals(listOf(3 until 5), hostSearchHighlightRanges("先看 AB 再看", "ab"))
    }
}
