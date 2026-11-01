package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Versioned, host-independent local backup format.
 *
 * Only local-only state the user would miss on a new phone is included: session aliases,
 * pinned/muted/hidden lists, composer drafts, host-accepted attachment draft records, the offline
 * send queue, prompt snippets and safe presentation/notification preferences.
 *
 * NEVER included: host pairing profiles/tokens (Keystore-encrypted), the app-lock secret/grace,
 * crash logs, attachment caches or any other secret. Import only rejects *top-level* JSON keys that
 * name such data (see [LOCAL_BACKUP_FORBIDDEN_TOP_LEVEL_KEYS]); user aliases/drafts/queue text is
 * never scanned for words like "token", so a legitimate backup can't be rejected for its content.
 *
 * The encode/decode/validate/merge helpers here are pure JVM code (no Android dependencies) so
 * they can be unit tested and reused.
 */
const val LOCAL_BACKUP_SCHEMA: Int = 1

/** Hard cap for a backup file (~5 MB of JSON); larger files are rejected. */
internal const val LOCAL_BACKUP_MAX_CHARS: Int = 5_000_000

/** Byte cap enforced while streaming an import, so an oversized file never lands in memory. */
internal const val LOCAL_BACKUP_MAX_FILE_BYTES: Long = 5_000_000L

/**
 * Top-level JSON keys that must never appear in a local backup (tokens/pairing/app lock/caches).
 * Only top-level keys are checked: export uses a strict field whitelist, and user text may contain
 * words like "token" harmlessly.
 */
internal val LOCAL_BACKUP_FORBIDDEN_TOP_LEVEL_KEYS: Set<String> = setOf(
    "token",
    "tokens",
    "accesstoken",
    "authorization",
    "app_lock",
    "applock",
    "pairing",
    "pairings",
    "hosts",
    "profiles",
    "devices",
    "keystore",
    "crash",
    "crashes",
    "attachmentbytes",
    "cachedir",
    "app_foreground",
    "background_sync_last",
)

@Serializable
data class BackupAttachmentDraft(
    val name: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val attachmentId: String = "",
)

@Serializable
data class BackupQueuedPrompt(
    val id: Long,
    val text: String = "",
    val attachmentIds: List<String> = emptyList(),
)

@Serializable
data class BackupSnippet(
    val id: String,
    val title: String = "",
    val prompt: String = "",
)

/** Safe, device-local preferences only; every field is nullable so older backups stay valid. */
@Serializable
data class BackupSettings(
    val darkTheme: Boolean? = null,
    val amoledDark: Boolean? = null,
    val chatFontScale: Float? = null,
    val appFontScale: Float? = null,
    val wrapCodeBlocks: Boolean? = null,
    val notifyInput: Boolean? = null,
    val notifyDone: Boolean? = null,
    val notifyGoal: Boolean? = null,
    val lowBalanceAlerts: Boolean? = null,
    val lowBalanceThreshold: Double? = null,
    val quietHours: Boolean? = null,
    val quietStartHour: Int? = null,
    val quietEndHour: Int? = null,
    val voicePreferOffline: Boolean? = null,
    val voiceLanguage: String? = null,
)

/** The full local backup document (`schema` is the format version). */
@Serializable
data class LocalBackupData(
    val schema: Int = LOCAL_BACKUP_SCHEMA,
    val exportedAt: String = "",
    val appVersion: String = "",
    val aliases: Map<String, String> = emptyMap(),
    val pinned: List<String> = emptyList(),
    val muted: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val drafts: Map<String, String> = emptyMap(),
    val pendingAttachmentDrafts: Map<String, List<BackupAttachmentDraft>> = emptyMap(),
    val queue: Map<String, List<BackupQueuedPrompt>> = emptyMap(),
    val snippets: List<BackupSnippet> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

/** What an import would replace, shown in the confirmation dialog. */
data class LocalBackupCounts(
    val aliases: Int = 0,
    val pinned: Int = 0,
    val muted: Int = 0,
    val hidden: Int = 0,
    val drafts: Int = 0,
    val attachmentDrafts: Int = 0,
    val queued: Int = 0,
    val snippets: Int = 0,
) {
    val total: Int
        get() = aliases + pinned + muted + hidden + drafts +
            attachmentDrafts + queued + snippets

    /** Compact one-line summary for Settings / the import dialog. */
    fun summaryLine(): String {
        val parts = listOf(
            aliases to "aliases",
            pinned to "pinned",
            muted to "muted",
            hidden to "hidden",
            drafts to "drafts",
            attachmentDrafts to "attachment drafts",
            queued to "queued",
            snippets to "snippets",
        ).filter { it.first > 0 }.map { "${it.first} ${it.second}" }
        return if (parts.isEmpty()) "empty backup" else parts.joinToString(" · ")
    }
}

/** Result of parsing a backup file. */
sealed interface LocalBackupDecodeResult {
    data class Success(val data: LocalBackupData) : LocalBackupDecodeResult
    data class Invalid(val message: String) : LocalBackupDecodeResult
    data class UnsupportedVersion(val version: Int?) : LocalBackupDecodeResult
}

/** Serializes [data] into the versioned backup JSON. Pure JVM; never includes secrets. */
internal fun encodeLocalBackup(data: LocalBackupData): String =
    DshJson.encodeToString(data)

/**
 * Parses and validates backup JSON: size cap, a top-level forbidden-key rejection (tokens/app
 * lock/pairing/caches), then the versioned schema decode. User text values are never scanned.
 */
internal fun decodeLocalBackup(raw: String): LocalBackupDecodeResult {
    val text = raw.trim()
    if (text.isEmpty()) return LocalBackupDecodeResult.Invalid("The backup file is empty.")
    if (text.length > LOCAL_BACKUP_MAX_CHARS) {
        return LocalBackupDecodeResult.Invalid("The backup file is too large to import.")
    }
    val root = runCatching { DshJson.parseToJsonElement(text) }.getOrNull()
    val topLevelKeys = (root as? JsonObject)?.keys
        ?: return LocalBackupDecodeResult.Invalid("Not a valid DSH Mobile backup file.")
    val forbidden = topLevelKeys.firstOrNull { key ->
        key.lowercase(Locale.US) in LOCAL_BACKUP_FORBIDDEN_TOP_LEVEL_KEYS
    }
    if (forbidden != null) {
        return LocalBackupDecodeResult.Invalid(
            "That file contains host tokens or private app data; refusing to import it.",
        )
    }
    val data = runCatching { DshJson.decodeFromString<LocalBackupData>(text) }.getOrNull()
        ?: return LocalBackupDecodeResult.Invalid("Not a valid DSH Mobile backup file.")
    return when {
        data.schema == LOCAL_BACKUP_SCHEMA -> LocalBackupDecodeResult.Success(data)
        data.schema > LOCAL_BACKUP_SCHEMA ->
            LocalBackupDecodeResult.UnsupportedVersion(data.schema)

        else -> LocalBackupDecodeResult.Invalid("Unsupported backup schema ${data.schema}.")
    }
}

/** Counts used by Settings and the import confirmation dialog. */
internal fun localBackupCounts(data: LocalBackupData): LocalBackupCounts = LocalBackupCounts(
    aliases = data.aliases.count { it.key.isNotBlank() && it.value.isNotBlank() },
    pinned = data.pinned.count { it.isNotBlank() },
    muted = data.muted.count { it.isNotBlank() },
    hidden = data.hidden.count { it.isNotBlank() },
    drafts = data.drafts.count { it.key.isNotBlank() && it.value.isNotBlank() },
    attachmentDrafts = data.pendingAttachmentDrafts.values.sumOf { records ->
        records.count { it.attachmentId.isNotBlank() }
    },
    queued = data.queue.values.sumOf { prompts -> prompts.size },
    snippets = data.snippets.count { it.prompt.isNotBlank() },
)

/** Readable SAF filename for an export: `dsh-mobile-backup-YYYYMMDD.json`. */
internal fun localBackupFileName(date: LocalDate = LocalDate.now()): String {
    val stamp = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    return "dsh-mobile-backup-$stamp.json"
}
