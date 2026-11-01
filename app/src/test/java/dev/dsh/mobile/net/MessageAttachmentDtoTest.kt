package dev.dsh.mobile.net

import dev.dsh.mobile.data.AssistantMessage
import dev.dsh.mobile.data.AttachmentLimits
import dev.dsh.mobile.data.MessageAttachment
import dev.dsh.mobile.data.UserMessage
import dev.dsh.mobile.data.attachmentCacheFileName
import dev.dsh.mobile.data.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the message read-back attachment contract: `Message.attachments` decoding, the
 * DTO -> UI mapping (kept independent from the local pending-upload name list) and the download
 * cache-file naming used by the repository.
 */
class MessageAttachmentDtoTest {

    @Test
    fun `message decodes optional attachments and maps them for the ui`() {
        val json = """
            {"id":"m_1","role":"user","text":"Here is the screenshot","createdAt":"2026-10-31T06:00:00Z",
             "attachments":[
               {"id":"att_1","kind":"image","name":"screenshot.png","mimeType":"image/png",
                "bytes":1234,"width":800,"height":600},
               {"id":"att_2","kind":"file","name":"report.pdf","mimeType":"application/pdf",
                "bytes":2048}
             ]}
        """.trimIndent()
        val dto = DshJson.decodeFromString(MessageDto.serializer(), json)
        assertEquals(2, dto.attachments.size)
        assertEquals("att_1", dto.attachments[0].id)
        assertEquals("image", dto.attachments[0].kind)
        assertEquals(800, dto.attachments[0].width)
        assertEquals(600, dto.attachments[0].height)
        assertNull("width is optional", dto.attachments[1].width)

        val ui = dto.toUi() as UserMessage
        assertEquals(2, ui.messageAttachments.size)
        val image = ui.messageAttachments[0]
        val file = ui.messageAttachments[1]
        assertEquals("screenshot.png", image.name)
        assertTrue(image.isImage)
        assertEquals(1234L, image.bytes)
        assertEquals(800, image.width)
        assertEquals("report.pdf", file.name)
        assertEquals(false, file.isImage)
        assertEquals(2048L, file.bytes)
    }

    @Test
    fun `absent attachments decode and map to empty lists`() {
        val user = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m_1","role":"user","text":"plain","createdAt":"2026-10-31T06:00:00Z"}""",
        )
        assertTrue(user.attachments.isEmpty())
        assertTrue((user.toUi() as UserMessage).messageAttachments.isEmpty())

        val assistant = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m_2","role":"assistant","text":"plain reply"}""",
        )
        assertTrue(assistant.attachments.isEmpty())
        assertTrue((assistant.toUi() as AssistantMessage).messageAttachments.isEmpty())
    }

    @Test
    fun `assistant messages can carry read-back attachments`() {
        val json = """
            {"id":"m_3","role":"assistant","text":"Done","createdAt":"2026-10-31T06:00:00Z",
             "attachments":[{"id":"att_9","kind":"file","name":"build.log",
                             "mimeType":"text/plain","bytes":42}]}
        """.trimIndent()
        val ui = DshJson.decodeFromString(MessageDto.serializer(), json).toUi() as AssistantMessage
        assertEquals(1, ui.messageAttachments.size)
        assertEquals("build.log", ui.messageAttachments[0].name)
    }

    @Test
    fun `image detection tolerates an omitted kind`() {
        assertTrue(MessageAttachment(id = "a", mimeType = "image/webp").isImage)
        assertTrue(MessageAttachment(id = "a", kind = "IMAGE").isImage)
        assertEquals(false, MessageAttachment(id = "a", kind = "file", mimeType = "text/plain").isImage)
    }

    @Test
    fun `cache file names are sanitized and bounded`() {
        assertEquals("att_1-report.pdf", attachmentCacheFileName("att_1", "report.pdf"))
        assertEquals("a_b-x_y.png", attachmentCacheFileName("a/b", "x y.png"))
        val long = attachmentCacheFileName("id", "x".repeat(500) + ".bin")
        assertTrue(long.length < 160)
    }

    @Test
    fun `read-back download cap is 25 MB`() {
        assertEquals(25L * 1024L * 1024L, AttachmentLimits.MAX_READ_BACK_BYTES)
    }
}
