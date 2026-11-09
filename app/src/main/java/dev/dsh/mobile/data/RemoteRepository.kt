package dev.dsh.mobile.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.mobile.net.ApprovalDto
import dev.dsh.mobile.net.ApprovalResolvedEvent
import dev.dsh.mobile.net.DshApiClient
import dev.dsh.mobile.net.DshApiException
import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.HostStatusEvent
import dev.dsh.mobile.net.MessageAddedEvent
import dev.dsh.mobile.net.MessageCompletedEvent
import dev.dsh.mobile.net.HostQueueActionRequest
import dev.dsh.mobile.net.ForkResponse
import dev.dsh.mobile.net.MessageDeltaEvent
import dev.dsh.mobile.net.MessageDto
import dev.dsh.mobile.net.ModelSelectionDto
import dev.dsh.mobile.net.ModelSelectionRequest
import dev.dsh.mobile.net.ModelsResponse
import dev.dsh.mobile.net.QuestionDto
import dev.dsh.mobile.net.SessionModelResponse
import dev.dsh.mobile.net.QuestionResolvedEvent
import dev.dsh.mobile.net.ApprovalPolicyDto
import dev.dsh.mobile.net.PushConfigDto
import dev.dsh.mobile.net.PushSuppressionDto
import dev.dsh.mobile.net.PushSuppressionSummaryDto
import dev.dsh.mobile.net.PushUpdateRequest
import dev.dsh.mobile.net.ScheduleCreateRequest
import dev.dsh.mobile.net.ScheduleUpdateRequest
import dev.dsh.mobile.net.GoalChangedEventDto
import dev.dsh.mobile.net.GoalDto
import dev.dsh.mobile.net.SessionGoalResponse
import dev.dsh.mobile.net.SessionRemovedEvent
import dev.dsh.mobile.net.SessionSummaryDto
import dev.dsh.mobile.net.SessionDetailResponse
import dev.dsh.mobile.net.SseEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Live backend: HTTP for snapshots/actions, SSE for pushes, with automatic reconnect that replays
 * from the last event id seen (contract: `GET /mobile/v1/events?since=<eventId>`).
 *
 * All mutable state is Compose snapshot state so the existing screens observe it without changes.
 */
class RemoteRepository(
    private val api: DshApiClient,
    private val scope: CoroutineScope,
    initialWorkspaces: List<Workspace> = emptyList(),
    private val cache: HostSnapshotCache? = null,
    private val onConnectionEvent: (String) -> Unit = {},
    /** Offline transcript cache; null in tests or when the cache dir is unavailable. */
    private val transcriptCache: HostTranscriptCache? = null,
) : DshRepository {

    private val workspaceState = mutableStateListOf<Workspace>().apply { addAll(initialWorkspaces) }
    private val sessionState = mutableStateListOf<Session>()
    private val approvalState = mutableStateListOf<Approval>()
    private val questionState = mutableStateListOf<Question>()
    private var connectionState by mutableStateOf(ConnectionStatus.Connecting)
    private val goalEventChannel = MutableSharedFlow<GoalChanged>(extraBufferCapacity = 16)

    /** Live goal mutations pushed by the host's `goal.changed` SSE event. */
    override val goalChanges: SharedFlow<GoalChanged> = goalEventChannel.asSharedFlow()

    /**
     * The v0.2 host plugin may not implement approvals/questions yet (contract: 501 `unsupported`).
     * That is a normal host capability gap, never a crash.
     */
    override var approvalsSupported: Boolean by mutableStateOf(true)
        private set

    /** True when the last /approvals fetch reported a read-only (desktop-answered) bridge. */
    override var approvalsReadOnly: Boolean by mutableStateOf(false)
        private set

    override var questionsSupported: Boolean by mutableStateOf(true)
        private set

    /** True when the last /questions fetch reported a read-only (desktop-answered) bridge. */
    override var questionsReadOnly: Boolean by mutableStateOf(false)
        private set

    /** Wall-clock time of the last successful HTTP snapshot refresh (for the "updated" hint). */
    var lastSyncedAt: Long? by mutableStateOf(null)
        private set

    /** Diagnostics: SSE reconnect attempts (failure retries, resume/network heals, watchdog). */
    var reconnectAttempts: Int by mutableStateOf(0)
        private set

    /** Diagnostics: wall-clock time of the most recent SSE reconnect attempt. */
    var lastReconnectAt: Long? by mutableStateOf(null)
        private set

    /** Diagnostics: wall-clock time of the last SSE frame (the host pings every 20 s). */
    var lastEventAt: Long? by mutableStateOf(null)
        private set

    override val mode: RepositoryMode = RepositoryMode.Remote
    override val workspaces: List<Workspace> get() = workspaceState
    override val sessions: List<Session> get() = sessionState
    override val approvals: List<Approval> get() = approvalState
    override val questions: List<Question> get() = questionState
    override val connection: ConnectionStatus get() = connectionState

    private var eventsJob: Job? = null
    private var watchdogJob: Job? = null
    private var catchUpJob: Job? = null
    private var isStarted = false
    private var lastEventId: String? = null

    /**
     * Restarts bump this generation; work belonging to an older loop ignores events and state
     * writes, so a cancelled stream can never race a freshly started one.
     */
    private var loopGeneration = 0
    private var backoffMillis = INITIAL_BACKOFF_MILLIS

    /**
     * Latest default-network state supplied by [AppState]. A Tailscale hostname cannot resolve
     * while the VPN is down, so a DNS outage with no VPN should back off instead of burning a
     * reconnect every few seconds.
     */
    @Volatile private var networkAvailable = true
    @Volatile private var networkTransport = "None"
    private var networkWaitLogged = false

    /** Set from the activity lifecycle; the stale-socket watchdog only runs in the foreground. */
    private var foreground = true

    /** Generation of the loop that is currently blocked reading the SSE socket, or -1. */
    private var streamingGeneration = -1
    private var streamStartedAt = 0L
    private val loadedSessions = mutableSetOf<String>()
    private val transcriptLoadedAt = mutableMapOf<String, Long>()
    /** Message id before which the next older page starts, per session with more history. */
    private val nextCursors = mutableMapOf<String, String>()
    private val olderLoading = mutableSetOf<String>()

    /** Attachments uploaded for the next prompt, keyed by host attachment id (v0.2.2). */
    private val uploadedAttachments = mutableMapOf<String, UploadedAttachment>()

    // region lifecycle

    override fun start() {
        if (isStarted) return
        isStarted = true
        connectionState = ConnectionStatus.Connecting
        seedFromCache()
        scope.launch { runCatching { refresh() } }
        restartEventLoop()
        startWatchdog()
    }

    /** Keeps the stream loop in sync with the default network without restarting it. */
    fun setNetworkState(available: Boolean, transport: String) {
        if (networkAvailable != available) networkWaitLogged = false
        networkAvailable = available
        networkTransport = transport
    }

    /** Paint the last known list immediately; the network refresh replaces it moments later. */
    private fun seedFromCache() {
        val snapshot = cache?.load() ?: return
        if (workspaceState.isEmpty() && sessionState.isEmpty()) {
            workspaceState.addAll(snapshot.workspaces.map { it.toUi() })
            sessionState.addAll(snapshot.sessions.map { it.toUi() })
            lastSyncedAt = snapshot.savedAt.takeIf { it > 0 }
        }
    }

    override fun stop() {
        isStarted = false
        loopGeneration += 1
        eventsJob?.cancel()
        eventsJob = null
        catchUpJob?.cancel()
        catchUpJob = null
        stopWatchdog()
        connectionState = ConnectionStatus.Offline
    }

    /**
     * Restarts the SSE loop immediately, resetting the reconnect backoff, without clearing
     * [lastEventId]: the host replays from the same cursor, so nothing is lost. Used by the
     * resume/network auto-heal, the stale-socket watchdog and the manual reconnect banner.
     */
    fun forceReconnect(reason: String = "manual") {
        if (!isStarted) {
            start()
            return
        }
        backoffMillis = INITIAL_BACKOFF_MILLIS
        connectionState = ConnectionStatus.Connecting
        noteReconnectAttempt()
        noteEvent("Reconnect: $reason")
        restartEventLoop()
        scheduleCatchUp()
    }

    /**
     * Toggles the stale-socket watchdog. While backgrounded the app stays quiet (no periodic
     * reconnects); returning to the foreground re-arms it and the resume heal runs separately.
     */
    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        if (foreground) {
            if (isStarted) startWatchdog()
        } else {
            stopWatchdog()
        }
    }

    private fun restartEventLoop() {
        val generation = ++loopGeneration
        eventsJob?.cancel()
        eventsJob = scope.launch { eventLoop(generation) }
    }

    /** Refetches snapshots and loaded transcripts once a reconnect is under way. */
    private fun scheduleCatchUp() {
        catchUpJob?.cancel()
        catchUpJob = scope.launch {
            if (!isStarted) return@launch
            catchUpAfterReconnect()
        }
    }

    private suspend fun catchUpAfterReconnect() {
        // The host replays missed events when it can; refetch snapshots as a safety net.
        runCatching { refresh() }
        // A cursor gap makes the host resnapshot summaries; transcripts need their own refetch.
        loadedSessions.toList().forEach { sessionId ->
            if (!isStarted) return@forEach
            runCatching { loadMessages(sessionId, force = true) }
        }
    }

    /**
     * A silently dead route (Tailscale hand-off, Wi-Fi roaming) leaves the SSE socket blocked
     * forever because the stream client has no read timeout of its own contract. The host pings
     * every 20 s, so while foreground and actively streaming, no frame for [STALE_STREAM_MILLIS]
     * means the socket is dead: force a cursor-preserving reconnect.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(WATCHDOG_TICK_MILLIS)
                if (!foreground || !isStarted) continue
                if (streamingGeneration != loopGeneration) continue
                val idleMillis = System.currentTimeMillis() - maxOf(streamStartedAt, lastEventAt ?: 0L)
                if (idleMillis > STALE_STREAM_MILLIS) forceReconnect()
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun noteReconnectAttempt() {
        reconnectAttempts += 1
        lastReconnectAt = System.currentTimeMillis()
    }

    private fun noteEvent(message: String) {
        runCatching { onConnectionEvent(message) }
    }

    private fun errorLabel(error: Exception): String {
        val kind = error::class.simpleName ?: "error"
        val detail = error.message?.replace('\n', ' ')?.take(90)
        return if (detail.isNullOrBlank()) kind else "$kind: $detail"
    }

    override fun dispose() {
        stop()
        lastEventId = null
        lastSyncedAt = null
        lastEventAt = null
        reconnectAttempts = 0
        lastReconnectAt = null
        loadedSessions.clear()
        transcriptLoadedAt.clear()
        nextCursors.clear()
        olderLoading.clear()
        uploadedAttachments.clear()
        workspaceState.clear()
        sessionState.clear()
        approvalState.clear()
        questionState.clear()
    }

    override fun openSession(id: String) = Unit

    // endregion

    // region snapshots

    override suspend fun refresh() {
        try {
            // Host metadata and the session list are independent; fetch them together so a
            // cold start does not pay two full round trips on a slow tailnet.
            val (host, summaries) = coroutineScope {
                val hostRequest = async { api.host() }
                val sessionsRequest = async { api.sessions() }
                hostRequest.await() to sessionsRequest.await()
            }
            workspaceState.clear()
            workspaceState.addAll(host.workspaces.map { it.toUi() })
            upsertSummaries(summaries)
            cache?.save(summaries, host.workspaces)

            // Approvals and questions are independent browser feeds; fetch them concurrently.
            // Unsupported is a normal old-host answer, not a network failure.
            val (approvalsOutcome, questionsOutcome) = coroutineScope {
                val approvalsRequest = async {
                    try {
                        Result.success(api.approvalsResponse())
                    } catch (unsupported: DshApiException.Unsupported) {
                        Result.failure(unsupported)
                    }
                }
                val questionsRequest = async {
                    try {
                        Result.success(api.questionsResponse())
                    } catch (unsupported: DshApiException.Unsupported) {
                        Result.failure(unsupported)
                    }
                }
                approvalsRequest.await() to questionsRequest.await()
            }

            approvalsOutcome.fold(
                onSuccess = { approvalsResponse ->
                    approvalState.clear()
                    approvalState.addAll(approvalsResponse.approvals.map { it.toUi() })
                    approvalsSupported = approvalsResponse.supported
                    // SSE approval events keep this flag as-is until the next fetch.
                    approvalsReadOnly = approvalsResponse.readOnly
                },
                onFailure = { _ ->
                    approvalsSupported = false
                    approvalsReadOnly = false
                    approvalState.clear()
                },
            )
            questionsOutcome.fold(
                onSuccess = { questionsResponse ->
                    questionState.clear()
                    questionState.addAll(questionsResponse.questions.map { it.toUi() })
                    questionsSupported = questionsResponse.supported
                    // SSE question events keep this flag as-is until the next fetch.
                    questionsReadOnly = questionsResponse.readOnly
                },
                onFailure = { _ ->
                    questionsSupported = false
                    questionsReadOnly = false
                    questionState.clear()
                },
            )
            if (connectionState == ConnectionStatus.Connecting) {
                connectionState = ConnectionStatus.Live
            }
            lastSyncedAt = System.currentTimeMillis()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            connectionState = ConnectionStatus.Offline
            throw error
        }
    }

    override suspend fun loadMessages(sessionId: String): Boolean = loadMessages(sessionId, force = false)

    private suspend fun loadMessages(sessionId: String, force: Boolean): Boolean {
        // Switching back to a session that was just loaded should be instant. SSE keeps the
        // in-memory transcript current; a short TTL avoids a redundant full-detail round trip.
        val now = System.currentTimeMillis()
        val current = sessionState.firstOrNull { it.id == sessionId }
        val lastLoadedAt = transcriptLoadedAt[sessionId]
        if (!force && current?.messages?.isNotEmpty() == true && lastLoadedAt != null &&
            now - lastLoadedAt < TRANSCRIPT_FRESH_MILLIS
        ) {
            return true
        }
        // Cache-first: paint the last-known transcript before the network call so an offline
        // open still shows the conversation. An existing transcript is never replaced by older
        // cached data - the successful network result below always wins.
        if (current?.messages.isNullOrEmpty()) {
            seedTranscriptFromCache(sessionId)
        }
        val detail = api.session(sessionId, limit = TRANSCRIPT_PAGE_SIZE)
        detail.session?.let { upsertSummary(it, preserveGoalWhenMissing = true) }
        detail.messages.forEach { it.recordSeq() }
        val messages = detail.messages.map { it.toUi() }
        updateSession(sessionId) { it.copy(messages = messages) }
        loadedSessions += sessionId
        transcriptLoadedAt[sessionId] = System.currentTimeMillis()
        detail.nextCursor?.takeIf { it.isNotBlank() }?.let { nextCursors[sessionId] = it }
            ?: nextCursors.remove(sessionId)
        withContext(Dispatchers.IO) { transcriptCache?.save(sessionId, detail) }
        return true
    }

    override fun hasOlderMessages(sessionId: String): Boolean = nextCursors.containsKey(sessionId)

    override suspend fun loadOlderMessages(sessionId: String): Boolean {
        val cursor = nextCursors[sessionId] ?: return false
        if (!olderLoading.add(sessionId)) return false
        return try {
            val detail = api.session(sessionId, limit = TRANSCRIPT_PAGE_SIZE, before = cursor)
            detail.messages.forEach { it.recordSeq() }
            val older = detail.messages.map { it.toUi() }
            updateSession(sessionId) { session ->
                val existingIds = session.messages.mapTo(mutableSetOf()) { it.id }
                session.copy(messages = older.filterNot { it.id in existingIds } + session.messages)
            }
            detail.nextCursor?.takeIf { it.isNotBlank() }?.let { nextCursors[sessionId] = it }
                ?: nextCursors.remove(sessionId)
            true
        } finally {
            olderLoading -= sessionId
        }
    }

    /**
     * Seeds the session with the last cached transcript when it currently has no messages, so an
     * offline/failed open is still readable. A read failure is ignored and the network fetch
     * proceeds exactly as before; a transcript that already has messages is left untouched.
     */
    private suspend fun seedTranscriptFromCache(sessionId: String) {
        val cached: SessionDetailResponse = withContext(Dispatchers.IO) {
            transcriptCache?.load(sessionId)
        } ?: return
        if (cached.messages.isEmpty()) return
        cached.messages.forEach { it.recordSeq() }
        val existing = sessionState.firstOrNull { it.id == sessionId } ?: return
        if (existing.messages.isNotEmpty()) return
        updateSession(sessionId) { session ->
            session.copy(
                messages = cached.messages.map { message ->
                    val uiMessage = message.toUi()
                    // A transcript cached mid-run may claim to be streaming; offline there is no
                    // live stream, so show it as settled instead of a permanent "Thinking...".
                    if (uiMessage is AssistantMessage && uiMessage.streaming) {
                        uiMessage.copy(streaming = false)
                    } else {
                        uiMessage
                    }
                },
            )
        }
    }

    override suspend fun usage(): HostUsage = api.usage().toUi()

    /** Reuses this host's OkHttp pool for the diagnostics health probe. */
    suspend fun health(): dev.dsh.mobile.net.HealthDto = api.health()

    override suspend fun hiddenSessions(): Set<String> = api.hiddenSessions().ids.toSet()

    override suspend fun hideSession(sessionId: String) {
        val hidden = api.hiddenSessions().ids.toMutableSet()
        if (!hidden.add(sessionId)) return
        api.setHiddenSessions(hidden.toList())
        sessionState.removeAll { it.id == sessionId }
    }

    override suspend fun unhideSession(sessionId: String) {
        val hidden = api.hiddenSessions().ids.toMutableSet()
        if (!hidden.remove(sessionId)) return
        api.setHiddenSessions(hidden.toList())
    }

    override suspend fun renameSessionOnHost(sessionId: String, title: String) {
        val response = api.renameSession(sessionId, title)
        val resolved = response.title.ifBlank { title }
        updateSession(sessionId) { it.copy(title = resolved) }
    }

    override suspend fun archiveSessionOnHost(sessionId: String) {
        api.archiveSession(sessionId)
        sessionState.removeAll { it.id == sessionId }
        loadedSessions.remove(sessionId)
    }

    override suspend fun unarchiveSessionOnHost(sessionId: String) {
        api.unarchiveSession(sessionId)
    }

    override suspend fun archivedSessions(): ArchivedSessionsState = try {
        val response = api.archivedSessions()
        ArchivedSessionsState(
            supported = response.supported,
            sessions = response.sessions.map { it.toUi() },
        )
    } catch (unsupported: DshApiException.Unsupported) {
        ArchivedSessionsState.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose GET /sessions/archived yet.
        ArchivedSessionsState.Unsupported
    }

    // endregion

    // region actions

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        attachmentIds: List<String>,
    ) {
        val ids = AttachmentIds.normalize(attachmentIds)
        val attachmentNames = ids.mapNotNull { uploadedAttachments[it]?.name }
        val transcriptLoaded = sessionId in loadedSessions
        if (transcriptLoaded) {
            appendLocalUserMessage(sessionId, text, attachmentNames)
        }
        updateSession(sessionId) {
            it.copy(
                status = SessionStatus.Working,
                preview = text.ifBlank { attachmentNames.joinToString(", ").ifBlank { "Attachment" } },
                updated = "now",
                unread = false,
            )
        }
        try {
            api.prompt(sessionId, text, ids)
            // Attachment ids are consumed by a prompt (contract v0.2.2); drop local metadata.
            ids.forEach { uploadedAttachments.remove(it) }
        } catch (error: Exception) {
            if (transcriptLoaded) {
                removeLocalMessages(sessionId)
            }
            throw error
        }
    }

    override suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit,
    ): UploadedAttachment {
        val dto = api.uploadAttachment(sessionId, name, mimeType, bytes, onProgress)
        val uploaded = UploadedAttachment(
            id = dto.attachmentId,
            name = dto.name.ifBlank { name },
            mimeType = dto.mimeType.ifBlank { mimeType },
            bytes = if (dto.bytes > 0L) dto.bytes else bytes.size.toLong(),
            kind = dto.kind,
        )
        uploadedAttachments[uploaded.id] = uploaded
        return uploaded
    }

    override suspend fun downloadAttachment(
        sessionId: String,
        attachmentId: String,
        fileName: String,
        cacheDir: File,
    ): File {
        val cacheRoot = File(cacheDir, "attachments")
        val file = File(cacheRoot, attachmentCacheFileName(attachmentId, fileName))
        if (file.isFile && file.length() > 0L) return file
        val bytes = api.sessionAttachment(
            sessionId = sessionId,
            attachmentId = attachmentId,
            maxBytes = AttachmentLimits.MAX_READ_BACK_BYTES,
        )
        withContext(Dispatchers.IO) {
            cacheRoot.mkdirs()
            val partial = File(cacheRoot, "${file.name}.part")
            partial.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) {
                // Rename can fail on some file systems; fall back to a direct write.
                file.writeBytes(bytes)
                partial.delete()
            }
        }
        return file
    }

    override suspend fun interrupt(sessionId: String) {
        api.interrupt(sessionId)
        updateSession(sessionId) { it.copy(status = SessionStatus.Idle) }
    }

    override suspend fun goal(sessionId: String): SessionGoalState =
        api.sessionGoal(sessionId).toUi()

    override suspend fun resumeGoal(sessionId: String): SessionGoalState =
        api.resumeSessionGoal(sessionId).toUi()

    override suspend fun pauseGoal(sessionId: String): SessionGoalState =
        api.pauseSessionGoal(sessionId).toUi()

    override suspend fun completeGoal(
        sessionId: String,
        goalId: String?,
        revision: Int?,
    ): SessionGoalState =
        api.completeSessionGoal(sessionId, goalId, revision).toUi()

    override suspend fun createGoal(
        sessionId: String,
        objective: String,
        maxGoalRounds: Int?,
    ): SessionGoalState =
        api.createSessionGoal(sessionId, objective, maxGoalRounds).toUi()

    override suspend fun editGoal(
        sessionId: String,
        objective: String?,
        maxGoalRounds: Int?,
    ): SessionGoalState =
        api.editSessionGoal(sessionId, objective, maxGoalRounds).toUi()

    override suspend fun clearGoal(sessionId: String): SessionGoalState =
        api.clearSessionGoal(sessionId).toUi()

    override suspend fun schedules(): ScheduleListState = try {
        val response = api.schedules()
        ScheduleListState(
            supported = response.supported,
            schedules = response.schedules.map { it.toUi() },
        )
    } catch (unsupported: DshApiException.Unsupported) {
        ScheduleListState.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose GET /schedules yet; show the unsupported copy.
        ScheduleListState.Unsupported
    }

    override suspend fun createSchedule(
        sessionId: String,
        title: String,
        instruction: String,
        timing: ScheduleTimingInput,
    ): SessionSchedule {
        val request = when (timing) {
            is ScheduleTimingInput.AfterMinutes -> {
                val seconds = ScheduleTiming.afterSeconds(timing.minutes)
                    ?: throw DshApiException.InvalidRequest(
                        "One-off schedules must run within a year",
                        "invalid_request",
                    )
                ScheduleCreateRequest(
                    title = title,
                    instruction = instruction,
                    afterSeconds = seconds,
                )
            }

            is ScheduleTimingInput.Daily -> {
                val dailyTime = ScheduleTiming.parseDailyTime(timing.time)
                    ?: throw DshApiException.InvalidRequest(
                        "Daily time must be HH:MM (24h)",
                        "invalid_request",
                    )
                ScheduleCreateRequest(
                    title = title,
                    instruction = instruction,
                    dailyTime = dailyTime,
                    // The host defaults dailyTime to UTC; send the phone's zone explicitly.
                    timeZone = ScheduleTiming.dailyTimeZoneId(),
                )
            }
        }
        return api.createSessionSchedule(sessionId, request).toUi()
    }

    override suspend fun deleteSchedule(scheduleId: String) {
        api.deleteSchedule(scheduleId)
    }

    override suspend fun updateSchedule(
        scheduleId: String,
        title: String?,
        instruction: String?,
        timing: ScheduleTimingInput?,
    ): SessionSchedule {
        val request = ScheduleUpdateRequest(
            title = title,
            instruction = instruction,
            afterSeconds = (timing as? ScheduleTimingInput.AfterMinutes)?.let { after ->
                ScheduleTiming.afterSeconds(after.minutes)
                    ?: throw DshApiException.InvalidRequest(
                        "One-off schedules must run within a year",
                        "invalid_request",
                    )
            },
            dailyTime = (timing as? ScheduleTimingInput.Daily)?.let { daily ->
                ScheduleTiming.parseDailyTime(daily.time)
                    ?: throw DshApiException.InvalidRequest(
                        "Daily time must be HH:MM (24h)",
                        "invalid_request",
                    )
            },
            timeZone = (timing as? ScheduleTimingInput.Daily)?.let {
                ScheduleTiming.dailyTimeZoneId()
            },
        )
        return api.updateSchedule(scheduleId, request).toUi()
    }

    private fun SessionGoalResponse.toUi(): SessionGoalState = SessionGoalState(
        supported = supported,
        live = live,
        goal = goal?.toSessionGoal(),
    )

    private fun GoalDto.toSessionGoal(): SessionGoal = SessionGoal(
        id = id,
        objective = objective,
        phase = phase,
        maxGoalRounds = maxGoalRounds,
        roundsStarted = roundsStarted,
        revision = revision,
        activation = activation,
        blockedReason = blockedReason?.message?.takeIf { it.isNotBlank() },
    )

    override suspend fun createSession(workspaceId: String, preset: String?): Session {
        // The UI passes the host's machine preset id; send it verbatim.
        val summary = api.createSession(workspaceId, preset?.trim()?.takeIf { it.isNotEmpty() })
        val session = summary.toUi()
        sessionState.add(0, session)
        loadedSessions += session.id
        return session
    }

    override suspend fun presets(): PresetRoster = try {
        val response = api.presets()
        val roster = PresetRoster(
            supported = response.supported,
            presets = response.presets.map { preset ->
                AgentPreset(
                    id = preset.id,
                    name = preset.name.orEmpty(),
                    description = preset.description?.trim()?.takeIf { it.isNotEmpty() },
                    isDefault = preset.isDefault,
                    broken = preset.broken,
                )
            },
            defaultId = response.defaultId?.trim()?.takeIf { it.isNotEmpty() },
        )
        // No service / empty roster: keep old hosts usable with the fallback chips.
        if (roster.presets.isEmpty()) PresetRoster.Fallback else roster
    } catch (unsupported: DshApiException.Unsupported) {
        PresetRoster.Fallback
    } catch (notFound: DshApiException.NotFound) {
        PresetRoster.Fallback
    } catch (error: Exception) {
        // A preset fetch must never block creating a session.
        PresetRoster.Fallback
    }

    override suspend fun pluginCatalog(): PluginCatalog = try {
        val response = api.plugins()
        PluginCatalog(
            supported = response.supported,
            profile = response.profile.orEmpty(),
            packages = response.packages.map { entry ->
                ProfilePlugin(
                    name = entry.name,
                    version = entry.version.orEmpty(),
                    specifier = entry.specifier.orEmpty(),
                    local = entry.local,
                )
            },
            bundles = response.bundles,
            truncated = response.truncated,
        )
    } catch (unsupported: DshApiException.Unsupported) {
        PluginCatalog.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        PluginCatalog.Unsupported
    } catch (error: Exception) {
        PluginCatalog.Unsupported
    }

    override suspend fun searchMessages(
        query: String,
        roles: Set<String>,
        datePreset: SearchDatePreset,
        offset: Int,
    ): HostSearchResult = try {
        // One `now` captured per query so since/until form a single explicit window.
        val (since, until) = searchDateRange(datePreset, Instant.now())
        val response = api.search(
            query = query,
            limit = HOST_SEARCH_LIMIT,
            role = searchRoleQueryParam(roles),
            since = since,
            until = until,
            offset = offset,
        )
        // Frozen pagination contract: nextOffset = offset + hits.length when hasMore, else null.
        // Old hosts omit all pagination fields (hasMore defaults false => no cursor). If a host says
        // hasMore but omits nextOffset, derive the contract value instead of inferring from page size.
        val pageOffset = response.offset
        val candidateNext = if (response.hasMore) {
            response.nextOffset ?: (pageOffset + response.hits.size)
        } else {
            null
        }
        val validNext = candidateNext?.takeIf {
            it > pageOffset && it in 0..HOST_SEARCH_MAX_OFFSET
        }
        HostSearchResult(
            supported = response.supported,
            hits = response.hits.map { hit ->
                HostSearchHit(
                    sessionId = hit.sessionId,
                    sessionTitle = hit.sessionTitle?.trim()?.takeIf { it.isNotEmpty() },
                    messageId = hit.messageId,
                    role = hit.role.ifBlank { "assistant" },
                    snippet = hit.snippet,
                    createdAt = hit.createdAt?.takeIf { it.isNotBlank() },
                    seq = hit.seq?.takeIf { it > 0 },
                )
            },
            truncated = response.truncated,
            // S14: old hosts ignore filters and return no echo.
            filtersEchoed = response.filters != null,
            // Pagination echo: old hosts omit all three fields, so defaults keep today's behavior.
            offset = pageOffset,
            nextOffset = validNext,
            hasMore = response.hasMore && validNext != null,
            // Host 0.2.7 scan coverage; absent means the host did not report an incomplete scan.
            scanIncomplete = response.scanIncomplete,
            scannedSessions = response.scannedSessions,
            totalSessions = response.totalSessions,
        )
    } catch (unsupported: DshApiException.Unsupported) {
        HostSearchResult.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose GET /search yet.
        HostSearchResult.Unsupported
    }

    override suspend fun pushConfig(): PushConfig = try {
        api.pushConfig().toPushConfig()
    } catch (unsupported: DshApiException.Unsupported) {
        PushConfig.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose /push yet.
        PushConfig.Unsupported
    }

    override suspend fun updatePushConfig(
        enabled: Boolean,
        server: String,
        topic: String,
        token: String?,
    ): PushConfig {
        val request = PushUpdateRequest(
            enabled = enabled,
            // Omitted = keep the current host value; blank server falls back to the host default.
            server = server.trim().takeIf { it.isNotBlank() },
            topic = topic.trim().takeIf { it.isNotBlank() },
            // null keeps the stored token; "" explicitly clears it; non-blank sets it.
            token = token?.trim(),
        )
        return api.updatePushConfig(request).toPushConfig()
    }

    override suspend fun sendPushTest(): Boolean = api.testPush().ok

    override suspend fun updatePushSuppression(payload: PushSuppressionPayload): PushSuppressionState =
        try {
            api.updatePushSuppression(
                PushSuppressionDto(
                    quietHoursEnabled = payload.quietHoursEnabled,
                    quietStartHour = payload.quietStartHour,
                    quietEndHour = payload.quietEndHour,
                    timeZone = payload.timeZone,
                    mutedSessionIds = payload.mutedSessionIds,
                ),
            ).toPushSuppressionState()
        } catch (unsupported: DshApiException.Unsupported) {
            PushSuppressionState.Unsupported
        } catch (notFound: DshApiException.NotFound) {
            // Old host plugin without the additive endpoint: silent no-op for the caller.
            PushSuppressionState.Unsupported
        }

    override suspend fun approvalPolicy(sessionId: String): ApprovalPolicy = try {
        api.approvalPolicy(sessionId).toApprovalPolicy()
    } catch (unsupported: DshApiException.Unsupported) {
        ApprovalPolicy.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose /approval-policy yet.
        ApprovalPolicy.Unsupported
    }

    override suspend fun updateApprovalPolicy(
        sessionId: String,
        policy: String,
    ): ApprovalPolicy {
        require(approvalPolicyValid(policy)) { "Unknown approval policy: $policy" }
        return try {
            api.updateApprovalPolicy(sessionId, policy).toApprovalPolicy()
        } catch (unsupported: DshApiException.Unsupported) {
            ApprovalPolicy.Unsupported
        } catch (notFound: DshApiException.NotFound) {
            ApprovalPolicy.Unsupported
        }
    }

    private fun ApprovalPolicyDto.toApprovalPolicy(): ApprovalPolicy = ApprovalPolicy(
        supported = supported,
        live = live,
        policy = if (approvalPolicyValid(policy)) policy else APPROVAL_POLICY_ASK,
    )

    override suspend fun devices(): PairedDevices = try {
        val response = api.devices()
        PairedDevices(
            supported = response.supported,
            devices = response.devices.map { device ->
                PairedDevice(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    platform = device.platform,
                    pairedAt = device.pairedAt?.takeIf { it.isNotBlank() },
                    lastSeenAt = device.lastSeenAt?.takeIf { it.isNotBlank() },
                )
            },
            currentDeviceId = response.currentDeviceId,
        )
    } catch (unsupported: DshApiException.Unsupported) {
        PairedDevices.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose /devices yet.
        PairedDevices.Unsupported
    }

    override suspend fun revokeDevice(deviceId: String) {
        require(revokeDeviceIdValid(deviceId)) { "A device id is required." }
        api.revokeDevice(deviceId)
    }

    override suspend fun recoveryAccess(): RecoveryAccess = try {
        val response = api.recoveryStatus()
        RecoveryAccess(
            supported = response.supported,
            configured = response.configured,
            createdAt = response.createdAt?.takeIf { it.isNotBlank() },
        )
    } catch (unsupported: DshApiException.Unsupported) {
        RecoveryAccess.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose /recovery yet.
        RecoveryAccess.Unsupported
    }

    override suspend fun generateRecoveryCode(): RecoveryCode {
        val response = api.generateRecoveryCode()
        return RecoveryCode(code = response.code, createdAt = response.createdAt)
    }

    override suspend fun modelCatalog(): ModelCatalog = try {
        api.models().toModelCatalog()
    } catch (unsupported: DshApiException.Unsupported) {
        ModelCatalog.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Older host plugins may not expose /models yet.
        ModelCatalog.Unsupported
    }

    override suspend fun sessionModel(sessionId: String): SessionModel = try {
        api.sessionModel(sessionId).toSessionModel()
    } catch (unsupported: DshApiException.Unsupported) {
        SessionModel.Unsupported
    }

    override suspend fun updateSessionModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): SessionModel {
        require(modelSelectionValid(ModelSelection(provider, model))) {
            "A provider and model are required."
        }
        val request = ModelSelectionRequest(
            provider = provider.trim(),
            model = model.trim(),
            reasoningEffort = reasoningEffort?.trim()?.takeIf { it.isNotBlank() },
        )
        return try {
            api.updateSessionModel(sessionId, request).toSessionModel()
        } catch (unsupported: DshApiException.Unsupported) {
            SessionModel.Unsupported
        }
    }

    private fun ModelSelectionDto.toModelSelection(): ModelSelection = ModelSelection(
        provider = provider,
        model = model,
        reasoningEffort = reasoningEffort?.takeIf { it.isNotBlank() },
    )

    private fun ModelsResponse.toModelCatalog(): ModelCatalog = ModelCatalog(
        supported = supported,
        default = default?.toModelSelection(),
        groups = groups.map { group ->
            ModelGroup(
                id = group.id,
                name = group.name,
                models = group.models.map { model ->
                    ModelInfo(
                        id = model.id,
                        name = model.name,
                        description = model.description?.takeIf { it.isNotBlank() },
                        reasoning = model.reasoning?.let { reasoning ->
                            ModelReasoning(
                                efforts = reasoning.efforts.map { effort ->
                                    ModelEffort(
                                        id = effort.id,
                                        name = effort.name,
                                        description = effort.description
                                            ?.takeIf { it.isNotBlank() },
                                    )
                                },
                                defaultEffort = reasoning.defaultEffort
                                    ?.takeIf { it.isNotBlank() },
                            )
                        },
                    )
                },
            )
        },
        failures = failures.map { failure ->
            ModelFailure(
                id = failure.id,
                name = failure.name,
                message = failure.message,
            )
        },
        truncated = truncated,
    )

    private fun SessionModelResponse.toSessionModel(): SessionModel = SessionModel(
        supported = supported,
        live = live,
        selection = selection?.toModelSelection(),
        source = if (source == MODEL_SOURCE_SESSION) MODEL_SOURCE_SESSION else MODEL_SOURCE_DEFAULT,
    )

    override suspend fun hostQueue(sessionId: String): HostQueue = try {
        val response = api.hostQueue(sessionId)
        HostQueue(
            supported = response.supported,
            live = response.live,
            items = response.items.map { item ->
                HostQueueItem(
                    itemId = item.itemId,
                    text = item.text,
                    target = if (item.target == QUEUE_TARGET_NEXT_STEP) {
                        QUEUE_TARGET_NEXT_STEP
                    } else {
                        QUEUE_TARGET_NEXT_TURN
                    },
                    attachmentCount = item.attachmentCount.coerceAtLeast(0),
                    createdAt = item.createdAt?.takeIf { it.isNotBlank() },
                )
            },
        )
    } catch (unsupported: DshApiException.Unsupported) {
        HostQueue.Unsupported
    }

    override suspend fun sessionSkills(sessionId: String): HostSkills = try {
        val response = api.sessionSkills(sessionId)
        if (!response.supported) {
            HostSkills.Unsupported
        } else {
            HostSkills(
                supported = true,
                skills = response.skills.mapNotNull { skill ->
                    val name = sanitizedSkillName(skill.name) ?: return@mapNotNull null
                    HostSkill(
                        name = name,
                        description = sanitizedSkillDescription(skill.description).orEmpty(),
                    )
                },
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostSkills.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Invisible/removed sessions are a distinct state from "host has no skill registry".
        HostSkills.SessionUnavailable
    }

    override suspend fun sessionTodos(sessionId: String): HostPlan = try {
        val response = api.sessionTodos(sessionId)
        if (!response.supported) {
            HostPlan.Unsupported
        } else {
            val todos = response.todos.mapNotNull { todo ->
                val content = sanitizedPlanTodoContent(todo.content) ?: return@mapNotNull null
                PlanTodo(content = content, status = normalizedPlanStatus(todo.status))
            }
            val counts = clampedPlanCounts(
                hostCounts = response.counts?.let { counts ->
                    PlanCounts(
                        pending = counts.pending.coerceAtLeast(0),
                        inProgress = counts.inProgress.coerceAtLeast(0),
                        completed = counts.completed.coerceAtLeast(0),
                    )
                },
                todos = todos,
            )
            HostPlan(
                supported = true,
                counts = counts,
                todos = todos,
                updatedAt = response.updatedAt?.takeIf { it.isNotBlank() },
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostPlan.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        // Old host plugin without the additive endpoint: silent unsupported state.
        HostPlan.Unsupported
    }

    override suspend fun sessionTeam(sessionId: String): HostTeam = try {
        val response = api.sessionTeam(sessionId)
        if (!response.supported) {
            HostTeam.Unsupported
        } else {
            val members = response.members.map { member ->
                TeamMember(
                    name = sanitizedTeamName(member.name),
                    phase = sanitizedTeamPhase(member.phase),
                )
            }
            val tasks = response.tasks.mapNotNull { task ->
                val id = task.id.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                TeamTask(
                    id = id,
                    subject = sanitizedTeamSubject(task.subject),
                    status = normalizedTeamTaskStatus(task.status),
                    blockedBy = task.blockedBy.map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
            HostTeam(
                supported = true,
                members = members,
                tasks = tasks,
                counts = clampedTeamCounts(
                    hostCounts = response.counts?.let { counts ->
                        TeamCounts(
                            pending = counts.pending,
                            inProgress = counts.inProgress,
                            blocked = counts.blocked,
                            completed = counts.completed,
                        )
                    },
                    tasks = tasks,
                ),
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostTeam.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        HostTeam.Unsupported
    }

    override suspend fun sessionTrajectory(
        sessionId: String,
        beforeSeq: Int?,
    ): HostTrajectoryPage = try {
        val response = api.sessionTrajectory(
            sessionId = sessionId,
            limit = TRAJECTORY_PAGE_LIMIT,
            beforeSeq = beforeSeq,
        )
        if (!response.supported) {
            HostTrajectoryPage.Unsupported
        } else {
            HostTrajectoryPage(
                supported = true,
                events = response.events
                    .mapNotNull { event ->
                        val seq = event.seq.takeIf { it > 0 } ?: return@mapNotNull null
                        TrajectoryEvent(
                            seq = seq,
                            time = sanitizedTrajectoryTime(event.time),
                            typeLabel = trajectoryTypeLabel(event.type),
                            summary = sanitizedTrajectorySummary(event.summary),
                            status = normalizedTrajectoryStatus(event.status),
                        )
                    }
                    .distinctBy { it.seq },
                nextBeforeSeq = response.nextBeforeSeq?.takeIf { it > 0 },
                hasMore = response.hasMore,
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostTrajectoryPage.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        HostTrajectoryPage.Unsupported
    }

    override suspend fun sessionFiles(sessionId: String, path: String): HostFilesPage = try {
        val response = api.sessionFiles(sessionId, sanitizedRelativePath(path))
        if (!response.supported) {
            HostFilesPage.Unsupported
        } else {
            val entries = response.entries.mapNotNull { entry ->
                val name = entry.name
                    .filter { ch -> !ch.isISOControl() }
                    .trim()
                    .take(240)
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val entryPath = sanitizedRelativePath(
                    entry.path.ifBlank { joinWorkspacePath(response.path, name) },
                )
                WorkspaceEntry(
                    name = name,
                    kind = if (entry.kind.trim().equals("dir", ignoreCase = true)) {
                        WorkspaceEntryKind.Dir
                    } else {
                        WorkspaceEntryKind.File
                    },
                    path = entryPath,
                    bytes = entry.bytes?.takeIf { it >= 0 },
                    mimeType = entry.mimeType?.trim()?.takeIf { it.isNotEmpty() },
                    modifiedAt = entry.modifiedAt?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            HostFilesPage(
                supported = true,
                path = sanitizedRelativePath(response.path.ifBlank { path }),
                parent = response.parent
                    ?.let { sanitizedRelativePath(it) }
                    ?.takeIf { it.isNotEmpty() },
                entries = entries,
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostFilesPage.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        HostFilesPage.Unsupported
    }

    override suspend fun sessionFileContent(
        sessionId: String,
        path: String,
    ): HostFileContent {
        val response = api.sessionFileContent(sessionId, sanitizedRelativePath(path))
        val preview = previewTextFor(response.text, response.truncated)
        return HostFileContent(
            name = response.name
                .filter { ch -> !ch.isISOControl() }
                .trim()
                .ifEmpty { sanitizedRelativePath(response.path).substringAfterLast('/') },
            path = sanitizedRelativePath(response.path.ifBlank { path }),
            bytes = response.bytes.coerceAtLeast(0),
            mimeType = response.mimeType.trim(),
            text = preview.first,
            truncated = preview.second,
        )
    }

    override suspend fun downloadWorkspaceFileRaw(
        sessionId: String,
        path: String,
        fileName: String,
        cacheDir: File,
    ): File {
        val safePath = sanitizedRelativePath(path)
        val bytes = api.sessionFileRaw(
            sessionId = sessionId,
            path = safePath,
            maxBytes = FILES_RAW_MAX_BYTES,
        )
        // Reuse the FileProvider attachment root: it is the only cache root served by the provider.
        val cacheRoot = File(cacheDir, "attachments")
        val file = File(cacheRoot, workspaceFileCacheName(safePath, fileName))
        withContext(Dispatchers.IO) {
            cacheRoot.mkdirs()
            val partial = File(cacheRoot, "${file.name}.part")
            partial.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!partial.renameTo(file)) {
                file.writeBytes(bytes)
                partial.delete()
            }
        }
        return file
    }

    /** Collision-safe cache name so different workspace paths never overwrite each other. */
    private fun workspaceFileCacheName(path: String, fileName: String): String {
        val base = fileName
            .substringAfterLast('/')
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .take(80)
            .ifBlank { "file" }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(10)
        return "ws-$digest-$base"
    }

    override suspend fun sessionJobs(sessionId: String): HostJobs = try {
        val response = api.sessionJobs(sessionId)
        if (!response.supported) {
            HostJobs.Unsupported
        } else {
            val jobs = response.jobs.mapNotNull { job ->
                val id = job.id.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                JobEntry(
                    id = id,
                    kindLabel = jobKindLabel(job.kind),
                    label = sanitizedJobLabel(job.label),
                    status = normalizedJobStatus(job.status),
                    startedAt = jobStartedLabel(job.startedAt).takeIf { it.isNotEmpty() },
                    finishedAt = jobStartedLabel(job.finishedAt).takeIf { it.isNotEmpty() },
                    durationMs = job.durationMs?.takeIf { it >= 0 },
                    detail = sanitizedJobDetail(job.detail),
                )
            }
            HostJobs(
                supported = true,
                counts = clampedJobCounts(
                    hostCounts = response.counts?.let { counts ->
                        JobCounts(
                            running = counts.running,
                            completed = counts.completed,
                            failed = counts.failed,
                        )
                    },
                    jobs = jobs,
                ),
                jobs = jobs,
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostJobs.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        HostJobs.Unsupported
    }

    override suspend fun sessionContext(sessionId: String): HostContext = try {
        val response = api.sessionContext(sessionId)
        if (!response.supported) {
            HostContext.Unsupported
        } else {
            val breakdown = response.breakdown?.let { raw ->
                val values = listOfNotNull(
                    raw.systemTokens?.takeIf { it >= 0 },
                    raw.toolsTokens?.takeIf { it >= 0 },
                    raw.messageTokens?.takeIf { it >= 0 },
                )
                if (values.isEmpty()) {
                    null
                } else {
                    ContextBreakdown(
                        systemTokens = raw.systemTokens?.takeIf { it >= 0 },
                        toolsTokens = raw.toolsTokens?.takeIf { it >= 0 },
                        messageTokens = raw.messageTokens?.takeIf { it >= 0 },
                    )
                }
            }
            HostContext(
                supported = true,
                usage = ContextUsage(
                    surfaceTokens = response.surfaceTokens?.takeIf { it >= 0 },
                    contextWindow = response.contextWindow?.takeIf { it > 0 },
                    pressureTokens = response.pressureTokens?.takeIf { it >= 0 },
                    percent = response.percent?.takeIf { it.isFinite() && it >= 0.0 },
                    breakdown = breakdown,
                    sampledAt = response.sampledAt?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
    } catch (unsupported: DshApiException.Unsupported) {
        HostContext.Unsupported
    } catch (notFound: DshApiException.NotFound) {
        HostContext.Unsupported
    }

    override suspend fun hostQueueAction(
        sessionId: String,
        itemId: String,
        action: String,
        text: String?,
    ) {
        require(itemId.isNotBlank()) { "A queue item id is required." }
        require(hostQueueActionValid(action)) { "Unknown queue action: $action" }
        if (action == QUEUE_ACTION_EDIT) {
            require(hostQueueEditTextValid(text.orEmpty())) {
                "An edited queued prompt can't be empty."
            }
        }
        api.hostQueueAction(
            sessionId = sessionId,
            itemId = itemId,
            request = HostQueueActionRequest(
                action = action,
                text = if (action == QUEUE_ACTION_EDIT) text?.trim() else null,
            ),
        )
    }

    /** Fork seqs captured from message DTOs (the UI Message model intentionally has no seq). */
    private val messageSeqById = mutableStateMapOf<String, Int>()

    private fun MessageDto.recordSeq() {
        val forkSeq = seq?.takeIf { it > 0 } ?: return
        if (id.isNotBlank()) messageSeqById[id] = forkSeq
    }

    override fun messageSeq(messageId: String): Int? =
        if (messageId.isBlank()) null else messageSeqById[messageId]

    override suspend fun forkSession(sessionId: String, atSeq: Int?): ForkResult {
        require(sessionId.isNotBlank()) { "A session id is required." }
        require(atSeq == null || forkSeqValid(atSeq)) { "Invalid fork boundary." }
        val response = api.forkSession(sessionId, forkAtSeqOrNull(atSeq))
        return ForkResult(
            sessionId = response.sessionId,
            forkedFrom = response.forkedFrom,
            atSeq = response.atSeq,
        )
    }

    private fun PushConfigDto.toPushConfig(): PushConfig = PushConfig(
        supported = supported,
        enabled = enabled,
        server = server,
        topic = topic,
        hasToken = hasToken,
        suppression = suppression?.toPushSuppressionState(),
    )

    private fun PushSuppressionSummaryDto.toPushSuppressionState(): PushSuppressionState =
        PushSuppressionState(
            supported = true,
            quietHoursEnabled = quietHoursEnabled,
            quietStartHour = quietStartHour.coerceIn(0, 23),
            quietEndHour = quietEndHour.coerceIn(0, 23),
            timeZone = timeZone,
            timeZoneValid = timeZoneValid,
            mutedCount = mutedCount.coerceAtLeast(0),
        )

    override suspend fun decideApproval(approvalId: String, allow: Boolean) {
        try {
            api.decideApproval(approvalId, if (allow) "allow" else "deny")
        } catch (unsupported: DshApiException.Unsupported) {
            approvalsSupported = false
            throw unsupported
        }
        approvalState.removeAll { it.id == approvalId }
    }

    override suspend fun answerQuestion(questionId: String, answer: String, optionIndex: Int?) {
        try {
            api.answerQuestion(questionId, answer, optionIndex)
        } catch (unsupported: DshApiException.Unsupported) {
            questionsSupported = false
            questionsReadOnly = false
            throw unsupported
        }
        questionState.removeAll { it.id == questionId }
    }

    // endregion

    // region SSE

    private suspend fun eventLoop(generation: Int) {
        while (currentCoroutineContext().isActive && generation == loopGeneration) {
            if (!networkAvailable) {
                connectionState = ConnectionStatus.Offline
                if (!networkWaitLogged) {
                    noteEvent("Network offline — waiting for connectivity")
                    networkWaitLogged = true
                }
                delay(MAX_RECONNECT_BACKOFF_MILLIS)
                continue
            }
            networkWaitLogged = false
            if (lastEventId == null) {
                connectionState = ConnectionStatus.Connecting
            }
            try {
                streamStartedAt = System.currentTimeMillis()
                streamingGeneration = generation
                try {
                    api.events(lastEventId).collect { event ->
                        // A superseded loop must never touch shared state.
                        if (generation != loopGeneration) return@collect
                        lastEventAt = System.currentTimeMillis()
                        connectionState = ConnectionStatus.Live
                        backoffMillis = INITIAL_BACKOFF_MILLIS
                        applyEvent(event)
                    }
                } finally {
                    if (streamingGeneration == generation) streamingGeneration = -1
                }
                // Clean end of stream (host restart / network switch): reconnect.
                if (generation == loopGeneration) {
                    connectionState = ConnectionStatus.Offline
                    noteEvent("Stream ended by host")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: DshApiException.Unauthorized) {
                connectionState = ConnectionStatus.Offline
                noteEvent("Unauthorized — device token rejected")
                return
            } catch (error: Exception) {
                connectionState = ConnectionStatus.Offline
                noteEvent("Stream error: " + errorLabel(error))
                if (isTailnetDnsFailure(error, api.isTailnetHost, networkTransport)) {
                    // MagicDNS is only available while the Tailscale VPN is up. The network
                    // callback forces an immediate reconnect when the VPN comes back.
                    backoffMillis = MAX_RECONNECT_BACKOFF_MILLIS
                }
            }
            if (!currentCoroutineContext().isActive || generation != loopGeneration) break
            noteReconnectAttempt()
            delay(backoffMillis)
            backoffMillis = nextReconnectBackoff(backoffMillis)
            if (!currentCoroutineContext().isActive || generation != loopGeneration) break
            catchUpAfterReconnect()
        }
    }

    private fun applyEvent(event: SseEvent) {
        event.id?.let { lastEventId = it }
        val payload = event.data ?: return
        when (event.type) {
            "session.updated" -> decodePayload<SessionSummaryDto>(payload)?.let {
                // Partial update: preserve goal/lineage/delegation the event payload omits.
                upsertSummary(
                    it,
                    preserveGoalWhenMissing = true,
                    preserveLineageWhenMissing = true,
                    preserveDelegationWhenMissing = true,
                )
            }

            "session.removed" -> decodePayload<SessionRemovedEvent>(payload)?.let { removed ->
                sessionState.removeAll { it.id == removed.sessionId }
                loadedSessions -= removed.sessionId
            }

            "message.added" -> decodePayload<MessageAddedEvent>(payload)?.let { added ->
                added.message?.recordSeq()
                val message = added.message?.toUi() ?: return@let
                val incomingUser = message as? UserMessage
                val sessionId = added.sessionId.ifBlank { event.sessionId.orEmpty() }
                if (sessionId.isBlank()) return@let
                updateSession(sessionId) { session ->
                    // A user message sent with attachments is rendered from the local optimistic
                    // row: v0.2.2 Message has no attachment metadata, so keep the display names
                    // when the host's canonical row replaces it.
                    val localWithAttachments = if (incomingUser != null) {
                        session.messages
                            .filterIsInstance<UserMessage>()
                            .lastOrNull { local ->
                                local.id.startsWith(LOCAL_PREFIX) &&
                                    local.attachments.isNotEmpty() &&
                                    (local.text == incomingUser.text || incomingUser.text.isBlank())
                            }
                    } else {
                        null
                    }
                    val withoutLocal = session.messages.filterNot { it.id.startsWith(LOCAL_PREFIX) }
                    if (localWithAttachments != null && incomingUser != null) {
                        session.copy(
                            messages = withoutLocal + incomingUser.copy(
                                attachments = localWithAttachments.attachments,
                            ),
                            unread = false,
                        )
                    } else {
                        // Upsert by id: the host re-sends message.added with the same id to move a
                        // tool row from "running" to "done" (there is no message.updated event).
                        val existingIndex = withoutLocal.indexOfFirst { it.id == message.id }
                        val messages = if (existingIndex >= 0) {
                            withoutLocal.toMutableList().also { it[existingIndex] = message }
                        } else {
                            withoutLocal + message
                        }
                        session.copy(messages = messages, unread = false)
                    }
                }
            }

            "message.delta" -> decodePayload<MessageDeltaEvent>(payload)?.let { applyDelta(it) }

            "message.completed" -> decodePayload<MessageCompletedEvent>(payload)?.let { completed ->
                updateMessage(completed.sessionId, completed.messageId) { message ->
                    if (message is AssistantMessage) message.copy(streaming = false) else message
                }
                // Deltas arrive under a transient stream id; the durable transcript only exists
                // after a detail refetch. Reconcile once the run completes.
                if (completed.sessionId in loadedSessions) {
                    scope.launch { runCatching { loadMessages(completed.sessionId, force = true) } }
                }
            }

            "approval.requested" -> decodePayload<ApprovalDto>(payload)?.let { approval ->
                if (approvalState.none { it.id == approval.id }) {
                    approvalState.add(approval.toUi())
                }
            }

            "approval.resolved" -> decodePayload<ApprovalResolvedEvent>(payload)?.let { resolved ->
                approvalState.removeAll { it.id == resolved.approvalId }
            }

            "question.requested" -> decodePayload<QuestionDto>(payload)?.let { question ->
                if (questionState.none { it.id == question.id }) {
                    questionState.add(question.toUi())
                }
            }

            "question.resolved" -> decodePayload<QuestionResolvedEvent>(payload)?.let { resolved ->
                questionState.removeAll { it.id == resolved.questionId }
            }

            "host.status" -> decodePayload<HostStatusEvent>(payload)?.let { status ->
                connectionState = if (status.connected) ConnectionStatus.Live else ConnectionStatus.Offline
            }

            "goal.changed" -> decodePayload<GoalChangedEventDto>(payload)?.let { changed ->
                val sessionId = changed.sessionId.ifBlank { event.sessionId.orEmpty() }
                if (sessionId.isNotBlank()) {
                    val goal = changed.goal?.toSessionGoal()
                    // Keep the session-list badge live without waiting for a full refetch.
                    updateSessionGoal(sessionId, goal)
                    goalEventChannel.tryEmit(
                        GoalChanged(
                            sessionId = sessionId,
                            operation = changed.operation,
                            goal = goal,
                        ),
                    )
                }
            }

            else -> Unit // "ping" heartbeats and future event types are ignored on purpose.
        }
    }

    private fun applyDelta(delta: MessageDeltaEvent) {
        val index = sessionState.indexOfFirst { it.id == delta.sessionId }
        if (index < 0) return
        val session = sessionState[index]
        // A transcript that was never fetched is loaded fresh when opened; deltas would be partial.
        if (session.id !in loadedSessions) return
        val messages = session.messages.toMutableList()
        val messageIndex = messages.indexOfFirst { it.id == delta.messageId }
        if (messageIndex >= 0) {
            messages[messageIndex] = when (val current = messages[messageIndex]) {
                is AssistantMessage -> current.copy(text = current.text + delta.text, streaming = true)
                is UserMessage -> current.copy(text = current.text + delta.text)
                is SystemNote -> current.copy(text = current.text + delta.text)
            }
        } else {
            messages.add(
                AssistantMessage(
                    id = delta.messageId,
                    time = nowClock(),
                    text = delta.text,
                    streaming = true,
                ),
            )
        }
        sessionState[index] = session.copy(messages = messages, status = SessionStatus.Working)
    }

    // endregion

    // region helpers

    private fun upsertSummaries(summaries: List<SessionSummaryDto>) {
        val seen = summaries.mapTo(mutableSetOf()) { it.id }
        summaries.forEach { upsertSummary(it) }
        sessionState.removeAll { it.id !in seen }
        loadedSessions.retainAll(seen)
    }

    private fun upsertSummary(
        summary: SessionSummaryDto,
        preserveGoalWhenMissing: Boolean = false,
        preserveLineageWhenMissing: Boolean = false,
        preserveDelegationWhenMissing: Boolean = false,
    ) {
        val index = sessionState.indexOfFirst { it.id == summary.id }
        val existing = if (index >= 0) sessionState[index] else null
        val updated = summary.toUi(
            existing = existing,
            preserveGoalWhenMissing = preserveGoalWhenMissing,
            preserveLineageWhenMissing = preserveLineageWhenMissing,
            preserveDelegationWhenMissing = preserveDelegationWhenMissing,
        )
        if (index >= 0) {
            sessionState[index] = updated
        } else {
            // Host order is authoritative; unseen sessions arrive through the snapshot refetch.
            sessionState.add(updated)
        }
    }

    private fun updateSession(sessionId: String, transform: (Session) -> Session) {
        val index = sessionState.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            sessionState[index] = transform(sessionState[index])
        }
    }

    /**
     * Mirrors a live `goal.changed` onto the session row so the session-list badge updates
     * immediately; a null goal clears the badge (goal cleared).
     */
    private fun updateSessionGoal(sessionId: String, goal: SessionGoal?) {
        val index = sessionState.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        sessionState[index] = sessionState[index].copy(
            goalPhase = goal?.phase?.trim()?.takeIf { it.isNotEmpty() },
            goalRoundsStarted = goal?.roundsStarted ?: 0,
            goalMaxRounds = goal?.maxGoalRounds ?: 0,
            goalObjective = goal?.objective?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun updateMessage(sessionId: String, messageId: String, transform: (Message) -> Message) {
        val index = sessionState.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val session = sessionState[index]
        var changed = false
        val messages = session.messages.map { message ->
            if (message.id == messageId) {
                changed = true
                transform(message)
            } else {
                message
            }
        }
        if (changed) {
            sessionState[index] = session.copy(messages = messages)
        }
    }

    private fun appendLocalUserMessage(
        sessionId: String,
        text: String,
        attachments: List<String> = emptyList(),
    ) {
        val id = "$LOCAL_PREFIX${System.nanoTime()}"
        updateSession(sessionId) { session ->
            session.copy(messages = session.messages + UserMessage(id, nowClock(), text, attachments))
        }
    }

    private fun removeLocalMessages(sessionId: String) {
        updateSession(sessionId) { session ->
            session.copy(messages = session.messages.filterNot { it.id.startsWith(LOCAL_PREFIX) })
        }
    }

    private inline fun <reified T> decodePayload(payload: JsonObject): T? =
        runCatching { DshJson.decodeFromJsonElement<T>(payload) }.getOrNull()

    // endregion

    private companion object {
        const val LOCAL_PREFIX = "local-"
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val WATCHDOG_TICK_MILLIS = 15_000L
        const val STALE_STREAM_MILLIS = 75_000L
        const val TRANSCRIPT_PAGE_SIZE = 60
        const val TRANSCRIPT_FRESH_MILLIS = 20_000L
    }
}

/** Upper bound for stream reconnect delays after repeated failures. */
internal const val MAX_RECONNECT_BACKOFF_MILLIS = 60_000L

/** A DNS failure for a MagicDNS host is usually a disconnected Tailscale VPN, not a dead host. */
internal fun isTailnetDnsFailure(
    error: Throwable,
    isTailnetHost: Boolean,
    networkTransport: String,
): Boolean = error is UnknownHostException && isTailnetHost && networkTransport != "VPN"

/** Doubles the current delay up to [MAX_RECONNECT_BACKOFF_MILLIS]. */
internal fun nextReconnectBackoff(currentMillis: Long): Long =
    (currentMillis * 2).coerceAtMost(MAX_RECONNECT_BACKOFF_MILLIS)
