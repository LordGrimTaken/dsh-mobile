package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Minimal persisted metadata for an attachment the host already accepted. */
@Serializable
data class PendingAttachmentRecord(
    val name: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val attachmentId: String = "",
)

/**
 * Per-session attachment draft persistence. Only host-accepted attachment ids are retained (the
 * bytes stay on the host for 24 h), so leaving/reopening the app keeps the composer tray intact
 * without re-uploading. Expired ids simply fail at send time.
 */
class PendingAttachmentStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dsh-pending-attachments", Context.MODE_PRIVATE)

    fun load(sessionId: String): List<PendingAttachmentRecord> {
        if (sessionId.isBlank()) return emptyList()
        return runCatching {
            val raw = prefs.getString(sessionId, null) ?: return emptyList()
            DshJson.decodeFromString<List<PendingAttachmentRecord>>(raw)
        }.getOrDefault(emptyList())
            .filter { it.attachmentId.isNotBlank() }
    }

    fun save(sessionId: String, records: List<PendingAttachmentRecord>) {
        if (sessionId.isBlank()) return
        runCatching {
            if (records.isEmpty()) {
                prefs.edit().remove(sessionId).apply()
            } else {
                prefs.edit()
                    .putString(sessionId, DshJson.encodeToString(records))
                    .apply()
            }
        }
    }
}
