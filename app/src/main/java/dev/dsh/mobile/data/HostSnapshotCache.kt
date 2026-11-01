package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.SessionSummaryDto
import dev.dsh.mobile.net.WorkspaceDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Last successful session-list snapshot for one host, persisted for a cache-first cold start. */
@Serializable
data class CachedHostSnapshot(
    val savedAt: Long = 0L,
    val sessions: List<SessionSummaryDto> = emptyList(),
    val workspaces: List<WorkspaceDto> = emptyList(),
)

/**
 * Per-host session-list cache in app-private SharedPreferences.
 *
 * Only the already-visible session summaries and workspaces are stored; tokens, messages and
 * attachments are never cached here. The repository replaces the snapshot on every successful
 * refresh, so a stale cache is bounded by the last time the host was reachable.
 */
class HostSnapshotCache(context: Context, hostKey: String) {
    private val prefs = context.applicationContext.getSharedPreferences("host-snapshots", Context.MODE_PRIVATE)
    private val key = "snapshot:${hostKey.trim().lowercase()}"

    fun load(): CachedHostSnapshot? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { DshJson.decodeFromString<CachedHostSnapshot>(raw) }.getOrNull()
    }

    fun save(sessions: List<SessionSummaryDto>, workspaces: List<WorkspaceDto>) {
        val snapshot = CachedHostSnapshot(
            savedAt = System.currentTimeMillis(),
            sessions = sessions,
            workspaces = workspaces,
        )
        runCatching { prefs.edit().putString(key, DshJson.encodeToString(snapshot)).apply() }
    }

    companion object {
        /** Drops every host's session-list snapshot; next cold start waits for the network. */
        fun clearAll(context: Context) {
            runCatching {
                context.applicationContext
                    .getSharedPreferences("host-snapshots", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
        }
    }
}
