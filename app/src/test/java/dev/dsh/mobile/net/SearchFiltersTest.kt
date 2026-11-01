package dev.dsh.mobile.net

import dev.dsh.mobile.data.HostSearchResult
import dev.dsh.mobile.data.SEARCH_FILTER_ROLES
import dev.dsh.mobile.data.SEARCH_UNDATED_NOTE
import dev.dsh.mobile.data.SearchDatePreset
import dev.dsh.mobile.data.hostSearchKey
import dev.dsh.mobile.data.normalizedSearchRoles
import dev.dsh.mobile.data.searchActiveFilterSummary
import dev.dsh.mobile.data.searchDatePolicyHint
import dev.dsh.mobile.data.searchDateRange
import dev.dsh.mobile.data.searchFiltersIgnored
import dev.dsh.mobile.data.searchHasFilters
import dev.dsh.mobile.data.searchRoleQueryParam
import dev.dsh.mobile.data.toggleSearchRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * JVM tests for the host search filters: request query params, role normalization/multi-select,
 * date presets to ISO boundaries and the active-filter summary.
 */
class SearchFiltersTest {

    @Test
    fun `search response decodes optional filters`() {
        val json = """
            {"supported":true,"truncated":false,
             "filters":{"roles":["user","assistant"],"since":"2026-10-26T00:00:00Z"},
             "hits":[]}
        """.trimIndent()
        val response = DshJson.decodeFromString(SearchResponse.serializer(), json)
        assertEquals(listOf("user", "assistant"), response.filters?.roles)
        assertEquals("2026-10-26T00:00:00Z", response.filters?.since)
        assertNull(response.filters?.until)

        val withoutFilters = DshJson.decodeFromString(SearchResponse.serializer(), """{"hits":[]}""")
        assertNull(withoutFilters.filters)
    }

    @Test
    fun `query params add only the active filters`() {
        assertEquals(
            mapOf("q" to "deploy", "limit" to "50"),
            hostSearchQueryParams("deploy"),
        )
        assertEquals(
            mapOf(
                "q" to "deploy",
                "limit" to "20",
                "role" to "user,assistant",
                "since" to "2026-10-26T00:00:00Z",
            ),
            hostSearchQueryParams(
                query = "deploy",
                limit = 20,
                role = "user,assistant",
                since = "2026-10-26T00:00:00Z",
            ),
        )
        assertEquals(
            mapOf("q" to "x", "limit" to "50", "until" to "2026-11-02T00:00:00Z"),
            hostSearchQueryParams(query = "x", until = "2026-11-02T00:00:00Z"),
        )
        // Blank values are never emitted.
        assertEquals(
            mapOf("q" to "x", "limit" to "50"),
            hostSearchQueryParams(query = "x", role = " ", since = "", until = " "),
        )
    }

    @Test
    fun `role normalization keeps canonical order and drops unknown roles`() {
        assertEquals(listOf("user", "assistant", "tool"), SEARCH_FILTER_ROLES)
        assertEquals(
            listOf("user", "assistant"),
            normalizedSearchRoles(setOf("assistant", "user")).toList(),
        )
        assertTrue(normalizedSearchRoles(setOf("admin", "root")).isEmpty())
    }

    @Test
    fun `role query param is comma separated and empty means all`() {
        assertNull(searchRoleQueryParam(emptySet()))
        assertNull(searchRoleQueryParam(setOf("unknown")))
        assertEquals("user", searchRoleQueryParam(setOf("user")))
        assertEquals("assistant,tool", searchRoleQueryParam(setOf("tool", "assistant")))
        assertEquals(
            "user,assistant,tool",
            searchRoleQueryParam(setOf("tool", "user", "assistant")),
        )
    }

    @Test
    fun `role toggles are multi select and collapse to all`() {
        assertEquals(setOf("user"), toggleSearchRole(emptySet(), "user"))
        assertEquals(
            setOf("user", "assistant"),
            toggleSearchRole(setOf("user"), "assistant"),
        )
        assertEquals(emptySet<String>(), toggleSearchRole(setOf("user", "assistant"), "tool"))
        assertEquals(setOf("assistant"), toggleSearchRole(setOf("user", "assistant"), "user"))
        assertEquals(emptySet<String>(), toggleSearchRole(emptySet(), "admin"))
    }

    @Test
    fun `date presets map to explicit stable ISO windows`() {
        val now = Instant.parse("2026-11-02T12:34:56Z")
        val utc = ZoneId.of("UTC")
        assertEquals(null to null, searchDateRange(SearchDatePreset.ANY, now, utc))
        assertEquals(
            "2026-11-02T00:00:00Z" to "2026-11-02T12:34:56Z",
            searchDateRange(SearchDatePreset.TODAY, now, utc),
        )
        assertEquals(
            "2026-10-26T12:34:56Z" to "2026-11-02T12:34:56Z",
            searchDateRange(SearchDatePreset.LAST_7_DAYS, now, utc),
        )
        assertEquals(
            "2026-10-03T12:34:56Z" to "2026-11-02T12:34:56Z",
            searchDateRange(SearchDatePreset.LAST_30_DAYS, now, utc),
        )
        // The host does NOT default `until`, so every non-Any preset sends both bounds.
        SearchDatePreset.entries.filter { it != SearchDatePreset.ANY }.forEach { preset ->
            val (since, until) = searchDateRange(preset, now, utc)
            assertTrue(preset.name, since != null && until != null)
        }
    }

    @Test
    fun `today uses local midnight and values are UTC Z strings`() {
        val now = Instant.parse("2026-11-02T12:34:56Z")
        val newYork = ZoneId.of("America/New_York")
        val (since, until) = searchDateRange(SearchDatePreset.TODAY, now, newYork)
        // 00:00 EST is 05:00 UTC.
        assertEquals("2026-11-02T05:00:00Z", since)
        assertEquals(now.toString(), until)
        listOfNotNull(since, until).forEach { value ->
            assertTrue("ISO values use Z, never a + offset to URL-encode", value.endsWith("Z"))
            assertFalse(value.contains("+"))
        }
    }

    @Test
    fun `filters requested but not echoed by the host are detected`() {
        val echoed = HostSearchResult(supported = true, filtersEchoed = true)
        val notEchoed = HostSearchResult(supported = true, filtersEchoed = false)
        assertTrue(searchFiltersIgnored(filtersRequested = true, result = notEchoed))
        assertFalse(searchFiltersIgnored(filtersRequested = true, result = echoed))
        assertFalse(searchFiltersIgnored(filtersRequested = false, result = notEchoed))
        assertFalse(searchFiltersIgnored(filtersRequested = true, result = null))
        assertFalse(
            searchFiltersIgnored(
                filtersRequested = true,
                result = HostSearchResult(supported = false, filtersEchoed = false),
            ),
        )
        // An old host's response decodes with no filters object.
        val oldHost = DshJson.decodeFromString(
            SearchResponse.serializer(),
            """{"supported":true,"hits":[]}""",
        )
        assertNull(oldHost.filters)
    }

    @Test
    fun `date filter policy hints and undated note`() {
        assertNull(searchDatePolicyHint(SearchDatePreset.ANY))
        assertEquals(
            "Today: local midnight to now.",
            searchDatePolicyHint(SearchDatePreset.TODAY),
        )
        assertEquals(
            "7 days: rolling window ending now.",
            searchDatePolicyHint(SearchDatePreset.LAST_7_DAYS),
        )
        assertEquals(
            "30 days: rolling window ending now.",
            searchDatePolicyHint(SearchDatePreset.LAST_30_DAYS),
        )
        assertTrue(SEARCH_UNDATED_NOTE.contains("Undated messages"))
    }

    @Test
    fun `in-flight key includes query roles and date preset`() {
        val base = hostSearchKey("deploy", setOf("user"), SearchDatePreset.ANY)
        assertTrue(base.startsWith("deploy|user|"))
        assertEquals(
            base,
            hostSearchKey("deploy", setOf("user"), SearchDatePreset.ANY),
        )
        assertEquals(
            "deploy|user,assistant|ANY",
            hostSearchKey("deploy", setOf("user", "assistant"), SearchDatePreset.ANY),
        )
        assertFalse(base == hostSearchKey("deploy", setOf("user"), SearchDatePreset.TODAY))
        assertFalse(base == hostSearchKey("deploy", setOf("assistant"), SearchDatePreset.ANY))
        assertFalse(base == hostSearchKey("release", setOf("user"), SearchDatePreset.ANY))
    }

    @Test
    fun `active filter summary and has filters`() {
        assertFalse(searchHasFilters(emptySet(), SearchDatePreset.ANY))
        assertNull(searchActiveFilterSummary(emptySet(), SearchDatePreset.ANY))

        assertTrue(searchHasFilters(setOf("user"), SearchDatePreset.ANY))
        assertEquals(
            "Filters: user",
            searchActiveFilterSummary(setOf("user"), SearchDatePreset.ANY),
        )

        assertTrue(searchHasFilters(emptySet(), SearchDatePreset.LAST_7_DAYS))
        assertEquals(
            "Filters: 7 days",
            searchActiveFilterSummary(emptySet(), SearchDatePreset.LAST_7_DAYS),
        )

        assertEquals(
            "Filters: user, assistant · 30 days",
            searchActiveFilterSummary(setOf("assistant", "user"), SearchDatePreset.LAST_30_DAYS),
        )
    }
}
