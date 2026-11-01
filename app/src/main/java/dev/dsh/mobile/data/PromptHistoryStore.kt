package dev.dsh.mobile.data

import android.content.Context
import dev.dsh.mobile.net.DshJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Last few prompts sent from this phone, most recent first. */
class PromptHistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prompt-history", Context.MODE_PRIVATE)

    fun load(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { DshJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val next = (listOf(trimmed) + load().filterNot { it == trimmed }).take(LIMIT)
        prefs.edit().putString(KEY, DshJson.encodeToString(next)).apply()
    }

    private companion object {
        const val KEY = "prompts"
        const val LIMIT = 20
    }
}
