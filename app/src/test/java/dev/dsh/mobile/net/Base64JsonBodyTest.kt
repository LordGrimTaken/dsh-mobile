package dev.dsh.mobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Guards the streaming upload encoding: Base64JsonBody must produce the exact JSON the host
 * expects without materialising a second copy of the encoded payload as a String.
 */
class Base64JsonBodyTest {

    @Test
    fun streamsValidJsonWithExactContentLength() {
        val bytes = ByteArray(12_345) { index -> (index % 251).toByte() }
        val body = Base64JsonBody(name = "clip \"quoted\".mp4", mimeType = "video/mp4", bytes = bytes)

        val buffer = Buffer()
        body.writeTo(buffer)
        val encoded = buffer.readByteArray()

        assertEquals(body.contentLength(), encoded.size.toLong())

        val json = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
        assertEquals("clip \"quoted\".mp4", json.getValue("name").jsonPrimitive.content)
        assertEquals("video/mp4", json.getValue("mimeType").jsonPrimitive.content)
        val decoded = Base64.getDecoder().decode(json.getValue("dataBase64").jsonPrimitive.content)
        assertArrayEquals(bytes, decoded)
    }

    @Test
    fun handlesChunkBoundariesAndEmptyPayload() {
        for (size in listOf(0, 1, 2, 3, 4_095, 4_096, 4_097, 12_288, 12_289)) {
            val bytes = ByteArray(size) { index -> (index * 31 % 256).toByte() }
            val body = Base64JsonBody("f", "application/octet-stream", bytes)
            val buffer = Buffer()
            body.writeTo(buffer)
            val encoded = buffer.readByteArray()
            assertEquals("size=$size", body.contentLength(), encoded.size.toLong())
            val json = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
            val decoded = Base64.getDecoder().decode(json.getValue("dataBase64").jsonPrimitive.content)
            assertArrayEquals("size=$size", bytes, decoded)
        }
    }

    @Test
    fun reportsMonotonicProgressEndingAtOne() {
        val bytes = ByteArray(50_001) { index -> (index % 251).toByte() }
        val fractions = mutableListOf<Float>()
        val body = Base64JsonBody("f", "video/mp4", bytes, onProgress = { fractions += it })
        body.writeTo(Buffer())

        assertTrue("progress should be reported", fractions.isNotEmpty())
        assertEquals(1f, fractions.last(), 0.0001f)
        fractions.zipWithNext().forEach { (previous, next) ->
            assertTrue("progress must not go backwards: $previous -> $next", next >= previous)
        }
    }
}
