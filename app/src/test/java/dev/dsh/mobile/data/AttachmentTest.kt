package dev.dsh.mobile.data

import dev.dsh.mobile.net.AttachmentUploadRequest
import dev.dsh.mobile.net.AttachmentUploadResponse
import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.PromptRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * v0.2.2 attachment wiring: request-body serialization (upload + prompt), attachment-id list
 * handling and the local decoded/base64 size guards. The streamed upload body itself is covered by
 * Base64JsonBodyTest; pure JVM, no Android or network.
 */
class AttachmentTest {

    // region prompt body

    @Test
    fun `prompt body serializes attachment ids`() {
        val json = DshJson.encodeToString(
            PromptRequest.serializer(),
            PromptRequest(text = "Summarise these", attachmentIds = listOf("att_1", "att_2")),
        )
        assertTrue(json.contains("\"text\":\"Summarise these\""))
        assertTrue(json.contains("\"attachmentIds\":[\"att_1\",\"att_2\"]"))
    }

    @Test
    fun `prompt body omits attachmentIds when there are none`() {
        val json = DshJson.encodeToString(PromptRequest.serializer(), PromptRequest(text = "hello"))
        assertFalse(json.contains("attachmentIds"))
    }

    @Test
    fun `prompt body allows empty text when attachments are present`() {
        val json = DshJson.encodeToString(
            PromptRequest.serializer(),
            PromptRequest(text = "", attachmentIds = listOf("att_9")),
        )
        assertTrue(json.contains("\"text\":\"\""))
        assertTrue(json.contains("\"attachmentIds\":[\"att_9\"]"))
    }

    @Test
    fun `prompt request decodes attachment ids from host style json`() {
        val request = DshJson.decodeFromString(
            PromptRequest.serializer(),
            """{"text":"","attachmentIds":["att_a","att_b"]}""",
        )
        assertEquals("", request.text)
        assertEquals(listOf("att_a", "att_b"), request.attachmentIds)
    }

    @Test
    fun `attachment ids are normalized before upload references are sent`() {
        val normalized = AttachmentIds.normalize(
            listOf(" att_1 ", "", "att_2", "att_1", "   ", "att_2 "),
        )
        assertEquals(listOf("att_1", "att_2"), normalized)
        assertTrue(AttachmentIds.normalize(emptyList()).isEmpty())
    }

    // endregion

    // region upload body

    @Test
    fun `upload request body has name mimeType and unwrapped base64`() {
        val bytes = "hello".toByteArray()
        val body = AttachmentUploadRequest(
            name = "report.pdf",
            mimeType = "application/pdf",
            dataBase64 = Base64.getEncoder().encodeToString(bytes),
        )
        assertEquals("aGVsbG8=", body.dataBase64)

        val json = DshJson.encodeToString(AttachmentUploadRequest.serializer(), body)
        assertTrue(json.contains("\"name\":\"report.pdf\""))
        assertTrue(json.contains("\"mimeType\":\"application/pdf\""))
        assertTrue(json.contains("\"dataBase64\":\"aGVsbG8=\""))
        assertFalse("base64 data must not be line-wrapped", json.contains("\\n"))
    }

    @Test
    fun `upload response decodes the contract sample`() {
        val json = """
            {"attachment":{"attachmentId":"att_01J","name":"report.pdf",
             "mimeType":"application/pdf","bytes":182734,"kind":"file"}}
        """.trimIndent()
        val response = DshJson.decodeFromString(AttachmentUploadResponse.serializer(), json)
        val attachment = response.attachment
        assertEquals("att_01J", attachment?.attachmentId)
        assertEquals("report.pdf", attachment?.name)
        assertEquals(182734L, attachment?.bytes)
        assertEquals("file", attachment?.kind)
    }

    @Test
    fun `upload response tolerates extra fields and missing kind`() {
        val json = """{"attachment":{"attachmentId":"att_2","name":"x.png","mimeType":"image/png","bytes":12}}"""
        val attachment = DshJson.decodeFromString(AttachmentUploadResponse.serializer(), json).attachment
        assertEquals("att_2", attachment?.attachmentId)
        assertEquals("file", attachment?.kind)
    }

    // endregion

    // region size guard

    @Test
    fun `decoded size guard allows up to the configured cap`() {
        assertTrue(AttachmentLimits.isWithinLimit(1))
        assertTrue(AttachmentLimits.isWithinLimit(AttachmentLimits.MAX_DECODED_BYTES))
        assertFalse(AttachmentLimits.isWithinLimit(AttachmentLimits.MAX_DECODED_BYTES + 1))
        assertFalse(AttachmentLimits.isWithinLimit(0))
        assertFalse(AttachmentLimits.isWithinLimit(-1))
    }

    @Test
    fun `base64 body stays inside the host limit at the decoded cap`() {
        val atCap = AttachmentLimits.estimatedBase64Length(AttachmentLimits.MAX_DECODED_BYTES)
        assertEquals(4L * ((AttachmentLimits.MAX_DECODED_BYTES + 2L) / 3L), atCap)
        assertTrue(AttachmentLimits.isBase64WithinLimit(AttachmentLimits.MAX_DECODED_BYTES))
        assertTrue(atCap <= AttachmentLimits.MAX_BASE64_BYTES)
        assertFalse(
            // A decoded payload equal to the whole base64 budget encodes past that budget.
            AttachmentLimits.isBase64WithinLimit(AttachmentLimits.MAX_BASE64_BYTES),
        )
        assertEquals(0L, AttachmentLimits.estimatedBase64Length(0))
    }

    // endregion
}
