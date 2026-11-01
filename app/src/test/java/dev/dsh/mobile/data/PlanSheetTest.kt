package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.TodosResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GET /sessions/{id}/todos contract + the pure plan-sheet helpers. */
class PlanSheetTest {

    private val todos = listOf(
        PlanTodo("inspect repository", PlanTodoStatus.Completed),
        PlanTodo("write tests", PlanTodoStatus.InProgress),
        PlanTodo("update docs", PlanTodoStatus.Pending),
        PlanTodo("ship it", PlanTodoStatus.Pending),
    )

    @Test
    fun `todos decode supported, unsupported and missing counts`() {
        val supported = DshJson.decodeFromString(
            TodosResponse.serializer(),
            """{"supported":true,"updatedAt":"2026-11-01T10:00:00Z",
               "counts":{"pending":2,"inProgress":1,"completed":1},
               "todos":[
                 {"content":"inspect","status":"completed"},
                 {"content":"write tests","status":"in_progress"},
                 {"content":"docs","status":"pending"}]}""",
        )
        assertTrue(supported.supported)
        assertEquals(2, supported.counts?.pending)
        assertEquals(1, supported.counts?.inProgress)
        assertEquals(1, supported.counts?.completed)
        assertEquals(3, supported.todos.size)

        val unsupported = DshJson.decodeFromString(
            TodosResponse.serializer(),
            """{"supported":false,"todos":[]}""",
        )
        assertFalse(unsupported.supported)
        assertTrue(unsupported.todos.isEmpty())

        val missingCounts = DshJson.decodeFromString(
            TodosResponse.serializer(),
            """{"supported":true,"todos":[{"content":"x","status":"pending"}]}""",
        )
        assertNull(missingCounts.counts)
        assertEquals("x", missingCounts.todos.single().content)
    }

    @Test
    fun `old hosts default to unsupported and empty`() {
        val defaults = DshJson.decodeFromString(TodosResponse.serializer(), """{}""")
        assertFalse(defaults.supported)
        assertNull(defaults.counts)
        assertTrue(defaults.todos.isEmpty())
        assertNull(defaults.updatedAt)
    }

    @Test
    fun `statuses normalize and unknown statuses are pending`() {
        assertEquals(PlanTodoStatus.Completed, normalizedPlanStatus("completed"))
        assertEquals(PlanTodoStatus.Completed, normalizedPlanStatus(" DONE "))
        assertEquals(PlanTodoStatus.InProgress, normalizedPlanStatus("in_progress"))
        assertEquals(PlanTodoStatus.InProgress, normalizedPlanStatus("in-progress"))
        assertEquals(PlanTodoStatus.InProgress, normalizedPlanStatus("working"))
        assertEquals(PlanTodoStatus.Pending, normalizedPlanStatus("pending"))
        assertEquals(PlanTodoStatus.Pending, normalizedPlanStatus(null))
        assertEquals(PlanTodoStatus.Pending, normalizedPlanStatus("mystery"))
        assertEquals("Done", PlanTodoStatus.Completed.label)
        assertEquals("In progress", PlanTodoStatus.InProgress.label)
        assertEquals("Pending", PlanTodoStatus.Pending.label)
    }

    @Test
    fun `content is sanitized and bounded`() {
        assertEquals("write tests", sanitizedPlanTodoContent("write\ntests"))
        assertEquals("a b", sanitizedPlanTodoContent("a\u0000 b"))
        assertNull(sanitizedPlanTodoContent("   "))
        assertNull(sanitizedPlanTodoContent(null))
        assertEquals(PLAN_MAX_CONTENT_CHARS, sanitizedPlanTodoContent("x".repeat(900))?.length)
    }

    @Test
    fun `counts clamp to rows and derive when absent`() {
        assertEquals(
            PlanCounts(pending = 2, inProgress = 1, completed = 1),
            planCountsFromTodos(todos),
        )
        // host counts over the list are clamped to the visible row count
        val clamped = clampedPlanCounts(
            hostCounts = PlanCounts(pending = 99, inProgress = 99, completed = 99),
            todos = todos,
        )
        assertEquals(4, clamped.total)
        assertEquals(4, clamped.completed)
        assertEquals(0, clamped.inProgress)
        assertEquals(0, clamped.pending)
        // negative host counts never surface
        val negative = clampedPlanCounts(
            hostCounts = PlanCounts(pending = -1, inProgress = -1, completed = -1),
            todos = todos,
        )
        assertEquals(4, negative.pending)
        assertEquals(0, negative.completed)
        assertEquals(0, negative.inProgress)
        assertEquals(planCountsFromTodos(todos), clampedPlanCounts(null, todos))
    }

    @Test
    fun `progress hint and label`() {
        val counts = PlanCounts(pending = 2, inProgress = 1, completed = 1)
        assertEquals("1/4", planProgressHint(counts))
        assertEquals("1 of 4 done", planProgressLabel(counts))
        assertNull(planProgressHint(PlanCounts(0, 0, 0)))
        assertEquals("0 of 0 done", planProgressLabel(PlanCounts(0, 0, 0)))
        assertEquals("Open plan, 1/4 done", planActionContentDescription(counts))
        assertEquals("Open plan", planActionContentDescription(null))
        assertEquals("Open plan", planActionContentDescription(PlanCounts(0, 0, 0)))
    }

    @Test
    fun `display order is actionable first`() {
        val ordered = orderedPlanTodos(todos)
        assertEquals(
            listOf("write tests", "update docs", "ship it", "inspect repository"),
            ordered.map { it.content },
        )
        assertEquals(PlanTodoStatus.InProgress, ordered[0].status)
        assertEquals(PlanTodoStatus.Completed, ordered.last().status)
    }

    @Test
    fun `A1 plan cache respects the thirty second TTL`() {
        val cached = HostPlan.Empty
        val now = 1_800_000_000_000L
        assertFalse(
            shouldLoadSessionPlan(cached, false, false, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionPlan(cached, false, true, fetchedAtMs = now - 1_000, nowMs = now),
        )
        assertTrue(
            shouldLoadSessionPlan(
                cached,
                false,
                false,
                fetchedAtMs = now - PLAN_CACHE_TTL_MS,
                nowMs = now,
            ),
        )
        assertFalse(shouldLoadSessionPlan(cached, true, true, fetchedAtMs = now, nowMs = now))
        assertTrue(shouldLoadSessionPlan(null, false, false, nowMs = now))
        assertEquals(30_000L, PLAN_CACHE_TTL_MS)
    }

    @Test
    fun `empty and unsupported copy are distinct`() {
        assertEquals("No plan yet.", PLAN_EMPTY_COPY)
        assertEquals("This host does not expose plans.", PLAN_UNSUPPORTED_COPY)
        assertEquals("Couldn't load the plan.", PLAN_LOAD_FAILED_COPY)
        assertEquals("Loading plan…", PLAN_LOADING_COPY)
        assertEquals("Retry", PLAN_RETRY_LABEL)
        assertEquals("Plan", PLAN_ACTION_LABEL)
        assertEquals("Plan", PLAN_SHEET_TITLE)
        assertFalse(HostPlan.Unsupported.supported)
        assertTrue(HostPlan.Empty.supported)
    }
}
