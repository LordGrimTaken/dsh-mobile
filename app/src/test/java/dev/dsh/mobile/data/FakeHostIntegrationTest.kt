package dev.dsh.mobile.data

import dev.dsh.mobile.net.DshApiClient
import dev.dsh.mobile.net.DshApiException
import dev.dsh.mobile.net.DshJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live, non-hermetic smoke test of the real client stack against `tools/fake-host/server.mjs`
 * (or the real host plugin).
 *
 * The pairing code is obtained the way the product does it: `POST /mobile/v1/pair/new` first
 * (the desktop-side helper endpoint), then `POST /mobile/v1/pair/complete` with the returned
 * code. Nothing is hardcoded, so this works against a freshly started host whose code is random.
 *
 * Start the fake host, then run:
 *   $env:DSH_FAKE_HOST = '1'
 *   $env:DSH_FAKE_HOST_URL = 'http://127.0.0.1:3548'   # optional, default 3543
 *   ./gradlew testDebugUnitTest --tests dev.dsh.mobile.data.FakeHostIntegrationTest
 *
 * Skipped by default so a normal `testDebugUnitTest` stays hermetic.
 */
class FakeHostIntegrationTest {

    @Test
    fun `pairing, sessions, SSE streaming and approvals against the fake host`() = runBlocking {
        assumeTrue(
            "Set DSH_FAKE_HOST=1 and start tools/fake-host/server.mjs to run this test",
            System.getenv("DSH_FAKE_HOST") == "1",
        )
        val baseUrl = System.getenv("DSH_FAKE_HOST_URL") ?: "http://127.0.0.1:3543"

        // 1. Public health, then pair exactly as the app does:
        //    POST /pair/new (desktop helper) -> 6-digit code -> POST /pair/complete.
        //    The host owns code generation, so the code must never be hardcoded here.
        val anonymous = DshApiClient(baseUrl)
        val health = anonymous.health()
        assertTrue("health ok", health.ok)

        val pairingCode = requestPairingCode(baseUrl)
        assertTrue("pair/new returned a usable code", pairingCode.isNotBlank())

        val pairing = anonymous.pairComplete(pairingCode, "JVM integration test")
        assertTrue("pairing returned a token", pairing.token.isNotBlank())
        assertTrue("pairing returned a device id", pairing.deviceId.isNotBlank())
        assertTrue("pairing returned workspaces", pairing.workspaces.isNotEmpty())

        val api = DshApiClient(baseUrl, pairing.token)

        // 2. Authenticated snapshots.
        val host = api.host()
        assertTrue("host has workspaces", host.workspaces.isNotEmpty())
        val sessions = api.sessions()
        assertTrue("sessions list is not empty", sessions.isNotEmpty())
        val detail = api.session("s_android")
        assertTrue("session detail has messages", detail.messages.isNotEmpty())

        // 3. Typed error mapping.
        try {
            DshApiClient(baseUrl).sessions()
            fail("unauthenticated sessions call should fail")
        } catch (expected: DshApiException.Unauthorized) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }

        // 4. RemoteRepository: SSE connection, transcript, live deltas, approval resolution.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = RemoteRepository(
            api = api,
            scope = scope,
            initialWorkspaces = host.workspaces.map { it.toUi() },
        )
        try {
            repo.start()
            assertTrue("SSE connected", waitUntil(10_000) { repo.connection == ConnectionStatus.Live })
            assertTrue("sessions arrived", waitUntil(10_000) { repo.sessions.isNotEmpty() })

            repo.loadMessages("s_android")
            val before = repo.sessions.firstOrNull { it.id == "s_android" }?.messages.orEmpty()
            repo.sendPrompt("s_android", "Run the test suite")
            assertTrue(
                "streaming reply appended to the transcript",
                waitUntil(10_000) {
                    val session = repo.sessions.firstOrNull { it.id == "s_android" }
                    val messages = session?.messages.orEmpty()
                    messages.size > before.size ||
                        messages.any { it is AssistantMessage && it.text.isNotEmpty() }
                },
            )

            // 5. Approval flow: the fake host raises an approval for "deploy".
            repo.sendPrompt("s_release", "deploy the release")
            assertTrue(
                "approval requested over SSE",
                waitUntil(10_000) { repo.approvals.isNotEmpty() },
            )
            val approval = repo.approvals.first()
            repo.decideApproval(approval.id, allow = false)
            assertTrue(
                "approval resolved",
                waitUntil(10_000) { repo.approvals.none { it.id == approval.id } },
            )

            // 6. Questions are readable and answerable against the fake host. A just-started fake
            // host seeds a pending question; other runs may have answered it, so tolerate empty.
            val questions = api.questions()
            if (questions.isNotEmpty()) {
                val question = questions.first()
                api.answerQuestion(question.id, question.options.firstOrNull() ?: "ok", 0)
            }
        } finally {
            repo.dispose()
            scope.cancel()
        }
    }

    /**
     * Arms a one-use pairing code through the loopback pairing endpoint, mirroring the desktop
     * helper. Returns the host-generated code; pairing would fail with `invalid_code` without it.
     */
    private fun requestPairingCode(baseUrl: String): String {
        val url = (DshApiClient.normalizeApiRoot(baseUrl) + "/pair/new").toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(
                "POST /pair/new failed: HTTP ${response.code} $body",
                response.isSuccessful,
            )
            val code = runCatching {
                DshJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
            }.getOrNull()
            assertTrue("POST /pair/new did not return a code: $body", !code.isNullOrBlank())
            return code ?: ""
        }
    }

    private suspend fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(100)
        }
        return condition()
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
