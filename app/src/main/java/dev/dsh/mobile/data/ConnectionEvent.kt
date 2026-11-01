package dev.dsh.mobile.data

/** One recent connection/SSE lifecycle event, shown in Settings → Connection diagnostics. */
data class ConnectionEvent(
    val at: Long,
    val message: String,
)
