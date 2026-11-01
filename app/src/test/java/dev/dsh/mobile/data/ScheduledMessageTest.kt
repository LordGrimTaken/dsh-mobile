package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.MessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host 0.3.6 `source` flag: scheduled prompts are marked only for the exact "schedule" value. */
class ScheduledMessageTest {

    @Test
    fun `message source decodes with and without the field`() {
        val scheduled = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m1","role":"user","text":"daily report","createdAt":"2026-11-01T10:00:00Z",
               "source":"schedule"}""",
        )
        assertEquals("schedule", scheduled.source)

        val ordinary = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m2","role":"user","text":"hello","createdAt":"2026-11-01T10:00:00Z"}""",
        )
        assertNull(ordinary.source)

        val unknown = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m3","role":"user","text":"x","source":"automation"}""",
        )
        assertEquals("automation", unknown.source)
    }

    @Test
    fun `mapping marks only exact schedule sources`() {
        val scheduled = MessageDto(
            id = "m1",
            role = "user",
            text = "daily report",
            source = "schedule",
        ).toUi() as UserMessage
        assertTrue(scheduled.scheduled)

        val padded = MessageDto(
            id = "m2",
            role = "user",
            text = "daily report",
            source = " schedule ",
        ).toUi() as UserMessage
        assertTrue(padded.scheduled)

        val ordinary = MessageDto(id = "m3", role = "user", text = "hello").toUi() as UserMessage
        assertFalse(ordinary.scheduled)

        listOf("Schedule", "SCHEDULE", "scheduled", "schedule.extra", "automation", "").forEach { source ->
            val message = MessageDto(id = "x", role = "user", text = "x", source = source)
                .toUi() as UserMessage
            assertFalse("source=$source must not mark scheduled", message.scheduled)
        }
    }

    @Test
    fun `only user messages carry the scheduled flag`() {
        val assistant = MessageDto(
            id = "a1",
            role = "assistant",
            text = "report",
            source = "schedule",
        ).toUi()
        assertTrue(assistant is AssistantMessage)
        assertFalse(assistant is UserMessage)

        val tool = MessageDto(id = "t1", role = "tool", text = "x", source = "schedule").toUi()
        assertTrue(tool is AssistantMessage)
        assertFalse(tool is UserMessage)
    }

    @Test
    fun `label and content description are accessible and non-color-only`() {
        assertEquals("Scheduled", scheduledMessageLabel(true))
        assertNull(scheduledMessageLabel(false))
        assertEquals("Scheduled", SCHEDULED_MESSAGE_LABEL)
        assertEquals("Scheduled prompt", SCHEDULED_MESSAGE_CONTENT_DESCRIPTION)
    }

    @Test
    fun `copy and transcript keep the scheduled marker`() {
        assertEquals("Scheduled: daily report", scheduledMessageCopyText("daily report", true))
        assertEquals("daily report", scheduledMessageCopyText("daily report", false))
        assertEquals("User (Scheduled)", scheduledTranscriptHeading("User", true))
        assertEquals("User", scheduledTranscriptHeading("User", false))
        assertEquals("Assistant", scheduledTranscriptHeading("Assistant", false))
    }

    @Test
    fun `exact source helper is strict`() {
        assertTrue(isScheduledMessageSource("schedule"))
        assertTrue(isScheduledMessageSource("  schedule  "))
        assertFalse(isScheduledMessageSource(null))
        assertFalse(isScheduledMessageSource(""))
        assertFalse(isScheduledMessageSource("Schedule"))
        assertFalse(isScheduledMessageSource("scheduled"))
        assertFalse(isScheduledMessageSource("schedule:1"))
    }
}
