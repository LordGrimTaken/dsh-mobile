package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the free-text question answer helpers: blank drafts keep Send disabled, answers are
 * trimmed and capped before being posted with `optionIndex = null` (host maps them to `custom`).
 */
class QuestionFreeTextTest {

    @Test
    fun `blank drafts are not sendable`() {
        assertNull(normalizeQuestionAnswer(""))
        assertNull(normalizeQuestionAnswer("   "))
        assertNull(normalizeQuestionAnswer("\n\t "))
    }

    @Test
    fun `answers are trimmed`() {
        assertEquals("Use the streaming path", normalizeQuestionAnswer("  Use the streaming path  "))
        assertEquals("line one\nline two", normalizeQuestionAnswer("\nline one\nline two\n"))
    }

    @Test
    fun `long answers are capped`() {
        assertEquals(MAX_QUESTION_ANSWER_CHARS, 2_000)
        val long = "x".repeat(MAX_QUESTION_ANSWER_CHARS + 500)
        val normalized = normalizeQuestionAnswer(long)
        assertEquals(MAX_QUESTION_ANSWER_CHARS, normalized?.length)
        assertTrue(normalized != null)
    }

    @Test
    fun `unicode custom answers pass through`() {
        assertEquals("先保持流，再重新连接 ✓", normalizeQuestionAnswer("  先保持流，再重新连接 ✓ "))
    }
}
