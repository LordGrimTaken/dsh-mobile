package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.MessageDto
import dev.dsh.mobile.net.SearchHitDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host 0.3.5 seq fields + the pure jump-to-search-hit helpers. */
class MessageJumpTest {

    private fun user(id: String, seq: Int? = null) =
        UserMessage(id = id, time = "10:00", text = id, eventSeq = seq)

    @Test
    fun `search hit and message seq decode with and without the field`() {
        val hit = DshJson.decodeFromString(
            SearchHitDto.serializer(),
            """{"sessionId":"s1","messageId":"m1","role":"user","snippet":"x","seq":42}""",
        )
        assertEquals(42, hit.seq)

        val oldHit = DshJson.decodeFromString(
            SearchHitDto.serializer(),
            """{"sessionId":"s1","messageId":"m1","role":"user","snippet":"x"}""",
        )
        assertNull(oldHit.seq)

        val message = DshJson.decodeFromString(
            MessageDto.serializer(),
            """{"id":"m1","role":"user","text":"hi","createdAt":"2026-11-01T10:00:00Z","seq":7}""",
        )
        assertEquals(7, message.seq)
    }

    @Test
    fun `mapping carries event seq and ignores non-positive values`() {
        val mapped = MessageDto(
            id = "m1",
            role = "assistant",
            text = "hi",
            createdAt = "2026-11-01T10:00:00Z",
            seq = 9,
        ).toUi()
        assertEquals(9, mapped.eventSeq)

        val invalid = MessageDto(
            id = "m2",
            role = "assistant",
            text = "hi",
            createdAt = "2026-11-01T10:00:00Z",
            seq = 0,
        ).toUi()
        assertNull(invalid.eventSeq)
    }

    @Test
    fun `targets need a session and at least one match key`() {
        assertNull(messageJumpTargetForSearchHit("", 3, "m1"))
        assertNull(messageJumpTargetForSearchHit("  ", 3, "m1"))
        assertNull(messageJumpTargetForSearchHit("s1", null, "   "))
        assertNull(messageJumpTargetForSearchHit("s1", 0, ""))
        val target = messageJumpTargetForSearchHit(" s1 ", 4, " m1 ")
        assertEquals("s1", target?.sessionId)
        assertEquals(4, target?.seq)
        assertEquals("m1", target?.messageId)
        assertNull("non-positive seq with no id has nothing to match", messageJumpTargetForSearchHit("s1", -3, ""))
    }

    @Test
    fun `J13 message-id-only hits still arm the jump`() {
        // Old host / mixed semantic fallback: no seq, but the hit has a chat message id.
        val target = messageJumpTargetForSearchHit("s1", null, "m_1")
        assertEquals("s1", target?.sessionId)
        assertNull(target?.seq)
        assertEquals("m_1", target?.messageId)
        assertEquals(
            1,
            messageJumpIndex(
                listOf(user("m_0"), user("m_1")),
                target!!,
            ),
        )
        // Neither key present: no target, so the caller opens the session normally.
        assertNull(messageJumpTargetForSearchHit("s1", null, ""))
        assertNull(messageJumpTargetForSearchHit("s1", 0, "  "))
    }

    @Test
    fun `target does not leak across sessions`() {
        val target = messageJumpTargetForSearchHit("s1", 2, "m1")
        assertTrue(messageJumpTargetMatches(target, "s1"))
        assertTrue(!messageJumpTargetMatches(target, "s2"))
        assertTrue(!messageJumpTargetMatches(null, "s1"))
    }

    @Test
    fun `J2 seq resolution runs first so tool-call hits land on the right row`() {
        // Search hit carries seq=20 but its chat id is the earlier tool-call row m_call.
        val messages = listOf(user("m_call", 10), user("m_result", 20))
        assertEquals(
            1,
            messageJumpIndex(messages, messageJumpTargetForSearchHit("s1", 20, "m_call")!!),
        )
        // Without seq candidates the message id is the fallback.
        assertEquals(
            1,
            messageJumpIndex(
                listOf(user("m0"), user("m1")),
                messageJumpTargetForSearchHit("s1", 50, "m1")!!,
            ),
        )
        // Seq found but no message id at all still resolves by sequence.
        assertEquals(
            2,
            messageJumpIndex(
                listOf(user("a", 10), user("b", 30), user("c", 40)),
                messageJumpTargetForSearchHit("s1", 35, "")!!,
            ),
        )
    }

    @Test
    fun `seq-first resolution beats an exact id match`() {
        val target = messageJumpTargetForSearchHit("s1", 50, "m1")!!
        val messages = listOf(user("m1", 10), user("m2", 99), user("m3", 30))
        assertEquals(
            "seq wins: the nearest at/after row is m2",
            1,
            messageJumpIndex(messages, target),
        )
    }

    @Test
    fun `exact seq then nearest at-or-after then nearest before`() {
        val seqs = listOf(user("a", 10), user("b", 20), user("c", 40))
        assertEquals(
            1,
            messageJumpIndex(seqs, messageJumpTargetForSearchHit("s1", 20, "")!!),
        )
        // tool-call hit sits between messages: choose the following row
        assertEquals(
            2,
            messageJumpIndex(seqs, messageJumpTargetForSearchHit("s1", 30, "")!!),
        )
        // after the last seq falls back to the nearest at/before
        assertEquals(
            2,
            messageJumpIndex(seqs, messageJumpTargetForSearchHit("s1", 99, "")!!),
        )
        // before the first seq has no after -> nearest before
        assertEquals(
            0,
            messageJumpIndex(seqs, messageJumpTargetForSearchHit("s1", 5, "")!!),
        )
    }

    @Test
    fun `duplicate seqs and missing seqs`() {
        val duplicates = listOf(user("a", 20), user("b", 20), user("c", 30))
        assertEquals(
            0,
            messageJumpIndex(duplicates, messageJumpTargetForSearchHit("s1", 20, "")!!),
        )
        val noSeqs = listOf(user("a"), user("b"))
        assertNull(messageJumpIndex(noSeqs, messageJumpTargetForSearchHit("s1", 20, "")!!))
        assertEquals(
            1,
            messageJumpIndex(noSeqs, messageJumpTargetForSearchHit("s1", null, "b")!!),
        )
        assertNull(messageJumpIndex(emptyList(), messageJumpTargetForSearchHit("s1", 1, "")!!))
    }

    @Test
    fun `scroll index accounts for the older-messages header`() {
        assertEquals(0, messageJumpScrollIndex(0, hasOlderHeader = false))
        assertEquals(3, messageJumpScrollIndex(3, hasOlderHeader = false))
        assertEquals(1, messageJumpScrollIndex(0, hasOlderHeader = true))
        assertEquals(4, messageJumpScrollIndex(3, hasOlderHeader = true))
        assertEquals(20, MESSAGE_JUMP_MAX_OLDER_LOADS)
        assertEquals(1_600L, MESSAGE_JUMP_HIGHLIGHT_MS)
        assertTrue(MESSAGE_JUMP_NOT_FOUND_COPY.contains("older than the loaded history"))
    }
}
