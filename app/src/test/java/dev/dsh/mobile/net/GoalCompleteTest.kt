package dev.dsh.mobile.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * JVM tests for the goal-complete contract:
 * `POST /sessions/{id}/goal/complete` body `{id?, revision?}` returning the goal envelope.
 */
class GoalCompleteTest {

    @Test
    fun `ref request encodes id and revision`() {
        val json = DshJson.encodeToString(
            GoalRefRequest.serializer(),
            GoalRefRequest(id = "goal-7", revision = 3),
        )
        assertTrue(json.contains("\"id\":\"goal-7\""))
        assertTrue(json.contains("\"revision\":3"))
    }

    @Test
    fun `ref request omits absent id and revision`() {
        val json = DshJson.encodeToString(GoalRefRequest.serializer(), GoalRefRequest())
        assertFalse("null id must be omitted", json.contains("id"))
        assertFalse("null revision must be omitted", json.contains("revision"))
    }

    @Test
    fun `complete response decodes phase complete and disarmed activation`() {
        val json = """
            {"supported":true,"live":true,"goal":{
              "id":"goal-1","objective":"Ship the complete action","phase":"complete",
              "maxGoalRounds":120,"roundsStarted":12,"revision":9,
              "activation":"disarmed","blockedReason":null
            }}
        """.trimIndent()
        val response = DshJson.decodeFromString(SessionGoalResponse.serializer(), json)
        assertTrue(response.supported)
        assertTrue(response.live)
        assertEquals("complete", response.goal?.phase)
        assertEquals("disarmed", response.goal?.activation)
        assertEquals(9, response.goal?.revision)
    }

    @Test
    fun `goal changed complete event decodes the operation`() {
        val json = """
            {"sessionId":"session-1","operation":"complete","goal":{
              "id":"goal-1","objective":"Ship it","phase":"complete","maxGoalRounds":120,
              "roundsStarted":12,"revision":9,"activation":"disarmed"}}
        """.trimIndent()
        val event = DshJson.decodeFromString(GoalChangedEventDto.serializer(), json)
        assertEquals("session-1", event.sessionId)
        assertEquals("complete", event.operation)
        assertEquals("complete", event.goal?.phase)
    }

    @Test
    fun `complete call posts to the goal complete route with the ref body`() = runBlocking {
        OneShotHost(RESPONSE_BODY).use { host ->
            val api = DshApiClient("http://127.0.0.1:${host.port}", token = "test-token")
            val response = api.completeSessionGoal("session-1", goalId = "goal-7", revision = 3)
            host.await()

            assertEquals("POST", host.method)
            assertTrue(
                "unexpected route: ${host.path}",
                host.path.endsWith("/mobile/v1/sessions/session-1/goal/complete"),
            )
            assertTrue("missing goal id in body: ${host.body}", host.body.contains("\"id\":\"goal-7\""))
            assertTrue("missing revision in body: ${host.body}", host.body.contains("\"revision\":3"))
            assertEquals("complete", response.goal?.phase)
            assertEquals("disarmed", response.goal?.activation)
        }
    }

    @Test
    fun `complete call without ref sends an empty body`() = runBlocking {
        OneShotHost(RESPONSE_BODY).use { host ->
            val api = DshApiClient("http://127.0.0.1:${host.port}")
            api.completeSessionGoal("session-1")
            host.await()
            assertEquals("{}", host.body.replace(" ", ""))
        }
    }

    /** Minimal one-shot HTTP/1.1 server (com.sun.net.httpserver is unavailable in unit tests). */
    private class OneShotHost(private val responseBody: String) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = serverSocket.localPort
        @Volatile
        var method: String = ""
        @Volatile
        var path: String = ""
        @Volatile
        var body: String = ""
        private val thread = Thread {
            runCatching {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine().orEmpty()
                    val parts = requestLine.split(' ')
                    method = parts.getOrElse(0) { "" }
                    path = parts.getOrElse(1) { "" }
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    val expected = headers["content-length"]?.toIntOrNull() ?: 0
                    val buffer = CharArray(expected)
                    var read = 0
                    while (read < expected) {
                        val count = input.read(buffer, read, expected - read)
                        if (count < 0) break
                        read += count
                    }
                    body = String(buffer, 0, read)
                    val bytes = responseBody.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().use { output ->
                        output.write(head.toByteArray(Charsets.UTF_8))
                        output.write(bytes)
                        output.flush()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun await(timeoutMillis: Long = 5_000) {
            thread.join(timeoutMillis)
        }

        override fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private companion object {
        val RESPONSE_BODY = """
            {"supported":true,"live":true,"goal":{
              "id":"goal-7","objective":"Ship it","phase":"complete","maxGoalRounds":120,
              "roundsStarted":40,"revision":4,"activation":"disarmed",
              "createdAt":"2026-10-31T06:00:00Z","updatedAt":"2026-10-31T06:05:00Z"}}
        """.trimIndent()
    }
}
