package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM DTO tests for the read-only approvals envelope added by the host task:
 * `GET /approvals -> {approvals:[...], supported:true, readOnly:true}`.
 */
class ApprovalsReadOnlyDtoTest {

    @Test
    fun `decodes a read-only approvals response`() {
        val json = """
            {
              "approvals": [{
                "id": "ap_1", "sessionId": "session-1",
                "title": "Permission needed: run command",
                "description": "The agent wants to run a command",
                "command": "pwsh -File tools/publish.mjs --patch",
                "createdAt": "2026-10-31T06:00:00Z"
              }],
              "supported": true,
              "readOnly": true
            }
        """.trimIndent()
        val response = DshJson.decodeFromString(ApprovalsResponse.serializer(), json)
        assertEquals(1, response.approvals.size)
        assertEquals("ap_1", response.approvals[0].id)
        assertEquals("session-1", response.approvals[0].sessionId)
        assertEquals("pwsh -File tools/publish.mjs --patch", response.approvals[0].command)
        assertTrue(response.supported)
        assertTrue(response.readOnly)
    }

    @Test
    fun `absent flags default to supported and writable`() {
        val response = DshJson.decodeFromString(
            ApprovalsResponse.serializer(),
            """{"approvals":[]}""",
        )
        assertTrue("older hosts default to supported", response.supported)
        assertFalse("older hosts default to writable", response.readOnly)
        assertTrue(response.approvals.isEmpty())
    }

    @Test
    fun `explicit false flags decode`() {
        val response = DshJson.decodeFromString(
            ApprovalsResponse.serializer(),
            """{"approvals":[],"supported":true,"readOnly":false}""",
        )
        assertTrue(response.supported)
        assertFalse(response.readOnly)
    }

    @Test
    fun `read-only response tolerates unknown fields and missing approvals`() {
        val response = DshJson.decodeFromString(
            ApprovalsResponse.serializer(),
            """{"supported":true,"readOnly":true,"futureField":42}""",
        )
        assertTrue(response.readOnly)
        assertTrue(response.approvals.isEmpty())
    }
}
