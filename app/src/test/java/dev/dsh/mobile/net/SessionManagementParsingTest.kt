package dev.dsh.mobile.net

import dev.dsh.mobile.data.toUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the host session-management contract: rename request/response,
 * archive/unarchive responses, and the archived sessions envelope + UI mapping.
 */
class SessionManagementParsingTest {

    @Test
    fun `rename request encodes the title`() {
        val json = DshJson.encodeToString(
            RenameSessionRequest.serializer(),
            RenameSessionRequest(title = "Release train"),
        )
        assertEquals("""{"title":"Release train"}""", json)
    }

    @Test
    fun `rename response decodes title and optional sequence`() {
        val response = DshJson.decodeFromString(
            RenameSessionResponse.serializer(),
            """{"ok":true,"title":"Release train","seq":42}""",
        )
        assertTrue(response.ok)
        assertEquals("Release train", response.title)
        assertEquals(42, response.seq)

        val withoutSeq = DshJson.decodeFromString(
            RenameSessionResponse.serializer(),
            """{"ok":true,"title":"Renamed"}""",
        )
        assertNull(withoutSeq.seq)
        assertEquals("Renamed", withoutSeq.title)
    }

    @Test
    fun `archive and unarchive responses decode`() {
        val archived = DshJson.decodeFromString(
            SessionArchiveResponse.serializer(),
            """{"ok":true,"archived":true}""",
        )
        assertTrue(archived.ok)
        assertTrue(archived.archived)

        val unarchived = DshJson.decodeFromString(
            SessionArchiveResponse.serializer(),
            """{"ok":true,"archived":false}""",
        )
        assertTrue(unarchived.ok)
        assertTrue(!unarchived.archived)
    }

    @Test
    fun `archived sessions decode and map to ui sessions`() {
        val json = """
            {"supported":true,"sessions":[
              {"id":"s_old","title":"Old investigation","workspaceId":"ws_1",
               "status":"idle","preview":"wrapped up","updatedAt":"2026-10-20T09:00:00Z",
               "unread":false}
            ]}
        """.trimIndent()
        val response = DshJson.decodeFromString(ArchivedSessionsResponse.serializer(), json)
        assertTrue(response.supported)
        assertEquals(1, response.sessions.size)

        val session = response.sessions[0].toUi()
        assertEquals("s_old", session.id)
        assertEquals("Old investigation", session.title)
        assertEquals("ws_1", session.workspaceId)
        assertEquals("wrapped up", session.preview)
    }

    @Test
    fun `archived sessions tolerate missing rows and unsupported hosts`() {
        val supportedDefault = DshJson.decodeFromString(
            ArchivedSessionsResponse.serializer(),
            """{"sessions":[]}""",
        )
        assertTrue("supported defaults to true", supportedDefault.supported)
        assertTrue(supportedDefault.sessions.isEmpty())

        val unsupported = DshJson.decodeFromString(
            ArchivedSessionsResponse.serializer(),
            """{"supported":false,"sessions":[]}""",
        )
        assertTrue(!unsupported.supported)
        assertTrue(unsupported.sessions.isEmpty())
    }

    @Test
    fun `archived row with missing workspace maps to an empty workspace id`() {
        val response = DshJson.decodeFromString(
            ArchivedSessionsResponse.serializer(),
            """{"supported":true,"sessions":[{"id":"s_1","title":"t","workspaceId":null}]}""",
        )
        assertEquals("", response.sessions[0].toUi().workspaceId)
    }
}
