package dev.dsh.mobile.net

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Call
import okhttp3.EventListener

/**
 * Coarse request timing shared by the API clients and the Connection page.
 *
 * The Connection page shows the most recent request's first-byte and total time instead of
 * fabricated numbers; the values come from OkHttp's real event callbacks.
 */
object PerfBus {
    var lastFirstByteMillis by mutableStateOf<Long?>(null)
        private set
    var lastRequestMillis by mutableStateOf<Long?>(null)
        private set

    /** Number of conditional GETs answered with 304 in this app session. */
    var cacheHits by mutableStateOf(0)
        private set

    internal fun recordCacheHit() {
        cacheHits += 1
    }

    internal fun record(firstByteMillis: Long?, totalMillis: Long?) {
        firstByteMillis?.let { lastFirstByteMillis = it.coerceAtLeast(0) }
        totalMillis?.let { lastRequestMillis = it.coerceAtLeast(0) }
    }
}

internal class PerfEventListener : EventListener() {
    private var requestStartNanos = 0L
    private var headersStartNanos = 0L

    override fun requestHeadersStart(call: Call) {
        requestStartNanos = System.nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
        headersStartNanos = System.nanoTime()
    }

    override fun callEnd(call: Call) {
        if (requestStartNanos > 0L && headersStartNanos > 0L) {
            PerfBus.record(
                firstByteMillis = (headersStartNanos - requestStartNanos) / 1_000_000,
                totalMillis = (System.nanoTime() - requestStartNanos) / 1_000_000,
            )
        }
        requestStartNanos = 0L
        headersStartNanos = 0L
    }
}
