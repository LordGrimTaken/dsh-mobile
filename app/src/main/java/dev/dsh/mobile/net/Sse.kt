package dev.dsh.mobile.net

import kotlinx.serialization.json.JsonObject

/**
 * One parsed Server-Sent Event from `GET /mobile/v1/events`.
 *
 * [data] is the `data` object of the event envelope; [type] is the event type (falling back to
 * the `event:` line when the envelope omits it); [id] is the replay cursor sent as `since`.
 */
data class SseEvent(
    val id: String?,
    val type: String?,
    val sessionId: String? = null,
    val data: JsonObject?,
)

/**
 * Incremental parser for the text/event-stream wire format.
 *
 * Feed every line (without its newline) into [feed]; a blank line dispatches the buffered event,
 * so [feed] returns a non-null event at the end of each block. [finish] flushes a trailing block
 * that was not followed by a blank line.
 */
internal class SseParser {

    private var id: String? = null
    private var type: String? = null
    private val data = StringBuilder()

    fun feed(line: String): SseEvent? = when {
        line.isEmpty() -> dispatch()
        line.startsWith(":") -> null // comment / heartbeat
        else -> {
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "id" -> id = value.trim().ifEmpty { null }
                "event" -> type = value.trim().ifEmpty { null }
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
                else -> Unit
            }
            null
        }
    }

    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val raw = data.toString()
        val lineType = type
        val lineId = id
        reset()
        if (raw.isBlank()) {
            return lineType?.let { SseEvent(id = lineId, type = it, sessionId = null, data = null) }
        }
        val payload = runCatching { DshJson.parseToJsonElement(raw) }.getOrNull()
        val obj = payload as? JsonObject
            ?: return lineType?.let { SseEvent(id = lineId, type = it, sessionId = null, data = null) }
        val envelope = runCatching {
            DshJson.decodeFromJsonElement(EventEnvelopeDto.serializer(), obj)
        }.getOrNull()
        val eventType = envelope?.type?.takeIf { it.isNotBlank() } ?: lineType
        val eventData = envelope?.data ?: (obj["data"] as? JsonObject)
        val eventId = envelope?.id?.takeIf { it.isNotBlank() } ?: lineId
        return SseEvent(
            id = eventId,
            type = eventType,
            sessionId = envelope?.sessionId,
            data = eventData,
        )
    }

    private fun reset() {
        id = null
        type = null
        data.setLength(0)
    }
}
