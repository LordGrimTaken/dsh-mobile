package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for host-search pagination: page merging/dedupe, cursor guards and the “Showing N
 * matches” note. Old hosts (hasMore=false, nextOffset=null) must never appear paginated.
 */
class SearchPaginationTest {

    private fun hit(
        sessionId: String = "s1",
        messageId: String = "m1",
        snippet: String = "text",
    ) = HostSearchHit(
        sessionId = sessionId,
        sessionTitle = "Session $sessionId",
        messageId = messageId,
        role = "user",
        snippet = snippet,
        createdAt = "2026-11-01T09:00:00Z",
    )

    @Test
    fun `append keeps order and dedupes by session and message id`() {
        val first = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m1"), hit("s1", "m2")),
            offset = 0,
            nextOffset = 50,
            hasMore = true,
        )
        val page = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m2", "duplicate"), hit("s2", "m1"), hit("s1", "m3")),
            offset = 50,
            nextOffset = 100,
            hasMore = true,
        )
        val merged = appendHostSearchPage(first, page)
        assertEquals(listOf("s1:m1", "s1:m2", "s2:m1", "s1:m3"), merged.hits.map { "${it.sessionId}:${it.messageId}" })
        assertEquals("first occurrence wins", "text", merged.hits[1].snippet)
        assertEquals(50, merged.offset)
        assertEquals(100, merged.nextOffset)
        assertTrue(merged.hasMore)
    }

    @Test
    fun `last page clears hasMore and keeps dedupe`() {
        val current = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m1")),
            offset = 0,
            nextOffset = 50,
            hasMore = true,
        )
        val page = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m1"), hit("s2", "m2")),
            offset = 50,
            nextOffset = null,
            hasMore = false,
        )
        val merged = appendHostSearchPage(current, page)
        assertEquals(2, merged.hits.size)
        assertFalse(merged.hasMore)
        assertNull(merged.nextOffset)
    }

    @Test
    fun `old host results are never paginated`() {
        val legacy = HostSearchResult(supported = true, hits = listOf(hit()))
        assertFalse(canLoadMoreHostSearch(legacy, freshLoading = false, loadingMore = false))
        val noCursor = legacy.copy(hasMore = true, nextOffset = null)
        assertFalse(canLoadMoreHostSearch(noCursor, freshLoading = false, loadingMore = false))
        val outOfRange = legacy.copy(hasMore = true, nextOffset = HOST_SEARCH_MAX_OFFSET + 1)
        assertFalse(canLoadMoreHostSearch(outOfRange, freshLoading = false, loadingMore = false))
        assertFalse(
            "unsupported hosts never paginate",
            canLoadMoreHostSearch(HostSearchResult.Unsupported, freshLoading = false, loadingMore = false),
        )
        assertFalse(
            "no result yet",
            canLoadMoreHostSearch(null, freshLoading = false, loadingMore = false),
        )
    }

    @Test
    fun `load more requires no fresh or in-flight page`() {
        val paged = HostSearchResult(
            supported = true,
            hits = listOf(hit()),
            offset = 0,
            nextOffset = 50,
            hasMore = true,
        )
        assertTrue(canLoadMoreHostSearch(paged, freshLoading = false, loadingMore = false))
        assertFalse(canLoadMoreHostSearch(paged, freshLoading = true, loadingMore = false))
        assertFalse(canLoadMoreHostSearch(paged, freshLoading = false, loadingMore = true))
        assertTrue(
            "the contract's maximum cursor is allowed",
            canLoadMoreHostSearch(
                paged.copy(nextOffset = HOST_SEARCH_MAX_OFFSET),
                freshLoading = false,
                loadingMore = false,
            ),
        )
    }

    @Test
    fun `showing note pluralizes`() {
        assertEquals("Showing 0 matches", searchShowingNote(0))
        assertEquals("Showing 1 match", searchShowingNote(1))
        assertEquals("Showing 50 matches", searchShowingNote(50))
    }

    @Test
    fun `nextOffset must advance and pages must not be empty`() {
        val base = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m1")),
            offset = 50,
            nextOffset = 100,
            hasMore = true,
        )
        assertTrue(canLoadMoreHostSearch(base, freshLoading = false, loadingMore = false))
        assertFalse(
            "nextOffset equal to the current offset would repeat a page",
            canLoadMoreHostSearch(base.copy(nextOffset = 50), false, false),
        )
        assertFalse(
            "nextOffset behind the current offset is stale",
            canLoadMoreHostSearch(base.copy(nextOffset = 25), false, false),
        )
        assertFalse(
            "hasMore without a nextOffset stops",
            canLoadMoreHostSearch(base.copy(nextOffset = null), false, false),
        )
        assertFalse(
            "an empty result never loads another page",
            canLoadMoreHostSearch(base.copy(hits = emptyList()), false, false),
        )
    }

    @Test
    fun `stale page offset is ignored and keeps current hits`() {
        val current = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m1")),
            offset = 0,
            nextOffset = 50,
            hasMore = true,
            truncated = true,
        )
        val stale = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m9")),
            offset = 100,
            nextOffset = 150,
            hasMore = true,
        )
        val result = appendHostSearchPage(current, stale, requestedOffset = 50)
        assertEquals(current.hits, result.hits)
        assertEquals(50, result.nextOffset)
        assertTrue(result.truncated)
    }

    @Test
    fun `empty page stops pagination and keeps existing hits`() {
        val current = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m1")),
            offset = 0,
            nextOffset = 50,
            hasMore = true,
        )
        val emptyPage = HostSearchResult(
            supported = true,
            hits = emptyList(),
            offset = 50,
            nextOffset = 100,
            hasMore = true,
        )
        val result = appendHostSearchPage(current, emptyPage, requestedOffset = 50)
        assertEquals(current.hits, result.hits)
        assertFalse(result.hasMore)
        assertNull(result.nextOffset)
    }

    @Test
    fun `invalid hasMore without a forwarding nextOffset is sanitized away`() {
        val page = HostSearchResult(
            supported = true,
            hits = listOf(hit("s1", "m2")),
            offset = 50,
            nextOffset = null,
            hasMore = true,
        )
        val sanitized = sanitizeHostSearchPage(page)
        assertFalse(sanitized.hasMore)
        assertNull(sanitized.nextOffset)
    }

    @Test
    fun `search key changes on query filters and date but cannot encode an offset`() {
        val base = hostSearchKey("deploy", emptySet(), SearchDatePreset.ANY)
        assertEquals(base, hostSearchKey("deploy", emptySet(), SearchDatePreset.ANY))
        assertNotEquals(base, hostSearchKey("deploy other", emptySet(), SearchDatePreset.ANY))
        assertNotEquals(base, hostSearchKey("deploy", setOf("user"), SearchDatePreset.ANY))
        assertNotEquals(base, hostSearchKey("deploy", emptySet(), SearchDatePreset.TODAY))
        // The key function has no offset argument at all: a page request shares the fresh-search key,
        // so a stale page cannot append to a different query/filter result set.
        assertFalse(base.contains("offset"))
    }
}
