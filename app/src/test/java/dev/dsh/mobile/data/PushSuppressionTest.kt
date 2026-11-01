package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.PushConfigDto
import dev.dsh.mobile.net.PushSuppressionDto
import dev.dsh.mobile.net.PushSuppressionSummaryDto
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Additive push-suppression contract: DTO decode, exact request body and safe payload helpers. */
class PushSuppressionTest {

    @Test
    fun `old host push config has no suppression summary`() {
        val oldHost = DshJson.decodeFromString(
            PushConfigDto.serializer(),
            """{"supported":true,"enabled":true,"server":"https://ntfy.sh","topic":"dsh-x"}""",
        )
        assertNull(oldHost.suppression)
        assertTrue(oldHost.supported)
    }

    @Test
    fun `GET push suppression summary decodes the unified public fields`() {
        val newHost = DshJson.decodeFromString(
            PushConfigDto.serializer(),
            """{"supported":true,"enabled":true,"suppression":{
                 "quietHoursEnabled":true,"quietStartHour":22,"quietEndHour":8,
                 "timeZone":"America/New_York","timeZoneValid":true,"mutedCount":3}}""",
        )
        val summary = newHost.suppression
        assertTrue(summary?.quietHoursEnabled == true)
        assertEquals(22, summary?.quietStartHour)
        assertEquals(8, summary?.quietEndHour)
        assertEquals("America/New_York", summary?.timeZone)
        assertTrue(summary?.timeZoneValid == true)
        assertEquals(3, summary?.mutedCount)
    }

    @Test
    fun `suppression summary decodes with exactly the frozen field names`() {
        val summary = DshJson.decodeFromString(
            PushSuppressionSummaryDto.serializer(),
            """{"quietHoursEnabled":true,"quietStartHour":21,"quietEndHour":7,
               "timeZone":"Europe/Berlin","timeZoneValid":false,"mutedCount":5}""",
        )
        assertTrue(summary.quietHoursEnabled)
        assertEquals(21, summary.quietStartHour)
        assertEquals(7, summary.quietEndHour)
        assertEquals("Europe/Berlin", summary.timeZone)
        assertFalse(summary.timeZoneValid)
        assertEquals(5, summary.mutedCount)

        val defaults = DshJson.decodeFromString(
            PushSuppressionSummaryDto.serializer(),
            """{}""",
        )
        assertFalse(defaults.quietHoursEnabled)
        assertEquals(22, defaults.quietStartHour)
        assertEquals(8, defaults.quietEndHour)
        assertEquals("", defaults.timeZone)
        assertFalse(defaults.timeZoneValid)
        assertEquals(0, defaults.mutedCount)
        // A stale pre-fix summary with mutedSessionIds is not part of the public contract.
        val legacy = DshJson.decodeFromString(
            PushSuppressionSummaryDto.serializer(),
            """{"quietHoursEnabled":true,"mutedSessionIds":["s1"]}""",
        )
        assertTrue(legacy.quietHoursEnabled)
        assertEquals(0, legacy.mutedCount)
    }

    @Test
    fun `suppression summary defaults when field is absent`() {
        val dto = DshJson.decodeFromString(
            PushSuppressionDto.serializer(),
            """{}""",
        )
        assertFalse(dto.quietHoursEnabled)
        assertEquals(22, dto.quietStartHour)
        assertEquals(8, dto.quietEndHour)
        assertEquals("", dto.timeZone)
        assertTrue(dto.mutedSessionIds.isEmpty())
    }

    @Test
    fun `request body has exactly the frozen fields and values`() {
        val payload = buildPushSuppressionPayload(
            quietHoursEnabled = true,
            quietStartHour = 22,
            quietEndHour = 8,
            timeZone = "America/New_York",
            mutedSessionIds = listOf("s1", "s2"),
        )
        val json = Json.parseToJsonElement(
            DshJson.encodeToString(PushSuppressionDto.serializer(), payload.toDto()),
        ).jsonObject
        assertEquals(
            setOf(
                "quietHoursEnabled",
                "quietStartHour",
                "quietEndHour",
                "timeZone",
                "mutedSessionIds",
            ),
            json.keys,
        )
        assertTrue(json.getValue("quietHoursEnabled").jsonPrimitive.boolean)
        assertEquals(22, json.getValue("quietStartHour").jsonPrimitive.int)
        assertEquals(8, json.getValue("quietEndHour").jsonPrimitive.int)
        assertEquals("America/New_York", json.getValue("timeZone").jsonPrimitive.content)
        assertEquals(
            listOf("s1", "s2"),
            json.getValue("mutedSessionIds").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `payload sanitizes hours timezone and muted ids`() {
        val payload = buildPushSuppressionPayload(
            quietHoursEnabled = true,
            quietStartHour = -5,
            quietEndHour = 28,
            timeZone = "Not/AZone",
            mutedSessionIds = listOf(" s2 ", "", "s1", "s2", "  "),
        )
        assertEquals(0, payload.quietStartHour)
        assertEquals(23, payload.quietEndHour)
        assertEquals("UTC", payload.timeZone)
        assertEquals(listOf("s1", "s2"), payload.mutedSessionIds)
    }

    @Test
    fun `valid IANA timezone is preserved and fallback is safe`() {
        assertEquals("America/New_York", sanitizedTimeZoneId(" America/New_York "))
        assertEquals("Europe/Berlin", sanitizedTimeZoneId("Europe/Berlin"))
        assertEquals("UTC", sanitizedTimeZoneId(""))
        assertEquals("UTC", sanitizedTimeZoneId(null))
        assertEquals("UTC", sanitizedTimeZoneId("Definitely/NotAZone"))
        val device = deviceTimeZoneId()
        assertTrue(device.isNotBlank())
        assertTrue(runCatching { ZoneId.of(device) }.isSuccess)
        assertEquals(500L, PUSH_SUPPRESS_DEBOUNCE_MS)
    }

    @Test
    fun `muted sessions are bounded`() {
        val many = (1..PUSH_SUPPRESS_MAX_MUTED_SESSIONS + 20).map { "s$it" }
        val payload = buildPushSuppressionPayload(
            quietHoursEnabled = false,
            quietStartHour = 22,
            quietEndHour = 8,
            timeZone = null,
            mutedSessionIds = many,
        )
        assertEquals(PUSH_SUPPRESS_MAX_MUTED_SESSIONS, payload.mutedSessionIds.size)
    }

    @Test
    fun `unsupported suppression state is explicit and silent`() {
        assertFalse(PushSuppressionState.Unsupported.supported)
        assertFalse(PushSuppressionState.Unsupported.timeZoneValid)
        assertEquals(0, PushSuppressionState.Unsupported.mutedCount)
    }
}

/** Test-only conversion mirroring the repository request mapping. */
private fun PushSuppressionPayload.toDto(): PushSuppressionDto = PushSuppressionDto(
    quietHoursEnabled = quietHoursEnabled,
    quietStartHour = quietStartHour,
    quietEndHour = quietEndHour,
    timeZone = timeZone,
    mutedSessionIds = mutedSessionIds,
)
