package dev.dsh.mobile.net

import dev.dsh.mobile.data.HOST_QUEUE_MAX_ITEMS
import dev.dsh.mobile.data.HOST_QUEUE_PREVIEW_CHARS
import dev.dsh.mobile.data.HostQueue
import dev.dsh.mobile.data.HostQueueItem
import dev.dsh.mobile.data.QUEUE_ACTION_EDIT
import dev.dsh.mobile.data.QUEUE_ACTION_REMOVE
import dev.dsh.mobile.data.QUEUE_ACTION_STEER
import dev.dsh.mobile.data.QUEUE_TARGET_NEXT_STEP
import dev.dsh.mobile.data.QUEUE_TARGET_NEXT_TURN
import dev.dsh.mobile.data.hostQueueActionValid
import dev.dsh.mobile.data.hostQueueAtCap
import dev.dsh.mobile.data.hostQueueCanSteer
import dev.dsh.mobile.data.hostQueueEditTextValid
import dev.dsh.mobile.data.hostQueueEditable
import dev.dsh.mobile.data.hostQueueItemPreview
import dev.dsh.mobile.data.hostQueueTargetLabel
import dev.dsh.mobile.data.queueItemAttachmentLabel
import dev.dsh.mobile.data.queueItemPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the host queue contract: GET decode, PATCH request/response shape, item/action
 * validation and the preview/target/attachment helpers used by the chat strip and dialog.
 */
class HostQueueParsingTest {

    @Test
    fun `host queue decodes items and optional fields`() {
        val json = """
            {"supported":true,"live":true,"items":[
              {"itemId":"q1","text":"Run the release checks first.","target":"next-turn",
               "attachmentCount":0,"createdAt":"2026-11-02T09:00:00Z"},
              {"itemId":"q2","text":"Then update the changelog.","target":"next-step",
               "attachmentCount":2}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(HostQueueResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.live)
        assertEquals(2, response.items.size)
        assertEquals("q1", response.items[0].itemId)
        assertEquals("Run the release checks first.", response.items[0].text)
        assertEquals("next-turn", response.items[0].target)
        assertEquals(0, response.items[0].attachmentCount)
        assertEquals("2026-11-02T09:00:00Z", response.items[0].createdAt)
        assertEquals("next-step", response.items[1].target)
        assertEquals(2, response.items[1].attachmentCount)
        assertNull("createdAt is optional", response.items[1].createdAt)
    }

    @Test
    fun `host queue defaults and unsupported decode`() {
        val defaults = DshJson.decodeFromString(HostQueueResponse.serializer(), """{}""")
        assertTrue(defaults.supported)
        assertFalse(defaults.live)
        assertTrue(defaults.items.isEmpty())

        val unsupported = DshJson.decodeFromString(
            HostQueueResponse.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
        assertFalse(HostQueue.Unsupported.supported)
    }

    @Test
    fun `action response decodes accepted`() {
        val accepted = DshJson.decodeFromString(
            HostQueueActionResponse.serializer(),
            """{"accepted":true}""",
        )
        assertTrue(accepted.accepted)
        val rejected = DshJson.decodeFromString(
            HostQueueActionResponse.serializer(),
            """{"accepted":false}""",
        )
        assertFalse(rejected.accepted)
    }

    @Test
    fun `patch request carries text only for edit`() {
        val edit = DshJson.encodeToString(
            HostQueueActionRequest.serializer(),
            HostQueueActionRequest(action = QUEUE_ACTION_EDIT, text = "Replacement prompt"),
        )
        assertTrue(edit.contains("\"action\":\"edit\""))
        assertTrue(edit.contains("\"text\":\"Replacement prompt\""))

        val remove = DshJson.encodeToString(
            HostQueueActionRequest.serializer(),
            HostQueueActionRequest(action = QUEUE_ACTION_REMOVE),
        )
        assertFalse(remove.contains("text"))

        val steer = DshJson.encodeToString(
            HostQueueActionRequest.serializer(),
            HostQueueActionRequest(action = QUEUE_ACTION_STEER),
        )
        assertTrue(steer.contains("\"action\":\"steer\""))
        assertFalse(steer.contains("text"))
    }

    @Test
    fun `action and edit text validation`() {
        assertTrue(hostQueueActionValid(QUEUE_ACTION_EDIT))
        assertTrue(hostQueueActionValid(QUEUE_ACTION_REMOVE))
        assertTrue(hostQueueActionValid(QUEUE_ACTION_STEER))
        assertFalse(hostQueueActionValid(""))
        assertFalse(hostQueueActionValid("delete"))

        assertTrue(hostQueueEditTextValid("new prompt"))
        assertFalse(hostQueueEditTextValid(""))
        assertFalse(hostQueueEditTextValid("   "))
    }

    @Test
    fun `steer is only available while the session is working on a next turn item`() {
        val nextTurn = HostQueueItem("q1", target = QUEUE_TARGET_NEXT_TURN)
        val nextStep = HostQueueItem("q2", target = QUEUE_TARGET_NEXT_STEP)
        assertTrue(hostQueueCanSteer(nextTurn, sessionWorking = true))
        assertFalse(hostQueueCanSteer(nextTurn, sessionWorking = false))
        assertFalse(hostQueueCanSteer(nextStep, sessionWorking = true))
        assertFalse(hostQueueCanSteer(nextStep, sessionWorking = false))
        assertEquals(
            "Next turn",
            hostQueueTargetLabel(QUEUE_TARGET_NEXT_TURN),
        )
        assertEquals("Next step", hostQueueTargetLabel(QUEUE_TARGET_NEXT_STEP))
        assertEquals("custom", hostQueueTargetLabel("custom"))
        assertEquals("Queued", hostQueueTargetLabel(""))
    }

    @Test
    fun `attachment prompts are not editable from the phone`() {
        assertTrue(hostQueueEditable(HostQueueItem("q1", attachmentCount = 0)))
        assertFalse(hostQueueEditable(HostQueueItem("q2", attachmentCount = 1)))
        assertFalse(hostQueueEditable(HostQueueItem("q3", attachmentCount = 3)))
    }

    @Test
    fun `attachment only prompts show an attachment label as preview`() {
        assertEquals(
            "2 attachments",
            hostQueueItemPreview(HostQueueItem("q1", text = "   ", attachmentCount = 2)),
        )
        assertEquals(
            "1 attachment",
            hostQueueItemPreview(HostQueueItem("q1", text = "", attachmentCount = 1)),
        )
        assertEquals(
            "(empty prompt)",
            hostQueueItemPreview(HostQueueItem("q1", text = "", attachmentCount = 0)),
        )
        assertEquals(
            "Run the tests",
            hostQueueItemPreview(HostQueueItem("q1", text = " Run the   tests ")),
        )
    }

    @Test
    fun `queue cap is detected at 100 items`() {
        assertEquals(100, HOST_QUEUE_MAX_ITEMS)
        val items = (1..HOST_QUEUE_MAX_ITEMS).map { index ->
            HostQueueItem(itemId = "q$index", text = "prompt $index")
        }
        assertTrue(hostQueueAtCap(HostQueue(supported = true, live = true, items = items)))
        assertFalse(
            hostQueueAtCap(
                HostQueue(supported = true, live = true, items = items.dropLast(1)),
            ),
        )
        assertFalse(hostQueueAtCap(HostQueue.Unsupported))
    }

    @Test
    fun `preview collapses whitespace and truncates`() {
        assertEquals(
            "Run the tests then ship",
            queueItemPreview("Run the   tests\nthen\tship"),
        )
        val long = "word ".repeat(60)
        val preview = queueItemPreview(long, limit = 40)
        assertEquals("word word word word word word word word…", preview)
        assertEquals(40, preview.length)
        val exact = "x".repeat(HOST_QUEUE_PREVIEW_CHARS)
        assertEquals(exact, queueItemPreview(exact))
        assertEquals("", queueItemPreview("   "))
    }

    @Test
    fun `attachment labels are friendly`() {
        assertNull(queueItemAttachmentLabel(0))
        assertNull(queueItemAttachmentLabel(-1))
        assertEquals("1 attachment", queueItemAttachmentLabel(1))
        assertEquals("3 attachments", queueItemAttachmentLabel(3))
    }
}
