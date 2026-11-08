package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.SessionDetailResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/** Raw session detail stored on disk, plus when it was saved. */
@Serializable
data class CachedTranscript(
    val savedAt: Long = 0L,
    val sessionId: String = "",
    val response: SessionDetailResponse = SessionDetailResponse(),
)

/** A cached transcript message matching a search query. */
data class TranscriptSearchHit(
    val sessionId: String,
    val title: String,
    val snippet: String,
)

/** Count/size of offline transcript files, for Settings. */
data class OfflineCacheStats(
    val transcriptCount: Int = 0,
    val bytes: Long = 0L,
)

/**
 * Per-host offline transcript cache under the app's cache directory:
 *
 * `cacheDir/transcripts/<sha256(baseUrl)>/<sha256(sessionId)>.json`
 *
 * The whole [SessionDetailResponse] returned by `GET /sessions/{id}` (transcript page payload)
 * is persisted as JSON with [DshJson], so an offline open can paint the last-known conversation
 * instantly while the network fetch runs. Only the most recent [maxSessions] transcripts per host
 * are kept (oldest by file mtime pruned); everything is wrapped in runCatching so a missing,
 * corrupt, or unwritable cache can never crash a session open.
 */
class HostTranscriptCache(
    context: Context,
    baseUrl: String,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
) {

    private val hostDir: File = File(
        File(context.applicationContext.cacheDir, CACHE_DIR_NAME),
        safeKey(baseUrl),
    )
    private val writeLock = Any()

    /** Cached detail for [sessionId], or null when absent/corrupt/unreadable. */
    fun load(sessionId: String): SessionDetailResponse? {
        if (sessionId.isBlank()) return null
        return runCatching {
            val file = fileFor(sessionId)
            if (!file.isFile) return@runCatching null
            val cached = DshJson.decodeFromString<CachedTranscript>(file.readText())
            cached.response.takeIf { cached.sessionId == sessionId }
        }.getOrNull()
    }

    /** Persists [detail] for [sessionId], then prunes this host's cache to [maxSessions]. */
    fun save(sessionId: String, detail: SessionDetailResponse) {
        if (sessionId.isBlank()) return
        runCatching {
            synchronized(writeLock) {
                if (!hostDir.isDirectory && !hostDir.mkdirs()) return
                val file = fileFor(sessionId)
                val cached = CachedTranscript(
                    savedAt = System.currentTimeMillis(),
                    sessionId = sessionId,
                    response = detail,
                )
                // Write-then-rename so a kill mid-write never leaves a half-JSON transcript.
                val temp = File(hostDir, file.name + ".tmp")
                temp.writeText(DshJson.encodeToString(cached))
                if (!temp.renameTo(file)) {
                    file.delete()
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                }
                pruneLocked()
            }
        }
    }

    /** Number of cached transcripts currently on disk for this host (best effort). */
    fun size(): Int = runCatching { transcriptFiles().size }.getOrDefault(0)

    /**
     * Searches the text of every cached transcript for [query], newest cached session first.
     * One hit per session is returned (the first matching message) with a short context snippet.
     */
    fun search(query: String, limit: Int = 20): List<TranscriptSearchHit> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        return runCatching {
            transcriptFiles()
                .mapNotNull { file ->
                    runCatching {
                        val cached = DshJson.decodeFromString<CachedTranscript>(file.readText())
                        val normalized = cached.response.messages.firstOrNull { message ->
                            message.text.contains(needle, ignoreCase = true)
                        } ?: return@runCatching null
                        file.lastModified() to TranscriptSearchHit(
                            sessionId = cached.sessionId,
                            title = cached.response.session?.title?.ifBlank { "session" }
                                ?: "session",
                            snippet = snippetAround(normalized.text, needle),
                        )
                    }.getOrNull()
                }
                .sortedByDescending { it.first }
                .map { it.second }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    private fun snippetAround(text: String, needle: String): String {
        val clean = text.replace('\n', ' ').trim()
        val start = clean.indexOf(needle, ignoreCase = true).takeIf { it >= 0 } ?: 0
        val from = (start - 50).coerceAtLeast(0)
        val to = (start + needle.length + 90).coerceAtMost(clean.length)
        return (if (from > 0) "…" else "") + clean.substring(from, to) +
            (if (to < clean.length) "…" else "")
    }

    private fun pruneLocked() {
        val files = transcriptFiles()
        if (files.size <= maxSessions) return
        files
            .sortedWith(compareBy({ it.lastModified() }, { it.name }))
            .take(files.size - maxSessions)
            .forEach { it.delete() }
    }

    private fun transcriptFiles(): List<File> = hostDir
        .listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
        ?.toList()
        .orEmpty()

    private fun fileFor(sessionId: String): File = File(hostDir, safeKey(sessionId) + FILE_SUFFIX)

    /** Filesystem-safe key: SHA-256 of the trimmed value, so any base URL / session id is safe. */
    private fun safeKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        digest.forEachIndexed { index, byte ->
            val unsigned = byte.toInt() and 0xff
            hex[index * 2] = digits[unsigned ushr 4]
            hex[index * 2 + 1] = digits[unsigned and 0x0f]
        }
        return String(hex)
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS: Int = 30
        private const val CACHE_DIR_NAME = "transcripts"
        private const val FILE_SUFFIX = ".json"

        /** Aggregate on-disk transcript cache stats across every host. */
        fun stats(context: Context): OfflineCacheStats = runCatching {
            val root = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)
            var count = 0
            var bytes = 0L
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(FILE_SUFFIX) && !it.name.endsWith(".tmp") }
                .forEach { file ->
                    count += 1
                    bytes += file.length()
                }
            OfflineCacheStats(transcriptCount = count, bytes = bytes)
        }.getOrDefault(OfflineCacheStats())

        /** Removes every cached transcript for every host. */
        fun clearAll(context: Context) {
            runCatching {
                File(context.applicationContext.cacheDir, CACHE_DIR_NAME).deleteRecursively()
            }
        }
    }
}
