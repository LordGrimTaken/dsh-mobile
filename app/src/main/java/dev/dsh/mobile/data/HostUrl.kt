package dev.dsh.mobile.data

sealed interface HostUrlResult {
    data class Valid(val root: String) : HostUrlResult
    data class Invalid(val message: String) : HostUrlResult
}

/**
 * Normalises a host base URL. The app only ever needs the scheme + host + optional port: the API
 * root (`/mobile/v1`) is appended by [dev.dsh.mobile.net.DshApiClient].
 *
 * Accepts `https://host` and `http://host` (dev), trims whitespace and trailing slashes, and
 * tolerates a pasted `/mobile/v1` suffix. Deeper paths are rejected with a clear message instead
 * of silently producing a broken client.
 */
object HostUrl {

    fun parse(raw: String): HostUrlResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return HostUrlResult.Invalid("Enter the host URL.")
        if (trimmed.any { it.isWhitespace() }) {
            return HostUrlResult.Invalid("The host URL can't contain spaces.")
        }
        val lower = trimmed.lowercase()
        val scheme = when {
            lower.startsWith("https://") -> "https://"
            lower.startsWith("http://") -> "http://"
            else -> return HostUrlResult.Invalid(
                "Start the URL with https:// (or http:// for local dev).",
            )
        }
        val rest = trimmed.substring(scheme.length)
        if (rest.isEmpty()) return HostUrlResult.Invalid("That URL is missing a host name.")

        val separator = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPort = if (separator >= 0) rest.substring(0, separator) else rest
        val path = if (separator >= 0) rest.substring(separator) else ""
        if (hostPort.isBlank()) return HostUrlResult.Invalid("That URL is missing a host name.")
        if (hostPort.contains('@')) {
            return HostUrlResult.Invalid("Remove the username from the host URL.")
        }

        val trimmedPath = path.trimEnd('/')
        if (trimmedPath.isNotEmpty() && trimmedPath != "/" && trimmedPath != "/mobile/v1") {
            return HostUrlResult.Invalid(
                "Use the host root URL only (for example https://host), not a path.",
            )
        }
        return HostUrlResult.Valid(scheme + hostPort)
    }

    /** Root URL, or null when [raw] is not a usable host base URL. */
    fun rootOrNull(raw: String): String? =
        (parse(raw) as? HostUrlResult.Valid)?.root

    /** Human-friendly host part for labels, e.g. `dsh.example.com:443`. */
    fun displayHost(rootOrRaw: String): String {
        val withoutScheme = rootOrRaw.trim().substringAfter("://")
        return withoutScheme.substringBefore('/').ifBlank { rootOrRaw.trim() }
    }
}
