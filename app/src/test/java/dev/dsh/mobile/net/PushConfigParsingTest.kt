package dev.dsh.mobile.net

import dev.dsh.mobile.data.PUSH_TOPIC_MAX_CHARS
import dev.dsh.mobile.data.PUSH_TOPIC_RANDOM_LENGTH
import dev.dsh.mobile.data.generatePushTopic
import dev.dsh.mobile.data.pushConfigValid
import dev.dsh.mobile.data.pushServerError
import dev.dsh.mobile.data.pushServerValid
import dev.dsh.mobile.data.pushTopicValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM tests for the ntfy push contract: DTO decode/mapping, PUT request shape (blank token keeps
 * the host token, empty token clears it), topic/server validation and the topic generator.
 */
class PushConfigParsingTest {

    @Test
    fun `push config decodes supported host state`() {
        val json = """
            {"supported":true,"enabled":true,"server":"https://ntfy.example.com",
             "topic":"dsh-alerts_1","hasToken":true}
        """.trimIndent()
        val dto = DshJson.decodeFromString(PushConfigDto.serializer(), json)
        assertTrue(dto.supported)
        assertTrue(dto.enabled)
        assertEquals("https://ntfy.example.com", dto.server)
        assertEquals("dsh-alerts_1", dto.topic)
        assertTrue(dto.hasToken)
    }

    @Test
    fun `unsupported push config decodes with safe defaults`() {
        val dto = DshJson.decodeFromString(
            PushConfigDto.serializer(),
            """{"supported":false}""",
        )
        assertFalse(dto.supported)
        assertFalse(dto.enabled)
        assertEquals("", dto.server)
        assertEquals("", dto.topic)
        assertFalse(dto.hasToken)
    }

    @Test
    fun `put request omits null optional fields so the host keeps them`() {
        val keepToken = PushUpdateRequest(
            enabled = true,
            server = null,
            topic = "dsh-alerts",
            token = null,
        )
        val json = DshJson.encodeToString(PushUpdateRequest.serializer(), keepToken)
        assertTrue(json.contains("\"enabled\":true"))
        assertTrue(json.contains("\"topic\":\"dsh-alerts\""))
        assertFalse("a null token must not be serialized (keep stored token)", json.contains("token"))
        assertFalse("a null server must not be serialized", json.contains("server"))

        val replaceToken = PushUpdateRequest(
            enabled = true,
            server = "https://ntfy.sh",
            topic = "dsh-alerts",
            token = "tk_secret",
        )
        val withJson = DshJson.encodeToString(PushUpdateRequest.serializer(), replaceToken)
        assertTrue(withJson.contains("\"server\":\"https://ntfy.sh\""))
        assertTrue(withJson.contains("\"token\":\"tk_secret\""))
    }

    @Test
    fun `empty token explicitly clears the stored host token`() {
        val clear = PushUpdateRequest(
            enabled = true,
            server = null,
            topic = "dsh-alerts",
            token = "",
        )
        val json = DshJson.encodeToString(PushUpdateRequest.serializer(), clear)
        assertTrue("empty token must be sent to clear it", json.contains("\"token\":\"\""))
    }

    @Test
    fun `push test response decodes`() {
        val ok = DshJson.decodeFromString(PushTestResponse.serializer(), """{"ok":true}""")
        assertTrue(ok.ok)
        val notOk = DshJson.decodeFromString(PushTestResponse.serializer(), """{"ok":false}""")
        assertFalse(notOk.ok)
    }

    @Test
    fun `topic validation enforces 1-64 safe characters`() {
        assertTrue(pushTopicValid("dsh-alerts_1"))
        assertTrue(pushTopicValid("A".repeat(PUSH_TOPIC_MAX_CHARS)))
        assertFalse(pushTopicValid("A".repeat(PUSH_TOPIC_MAX_CHARS + 1)))
        assertFalse(pushTopicValid(""))
        assertFalse(pushTopicValid("   "))
        assertFalse(pushTopicValid("has space"))
        assertFalse(pushTopicValid("topic/with/slash"))
        assertFalse(pushTopicValid("topic.dot"))
        assertEquals(64, PUSH_TOPIC_MAX_CHARS)
    }

    @Test
    fun `server validation requires https except localhost`() {
        assertTrue("blank falls back to the host default", pushServerValid(""))
        assertTrue(pushServerValid("https://ntfy.sh"))
        assertTrue(pushServerValid("https://ntfy.example.com:8443/path"))
        assertTrue("loopback http is allowed", pushServerValid("http://localhost:8080"))
        assertTrue(pushServerValid("http://127.0.0.1:8080"))
        assertTrue(pushServerValid("https://127.0.0.1:8443"))

        assertFalse("plain http to a public server is unsafe", pushServerValid("http://ntfy.sh"))
        assertFalse("plain http to a private LAN host is rejected", pushServerValid("http://192.168.1.5:8080"))
        assertFalse("private LAN hosts are unreachable", pushServerValid("https://192.168.1.5"))
        assertFalse(pushServerValid("https://10.0.0.5"))
        assertFalse(pushServerValid("https://172.20.0.5"))
        assertFalse(pushServerValid("ntfy.sh"))
        assertFalse(pushServerValid("ftp://ntfy.sh"))
        assertFalse(pushServerValid("https://"))
        assertFalse(pushServerValid("not a url"))
    }

    @Test
    fun `server errors explain the https and private host rules`() {
        assertNull(pushServerError(""))
        assertNull(pushServerError("https://ntfy.sh"))
        assertNotNull(pushServerError("http://ntfy.sh"))
        assertTrue(pushServerError("http://ntfy.sh").orEmpty().contains("https"))
        assertTrue(pushServerError("http://ntfy.sh").orEmpty().contains("localhost"))
        assertNotNull(pushServerError("https://192.168.1.5"))
        assertTrue(pushServerError("https://192.168.1.5").orEmpty().contains("Private/loopback"))
        assertNotNull(pushServerError("not a url"))
    }

    @Test
    fun `save is only allowed when enabled config is valid`() {
        assertTrue("disabled config can always be saved", pushConfigValid(false, "", ""))
        assertTrue(pushConfigValid(true, "", "dsh-alerts"))
        assertTrue(pushConfigValid(true, "https://ntfy.example.com", "dsh-alerts"))
        assertTrue(pushConfigValid(true, "http://localhost:8080", "dsh-alerts"))
        assertFalse(pushConfigValid(true, "", ""))
        assertFalse(pushConfigValid(true, "", "bad topic"))
        assertFalse(pushConfigValid(true, "http://ntfy.sh", "dsh-alerts"))
        assertFalse(pushConfigValid(true, "https://192.168.1.5", "dsh-alerts"))
    }

    @Test
    fun `topic generator returns topic-safe dsh token`() {
        val topic = generatePushTopic(Random(7))
        assertTrue(topic.startsWith("dsh-"))
        assertEquals("dsh-".length + PUSH_TOPIC_RANDOM_LENGTH, topic.length)
        assertTrue("generated topic must satisfy the topic rule", pushTopicValid(topic))
        assertTrue(topic.removePrefix("dsh-").all { it in 'a'..'z' || it in '0'..'9' })
        assertTrue(generatePushTopic(Random(7)) == topic)
        assertTrue(generatePushTopic(Random(8)) != topic)
    }
}
