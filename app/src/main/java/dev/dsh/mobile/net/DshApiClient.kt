package dev.dsh.mobile.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSink
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Typed failures from [DshApiClient]. The UI maps these to human wording; the repository maps
 * them to connection state (network -> Offline, unauthorized -> needs re-pair).
 */
sealed class DshApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : DshApiException(message, cause)
    class Unauthorized(message: String) : DshApiException(message)
    class Forbidden(message: String) : DshApiException(message)
    class InvalidCode(message: String) : DshApiException(message)
    class RateLimited(message: String, val retryAfterSeconds: Long? = null) : DshApiException(message)
    class NotFound(message: String) : DshApiException(message)
    class InvalidRequest(message: String, val code: String? = null) : DshApiException(message)
    class Unsupported(message: String) : DshApiException(message)
    class Server(val httpStatus: Int, val code: String?, message: String) : DshApiException(message)
    class Protocol(message: String, cause: Throwable? = null) : DshApiException(message, cause)
}

/**
 * Minimal blocking HTTP client for DSH Mobile API v1 (OkHttp + kotlinx.serialization).
 *
 * All calls are suspend and run on [Dispatchers.IO]; non-2xx responses become a [DshApiException].
 * [events] streams SSE frames with the `since` replay cursor.
 */
class DshApiClient(
    baseUrl: String,
    private val token: String? = null,
    private val http: OkHttpClient = defaultHttp(),
    private val streamHttp: OkHttpClient = defaultStreamHttp(),
) {

    private val apiRoot: String = normalizeApiRoot(baseUrl)

    /** True for Tailscale MagicDNS / Serve hostnames, where DNS only works while the VPN is up. */
    internal val isTailnetHost: Boolean = runCatching {
        apiRoot.toHttpUrl().host.lowercase().endsWith(".ts.net")
    }.getOrDefault(false)

    // region endpoints

    suspend fun health(): HealthDto = decode(execute(request("GET", "/health")), HealthDto.serializer())

    suspend fun pairComplete(
        code: String,
        deviceName: String,
        publicKey: String? = null,
    ): PairCompleteResponse = decode(
        execute(
            request(
                "POST",
                "/pair/complete",
                body = encodeBody(PairCompleteRequest(code = code, deviceName = deviceName, publicKey = publicKey)),
                authenticated = false,
            ),
        ),
        PairCompleteResponse.serializer(),
    )

    suspend fun host(): HostDto = decode(execute(request("GET", "/host")), HostDto.serializer())

    suspend fun sessions(): List<SessionSummaryDto> =
        decode(execute(request("GET", "/sessions")), SessionsResponse.serializer()).sessions

    /** GET /usage — aggregate token use across the host plus optional API balance. */
    suspend fun usage(): UsageDto = decode(execute(request("GET", "/usage")), UsageDto.serializer())

    suspend fun hiddenSessions(): HiddenSessionsDto =
        decode(execute(request("GET", "/sessions/hidden")), HiddenSessionsDto.serializer())

    suspend fun setHiddenSessions(ids: List<String>): HiddenSessionsDto = decode(
        execute(
            request(
                "PUT",
                "/sessions/hidden",
                body = encodeBody(HiddenSessionsRequest(ids)),
            ),
        ),
        HiddenSessionsDto.serializer(),
    )

    // region session management (rename / archive)

    /** POST /sessions/{id}/rename — changes the host title everywhere. */
    suspend fun renameSession(sessionId: String, title: String): RenameSessionResponse = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/rename",
                body = encodeBody(RenameSessionRequest(title)),
            ),
        ),
        RenameSessionResponse.serializer(),
    )

    suspend fun archiveSession(sessionId: String): SessionArchiveResponse = decode(
        execute(request("POST", "/sessions/$sessionId/archive")),
        SessionArchiveResponse.serializer(),
    )

    suspend fun unarchiveSession(sessionId: String): SessionArchiveResponse = decode(
        execute(request("POST", "/sessions/$sessionId/unarchive")),
        SessionArchiveResponse.serializer(),
    )

    /** GET /sessions/archived — archived sessions (may be unsupported). */
    suspend fun archivedSessions(): ArchivedSessionsResponse = decode(
        execute(request("GET", "/sessions/archived")),
        ArchivedSessionsResponse.serializer(),
    )

    // endregion

    suspend fun session(
        sessionId: String,
        limit: Int = 100,
        before: String? = null,
    ): SessionDetailResponse = decode(
        execute(
            request(
                "GET",
                "/sessions/$sessionId",
                query = mapOf("limit" to limit.toString(), "before" to before),
            ),
        ),
        SessionDetailResponse.serializer(),
    )

    suspend fun createSession(workspaceId: String, preset: String? = null): SessionSummaryDto {
        val body = execute(
            request(
                "POST",
                "/sessions",
                body = encodeBody(CreateSessionRequest(workspaceId = workspaceId, preset = preset)),
            ),
        )
        runCatching { DshJson.decodeFromString(CreateSessionResponse.serializer(), body) }
            .getOrNull()
            ?.session
            ?.let { return it }
        runCatching { DshJson.decodeFromString(SessionSummaryDto.serializer(), body) }
            .getOrNull()
            ?.let { return it }
        throw DshApiException.Protocol("Host did not return a session")
    }

    /** GET /presets — the host's agent presets (unsupported hosts return supported=false). */
    suspend fun presets(): PresetsResponse = decode(
        execute(request("GET", "/presets")),
        PresetsResponse.serializer(),
    )

    /** GET /plugins — installed packages in the host's active DSH profile. */
    suspend fun plugins(): PluginsResponse = decode(
        execute(request("GET", "/plugins")),
        PluginsResponse.serializer(),
    )

    /** GET /search?q=…&limit=…&role=…&since=…&until=…&offset=… — host-wide message search. */
    suspend fun search(
        query: String,
        limit: Int = 50,
        role: String? = null,
        since: String? = null,
        until: String? = null,
        offset: Int = 0,
    ): SearchResponse = decode(
        execute(
            request(
                "GET",
                "/search",
                query = hostSearchQueryParams(
                    query = query,
                    limit = limit,
                    role = role,
                    since = since,
                    until = until,
                    offset = offset,
                ),
            ),
        ),
        SearchResponse.serializer(),
    )

    // region push (ntfy)

    suspend fun pushConfig(): PushConfigDto = decode(
        execute(request("GET", "/push")),
        PushConfigDto.serializer(),
    )

    suspend fun updatePushConfig(request: PushUpdateRequest): PushConfigDto = decode(
        execute(request("PUT", "/push", body = encodeBody(request))),
        PushConfigDto.serializer(),
    )

    /** PUT /push/suppress — additive quiet-hours/mute sync; old hosts answer 404/501. */
    suspend fun updatePushSuppression(
        request: PushSuppressionDto,
    ): PushSuppressionSummaryDto = decode(
        execute(request("PUT", "/push/suppress", body = encodeBody(request))),
        PushSuppressionSummaryDto.serializer(),
    )

    suspend fun testPush(): PushTestResponse = decode(
        execute(request("POST", "/push/test")),
        PushTestResponse.serializer(),
    )

    // endregion

    // region approval policy

    suspend fun approvalPolicy(sessionId: String): ApprovalPolicyDto = decode(
        execute(request("GET", "/sessions/$sessionId/approval-policy")),
        ApprovalPolicyDto.serializer(),
    )

    suspend fun updateApprovalPolicy(
        sessionId: String,
        policy: String,
    ): ApprovalPolicyDto = decode(
        execute(
            request(
                "PUT",
                "/sessions/$sessionId/approval-policy",
                body = encodeBody(ApprovalPolicyUpdateRequest(policy)),
            ),
        ),
        ApprovalPolicyDto.serializer(),
    )

    // endregion

    // region paired devices

    suspend fun devices(): DevicesResponse = decode(
        execute(request("GET", "/devices")),
        DevicesResponse.serializer(),
    )

    suspend fun revokeDevice(deviceId: String): RevokeDeviceResponse = decode(
        execute(request("DELETE", "/devices/$deviceId")),
        RevokeDeviceResponse.serializer(),
    )

    // endregion

    // region recovery access

    suspend fun recoveryStatus(): RecoveryStatusDto = decode(
        execute(request("GET", "/recovery")),
        RecoveryStatusDto.serializer(),
    )

    suspend fun generateRecoveryCode(): RecoveryCodeDto = decode(
        execute(request("POST", "/recovery")),
        RecoveryCodeDto.serializer(),
    )

    /** Unauthenticated restore: no device token exists yet after clearing app data. */
    suspend fun pairWithRecovery(request: RecoveryPairRequest): RecoveryPairResponse = decode(
        execute(
            request(
                "POST",
                "/pair/recovery",
                body = encodeBody(request),
                authenticated = false,
            ),
        ),
        RecoveryPairResponse.serializer(),
    )

    // endregion

    // region tailscale one-tap pairing

    /** Which pairing methods the host offers (public; no device token needed). */
    suspend fun pairMethods(): PairMethodsDto = decode(
        execute(request("GET", "/pair/methods", authenticated = false)),
        PairMethodsDto.serializer(),
    )

    /** Unauthenticated Tailscale Serve auto-pair: the tailnet identity replaces the code. */
    suspend fun pairWithTailscale(request: TailscalePairRequest): TailscalePairResponse = decode(
        execute(
            request(
                "POST",
                "/pair/tailscale",
                body = encodeBody(request),
                authenticated = false,
            ),
        ),
        TailscalePairResponse.serializer(),
    )

    // endregion

    // region model + thinking picker

    suspend fun models(): ModelsResponse = decode(
        execute(request("GET", "/models")),
        ModelsResponse.serializer(),
    )

    suspend fun sessionModel(sessionId: String): SessionModelResponse = decode(
        execute(request("GET", "/sessions/$sessionId/model")),
        SessionModelResponse.serializer(),
    )

    suspend fun updateSessionModel(
        sessionId: String,
        request: ModelSelectionRequest,
    ): SessionModelResponse = decode(
        execute(
            request("PUT", "/sessions/$sessionId/model", body = encodeBody(request)),
        ),
        SessionModelResponse.serializer(),
    )

    // endregion

    // region host queue

    suspend fun hostQueue(sessionId: String): HostQueueResponse = decode(
        execute(request("GET", "/sessions/$sessionId/queue")),
        HostQueueResponse.serializer(),
    )

    /** GET /sessions/{id}/skills — per-session skill registry (unsupported hosts return false). */
    suspend fun sessionSkills(sessionId: String): SkillsResponse = decode(
        execute(request("GET", "/sessions/$sessionId/skills")),
        SkillsResponse.serializer(),
    )

    /** GET /sessions/{id}/todos — the agent's plan (unsupported/old hosts return 404/false). */
    suspend fun sessionTodos(sessionId: String): TodosResponse = decode(
        execute(request("GET", "/sessions/$sessionId/todos")),
        TodosResponse.serializer(),
    )

    /** GET /sessions/{id}/team — agent-team board (unsupported/old hosts return 404/false). */
    suspend fun sessionTeam(sessionId: String): TeamResponse = decode(
        execute(request("GET", "/sessions/$sessionId/team")),
        TeamResponse.serializer(),
    )

    /** GET /sessions/{id}/trajectory — newest-first journal page (unsupported hosts 404/false). */
    suspend fun sessionTrajectory(
        sessionId: String,
        limit: Int = 100,
        beforeSeq: Int? = null,
    ): TrajectoryResponse = decode(
        execute(
            request(
                "GET",
                "/sessions/$sessionId/trajectory",
                query = trajectoryQueryParams(limit = limit, beforeSeq = beforeSeq),
            ),
        ),
        TrajectoryResponse.serializer(),
    )

    /** GET /sessions/{id}/files — read-only directory listing (unsupported hosts 404/false). */
    suspend fun sessionFiles(sessionId: String, path: String = ""): FilesResponse = decode(
        execute(
            request(
                "GET",
                "/sessions/$sessionId/files",
                query = filesQueryParams(path),
            ),
        ),
        FilesResponse.serializer(),
    )

    /** GET /sessions/{id}/files/content — bounded text preview; binary/oversize is a 415 error. */
    suspend fun sessionFileContent(sessionId: String, path: String): FileContentDto = decode(
        execute(
            request(
                "GET",
                "/sessions/$sessionId/files/content",
                query = filesQueryParams(path),
            ),
        ),
        FileContentDto.serializer(),
    )

    /** GET /sessions/{id}/files/raw — raw bytes for external open/download (host cap 25 MB). */
    suspend fun sessionFileRaw(
        sessionId: String,
        path: String,
        maxBytes: Long,
    ): ByteArray = executeBytes(
        request(
            "GET",
            "/sessions/$sessionId/files/raw",
            query = filesQueryParams(path),
            accept = "*/*",
        ),
        maxBytes,
    )

    /** GET /sessions/{id}/jobs — read-only background jobs (unsupported/old hosts 404/false). */
    suspend fun sessionJobs(sessionId: String): JobsResponse = decode(
        execute(request("GET", "/sessions/$sessionId/jobs")),
        JobsResponse.serializer(),
    )

    /** GET /sessions/{id}/context — context-window usage (unsupported/old hosts 404/false). */
    suspend fun sessionContext(sessionId: String): ContextResponse = decode(
        execute(request("GET", "/sessions/$sessionId/context")),
        ContextResponse.serializer(),
    )

    suspend fun hostQueueAction(
        sessionId: String,
        itemId: String,
        request: HostQueueActionRequest,
    ): HostQueueActionResponse = decode(
        execute(
            request(
                "PATCH",
                "/sessions/$sessionId/queue/$itemId",
                body = encodeBody(request),
            ),
        ),
        HostQueueActionResponse.serializer(),
    )

    // endregion

    // region fork conversation

    suspend fun forkSession(sessionId: String, atSeq: Int?): ForkResponse = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/fork",
                body = encodeBody(ForkRequest(atSeq)),
            ),
        ),
        ForkResponse.serializer(),
    )

    // endregion

    suspend fun prompt(
        sessionId: String,
        text: String,
        attachmentIds: List<String> = emptyList(),
    ): PromptAcceptedDto = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/prompt",
                body = encodeBody(
                    PromptRequest(
                        text = text,
                        attachmentIds = attachmentIds.ifEmpty { null },
                    ),
                ),
            ),
        ),
        PromptAcceptedDto.serializer(),
    )

    /**
     * POST /sessions/{id}/attachments: one attachment per request, bytes base64-encoded in a
     * standard (unwrapped) alphabet. Host returns the id for the following prompt.
     */
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit = {},
    ): AttachmentDto {
        // Streamed: encoding into a single String needs ~2.7x the file size in
        // the heap and OOM-crashed on a 43 MB recording (see Base64JsonBody).
        val body = execute(
            request(
                "POST",
                "/sessions/$sessionId/attachments",
                bodyStream = Base64JsonBody(name, mimeType, bytes, onProgress),
            ),
        )
        val attachment = runCatching {
            DshJson.decodeFromString(AttachmentUploadResponse.serializer(), body)
        }.getOrNull()?.attachment
        if (attachment == null || attachment.attachmentId.isBlank()) {
            throw DshApiException.Protocol("Host did not return the uploaded attachment")
        }
        return attachment
    }

    suspend fun interrupt(sessionId: String): OkResponse = decode(
        execute(request("POST", "/sessions/$sessionId/interrupt")),
        OkResponse.serializer(),
    )

    /**
     * GET /sessions/{id}/attachments/{attachmentId} — raw attachment bytes. The body is read in
     * chunks and aborted once [maxBytes] is exceeded so a huge file can never exhaust the heap.
     */
    suspend fun sessionAttachment(
        sessionId: String,
        attachmentId: String,
        maxBytes: Long,
    ): ByteArray = executeBytes(
        request(
            "GET",
            "/sessions/$sessionId/attachments/$attachmentId",
            accept = "*/*",
        ),
        maxBytes,
    )

    suspend fun sessionGoal(sessionId: String): SessionGoalResponse = decode(
        execute(request("GET", "/sessions/$sessionId/goal")),
        SessionGoalResponse.serializer(),
    )

    suspend fun resumeSessionGoal(sessionId: String): SessionGoalResponse = decode(
        execute(request("POST", "/sessions/$sessionId/goal/resume")),
        SessionGoalResponse.serializer(),
    )

    suspend fun pauseSessionGoal(sessionId: String): SessionGoalResponse = decode(
        execute(request("POST", "/sessions/$sessionId/goal/pause")),
        SessionGoalResponse.serializer(),
    )

    /**
     * Marks the current goal complete. The optional ref (`id`, `revision`) lets the host reject
     * the action when the phone saw a stale goal; both fields may be omitted.
     */
    suspend fun completeSessionGoal(
        sessionId: String,
        goalId: String? = null,
        revision: Int? = null,
    ): SessionGoalResponse = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/goal/complete",
                body = encodeBody(GoalRefRequest(id = goalId, revision = revision)),
            ),
        ),
        SessionGoalResponse.serializer(),
    )

    suspend fun createSessionGoal(
        sessionId: String,
        objective: String,
        maxGoalRounds: Int? = null,
    ): SessionGoalResponse = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/goal",
                body = encodeBody(GoalCreateRequest(objective, maxGoalRounds)),
            ),
        ),
        SessionGoalResponse.serializer(),
    )

    /** Edits the current goal; null fields keep their current value. */
    suspend fun editSessionGoal(
        sessionId: String,
        objective: String? = null,
        maxGoalRounds: Int? = null,
    ): SessionGoalResponse = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/goal/edit",
                body = encodeBody(
                    GoalEditRequest(objective = objective, maxGoalRounds = maxGoalRounds),
                ),
            ),
        ),
        SessionGoalResponse.serializer(),
    )

    /** Clears the current goal; the response carries `goal: null`. */
    suspend fun clearSessionGoal(sessionId: String): SessionGoalResponse = decode(
        execute(request("POST", "/sessions/$sessionId/goal/clear")),
        SessionGoalResponse.serializer(),
    )

    // region schedules

    /** GET /schedules — every session schedule on the host (may be unsupported). */
    suspend fun schedules(): SchedulesResponse = decode(
        execute(request("GET", "/schedules")),
        SchedulesResponse.serializer(),
    )

    /** POST /sessions/{id}/schedules — creates one schedule and returns the created row. */
    suspend fun createSessionSchedule(
        sessionId: String,
        request: ScheduleCreateRequest,
    ): ScheduleDto = decode(
        execute(
            request(
                "POST",
                "/sessions/$sessionId/schedules",
                body = encodeBody(request),
            ),
        ),
        ScheduleDto.serializer(),
    )

    /** DELETE /schedules/{id} — removes one schedule. */
    suspend fun deleteSchedule(scheduleId: String): ScheduleDeletedResponse = decode(
        execute(request("DELETE", "/schedules/$scheduleId")),
        ScheduleDeletedResponse.serializer(),
    )

    /** PATCH /schedules/{id} — updates the provided fields and returns the updated row. */
    suspend fun updateSchedule(
        scheduleId: String,
        request: ScheduleUpdateRequest,
    ): ScheduleDto = decode(
        execute(
            request(
                "PATCH",
                "/schedules/$scheduleId",
                body = encodeBody(request),
            ),
        ),
        ScheduleDto.serializer(),
    )

    // endregion

    suspend fun approvals(): List<ApprovalDto> = approvalsResponse().approvals

    /** Full /approvals envelope, including the `supported` / `readOnly` capability flags. */
    suspend fun approvalsResponse(): ApprovalsResponse =
        decode(execute(request("GET", "/approvals")), ApprovalsResponse.serializer())

    suspend fun decideApproval(approvalId: String, decision: String, note: String? = null): OkResponse =
        decode(
            execute(
                request(
                    "POST",
                    "/approvals/$approvalId",
                    body = encodeBody(DecisionRequest(decision = decision, note = note)),
                ),
            ),
            OkResponse.serializer(),
        )

    suspend fun questions(): List<QuestionDto> = questionsResponse().questions

    /** Full /questions envelope, including the `supported` / `readOnly` capability flags. */
    suspend fun questionsResponse(): QuestionsResponse =
        decode(execute(request("GET", "/questions")), QuestionsResponse.serializer())

    suspend fun answerQuestion(questionId: String, answer: String, optionIndex: Int? = null): OkResponse =
        decode(
            execute(
                request(
                    "POST",
                    "/questions/$questionId",
                    body = encodeBody(AnswerRequest(answer = answer, optionIndex = optionIndex)),
                ),
            ),
            OkResponse.serializer(),
        )

    /** Opens the SSE stream; the flow ends when the host closes it or the collector is cancelled. */
    fun events(since: String? = null): Flow<SseEvent> = channelFlow {
        val call = streamHttp.newCall(
            request(
                "GET",
                "/events",
                query = mapOf("since" to since),
                accept = "text/event-stream",
            ),
        )
        val worker = launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                val response = call.execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw mapError(resp.code, resp.header("Retry-After"), resp.body?.string().orEmpty())
                    }
                    val body = resp.body ?: throw DshApiException.Protocol("Event stream had no body")
                    val parser = SseParser()
                    val source = body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        parser.feed(line)?.let { send(it) }
                    }
                    parser.finish()?.let { send(it) }
                }
            } catch (t: Throwable) {
                failure = if (t is CancellationException) t else t
            } finally {
                close(failure)
            }
        }
        awaitClose {
            call.cancel()
            worker.cancel()
        }
    }

    // endregion

    // region plumbing

    private fun encodeBody(value: Any): String = when (value) {
        is PairCompleteRequest -> DshJson.encodeToString(PairCompleteRequest.serializer(), value)
        is CreateSessionRequest -> DshJson.encodeToString(CreateSessionRequest.serializer(), value)
        is PromptRequest -> DshJson.encodeToString(PromptRequest.serializer(), value)
        is AttachmentUploadRequest ->
            DshJson.encodeToString(AttachmentUploadRequest.serializer(), value)
        is DecisionRequest -> DshJson.encodeToString(DecisionRequest.serializer(), value)
        is AnswerRequest -> DshJson.encodeToString(AnswerRequest.serializer(), value)
        is GoalCreateRequest -> DshJson.encodeToString(GoalCreateRequest.serializer(), value)
        is GoalEditRequest -> DshJson.encodeToString(GoalEditRequest.serializer(), value)
        is GoalRefRequest -> DshJson.encodeToString(GoalRefRequest.serializer(), value)
        is ScheduleCreateRequest -> DshJson.encodeToString(ScheduleCreateRequest.serializer(), value)
        is ScheduleUpdateRequest -> DshJson.encodeToString(ScheduleUpdateRequest.serializer(), value)
        is RenameSessionRequest -> DshJson.encodeToString(RenameSessionRequest.serializer(), value)
        is PushUpdateRequest -> DshJson.encodeToString(PushUpdateRequest.serializer(), value)
        is PushSuppressionDto -> DshJson.encodeToString(PushSuppressionDto.serializer(), value)
        is ApprovalPolicyUpdateRequest ->
            DshJson.encodeToString(ApprovalPolicyUpdateRequest.serializer(), value)
        is ModelSelectionRequest -> DshJson.encodeToString(ModelSelectionRequest.serializer(), value)
        is HostQueueActionRequest -> DshJson.encodeToString(HostQueueActionRequest.serializer(), value)
        is ForkRequest -> DshJson.encodeToString(ForkRequest.serializer(), value)
        is RecoveryPairRequest -> DshJson.encodeToString(RecoveryPairRequest.serializer(), value)
        is TailscalePairRequest -> DshJson.encodeToString(TailscalePairRequest.serializer(), value)
        else -> "{}"
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        DshApiException.Network("Could not reach the host", error),
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            throw mapError(resp.code, resp.header("Retry-After"), body)
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun <T> decode(body: String, deserializer: KSerializer<T>): T = try {
        DshJson.decodeFromString(deserializer, body)
    } catch (e: Exception) {
        throw DshApiException.Protocol("Unexpected response from the host", e)
    }

    /** Like [execute] but for binary bodies: streams the response and enforces [maxBytes]. */
    private suspend fun executeBytes(request: Request, maxBytes: Long): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            DshApiException.Network("Could not reach the host", error),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        try {
                            if (!resp.isSuccessful) {
                                throw mapError(
                                    resp.code,
                                    resp.header("Retry-After"),
                                    resp.body?.string().orEmpty(),
                                )
                            }
                            val declaredLength = resp.body?.contentLength() ?: -1L
                            if (declaredLength > maxBytes) {
                                throw tooLargeAttachment(maxBytes)
                            }
                            val stream = resp.body?.byteStream()
                                ?: throw DshApiException.Protocol("Host returned an empty attachment")
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
                            var total = 0L
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) throw tooLargeAttachment(maxBytes)
                                output.write(buffer, 0, read)
                            }
                            if (continuation.isActive) continuation.resume(output.toByteArray())
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun tooLargeAttachment(maxBytes: Long): DshApiException = DshApiException.InvalidRequest(
        "Attachment is larger than the ${maxBytes / (1024L * 1024L)} MB download limit",
        "attachment_too_large",
    )

    private fun request(
        method: String,
        path: String,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        bodyStream: RequestBody? = null,
        authenticated: Boolean = true,
        accept: String = "application/json",
    ): Request {
        val builder = Request.Builder()
            .url(url(path, query))
            .header("Accept", accept)
        if (authenticated && token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        if (method == "GET") {
            builder.get()
        } else if (method == "DELETE") {
            builder.delete()
        } else {
            builder.method(method, bodyStream ?: (body ?: "{}").toRequestBody(JSON_MEDIA))
        }
        return builder.build()
    }

    private fun url(path: String, query: Map<String, String?>): HttpUrl {
        val builder = (apiRoot + path).toHttpUrl().newBuilder()
        query.forEach { (key, value) ->
            if (value != null) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): DshApiException {
        val envelope = runCatching {
            DshJson.decodeFromString(ErrorEnvelopeDto.serializer(), body)
        }.getOrNull()
        val code = envelope?.error?.code?.takeIf { it.isNotBlank() }
        val message = envelope?.error?.message?.takeIf { it.isNotBlank() }
            ?: "Host returned HTTP $status"
        return when {
            status == 401 || code == "unauthorized" -> DshApiException.Unauthorized(message)
            status == 403 || code == "forbidden" -> DshApiException.Forbidden(message)
            status == 404 || code == "not_found" -> DshApiException.NotFound(message)
            status == 429 || code == "rate_limited" ->
                DshApiException.RateLimited(message, retryAfter?.trim()?.toLongOrNull())
            code == "invalid_code" -> DshApiException.InvalidCode(message)
            code == "unsupported" || status == 501 -> DshApiException.Unsupported(message)
            code == "invalid_request" || status == 400 ->
                DshApiException.InvalidRequest(message, code)
            else -> DshApiException.Server(status, code, message)
        }
    }

    // endregion

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .eventListenerFactory { PerfEventListener() }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun defaultStreamHttp(): OkHttpClient = OkHttpClient.Builder()
            .eventListenerFactory { PerfEventListener() }
            .connectTimeout(10, TimeUnit.SECONDS)
            // The host sends an SSE `ping` heartbeat every ~20 s. A 90 s read timeout is a bounded
            // liveness guarantee: a silently dead route (Tailscale hand-off, network switch) raises
            // a normal IOException instead of blocking forever, so the reconnect loop can recover.
            .readTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Accepts `https://host`, `https://host/` or a full `.../mobile/v1` root. */
        fun normalizeApiRoot(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "Base URL must start with http:// or https://"
            }
            return if (trimmed.endsWith("/mobile/v1")) trimmed else "$trimmed/mobile/v1"
        }
    }
}

/**
 * A JSON request body that base64-encodes [bytes] while writing, never
 * materialising the encoded text as a String. Peak extra memory is one chunk.
 */
private val JSON_BODY_MEDIA = "application/json".toMediaType()

internal class Base64JsonBody(
    name: String,
    mimeType: String,
    private val bytes: ByteArray,
    private val onProgress: ((Float) -> Unit)? = null,
) : RequestBody() {
    private val prefix: String =
        "{\"name\":" + jsonQuote(name) + ",\"mimeType\":" + jsonQuote(mimeType) + ",\"dataBase64\":\""
    private val suffix: String = "\"}"
    private val prefixBytes: Int = prefix.toByteArray(Charsets.UTF_8).size
    private val suffixBytes: Int = suffix.toByteArray(Charsets.UTF_8).size

    override fun contentType(): okhttp3.MediaType? = JSON_BODY_MEDIA

    override fun contentLength(): Long =
        prefixBytes.toLong() + 4L * ((bytes.size + 2L) / 3L) + suffixBytes.toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(prefix)
        val encoder = Base64.getEncoder()
        var offset = 0
        var reportedPercent = -1
        while (offset < bytes.size) {
            val length = minOf(CHUNK_BYTES, bytes.size - offset)
            // Copy each window out of the payload: reusing one shared buffer made every full
            // chunk encode zeros instead of the file bytes (caught by Base64JsonBodyTest).
            sink.write(encoder.encode(bytes.copyOfRange(offset, offset + length)))
            offset += length
            val percent = (offset * 100L / bytes.size).toInt()
            if (percent != reportedPercent) {
                reportedPercent = percent
                onProgress?.invoke(offset.toFloat() / bytes.size)
            }
        }
        sink.writeUtf8(suffix)
        if (bytes.isEmpty()) onProgress?.invoke(1f)
    }

    private companion object {
        /** Multiple of 3 so only the final chunk can carry base64 padding. */
        const val CHUNK_BYTES = 3 * 4096
    }
}

/** Minimal JSON string escaping for the attachment name and MIME type. */
private fun jsonQuote(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch < ' ' -> out.append(String.format("\\u%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
