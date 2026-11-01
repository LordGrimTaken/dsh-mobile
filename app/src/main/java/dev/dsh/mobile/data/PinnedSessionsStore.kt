package dev.dsh.mobile.data

import android.content.Context

/** User-pinned session ids, persisted locally (the host has no pin concept). */
class PinnedSessionsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pinned-sessions", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet("ids", emptySet()).orEmpty().toSet()

    fun save(ids: Set<String>) {
        prefs.edit().putStringSet("ids", ids).apply()
    }
}
