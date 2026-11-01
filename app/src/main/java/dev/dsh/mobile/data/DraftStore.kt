package dev.dsh.mobile.data

import android.content.Context

/**
 * Per-session composer drafts, persisted in app-private SharedPreferences.
 *
 * Drafts are intentionally not encrypted: they sit in the same app-private storage as DSH's
 * session logs and attachment cache, and a Keystore dependency here would only add failure modes.
 * Blank drafts are removed rather than stored as empty strings.
 */
class DraftStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("session-drafts", Context.MODE_PRIVATE)

    fun load(sessionId: String): String =
        if (sessionId.isBlank()) "" else prefs.getString(key(sessionId), "").orEmpty()

    fun save(sessionId: String, text: String) {
        if (sessionId.isBlank()) return
        if (text.isBlank()) prefs.edit().remove(key(sessionId)).apply()
        else prefs.edit().putString(key(sessionId), text).apply()
    }

    fun clear(sessionId: String) {
        if (sessionId.isNotBlank()) prefs.edit().remove(key(sessionId)).apply()
    }

    private fun key(sessionId: String): String = "draft:$sessionId"
}
