package dev.dsh.mobile.net

import dev.dsh.mobile.data.RECOVERY_CODE_FORMAT_MESSAGE
import dev.dsh.mobile.data.RECOVERY_CODE_LENGTH
import dev.dsh.mobile.data.RECOVERY_ROTATED_MISSING_COPY
import dev.dsh.mobile.data.RECOVERY_SETUP_COPY
import dev.dsh.mobile.data.RecoveryAccess
import dev.dsh.mobile.data.RecoveryCompletion
import dev.dsh.mobile.data.RecoveryFailureKind
import dev.dsh.mobile.data.normalizeRecoveryCode
import dev.dsh.mobile.data.parseRecoveryLink
import dev.dsh.mobile.data.recoveryAccessSummary
import dev.dsh.mobile.data.recoveryCodeLooksValid
import dev.dsh.mobile.data.recoveryCompletionLink
import dev.dsh.mobile.data.recoveryCompletionMessage
import dev.dsh.mobile.data.recoveryFailureMessage
import dev.dsh.mobile.data.recoveryLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the recovery-code contract: status/code/pair DTO decode, request shape, recovery
 * link build/parse with URL encoding, code normalization and the Settings copy model.
 */
class RecoveryAccessParsingTest {

    companion object {
        /** 52 RFC4648 base32 characters (A-Z2-7). */
        private val VALID_CODE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            .repeat(2)
            .take(RECOVERY_CODE_LENGTH)
    }

    @Test
    fun `recovery status decodes configured and unsupported states`() {
        val configured = DshJson.decodeFromString(
            RecoveryStatusDto.serializer(),
            """{"supported":true,"configured":true,"createdAt":"2026-11-01T09:00:00Z"}""",
        )
        assertTrue(configured.supported)
        assertTrue(configured.configured)
        assertEquals("2026-11-01T09:00:00Z", configured.createdAt)

        val unsupported = DshJson.decodeFromString(
            RecoveryStatusDto.serializer(),
            """{"supported":false}""",
        )
        assertFalse(unsupported.supported)
        assertFalse(unsupported.configured)
        assertNull(unsupported.createdAt)

        val defaults = DshJson.decodeFromString(RecoveryStatusDto.serializer(), """{}""")
        assertTrue(defaults.supported)
        assertFalse(defaults.configured)
    }

    @Test
    fun `generated recovery code decodes`() {
        val dto = DshJson.decodeFromString(
            RecoveryCodeDto.serializer(),
            """{"code":"ABCD-EFGH-IJKL","createdAt":"2026-11-02T12:00:00Z"}""",
        )
        assertEquals("ABCD-EFGH-IJKL", dto.code)
        assertEquals("2026-11-02T12:00:00Z", dto.createdAt)
    }

    @Test
    fun `recovery pair response decodes token device and optional rotated code`() {
        val json = """
            {"token":"tok_1","recoveryCode":"NEW-CODE",
             "device":{"deviceId":"dev_9","deviceName":"Pixel 9","platform":"Android",
                       "pairedAt":"2026-11-02T12:00:00Z"}}
        """.trimIndent()
        val response = DshJson.decodeFromString(RecoveryPairResponse.serializer(), json)
        assertEquals("tok_1", response.token)
        assertEquals("dev_9", response.device?.deviceId)
        assertEquals("Pixel 9", response.device?.deviceName)
        assertEquals("NEW-CODE", response.recoveryCode)

        val minimal = DshJson.decodeFromString(
            RecoveryPairResponse.serializer(),
            """{"token":"tok_2"}""",
        )
        assertNull(minimal.device)
        assertNull(minimal.recoveryCode)
    }

    @Test
    fun `recovery pair request carries only the provided fields`() {
        val full = DshJson.encodeToString(
            RecoveryPairRequest.serializer(),
            RecoveryPairRequest(code = "CODE1", deviceName = "Pixel 9", platform = "Android"),
        )
        assertEquals(
            """{"code":"CODE1","deviceName":"Pixel 9","platform":"Android"}""",
            full,
        )

        val minimal = DshJson.encodeToString(
            RecoveryPairRequest.serializer(),
            RecoveryPairRequest(code = "CODE1"),
        )
        assertFalse(minimal.contains("deviceName"))
        assertFalse(minimal.contains("platform"))
    }

    @Test
    fun `recovery links round-trip with URL encoding`() {
        val base = "https://host.example.com:8443/dsh/path?x=1"
        val link = recoveryLink(base, "ABCD EFGH-IJKL")
        assertTrue(link.startsWith("dshmobile://recover?base="))
        assertFalse("base URL is encoded", link.contains("https://host.example.com"))
        val parsed = parseRecoveryLink(link)
        assertEquals(base, parsed?.baseUrl)
        assertEquals("ABCDEFGHIJKL", parsed?.code)
        assertEquals(link, recoveryLink(base, parsed?.code.orEmpty()))
    }

    @Test
    fun `invalid recovery links are rejected`() {
        assertNull(parseRecoveryLink(""))
        assertNull(parseRecoveryLink("https://host.example.com"))
        assertNull(parseRecoveryLink("dshmobile://usage"))
        assertNull(parseRecoveryLink("dshmobile://recover"))
        assertNull(parseRecoveryLink("dshmobile://recover?code=ABC"))
        assertNull(parseRecoveryLink("dshmobile://recover?base=ftp%3A%2F%2Fhost&code=ABC"))
        assertNull(
            parseRecoveryLink("dshmobile://recover?base=https%3A%2F%2Fhost.example.com&code="),
        )
    }

    @Test
    fun `code normalization strips spaces and grouping dashes`() {
        // Case is preserved; only whitespace and grouping dashes are removed.
        assertEquals("abcdefghijkl", normalizeRecoveryCode("  abcd efgh-ijkl  "))
        assertEquals("ABCDEF", normalizeRecoveryCode("ABCD\tEF"))
        assertEquals("ABCDEF", normalizeRecoveryCode("AB-CD-EF"))
        assertEquals("", normalizeRecoveryCode("   "))
        assertEquals(52, RECOVERY_CODE_LENGTH)
        assertTrue(recoveryCodeLooksValid(VALID_CODE))
        assertTrue(recoveryCodeLooksValid(VALID_CODE.chunked(4).joinToString("-")))
        assertFalse(recoveryCodeLooksValid(" AB CD "))
        assertFalse(recoveryCodeLooksValid(" - "))
        assertFalse(recoveryCodeLooksValid(""))
        assertFalse(recoveryCodeLooksValid(VALID_CODE.lowercase()))
        assertFalse(recoveryCodeLooksValid(VALID_CODE.dropLast(1)))
        assertFalse(recoveryCodeLooksValid(VALID_CODE + "A"))
        assertFalse(recoveryCodeLooksValid("0" + VALID_CODE.drop(1)))
        assertFalse(recoveryCodeLooksValid("1" + VALID_CODE.drop(1)))
        assertFalse(recoveryCodeLooksValid("8" + VALID_CODE.drop(1)))
        assertFalse(recoveryCodeLooksValid("9" + VALID_CODE.drop(1)))
        assertEquals("That doesn't look like a recovery code.", RECOVERY_CODE_FORMAT_MESSAGE)
    }

    @Test
    fun `rotated recovery code produces a one-time completion link`() {
        val completion = RecoveryCompletion(
            baseUrl = "https://host.example.com:8443",
            rotatedCode = VALID_CODE,
        )
        val link = recoveryCompletionLink(completion)
        assertEquals(
            recoveryLink("https://host.example.com:8443", VALID_CODE),
            link,
        )
        assertEquals(
            "https://host.example.com:8443",
            link?.let { parseRecoveryLink(it)?.baseUrl },
        )
        assertEquals(VALID_CODE, link?.let { parseRecoveryLink(it)?.code })
        assertTrue(recoveryCompletionMessage(completion).contains("previous code no longer works"))

        val noCode = RecoveryCompletion("https://host.example.com", rotatedCode = null)
        assertNull(recoveryCompletionLink(noCode))
        assertEquals(RECOVERY_ROTATED_MISSING_COPY, recoveryCompletionMessage(noCode))
        assertNull(recoveryCompletionLink(noCode.copy(rotatedCode = "  ")))
    }

    @Test
    fun `deep link parsing accepts base and code in any query order`() {
        val base = "https://host.example.com:8443"
        val link = "dshmobile://recover?code=$VALID_CODE&base=" +
            java.net.URLEncoder.encode(base, "UTF-8")
        val parsed = parseRecoveryLink(link)
        assertEquals(base, parsed?.baseUrl)
        assertEquals(VALID_CODE, parsed?.code)
        assertEquals(
            recoveryLink(base, VALID_CODE),
            recoveryCompletionLink(RecoveryCompletion(base, VALID_CODE)),
        )
    }

    @Test
    fun `recovery status summary and setup copy are stable`() {
        assertEquals("Loading recovery status…", recoveryAccessSummary(null))
        assertEquals(
            "Not supported by this host",
            recoveryAccessSummary(RecoveryAccess.Unsupported),
        )
        assertEquals(
            "Not set up yet",
            recoveryAccessSummary(RecoveryAccess(supported = true, configured = false)),
        )
        assertEquals(
            "Recovery access is configured",
            recoveryAccessSummary(RecoveryAccess(supported = true, configured = true)),
        )
        assertTrue(RECOVERY_SETUP_COPY.contains("password manager"))
        assertTrue(RECOVERY_SETUP_COPY.contains("single-use"))
        assertTrue(RECOVERY_SETUP_COPY.contains("pairing code"))
    }

    @Test
    fun `recovery failure messages cover each bucket`() {
        RecoveryFailureKind.entries.forEach { kind ->
            assertTrue(kind.name, recoveryFailureMessage(kind).isNotBlank())
        }
        assertTrue(
            recoveryFailureMessage(RecoveryFailureKind.INVALID_CODE).contains("invalid"),
        )
        assertTrue(recoveryFailureMessage(RecoveryFailureKind.RATE_LIMITED).contains("Too many"))
        assertTrue(
            recoveryFailureMessage(RecoveryFailureKind.UNSUPPORTED).contains("doesn't support"),
        )
        assertTrue(recoveryFailureMessage(RecoveryFailureKind.NETWORK).contains("Can't reach"))
        assertTrue(recoveryFailureMessage(RecoveryFailureKind.NOT_FOUND).contains("DSH host"))
    }
}
