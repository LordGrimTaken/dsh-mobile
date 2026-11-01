package dev.dsh.mobile.data

import dev.dsh.mobile.net.ContextResponse
import dev.dsh.mobile.net.DshJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /sessions/{id}/context contract + pure context-meter formatting/threshold helpers. */
class ContextMeterTest {

    private fun usage(
        surface: Long? = 567_000L,
        window: Long? = 1_000_000L,
        percent: Double? = null,
        pressure: Long? = 12_000L,
        breakdown: ContextBreakdown? = null,
    ) = ContextUsage(
        surfaceTokens = surface,
        contextWindow = window,
        pressureTokens = pressure,
        percent = percent,
        breakdown = breakdown,
        sampledAt = null,
    )

    @Test
    fun `context response decodes supported fields and breakdown`() {
        val response = DshJson.decodeFromString(
            ContextResponse.serializer(),
            """{"supported":true,"surfaceTokens":567000,"contextWindow":1000000,
               "pressureTokens":12000,"percent":56.7,
               "breakdown":{"systemTokens":1000,"toolsTokens":2000,"messageTokens":564000},
               "sampledAt":"2026-11-01T10:00:00Z"}""",
        )
        assertTrue(response.supported)
        assertEquals(567_000L, response.surfaceTokens)
        assertEquals(1_000_000L, response.contextWindow)
        assertEquals(12_000L, response.pressureTokens)
        assertEquals(56.7, response.percent!!, 0.0001)
        assertEquals(564_000L, response.breakdown?.messageTokens)
        assertEquals("2026-11-01T10:00:00Z", response.sampledAt)
    }

    @Test
    fun `unsupported and missing fields default safely`() {
        val unsupported = DshJson.decodeFromString(
            ContextResponse.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
        assertNull(unsupported.surfaceTokens)
        assertNull(unsupported.contextWindow)
        assertNull(unsupported.percent)
        assertNull(unsupported.breakdown)

        val defaults = DshJson.decodeFromString(ContextResponse.serializer(), """{}""")
        assertFalse(defaults.supported)
        assertNull(defaults.sampledAt)

        val supportedEmpty = DshJson.decodeFromString(
            ContextResponse.serializer(),
            """{"supported":true}""",
        )
        assertTrue(supportedEmpty.supported)
        assertNull(supportedEmpty.contextWindow)
    }

    @Test
    fun `percent is clamped rounded and derived with pressure before surface`() {
        assertEquals(57, contextPercent(usage(percent = 56.7)))
        assertEquals(100, contextPercent(usage(percent = 140.0)))
        assertEquals(0, contextPercent(usage(percent = 0.0)))
        assertNull(contextPercent(usage(percent = -1.0)))
        assertNull(contextPercent(null))
        // C1: host formula prefers pressureTokens over surfaceTokens when percent is omitted.
        val pressurePreferred = usage(
            surface = 546_000L,
            window = 1_000_000L,
            pressure = 648_000L,
        )
        assertEquals(65, contextPercent(pressurePreferred))
        assertEquals(64.8, contextPercentRaw(pressurePreferred)!!, 0.0001)
        // Surface fallback only when pressure is absent.
        val surfaceFallback = usage(
            surface = 546_000L,
            window = 1_000_000L,
            pressure = null,
        )
        assertEquals(55, contextPercent(surfaceFallback))
        assertEquals(54.6, contextPercentRaw(surfaceFallback)!!, 0.0001)
        assertEquals(50, contextPercent(usage(surface = 500_000L, window = 1_000_000L, pressure = null)))
        assertEquals(1, contextPercent(usage(surface = 5_000L, window = 1_000_000L, pressure = null)))
        // Missing/degenerate window cannot produce a percent.
        assertNull(contextPercent(usage(surface = 5_000L, window = null, pressure = null)))
        assertNull(contextPercent(usage(surface = null, window = 1_000_000L, pressure = null)))
        assertNull(contextPercent(usage(surface = 5_000L, window = 0L, pressure = null)))
    }

    @Test
    fun `C4 meter accessibility label keeps one decimal`() {
        assertEquals(
            "Context 64.8 percent full",
            contextPercentA11yLabel(usage(percent = null, pressure = 648_000L)),
        )
        assertEquals(
            "Context 100 percent full",
            contextPercentA11yLabel(usage(percent = 140.0)),
        )
        assertEquals(
            "Context 0 percent full",
            contextPercentA11yLabel(usage(percent = 0.0)),
        )
        assertNull(contextPercentA11yLabel(usage(percent = null, pressure = null, surface = null, window = null)))
        assertNull(contextPercentA11yLabel(null))
        assertEquals(50.0, contextPercentRaw(usage(percent = 50.0))!!, 0.0001)
    }

    @Test
    fun `C6 partial data never fabricates a percent or unsupported state`() {
        // Breakdown-only: supported usage, no window/tokens -> no percent, but breakdown renders.
        val breakdownOnly = usage(
            surface = null,
            window = null,
            pressure = null,
            breakdown = ContextBreakdown(1_000L, 2_000L, 3_000L),
        )
        assertNull(contextPercent(breakdownOnly))
        assertNull(contextPercentRaw(breakdownOnly))
        assertNull(contextTokensSummary(breakdownOnly))
        assertEquals(3, orderedContextBreakdown(breakdownOnly.breakdown).size)
        assertEquals(ContextWarningLevel.Normal, contextWarningLevel(contextPercent(breakdownOnly)))
        // Surface-only: token count visible, no percent, no breakdown.
        val surfaceOnly = usage(surface = 546_000L, window = null, pressure = null)
        assertNull(contextPercent(surfaceOnly))
        assertEquals("546k tokens", contextTokensSummary(surfaceOnly))
        assertTrue(orderedContextBreakdown(surfaceOnly.breakdown).isEmpty())
        // Pressure-only: still no percent without a window.
        val pressureOnly = usage(surface = null, window = null, pressure = 12_000L)
        assertNull(contextPercent(pressureOnly))
        assertEquals("Pressure ~12k tokens", contextPressureSummary(pressureOnly))
    }

    @Test
    fun `compact token formatting rounds and preserves window decimals`() {
        assertEquals("", formatCompactTokenCount(null))
        assertEquals("", formatCompactTokenCount(-5))
        assertEquals("0", formatCompactTokenCount(0))
        assertEquals("999", formatCompactTokenCount(999))
        assertEquals("1k", formatCompactTokenCount(1_000))
        assertEquals("1.5k", formatCompactTokenCount(1_500))
        assertEquals("567k", formatCompactTokenCount(567_000))
        assertEquals("1.0M", formatCompactTokenCount(1_000_000))
        assertEquals("1.2M", formatCompactTokenCount(1_234_567))
        assertEquals("2.5G", formatCompactTokenCount(2_500_000_000L))
    }

    @Test
    fun `tokens summary and pressure degrade gracefully`() {
        assertEquals("567k / 1.0M tokens", contextTokensSummary(usage()))
        assertEquals("567k tokens", contextTokensSummary(usage(window = null)))
        assertEquals("of 1.0M tokens", contextTokensSummary(usage(surface = null)))
        assertNull(contextTokensSummary(usage(surface = null, window = null)))
        assertNull(contextTokensSummary(null))
        assertEquals("Pressure ~12k tokens", contextPressureSummary(usage()))
        assertNull(contextPressureSummary(usage(pressure = 0L)))
        assertNull(contextPressureSummary(usage(pressure = null)))
    }

    @Test
    fun `C5 warning thresholds use the rounded percent at the boundaries`() {
        assertEquals(ContextWarningLevel.Normal, contextWarningLevel(contextPercent(usage(percent = 79.4))))
        assertEquals(80, contextPercent(usage(percent = 79.9)))
        assertEquals(ContextWarningLevel.Amber, contextWarningLevel(contextPercent(usage(percent = 79.9))))
        assertEquals(ContextWarningLevel.Amber, contextWarningLevel(contextPercent(usage(percent = 80.0))))
        assertEquals(95, contextPercent(usage(percent = 94.9)))
        assertEquals(ContextWarningLevel.Red, contextWarningLevel(contextPercent(usage(percent = 94.9))))
        assertEquals(ContextWarningLevel.Red, contextWarningLevel(contextPercent(usage(percent = 95.0))))
        // Derived pressure ratios follow the same single rounding path.
        val pressureAtBoundary = usage(surface = null, window = 1_000_000L, pressure = 799_000L)
        assertEquals(80, contextPercent(pressureAtBoundary))
        assertEquals(ContextWarningLevel.Amber, contextWarningLevel(contextPercent(pressureAtBoundary)))
    }

    @Test
    fun `warning thresholds are amber at 80 and red at 95 with text labels`() {
        assertEquals(ContextWarningLevel.Normal, contextWarningLevel(null))
        assertEquals(ContextWarningLevel.Normal, contextWarningLevel(79))
        assertEquals(ContextWarningLevel.Amber, contextWarningLevel(80))
        assertEquals(ContextWarningLevel.Amber, contextWarningLevel(94))
        assertEquals(ContextWarningLevel.Red, contextWarningLevel(95))
        assertEquals(ContextWarningLevel.Red, contextWarningLevel(100))
        assertNull(contextWarningCopy(ContextWarningLevel.Normal))
        assertEquals("Context nearly full", contextWarningCopy(ContextWarningLevel.Amber))
        assertEquals("Context almost full — compact soon", contextWarningCopy(ContextWarningLevel.Red))
        assertEquals("Context nearly full", CONTEXT_WARNING_AMBER_COPY)
        assertEquals(80, CONTEXT_AMBER_THRESHOLD)
    }

    @Test
    fun `breakdown rows are ordered and missing values skipped`() {
        val breakdown = ContextBreakdown(
            systemTokens = 1_000L,
            toolsTokens = null,
            messageTokens = 564_000L,
        )
        assertEquals(
            listOf("System" to 1_000L, "Messages" to 564_000L),
            orderedContextBreakdown(breakdown),
        )
        assertEquals(
            listOf("System" to 1L, "Tools" to 2L, "Messages" to 3L),
            orderedContextBreakdown(ContextBreakdown(1L, 2L, 3L)),
        )
        assertTrue(orderedContextBreakdown(ContextBreakdown(null, null, null)).isEmpty())
        assertTrue(orderedContextBreakdown(null).isEmpty())
    }

    @Test
    fun `sampled label is sanitized and bounded`() {
        assertEquals("2026-11-01T10:00:00Z", contextSampledLabel(" 2026-11-01T10:00:00Z "))
        assertNull(contextSampledLabel("   "))
        assertNull(contextSampledLabel(null))
        assertEquals(40, contextSampledLabel("t".repeat(100))!!.length)
        assertFalse(contextSampledLabel("a\u0000b")!!.contains('\u0000'))
    }

    @Test
    fun `A1 context cache respects the thirty second TTL`() {
        val cached = HostContext.Unsupported
        val now = 1_800_000_000_000L
        assertFalse(
            shouldLoadSessionContext(cached, false, false, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionContext(cached, false, true, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionContext(
                cached,
                false,
                false,
                fetchedAtMs = now - CONTEXT_CACHE_TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(shouldLoadSessionContext(cached, true, true, fetchedAtMs = now, nowMs = now))
        assertTrue(shouldLoadSessionContext(null, false, false, nowMs = now))
        assertEquals(30_000L, CONTEXT_CACHE_TTL_MS)
    }

    @Test
    fun `copy is distinct and read-only`() {
        assertEquals("Context", CONTEXT_SECTION_TITLE)
        assertEquals("Loading context…", CONTEXT_LOADING_COPY)
        assertEquals("This host does not expose context usage.", CONTEXT_UNSUPPORTED_COPY)
        assertEquals("Couldn't load context usage.", CONTEXT_LOAD_FAILED_COPY)
        assertEquals("Context usage unknown.", CONTEXT_UNKNOWN_COPY)
        assertEquals("Retry", CONTEXT_RETRY_LABEL)
        assertEquals("Refresh", CONTEXT_REFRESH_LABEL)
        assertEquals("Breakdown (approx.)", CONTEXT_BREAKDOWN_HEADING)
        assertFalse(HostContext.Unsupported.supported)
        assertNull(HostContext.Unsupported.usage)
    }
}
