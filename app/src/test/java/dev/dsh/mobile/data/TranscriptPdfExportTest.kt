package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the PDF transcript exporter: names, sanitization, role labels, caps and copy. */
class TranscriptPdfExportTest {

    private fun user(text: String = "hello", time: String = "10:00") =
        UserMessage(id = "u", time = time, text = text)

    private fun assistant(text: String = "world", time: String = "10:01") =
        AssistantMessage(id = "a", time = time, text = text)

    private fun note(text: String = "note", time: String = "10:02") =
        SystemNote(id = "s", time = time, text = text)

    @Test
    fun `file name is sanitized and ends with pdf`() {
        assertEquals(
            "dsh-mobile-Release-train-20261031-1130.pdf",
            transcriptPdfFileName("Release / train", "20261031-1130"),
        )
        assertEquals(
            "dsh-mobile-session-1.pdf",
            transcriptPdfFileName("   ", "1"),
        )
        assertEquals(
            "dsh-mobile-a-b-0.pdf",
            transcriptPdfFileName("a  b", "!!"),
        )
        val long = transcriptPdfFileName("x".repeat(80), "20261031-1130")
        assertTrue(long.startsWith("dsh-mobile-"))
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.length < 80)
    }

    @Test
    fun `text sanitization drops controls normalizes newlines and caps with ellipsis`() {
        assertEquals("a\nb", sanitizeTranscriptPdfText("a\r\nb"))
        assertEquals("a\nb", sanitizeTranscriptPdfText("a\rb"))
        assertEquals("a\tb", sanitizeTranscriptPdfText("a\tb"))
        assertEquals("ab", sanitizeTranscriptPdfText("a\u0000\u0007b"))
        assertEquals("trimmed", sanitizeTranscriptPdfText("  trimmed \n"))
        assertEquals("", sanitizeTranscriptPdfText("   "))
        val capped = sanitizeTranscriptPdfText("x".repeat(50), maxChars = 10)
        assertEquals(11, capped.length)
        assertTrue(capped.endsWith("…"))
        assertEquals("", sanitizeTranscriptPdfText("abc", maxChars = 0))
    }

    @Test
    fun `truncation never splits an emoji surrogate pair`() {
        // Two emoji = 4 UTF-16 units but 2 code points; a max of 2 code points keeps both intact.
        val twoEmoji = "😀😀"
        val truncated = sanitizeTranscriptPdfText("$twoEmoji extra", maxChars = 2)
        assertEquals("😀😀…", truncated)
        assertFalse("no replacement character", truncated.contains('\uFFFD'))
        assertEquals("😀", takeTranscriptPdfCodePoints("😀😀", 1))
        assertEquals("a😀", takeTranscriptPdfCodePoints("a😀b", 2))
        assertEquals("", takeTranscriptPdfCodePoints("😀", 0))
        assertTrue(sanitizeTranscriptPdfText("a😀b", maxChars = 2).startsWith("a😀"))
    }

    @Test
    fun `role labels and message text cover all roles`() {
        assertEquals("User", transcriptPdfRoleLabel(user()))
        assertEquals("Assistant", transcriptPdfRoleLabel(assistant()))
        assertEquals("System", transcriptPdfRoleLabel(note()))
        assertEquals("hi", transcriptPdfMessageText(user("hi")))
        assertEquals("yo", transcriptPdfMessageText(assistant("yo")))
        assertEquals("n", transcriptPdfMessageText(note("n")))
    }

    @Test
    fun `empty messages are skipped and messages are capped with a visible flag`() {
        val messages = listOf(
            user("first"),
            user("   "),
            assistant(""),
            note("last"),
        )
        assertEquals(
            listOf("first", "last"),
            transcriptPdfExportableMessages(messages).map { transcriptPdfMessageText(it) },
        )
        val many = (1..TRANSCRIPT_PDF_MAX_MESSAGES + 5).map { index ->
            user("message $index", time = index.toString())
        }
        assertEquals(
            TRANSCRIPT_PDF_MAX_MESSAGES,
            transcriptPdfExportableMessages(many).size,
        )
        assertTrue(transcriptPdfWasCapped(TRANSCRIPT_PDF_MAX_MESSAGES + 1))
        assertFalse(transcriptPdfWasCapped(TRANSCRIPT_PDF_MAX_MESSAGES))
        assertTrue("message-count cap must be reported", transcriptPdfMessagesCapped(many))
        assertFalse(transcriptPdfMessagesCapped(messages))
        assertFalse(
            "empty messages do not count toward the cap",
            transcriptPdfMessagesCapped(many.map { user("   ") }),
        )
        assertEquals(200, TRANSCRIPT_PDF_MAX_MESSAGES)
    }

    @Test
    fun `page helpers and footer labels`() {
        assertEquals(1, transcriptPdfPagesNeeded(0, 700))
        assertEquals(1, transcriptPdfPagesNeeded(-5, 700))
        assertEquals(1, transcriptPdfPagesNeeded(700, 700))
        assertEquals(2, transcriptPdfPagesNeeded(701, 700))
        assertEquals(2, transcriptPdfPagesNeeded(1_400, 700))
        assertEquals(3, transcriptPdfPagesNeeded(1_401, 700))
        assertEquals("Page 1", transcriptPdfFooterLabel(1))
        assertEquals("Page 12", transcriptPdfFooterLabel(12))
        assertEquals(595, TRANSCRIPT_PDF_PAGE_WIDTH)
        assertEquals(842, TRANSCRIPT_PDF_PAGE_HEIGHT)
        assertEquals(48, TRANSCRIPT_PDF_MARGIN)
    }

    @Test
    fun `capped note reflects which cap was hit`() {
        assertNull(transcriptPdfCappedNote(messagesCapped = false, pagesCapped = false))
        assertTrue(
            transcriptPdfCappedNote(messagesCapped = true, pagesCapped = false)
                .orEmpty()
                .contains("first $TRANSCRIPT_PDF_MAX_MESSAGES messages"),
        )
        assertTrue(
            transcriptPdfCappedNote(messagesCapped = false, pagesCapped = true)
                .orEmpty()
                .contains("first $TRANSCRIPT_PDF_MAX_PAGES pages"),
        )
        val both = transcriptPdfCappedNote(messagesCapped = true, pagesCapped = true).orEmpty()
        assertTrue(both.contains("messages") && both.contains("pages"))
        assertTrue(both.startsWith("PDF was shortened"))
    }

    @Test
    fun `message time label is sanitized before drawing`() {
        assertEquals("10:00", transcriptPdfMessageTimeLabel(user(time = "10:00")))
        assertEquals("10:00", transcriptPdfMessageTimeLabel(user(time = " 10:00\n")))
        assertEquals("a b", transcriptPdfMessageTimeLabel(user(time = "a\r\nb")).replace("\n", " "))
        assertEquals("unknown time", transcriptPdfMessageTimeLabel(user(time = "   ")))
        assertEquals(
            "unknown time",
            transcriptPdfMessageTimeLabel(user(time = "\u0000\u0007")),
        )
    }

    @Test
    fun `status copy is stable`() {
        assertEquals("Exporting PDF…", TRANSCRIPT_PDF_EXPORTING_COPY)
        assertEquals("PDF ready", TRANSCRIPT_PDF_SUCCESS_COPY)
        assertEquals("Couldn't export the PDF.", TRANSCRIPT_PDF_FAILURE_COPY)
        assertEquals("No app can share the PDF.", TRANSCRIPT_PDF_SHARE_FAILURE_COPY)
        assertEquals("There are no messages to export.", TRANSCRIPT_PDF_EMPTY_COPY)
        assertEquals(80, TRANSCRIPT_PDF_MAX_PAGES)
        assertEquals(120_000, TRANSCRIPT_PDF_MAX_TOTAL_CHARS)
        assertEquals(20_000, TRANSCRIPT_PDF_MAX_MESSAGE_CHARS)
    }
}
