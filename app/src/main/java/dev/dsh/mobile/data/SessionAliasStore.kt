package dev.dsh.mobile.data

import android.content.Context

/**
 * Local-only session display names. The host title is never changed; this is a per-device alias
 * used in lists, search and the chat header.
 */
class SessionAliasStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("dsh-session-aliases", Context.MODE_PRIVATE)

    fun load(): Map<String, String> = prefs.all.mapNotNull { (key, value) ->
        val alias = (value as? String)?.trim().orEmpty()
        if (alias.isEmpty()) null else key to alias
    }.toMap()

    fun save(aliases: Map<String, String>) {
        prefs.edit().clear().apply()
        if (aliases.isEmpty()) return
        val editor = prefs.edit()
        aliases.forEach { (sessionId, alias) ->
            if (sessionId.isNotBlank() && alias.isNotBlank()) editor.putString(sessionId, alias.trim())
        }
        editor.apply()
    }
}
