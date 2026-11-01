package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * JVM tests for the versioned local backup format: round-trip, validation, secret exclusion and
 * the import-confirmation counts.
 */
class LocalBackupTest {

    private fun sampleBackup() = LocalBackupData(
        exportedAt = "2026-11-02T12:00:00Z",
        appVersion = "0.2.103",
        aliases = mapOf("s_1" to "Release train", "s_2" to "Bug triage"),
        pinned = listOf("s_1"),
        muted = listOf("s_2"),
        hidden = listOf("s_3"),
        drafts = mapOf("s_1" to "half-written prompt", "s_2" to "another draft"),
        pendingAttachmentDrafts = mapOf(
            "s_1" to listOf(
                BackupAttachmentDraft(
                    name = "screenshot.png",
                    mimeType = "image/png",
                    sizeBytes = 1_024,
                    attachmentId = "att_1",
                ),
            ),
        ),
        queue = mapOf(
            "s_1" to listOf(
                BackupQueuedPrompt(id = 7L, text = "queued prompt", attachmentIds = listOf("att_1")),
                BackupQueuedPrompt(id = 8L, text = "second queued"),
            ),
        ),
        snippets = listOf(
            BackupSnippet(id = "snip_1", title = "Review", prompt = "Review this diff"),
            BackupSnippet(id = "snip_2", title = "Tests", prompt = "Run the tests"),
        ),
        settings = BackupSettings(
            darkTheme = false,
            amoledDark = true,
            chatFontScale = 1.15f,
            appFontScale = 1.3f,
            wrapCodeBlocks = true,
            notifyInput = false,
            notifyDone = true,
            notifyGoal = false,
            lowBalanceAlerts = false,
            lowBalanceThreshold = 2.5,
            quietHours = true,
            quietStartHour = 23,
            quietEndHour = 7,
            voicePreferOffline = true,
            voiceLanguage = "en-US",
        ),
    )

    @Test
    fun `full backup round-trips`() {
        val original = sampleBackup()
        val decoded = decodeLocalBackup(encodeLocalBackup(original))
        assertTrue(decoded is LocalBackupDecodeResult.Success)
        val restored = (decoded as LocalBackupDecodeResult.Success).data
        assertEquals(original.schema, restored.schema)
        assertEquals(original.aliases, restored.aliases)
        assertEquals(original.pinned, restored.pinned)
        assertEquals(original.muted, restored.muted)
        assertEquals(original.hidden, restored.hidden)
        assertEquals(original.drafts, restored.drafts)
        assertEquals(original.pendingAttachmentDrafts, restored.pendingAttachmentDrafts)
        assertEquals(original.queue, restored.queue)
        assertEquals(original.snippets, restored.snippets)
        assertEquals(original.settings, restored.settings)
    }

    @Test
    fun `empty backup is valid`() {
        val decoded = decodeLocalBackup("""{"schema":1}""")
        assertTrue(decoded is LocalBackupDecodeResult.Success)
        val data = (decoded as LocalBackupDecodeResult.Success).data
        assertEquals("empty backup", localBackupCounts(data).summaryLine())
        assertEquals(0, localBackupCounts(data).total)
    }

    @Test
    fun `malformed and oversized files are rejected`() {
        val malformed = decodeLocalBackup("not a json backup")
        assertTrue(malformed is LocalBackupDecodeResult.Invalid)
        assertTrue((malformed as LocalBackupDecodeResult.Invalid).message.isNotBlank())

        assertTrue(decodeLocalBackup("   ") is LocalBackupDecodeResult.Invalid)

        val oversizedJson = "{\"schema\":1,\"appVersion\":\"" +
            "x".repeat(LOCAL_BACKUP_MAX_CHARS) + "\"}"
        assertTrue(decodeLocalBackup(oversizedJson) is LocalBackupDecodeResult.Invalid)
    }

    @Test
    fun `newer schema versions are reported as unsupported`() {
        val decoded = decodeLocalBackup("""{"schema":99,"aliases":{}}""")
        assertTrue(decoded is LocalBackupDecodeResult.UnsupportedVersion)
        assertEquals(99, (decoded as LocalBackupDecodeResult.UnsupportedVersion).version)
    }

    @Test
    fun `encoded backups never carry secrets or private app data`() {
        val json = encodeLocalBackup(sampleBackup())
        val topLevelKeys = DshJson.parseToJsonElement(json).jsonObject.keys
            .map { it.lowercase() }
            .toSet()
        assertTrue(topLevelKeys.contains("schema"))
        assertTrue(topLevelKeys.contains("aliases"))
        assertTrue(topLevelKeys.contains("settings"))
        LOCAL_BACKUP_FORBIDDEN_TOP_LEVEL_KEYS.forEach { key ->
            assertFalse("backup must not contain top-level '$key'", key in topLevelKeys)
        }
        // Also make sure no raw secret field names slipped into the serialized text.
        val lowered = json.lowercase()
        assertFalse(lowered.contains("\"app_lock\""))
        assertFalse(lowered.contains("\"accesstoken\""))
        assertFalse(lowered.contains("\"pairing\""))
    }

    @Test
    fun `user text containing secret-sounding words still round-trips`() {
        // B1 regression: the old whole-file substring scan rejected legitimate backups whose drafts /
        // aliases / queue text merely mentioned words like "token" or "pairing".
        val data = LocalBackupData(
            aliases = mapOf("s_1" to "token rotation pairing notes"),
            drafts = mapOf(
                "s_1" to "refresh the access token, check pairing and the crash log; keystore?",
            ),
            queue = mapOf(
                "s_1" to listOf(
                    BackupQueuedPrompt(id = 1L, text = "token pairing crash keystore app_lock"),
                ),
            ),
        )
        val encoded = encodeLocalBackup(data)
        val decoded = decodeLocalBackup(encoded)
        assertTrue(decoded is LocalBackupDecodeResult.Success)
        val restored = (decoded as LocalBackupDecodeResult.Success).data
        assertEquals(data.aliases, restored.aliases)
        assertEquals(data.drafts, restored.drafts)
        assertEquals(data.queue, restored.queue)
    }

    @Test
    fun `import files with forbidden top-level keys are rejected`() {
        // Exact secret keys at the top level are rejected.
        val exactToken = """{"schema":1,"appVersion":"0.2.103","token":"secret"}"""
        assertTrue(decodeLocalBackup(exactToken) is LocalBackupDecodeResult.Invalid)

        val withLock = """{"schema":1,"aliases":{},"app_lock":true}"""
        assertTrue(decodeLocalBackup(withLock) is LocalBackupDecodeResult.Invalid)

        val withHosts = """{"schema":1,"hosts":[{"token":"x"}]}"""
        assertTrue(decodeLocalBackup(withHosts) is LocalBackupDecodeResult.Invalid)

        // Secret-looking top-level keys are rejected (exact key match, case-insensitive).
        val accessToken = """{"schema":1,"appVersion":"0.2.103","accessToken":"secret"}"""
        assertTrue(decodeLocalBackup(accessToken) is LocalBackupDecodeResult.Invalid)

        // Unknown non-secret top-level keys are tolerated (ignored on decode).
        val futureField = """{"schema":1,"futureField":42}"""
        assertTrue(decodeLocalBackup(futureField) is LocalBackupDecodeResult.Success)

        // A harmless draft that mentions the word "token" is not rejected.
        val harmless = """{"schema":1,"drafts":{"s_1":"rename the token field"}}"""
        assertTrue(decodeLocalBackup(harmless) is LocalBackupDecodeResult.Success)
    }

    @Test
    fun `import byte cap constant is bounded`() {
        assertEquals(5_000_000L, LOCAL_BACKUP_MAX_FILE_BYTES)
        assertEquals(LOCAL_BACKUP_MAX_CHARS.toLong(), LOCAL_BACKUP_MAX_FILE_BYTES)
    }

    @Test
    fun `counts and summary describe what import would replace`() {
        val counts = localBackupCounts(sampleBackup())
        assertEquals(2, counts.aliases)
        assertEquals(1, counts.pinned)
        assertEquals(1, counts.muted)
        assertEquals(1, counts.hidden)
        assertEquals(2, counts.drafts)
        assertEquals(1, counts.attachmentDrafts)
        assertEquals(2, counts.queued)
        assertEquals(2, counts.snippets)
        assertEquals(12, counts.total)
        val summary = counts.summaryLine()
        assertTrue(summary.contains("2 aliases"))
        assertTrue(summary.contains("1 attachment drafts"))
        assertTrue(summary.contains("2 snippets"))
    }

    @Test
    fun `blank entries are ignored by counts`() {
        val data = LocalBackupData(
            aliases = mapOf("" to "alias", "s_1" to "  "),
            pinned = listOf("", "s_1"),
            drafts = mapOf("s_1" to "   "),
            snippets = listOf(BackupSnippet(id = "snip", prompt = "  ")),
        )
        val counts = localBackupCounts(data)
        assertEquals(0, counts.aliases)
        assertEquals(1, counts.pinned)
        assertEquals(0, counts.drafts)
        assertEquals(0, counts.snippets)
    }

    @Test
    fun `export file names are readable and dated`() {
        assertEquals(
            "dsh-mobile-backup-20261102.json",
            localBackupFileName(LocalDate.of(2026, 11, 2)),
        )
    }
}
