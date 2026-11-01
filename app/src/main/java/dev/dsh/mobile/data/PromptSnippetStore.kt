package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

@Serializable
data class PromptSnippet(
    val id: String,
    val title: String,
    val prompt: String,
)

/** User-saved reusable prompts, newest first. */
class PromptSnippetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prompt-snippets", Context.MODE_PRIVATE)

    fun load(): List<PromptSnippet> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { DshJson.decodeFromString<List<PromptSnippet>>(raw) }.getOrDefault(emptyList())
    }

    fun add(prompt: String): PromptSnippet? {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return null
        val title = trimmed.lineSequence().firstOrNull()?.trim()?.take(60).orEmpty()
            .ifBlank { "Snippet" }
        val snippet = PromptSnippet(id = UUID.randomUUID().toString(), title = title, prompt = trimmed)
        save((listOf(snippet) + load()).take(LIMIT))
        return snippet
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    private fun save(values: List<PromptSnippet>) {
        prefs.edit().putString(KEY, DshJson.encodeToString(values)).apply()
    }

    private companion object {
        const val KEY = "snippets"
        const val LIMIT = 30
    }
}
