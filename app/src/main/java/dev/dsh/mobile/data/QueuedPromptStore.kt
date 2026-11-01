package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
private data class StoredPrompt(
    val id: Long,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
)

@Serializable
private data class StoredQueues(
    val sessions: Map<String, List<StoredPrompt>> = emptyMap(),
)

/** Persists queued prompts so a process death or reconnect doesn't lose them. */
class QueuedPromptStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("queued-prompts", Context.MODE_PRIVATE)

    fun load(): Map<String, List<QueuedPrompt>> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val stored = runCatching { DshJson.decodeFromString<StoredQueues>(raw) }.getOrDefault(StoredQueues())
        return stored.sessions.mapValues { (_, prompts) ->
            prompts.map { prompt ->
                QueuedPrompt(
                    id = prompt.id,
                    text = prompt.text,
                    attachmentIds = prompt.attachmentIds,
                )
            }
        }
    }

    fun save(queues: Map<String, List<QueuedPrompt>>) {
        val stored = StoredQueues(
            sessions = queues.filterValues { it.isNotEmpty() }.mapValues { (_, prompts) ->
                prompts.map { prompt ->
                    StoredPrompt(prompt.id, prompt.text, prompt.attachmentIds)
                }
            },
        )
        prefs.edit().putString(KEY, DshJson.encodeToString(stored)).apply()
    }

    private companion object {
        const val KEY = "queues"
    }
}
