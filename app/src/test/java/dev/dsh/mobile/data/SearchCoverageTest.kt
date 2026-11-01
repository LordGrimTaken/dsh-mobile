package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host 0.2.7 scan-coverage feedback for the search results screen. */
class SearchCoverageTest {

    private fun result(
        scanIncomplete: Boolean,
        scanned: Int = 0,
        total: Int = 0,
        supported: Boolean = true,
    ) = HostSearchResult(
        supported = supported,
        scanIncomplete = scanIncomplete,
        scannedSessions = scanned,
        totalSessions = total,
    )

    @Test
    fun `old or complete hosts show no coverage notice`() {
        assertNull(searchCoverageNote(null))
        assertNull(searchCoverageNote(HostSearchResult.Unsupported))
        assertNull(searchCoverageNote(result(scanIncomplete = false, scanned = 0, total = 0)))
        assertNull(searchCoverageNote(result(scanIncomplete = false, scanned = 40, total = 40)))
    }

    @Test
    fun `incomplete scans show honest scanned vs total note`() {
        assertEquals(
            "Searched 12 of 40 sessions — results may be incomplete.",
            searchCoverageNote(result(scanIncomplete = true, scanned = 12, total = 40)),
        )
        assertEquals(
            "Searched 0 of 40 sessions — results may be incomplete.",
            searchCoverageNote(result(scanIncomplete = true, scanned = -5, total = 40)),
        )
        assertEquals(
            "Searched 40 of 40 sessions — results may be incomplete.",
            searchCoverageNote(result(scanIncomplete = true, scanned = 99, total = 40)),
        )
        assertEquals(
            SEARCH_SCAN_INCOMPLETE_NOTE,
            searchCoverageNote(result(scanIncomplete = true, scanned = 0, total = 0)),
        )
        assertTrue(SEARCH_SCAN_INCOMPLETE_NOTE.contains("could not scan every session"))
    }

    @Test
    fun `load-more pages keep the coverage warning visible`() {
        val first = HostSearchResult(
            supported = true,
            hits = listOf(
                HostSearchHit("s1", "Session", "m1", "user", "hello", null),
            ),
            offset = 0,
            nextOffset = 50,
            hasMore = true,
            scanIncomplete = true,
            scannedSessions = 12,
            totalSessions = 40,
        )
        val page = HostSearchResult(
            supported = true,
            hits = listOf(
                HostSearchHit("s2", "Session", "m2", "assistant", "world", null),
            ),
            offset = 50,
            nextOffset = null,
            hasMore = false,
        )
        val merged = appendHostSearchPage(first, page, requestedOffset = 50)
        assertTrue(merged.scanIncomplete)
        assertEquals(12, merged.scannedSessions)
        assertEquals(40, merged.totalSessions)
        assertEquals(2, merged.hits.size)
    }
}
