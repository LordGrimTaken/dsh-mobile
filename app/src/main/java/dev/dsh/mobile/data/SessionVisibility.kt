package dev.dsh.mobile.data

/**
 * Client-side safety net for the v0.2.3 filtered session API.
 *
 * Newer hosts hide subagent/internal sessions from `GET /sessions` (top-level user sessions have
 * ids beginning `session-`; internal ones are bare UUIDs). Against an older host that does not
 * filter yet, the app must not reintroduce those rows into the list.
 */
internal object SessionVisibility {

    private val BARE_UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    /** True for user-facing sessions; false for bare-UUID internal/subagent ids and blanks. */
    fun isUserSession(id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("session-")) return true
        return !BARE_UUID.matches(trimmed)
    }
}
