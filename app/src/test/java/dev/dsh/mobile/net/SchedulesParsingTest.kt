package dev.dsh.mobile.net

import dev.dsh.mobile.data.ScheduleEditableTiming
import dev.dsh.mobile.data.ScheduleTiming
import dev.dsh.mobile.data.editableScheduleTiming
import dev.dsh.mobile.data.scheduleTitleFromDraft
import dev.dsh.mobile.data.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the schedules contract: `GET /schedules` decode + mapping, the create request body
 * (afterSeconds vs dailyTime), and the pure timing validation used by the create form.
 */
class SchedulesParsingTest {

    @Test
    fun `schedules response decodes and maps rows`() {
        val json = """
            {"supported":true,"schedules":[
              {"id":"sch_1","sessionId":"session-1","sessionTitle":"Release train",
               "title":"Morning summary","instruction":"Summarize overnight logs",
               "status":"active",
               "schedule":{"kind":"daily","summary":"Daily at 08:00",
                           "nextRunAt":"2026-11-01T12:00:00Z",
                           "lastRunAt":"2026-10-31T12:00:00Z"},
               "createdAt":"2026-10-30T12:00:00Z","updatedAt":"2026-10-31T12:00:00Z"}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(SchedulesResponse.serializer(), json)
        assertTrue(response.supported)
        assertEquals(1, response.schedules.size)
        val dto = response.schedules[0]
        assertEquals("sch_1", dto.id)
        assertEquals("session-1", dto.sessionId)
        assertEquals("Release train", dto.sessionTitle)
        assertEquals("daily", dto.schedule?.kind)
        assertEquals("Daily at 08:00", dto.schedule?.summary)

        val ui = dto.toUi()
        assertEquals("Morning summary", ui.title)
        assertEquals("Release train", ui.sessionTitle)
        assertEquals("Summarize overnight logs", ui.instruction)
        assertEquals("active", ui.status)
        assertEquals("Daily at 08:00", ui.summary)
        assertEquals("2026-11-01T12:00:00Z", ui.nextRunAt)
    }

    @Test
    fun `schedules response tolerates missing optional fields and support flag`() {
        val json = """{"schedules":[{"id":"sch_2","sessionId":"session-2","title":"","instruction":"x"}]}"""
        val response = DshJson.decodeFromString(SchedulesResponse.serializer(), json)
        assertTrue("supported defaults to true", response.supported)
        val ui = response.schedules[0].toUi()
        assertEquals("Scheduled run", ui.title)
        assertNull(ui.sessionTitle)
        assertNull(ui.nextRunAt)
        assertNull(ui.lastRunAt)
        assertEquals("active", ui.status)
    }

    @Test
    fun `create request sends only the selected timing field`() {
        val after = DshJson.encodeToString(
            ScheduleCreateRequest.serializer(),
            ScheduleCreateRequest(
                title = "One-off check",
                instruction = "Check the deploy",
                afterSeconds = ScheduleTiming.afterSeconds(30),
            ),
        )
        assertTrue(after.contains("\"afterSeconds\":1800"))
        assertTrue(!after.contains("dailyTime"))
        assertTrue(!after.contains("everySeconds"))
        assertTrue("one-off requests leave the zone to the host", !after.contains("timeZone"))
        assertTrue(!after.contains("\"at\""))

        val daily = DshJson.encodeToString(
            ScheduleCreateRequest.serializer(),
            ScheduleCreateRequest(
                title = "Daily check",
                instruction = "Check the deploy",
                dailyTime = ScheduleTiming.parseDailyTime("8:05"),
                timeZone = ScheduleTiming.dailyTimeZoneId(),
            ),
        )
        assertTrue(daily.contains("\"dailyTime\":\"08:05\""))
        assertTrue("daily requests send the device zone", daily.contains("\"timeZone\":"))
        assertTrue(!daily.contains("afterSeconds"))
    }

    @Test
    fun `daily schedules carry the device IANA zone`() {
        val zone = ScheduleTiming.dailyTimeZoneId()
        assertEquals(java.time.ZoneId.systemDefault().id, zone)
        assertTrue("zone id must not be blank", zone.isNotBlank())

        val withExplicitZone = DshJson.encodeToString(
            ScheduleCreateRequest.serializer(),
            ScheduleCreateRequest(
                title = "Daily",
                instruction = "x",
                dailyTime = "09:00",
                timeZone = "America/New_York",
            ),
        )
        assertTrue(withExplicitZone.contains("\"timeZone\":\"America/New_York\""))
    }

    @Test
    fun `list renders host summaries for kinds the app does not create`() {
        val json = """
            {"supported":true,"schedules":[
              {"id":"a","sessionId":"s","title":"Every","instruction":"x","status":"active",
               "schedule":{"kind":"every","summary":"Every 2 hours",
                           "nextRunAt":"2026-11-01T10:00:00Z"}},
              {"id":"b","sessionId":"s","title":"Weekly","instruction":"x","status":"paused",
               "schedule":{"kind":"weekly","summary":"Weekly on Monday 09:00"}},
              {"id":"c","sessionId":"s","title":"Cron","instruction":"x","status":"active",
               "schedule":{"kind":"cron","summary":"Cron 0 9 * * 1-5"}},
              {"id":"d","sessionId":"s","title":"At","instruction":"x","status":"scheduled",
               "schedule":{"kind":"at","summary":"Once at 2026-11-02 08:00"}}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(SchedulesResponse.serializer(), json)
        val mapped = response.schedules.map { it.toUi() }
        assertEquals(listOf("every", "weekly", "cron", "at"), mapped.map { it.kind })
        assertEquals("Every 2 hours", mapped[0].summary)
        assertEquals("Weekly on Monday 09:00", mapped[1].summary)
        assertEquals("Cron 0 9 * * 1-5", mapped[2].summary)
        assertEquals("paused", mapped[1].status)
        assertEquals("2026-11-01T10:00:00Z", mapped[0].nextRunAt)
    }

    @Test
    fun `unsupported response still decodes and maps to an empty list`() {
        val response = DshJson.decodeFromString(
            SchedulesResponse.serializer(),
            """{"supported":false,"schedules":[]}""",
        )
        assertTrue(!response.supported)
        assertTrue(response.schedules.isEmpty())
    }

    @Test
    fun `after minutes parsing enforces the range`() {
        assertEquals(30L, ScheduleTiming.parseAfterMinutes("30"))
        assertEquals(1L, ScheduleTiming.parseAfterMinutes(" 1 "))
        assertEquals(ScheduleTiming.MAX_AFTER_MINUTES, ScheduleTiming.parseAfterMinutes("525600"))
        assertNull(ScheduleTiming.parseAfterMinutes("0"))
        assertNull(ScheduleTiming.parseAfterMinutes("-5"))
        assertNull(ScheduleTiming.parseAfterMinutes("525601"))
        assertNull(ScheduleTiming.parseAfterMinutes("abc"))
        assertNull(ScheduleTiming.parseAfterMinutes(""))
        assertEquals(1_800L, ScheduleTiming.afterSeconds(30))
        assertNull(ScheduleTiming.afterSeconds(0))
    }

    @Test
    fun `daily time parsing normalizes and validates 24h HH MM`() {
        assertEquals("08:05", ScheduleTiming.parseDailyTime("8:05"))
        assertEquals("00:00", ScheduleTiming.parseDailyTime("0:00"))
        assertEquals("23:59", ScheduleTiming.parseDailyTime("23:59"))
        assertNull(ScheduleTiming.parseDailyTime("24:00"))
        assertNull(ScheduleTiming.parseDailyTime("12:60"))
        assertNull(ScheduleTiming.parseDailyTime("8:5"))
        assertNull(ScheduleTiming.parseDailyTime(""))
        assertNull(ScheduleTiming.parseDailyTime("08:00am"))
    }

    @Test
    fun `delete response decodes`() {
        val response = DshJson.decodeFromString(
            ScheduleDeletedResponse.serializer(),
            """{"ok":true,"deleted":true}""",
        )
        assertTrue(response.ok)
        assertTrue(response.deleted)
    }

    @Test
    fun `update request sends only the provided fields`() {
        val timed = DshJson.encodeToString(
            ScheduleUpdateRequest.serializer(),
            ScheduleUpdateRequest(
                title = "New title",
                dailyTime = "09:00",
                timeZone = "America/New_York",
            ),
        )
        assertTrue(timed.contains("\"title\":\"New title\""))
        assertTrue(timed.contains("\"dailyTime\":\"09:00\""))
        assertTrue(timed.contains("\"timeZone\":\"America/New_York\""))
        assertTrue("instruction was not edited", !timed.contains("instruction"))
        assertTrue(!timed.contains("afterSeconds"))

        val textOnly = DshJson.encodeToString(
            ScheduleUpdateRequest.serializer(),
            ScheduleUpdateRequest(title = "Renamed", instruction = "New instruction"),
        )
        assertTrue(textOnly.contains("\"title\":\"Renamed\""))
        assertTrue(textOnly.contains("\"instruction\":\"New instruction\""))
        assertTrue("host-managed kinds keep their timing", !textOnly.contains("afterSeconds"))
        assertTrue(!textOnly.contains("dailyTime"))
        assertTrue(!textOnly.contains("timeZone"))
    }

    @Test
    fun `edit timing mapping follows the host kind`() {
        assertEquals(ScheduleEditableTiming.AfterMinutes, editableScheduleTiming("after"))
        assertEquals(ScheduleEditableTiming.Daily, editableScheduleTiming(" DAILY "))
        assertEquals(ScheduleEditableTiming.Unsupported, editableScheduleTiming("every"))
        assertEquals(ScheduleEditableTiming.Unsupported, editableScheduleTiming("weekly"))
        assertEquals(ScheduleEditableTiming.Unsupported, editableScheduleTiming("cron"))
        assertEquals(ScheduleEditableTiming.Unsupported, editableScheduleTiming("at"))
        assertEquals(ScheduleEditableTiming.Unsupported, editableScheduleTiming(""))
    }

    @Test
    fun `schedule title from draft uses the first non-blank line`() {
        assertEquals("Fix the failing test", scheduleTitleFromDraft("Fix the failing test\nmore"))
        assertEquals("Second line", scheduleTitleFromDraft("\n\n  Second line  "))
        assertEquals("Scheduled prompt", scheduleTitleFromDraft("   \n\t"))
        assertEquals(80, scheduleTitleFromDraft("x".repeat(200)).length)
    }

    @Test
    fun `updated schedule row decodes like the list shape`() {
        val json = """{"id":"sch_1","sessionId":"s","title":"Renamed","instruction":"x",
                       "status":"active",
                       "schedule":{"kind":"daily","summary":"Daily at 09:00",
                                   "nextRunAt":"2026-11-02T13:00:00Z"}}"""
        val dto = DshJson.decodeFromString(ScheduleDto.serializer(), json)
        assertEquals("Renamed", dto.title)
        assertEquals("Daily at 09:00", dto.schedule?.summary)
        assertEquals("2026-11-02T13:00:00Z", dto.toUi().nextRunAt)
    }
}
