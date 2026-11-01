package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers behind the "Share as image" PNG card. */
class ShareCardTest {

    @Test
    fun `role labels and role detection`() {
        assertEquals("You", shareCardRoleLabel("user"))
        assertEquals("Assistant", shareCardRoleLabel("assistant"))
        assertEquals("Assistant", shareCardRoleLabel("tool"))
        assertEquals("System", shareCardRoleLabel("system"))
        assertEquals("Message", shareCardRoleLabel("mystery"))
        assertEquals("Message", shareCardRoleLabel(null))
        assertEquals("user", shareCardRoleFor(UserMessage("u", "10:00", "hi")))
        assertEquals("assistant", shareCardRoleFor(AssistantMessage("a", "10:01", "yo")))
        assertEquals("system", shareCardRoleFor(SystemNote("s", "10:02", "note")))
    }

    @Test
    fun `time label is single line and bounded`() {
        assertEquals("10:00", shareCardTimeLabel(" 10:00 "))
        assertEquals("", shareCardTimeLabel(null))
        assertEquals("", shareCardTimeLabel(""))
        assertEquals(40, shareCardTimeLabel("t".repeat(100)).length)
        assertFalse(shareCardTimeLabel("a\u0000b").contains('\u0000'))
    }

    @Test
    fun `input is normalized and capped`() {
        assertEquals("a\nb" to false, sanitizedShareCardInput("a\r\nb"))
        assertEquals("a  b" to false, sanitizedShareCardInput("a\tb"))
        assertEquals("ab" to false, sanitizedShareCardInput("a\u0000b"))
        val long = "x".repeat(SHARE_CARD_MAX_CHARS + 100)
        val (text, truncated) = sanitizedShareCardInput(long)
        assertEquals(SHARE_CARD_MAX_CHARS, text.length)
        assertTrue(truncated)
        assertEquals("" to false, sanitizedShareCardInput(null))
    }

    @Test
    fun `markdown markers are stripped sensibly`() {
        assertEquals("Heading", stripShareCardMarkdownInline("## Heading"))
        assertEquals("quote", stripShareCardMarkdownInline("> quote"))
        assertEquals("• item", stripShareCardMarkdownInline("- item"))
        assertEquals("1. first", stripShareCardMarkdownInline("1. first"))
        assertEquals("bold and code", stripShareCardMarkdownInline("**bold** and `code`"))
        assertEquals("label", stripShareCardMarkdownInline("[label](https://example.com)"))
        assertEquals("alt", stripShareCardMarkdownInline("![alt](https://example.com/x.png)"))
        assertEquals("plain", stripShareCardMarkdownInline("~~plain~~"))
    }

    @Test
    fun `fenced code becomes monospace segments`() {
        val segments = parseShareCardSegments(
            "# Title\n\nHello **world**\n```kt\nval x = 1\nprintln(x)\n```\nbye",
        )
        assertEquals(3, segments.size)
        assertEquals("Title\n\nHello world", segments[0].text)
        assertFalse(segments[0].monospace)
        assertEquals("val x = 1\nprintln(x)", segments[1].text)
        assertTrue(segments[1].monospace)
        assertEquals("bye", segments[2].text)
        assertFalse(segments[2].monospace)
        assertTrue(parseShareCardSegments("   ").isEmpty())
        assertTrue(parseShareCardSegments(null.orEmpty()).isEmpty())
    }

    @Test
    fun `wrapping keeps short text and explicit line breaks`() {
        assertEquals(listOf("hello world") to false, wrapShareCardText("hello world", 20, 5))
        assertEquals(listOf("a", "b") to false, wrapShareCardText("a\nb", 20, 5))
        assertEquals(listOf("a", "", "b") to false, wrapShareCardText("a\n\nb", 20, 5))
        assertTrue(wrapShareCardText("", 20, 5).first.isNotEmpty())
    }

    @Test
    fun `wrapping hard-breaks unbroken words within the line budget`() {
        val (lines, truncated) = wrapShareCardText("x".repeat(100), maxCharsPerLine = 10, maxLines = 3)
        assertEquals(3, lines.size)
        assertTrue(truncated)
        assertTrue(lines.all { it.length <= 10 })
        assertTrue(lines.last().endsWith("…"))
    }

    @Test
    fun `wrapping caps lines and ellipsizes the last one`() {
        val (lines, truncated) = wrapShareCardText(
            "one two three four five six",
            maxCharsPerLine = 10,
            maxLines = 2,
        )
        assertEquals(2, lines.size)
        assertTrue(truncated)
        assertTrue(lines.last().endsWith("…"))
        assertTrue(lines.all { it.length <= 10 })
    }

    @Test
    fun `emoji and CJK never split surrogate pairs or crash`() {
        val emoji = "😀".repeat(30)
        val (lines, _) = wrapShareCardText(emoji, maxCharsPerLine = 5, maxLines = 20)
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            assertFalse("line starts with a lone low surrogate: $line", line.firstOrNull()?.isLowSurrogate() == true)
            assertFalse("line ends with a lone high surrogate: $line", line.lastOrNull()?.isHighSurrogate() == true)
        }
        val cjk = "日本語のテキストです".repeat(5)
        val (cjkLines, cjkTruncated) = wrapShareCardText(cjk, maxCharsPerLine = 6, maxLines = 4)
        assertEquals(4, cjkLines.size)
        assertTrue(cjkTruncated)
        assertTrue(cjkLines.all { it.isNotBlank() })
    }

    @Test
    fun `file names include a per-render suffix and stay unique`() {
        assertEquals("my-session", shareCardFileSlug("My Session!"))
        assertEquals("message", shareCardFileSlug(null))
        assertEquals("message", shareCardFileSlug("日本語"))
        assertEquals(32, shareCardFileSlug("x".repeat(100)).length)
        val first = shareCardFileName("Fix parser", "m1", nonce = 7L)
        assertEquals(first, shareCardFileName("Fix parser", "m1", nonce = 7L))
        assertTrue(first.endsWith(".png"))
        assertTrue(first.startsWith("share-card-fix-parser-"))
        // S4: every render gets a distinct file so an in-flight share URI is never overwritten.
        assertNotEquals(first, shareCardFileName("Fix parser", "m1", nonce = 8L))
        assertNotEquals(first, shareCardFileName("Fix parser", "m2", nonce = 7L))
        assertFalse(first.contains('/'))
        assertFalse(first.contains('\\'))
        assertTrue(shareCardFileName("x".repeat(200), "m1", nonce = 1L).endsWith(".png"))
    }

    @Test
    fun `S2 truncation counts code points and never splits an emoji pair`() {
        // Exactly at the cap: no truncation.
        val exact = "😀".repeat(SHARE_CARD_MAX_CHARS / 2)
        val (exactText, exactTruncated) = sanitizedShareCardInput(exact)
        assertFalse(exactTruncated)
        assertEquals(exact, exactText)

        // One code point over the cap: truncate by code points, not UTF-16 units.
        val over = "a" + "😀".repeat(SHARE_CARD_MAX_CHARS)
        val (capped, truncated) = sanitizedShareCardInput(over)
        assertTrue(truncated)
        assertEquals(SHARE_CARD_MAX_CHARS, capped.codePointCount(0, capped.length))
        assertFalse("cap must not end with a lone high surrogate", capped.last().isHighSurrogate())
        assertEquals("a" + "😀".repeat(SHARE_CARD_MAX_CHARS - 1), capped)
    }

    @Test
    fun `S5 credential-like strings are redacted before rendering`() {
        assertEquals("<redacted>", SHARE_CARD_REDACTED)
        assertTrue(SHARE_CARD_PRIVACY_NOTE.contains("redacted"))
        val sk = redactedShareCardText("key sk-abcdefghijklmnop1234 done")
        assertFalse(sk.contains("sk-abcdefghijklmnop1234"))
        assertTrue(sk.contains(SHARE_CARD_REDACTED))
        val bearer = redactedShareCardText("Authorization: Bearer abcdefghijklmnop")
        assertFalse(bearer.contains("abcdefghijklmnop"))
        assertTrue(bearer.contains(SHARE_CARD_REDACTED))
        val apiKey = redactedShareCardText("api_key=supersecret123 next")
        assertTrue(apiKey.contains("api_key="))
        assertFalse(apiKey.contains("supersecret123"))
        val quoted = redactedShareCardText("""password: "hunter2secret"""")
        assertFalse(quoted.contains("hunter2secret"))
        val token = redactedShareCardText("token: abcdef123456789")
        assertFalse(token.contains("abcdef123456789"))
        val base64 = redactedShareCardText("blob eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefgh end")
        assertFalse(base64.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefgh"))
        // Ordinary prose is untouched.
        assertEquals("hello world, this is fine", redactedShareCardText("hello world, this is fine"))
        // The sanitizer redacts too, so the card body can never carry the secret.
        val (cardText, _) = sanitizedShareCardInput("my token is sk-abcdefghijklmnop1234")
        assertFalse(cardText.contains("sk-abcdefghijklmnop1234"))
        assertTrue(cardText.contains(SHARE_CARD_REDACTED))
    }

    @Test
    fun `S3 share card cache dir is wired through the FileProvider xml`() {
        val xmlFile = sequenceOf(
            java.io.File("src/main/res/xml/update_file_paths.xml"),
            java.io.File("app/src/main/res/xml/update_file_paths.xml"),
        ).firstOrNull { it.isFile }
        org.junit.Assert.assertNotNull("update_file_paths.xml must exist", xmlFile)
        val xml = xmlFile!!.readText()
        assertTrue(
            "FileProvider must expose cacheDir/$SHARE_CARD_CACHE_DIR",
            xml.contains("path=\"$SHARE_CARD_CACHE_DIR/\""),
        )
        val manifestFile = sequenceOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
        org.junit.Assert.assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifest = manifestFile!!.readText()
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("update_provider"))
        assertTrue(manifest.contains("update_file_paths"))
        val name = shareCardFileName("Session", "m1", nonce = 3L)
        assertTrue(name.endsWith(".png"))
        assertTrue(name.startsWith("share-card-"))
        assertTrue(name.contains("session"))
    }

    @Test
    fun `card height is deterministic and memory bounded`() {
        assertEquals(900, shareCardHeightPx(0, false))
        assertTrue(shareCardHeightPx(1, false) >= 900)
        assertTrue(shareCardHeightPx(SHARE_CARD_MAX_LINES, true) <= SHARE_CARD_MAX_HEIGHT_PX)
        assertTrue(shareCardHeightPx(SHARE_CARD_MAX_LINES, true) > shareCardHeightPx(SHARE_CARD_MAX_LINES, false))
        assertEquals(SHARE_CARD_MAX_HEIGHT_PX, shareCardHeightPx(10_000, true))
        assertEquals(1080, SHARE_CARD_WIDTH_PX)
        assertEquals(6_000, SHARE_CARD_MAX_CHARS)
        assertEquals(80, SHARE_CARD_MAX_LINES)
    }

    @Test
    fun `copy includes the action and failure wording`() {
        assertEquals("Share as image", SHARE_CARD_ACTION_LABEL)
        assertEquals("Render this message as a PNG card", SHARE_CARD_ACTION_SUBTITLE)
        assertEquals("DSH Mobile", SHARE_CARD_BRAND)
        assertEquals("Shared from DSH Mobile", SHARE_CARD_TAGLINE)
        assertEquals("Couldn't create the image card.", SHARE_CARD_FAILURE_TOAST)
        assertEquals("share-cards", SHARE_CARD_CACHE_DIR)
        assertEquals("Message truncated for this card.", SHARE_CARD_TRUNCATED_NOTE)
        assertEquals("Credential-like strings are redacted from this card.", SHARE_CARD_PRIVACY_NOTE)
    }
}
