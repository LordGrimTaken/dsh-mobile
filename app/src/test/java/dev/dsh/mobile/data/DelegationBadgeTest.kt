package dev.dsh.mobile.data

import dev.dsh.mobile.net.DelegationSummaryDto
import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.SessionSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host 0.3.4 delegation counts: DTO decode, mapping and compact badge helpers. */
class DelegationBadgeTest {

    private fun session(
        total: Int = 0,
        working: Int = 0,
    ) = Session(
        id = "s1",
        title = "Session",
        workspaceId = "ws",
        preview = "",
        updated = "now",
        day = "Today",
        delegationTotal = total,
        delegationWorking = working,
    )

    @Test
    fun `delegation decodes with and without counts`() {
        val withCounts = DshJson.decodeFromString(
            SessionSummaryDto.serializer(),
            """{"id":"s1","title":"T","delegation":{"total":3,"working":1}}""",
        )
        assertEquals(3, withCounts.delegation?.total)
        assertEquals(1, withCounts.delegation?.working)

        val absent = DshJson.decodeFromString(
            SessionSummaryDto.serializer(),
            """{"id":"s1","title":"T"}""",
        )
        assertNull(absent.delegation)

        val defaults = DshJson.decodeFromString(
            DelegationSummaryDto.serializer(),
            """{}""",
        )
        assertEquals(0, defaults.total)
        assertEquals(0, defaults.working)
    }

    @Test
    fun `mapping carries clamped counts and clears on full refresh`() {
        val dto = SessionSummaryDto(
            id = "s1",
            title = "T",
            delegation = DelegationSummaryDto(total = -2, working = 5),
        )
        val mapped = dto.toUi()
        assertEquals(0, mapped.delegationTotal)
        assertEquals(0, mapped.delegationWorking)

        val valid = SessionSummaryDto(
            id = "s1",
            title = "T",
            delegation = DelegationSummaryDto(total = 4, working = 9),
        ).toUi()
        assertEquals(4, valid.delegationTotal)
        assertEquals(4, valid.delegationWorking)

        val existing = session(total = 3, working = 1)
        val partial = SessionSummaryDto(id = "s1", title = "T")
        val kept = partial.toUi(existing = existing, preserveDelegationWhenMissing = true)
        assertEquals(3, kept.delegationTotal)
        assertEquals(1, kept.delegationWorking)
        val cleared = partial.toUi(existing = existing)
        assertEquals(0, cleared.delegationTotal)
        assertEquals(0, cleared.delegationWorking)
    }

    @Test
    fun `zero counts hide the badge`() {
        assertNull(delegationBadgeLabel(0, 0, "agent"))
        assertNull(delegationBadgeLabel(-1, 2, "agent"))
        assertNull(delegationContentDescription(0, 0, "agent"))
    }

    @Test
    fun `labels pluralize and clamp counts`() {
        assertEquals("1 agent", delegationBadgeLabel(1, 0, "agent"))
        assertEquals("2 agents", delegationBadgeLabel(2, 0, "agent"))
        assertEquals("1 agent · 1 working", delegationBadgeLabel(1, 1, "agent"))
        assertEquals("2 agents · 1 working", delegationBadgeLabel(2, 1, "agent"))
        assertEquals("3 subagents", delegationBadgeLabel(3, 0, "subagent"))
        // working > total clamps to total
        assertEquals("2 agents · 2 working", delegationBadgeLabel(2, 9, "agent"))
        // display cap keeps narrow rows safe
        assertEquals("99+ agents", delegationBadgeLabel(150, 0, "agent"))
        assertEquals("99+ agents · 99+ working", delegationBadgeLabel(500, 400, "agent"))
        assertEquals("2 agents", delegationBadgeLabel(2, -3, "agent"))
    }

    @Test
    fun `content descriptions describe both counts`() {
        assertEquals("2 agents, 1 working", delegationContentDescription(2, 1, "agent"))
        assertEquals("1 agent", delegationContentDescription(1, 0, "agent"))
        assertEquals("3 subagents, 2 working", delegationContentDescription(3, 2, "subagent"))
        assertEquals("120 agents, 100 working", delegationContentDescription(120, 100, "agent"))
        assertNull(delegationContentDescription(-2, -2, "agent"))
    }

    @Test
    fun `delegation defaults are zero for old hosts`() {
        val oldHost = SessionSummaryDto(id = "s1", title = "T").toUi()
        assertEquals(0, oldHost.delegationTotal)
        assertEquals(0, oldHost.delegationWorking)
        assertTrue(delegationBadgeLabel(oldHost.delegationTotal, oldHost.delegationWorking, "agent") == null)
    }
}
