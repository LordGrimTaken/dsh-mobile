package dev.dsh.mobile.data

import java.io.File

enum class RepositoryMode { Mock, Remote }

/**
 * The single surface the UI talks to. [MockRepository] wraps the existing demo state machine and
 * [RemoteRepository] is backed by the host HTTP/SSE API, so the app can switch between demo data
 * and a paired desktop without touching the screens.
 */
interface DshRepository {

    val mode: RepositoryMode
    val workspaces: List<Workspace>
    val sessions: List<Session>
    val approvals: List<Approval>
    val questions: List<Question>
    val connection: ConnectionStatus

    /** Live goal change events for the GOAL banner; empty for demo/mock. */
    val goalChanges: kotlinx.coroutines.flow.Flow<GoalChanged>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /** False when the host explicitly reports that its approval bridge is unsupported (501). */
    val approvalsSupported: Boolean get() = true

    /** True when the host exposes approvals read-only (desktop remains the answerer). */
    val approvalsReadOnly: Boolean get() = false

    /** False when the host explicitly reports that its question bridge is unsupported (501). */
    val questionsSupported: Boolean get() = true

    /** True when the host exposes questions read-only (desktop remains the answerer). */
    val questionsReadOnly: Boolean get() = false

    /** Starts any background sync (SSE for the remote repository; no-op for mock). */
    fun start()

    /** Stops background sync but keeps the current data. */
    fun stop()

    /** Refetches the session list, host info, approvals and questions. */
    suspend fun refresh()

    /** Notifies the repository that the user opened a session. */
    fun openSession(id: String)

    /** Loads/refreshes the transcript; returns true when stored data changed. */
    suspend fun loadMessages(sessionId: String): Boolean

    /** Loads the page before the currently loaded transcript; false when there is no older page. */
    suspend fun loadOlderMessages(sessionId: String): Boolean

    /** Whether the host reported another older transcript page for the session. */
    fun hasOlderMessages(sessionId: String): Boolean

    /** Loads token usage and the optional DeepSeek API balance from the host. */
    suspend fun usage(): HostUsage

    /** Curated hidden-session ids (host-persisted list). */
    suspend fun hiddenSessions(): Set<String>

    /** Hides one session from lists without deleting it. */
    suspend fun hideSession(sessionId: String)

    /** Restores a previously hidden session to the list. */
    suspend fun unhideSession(sessionId: String)

    /** Renames the session on the host; the title changes on every paired device. */
    suspend fun renameSessionOnHost(sessionId: String, title: String)

    /** Archives the session on the host (removed from the active list everywhere). */
    suspend fun archiveSessionOnHost(sessionId: String)

    /** Restores an archived session to the active list. */
    suspend fun unarchiveSessionOnHost(sessionId: String)

    /**
     * Archived sessions. Hosts without the archive bridge return
     * [ArchivedSessionsState.Unsupported] instead of throwing.
     */
    suspend fun archivedSessions(): ArchivedSessionsState

    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        attachmentIds: List<String> = emptyList(),
    )

    /** Uploads one attachment (<=10 MB decoded) and returns its host id for [sendPrompt]. */
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit = {},
    ): UploadedAttachment

    /**
     * Downloads one historical message attachment (size-capped) and caches it under
     * `<cacheDir>/attachments/`; returns the cached file. Throws on host/network failure.
     */
    suspend fun downloadAttachment(
        sessionId: String,
        attachmentId: String,
        fileName: String,
        cacheDir: java.io.File,
    ): java.io.File

    suspend fun interrupt(sessionId: String)

    /** Current goal for a session; returns unsupported when the host bridge lacks goal controls. */
    suspend fun goal(sessionId: String): SessionGoalState

    /** Resume/re-arm the current goal for a live session. */
    suspend fun resumeGoal(sessionId: String): SessionGoalState

    /** Pause an active goal for a live session. */
    suspend fun pauseGoal(sessionId: String): SessionGoalState

    /**
     * Mark the current goal complete for a live session. The optional [goalId]/[revision] guard
     * against a stale phone view; omit them when unknown.
     */
    suspend fun completeGoal(
        sessionId: String,
        goalId: String? = null,
        revision: Int? = null,
    ): SessionGoalState

    /** Create a goal for a live session with no current non-complete goal. */
    suspend fun createGoal(
        sessionId: String,
        objective: String,
        maxGoalRounds: Int? = null,
    ): SessionGoalState

    /** Edit the current goal; null objective/maxGoalRounds keep their current value. */
    suspend fun editGoal(
        sessionId: String,
        objective: String?,
        maxGoalRounds: Int?,
    ): SessionGoalState

    /** Clear the current goal (the banner returns to NO GOAL / Set goal). */
    suspend fun clearGoal(sessionId: String): SessionGoalState

    /**
     * Schedules across sessions. Hosts without the schedules bridge return
     * [ScheduleListState.Unsupported] instead of throwing.
     */
    suspend fun schedules(): ScheduleListState

    /** Create one schedule for a live session; unsupported hosts throw an Unsupported error. */
    suspend fun createSchedule(
        sessionId: String,
        title: String,
        instruction: String,
        timing: ScheduleTimingInput,
    ): SessionSchedule

    /** Delete one schedule by id. */
    suspend fun deleteSchedule(scheduleId: String)

    /**
     * Update a schedule: title/instruction are applied when non-null; timing is applied when
     * non-null (unsupported host kinds pass null and only edit text). Returns the updated row.
     */
    suspend fun updateSchedule(
        scheduleId: String,
        title: String?,
        instruction: String?,
        timing: ScheduleTimingInput?,
    ): SessionSchedule

    suspend fun createSession(workspaceId: String, preset: String?): Session

    /**
     * Host agent presets. Hosts without the presets service return [PresetRoster.Fallback] rather
     * than throwing so session creation always stays available.
     */
    suspend fun presets(): PresetRoster

    /**
     * Host-wide message search. Hosts without the search service return
     * [HostSearchResult.Unsupported] rather than throwing; invalid queries throw.
     * [offset] requests a later page for hosts that advertise `hasMore` (0 = first page).
     */
    suspend fun searchMessages(
        query: String,
        roles: Set<String> = emptySet(),
        datePreset: SearchDatePreset = SearchDatePreset.ANY,
        offset: Int = 0,
    ): HostSearchResult

    /**
     * ntfy push configuration. Hosts without the push service return [PushConfig.Unsupported]
     * instead of throwing.
     */
    suspend fun pushConfig(): PushConfig

    /** Saves push settings; null [token] keeps the stored token, "" clears it. */
    suspend fun updatePushConfig(
        enabled: Boolean,
        server: String,
        topic: String,
        token: String?,
    ): PushConfig

    /** Asks the host to send one ntfy test notification; true when the host confirmed it. */
    suspend fun sendPushTest(): Boolean

    /**
     * Additive push suppression sync (quiet hours + per-session mutes). Hosts without the endpoint
     * return [PushSuppressionState.Unsupported]; network failures propagate for a silent caller.
     */
    suspend fun updatePushSuppression(payload: PushSuppressionPayload): PushSuppressionState

    /**
     * Per-session approval policy ("ask"/"never"). Hosts without the endpoint return
     * [ApprovalPolicy.Unsupported] instead of throwing.
     */
    suspend fun approvalPolicy(sessionId: String): ApprovalPolicy

    suspend fun updateApprovalPolicy(sessionId: String, policy: String): ApprovalPolicy

    /**
     * Devices paired with the host. Hosts without the endpoint return
     * [PairedDevices.Unsupported] instead of throwing.
     */
    suspend fun devices(): PairedDevices

    /** Revokes another paired device; the current device is rejected by the host (400). */
    suspend fun revokeDevice(deviceId: String)

    /** Recovery-code status for this host; unsupported hosts return [RecoveryAccess.Unsupported]. */
    suspend fun recoveryAccess(): RecoveryAccess

    /** Rotates the recovery code; the returned code is shown once and never persisted. */
    suspend fun generateRecoveryCode(): RecoveryCode

    /** Model catalog for the picker; unsupported hosts return [ModelCatalog.Unsupported]. */
    suspend fun modelCatalog(): ModelCatalog

    /** The session's effective model; unsupported hosts return [SessionModel.Unsupported]. */
    suspend fun sessionModel(sessionId: String): SessionModel

    /** Saves a session model selection; blank [reasoningEffort] means "host default effort". */
    suspend fun updateSessionModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): SessionModel

    /** Host-side queued prompts; unsupported hosts return [HostQueue.Unsupported]. */
    suspend fun hostQueue(sessionId: String): HostQueue

    /** Per-session skills; hosts without a skill registry return [HostSkills.Unsupported]. */
    suspend fun sessionSkills(sessionId: String): HostSkills

    /** Per-session plan/todos; hosts without the endpoint return [HostPlan.Unsupported]. */
    suspend fun sessionTodos(sessionId: String): HostPlan

    /** Per-session agent-team board; hosts without the endpoint return [HostTeam.Unsupported]. */
    suspend fun sessionTeam(sessionId: String): HostTeam

    /**
     * One trajectory journal page (newest-first). [beforeSeq] requests older events; null loads the
     * newest page. Hosts without the endpoint return [HostTrajectoryPage.Unsupported].
     */
    suspend fun sessionTrajectory(sessionId: String, beforeSeq: Int? = null): HostTrajectoryPage

    /** Read-only workspace directory listing; unsupported hosts return [HostFilesPage.Unsupported]. */
    suspend fun sessionFiles(sessionId: String, path: String = ""): HostFilesPage

    /** Text preview for one relative file; 415/binary/oversize errors propagate for the UI wording. */
    suspend fun sessionFileContent(sessionId: String, path: String): HostFileContent

    /** Downloads raw bytes (≤25 MB) into the app cache and returns the cached file. */
    suspend fun downloadWorkspaceFileRaw(
        sessionId: String,
        path: String,
        fileName: String,
        cacheDir: File,
    ): File

    /** Read-only background jobs; unsupported hosts return [HostJobs.Unsupported]. */
    suspend fun sessionJobs(sessionId: String): HostJobs

    /** Context-window usage; unsupported hosts return [HostContext.Unsupported]. */
    suspend fun sessionContext(sessionId: String): HostContext

    /**
     * Applies one queue action ("edit" with [text], "remove", or "steer"). Host errors
     * (cold/unknown/steer-unavailable/unsupported) propagate for the UI to explain.
     */
    suspend fun hostQueueAction(
        sessionId: String,
        itemId: String,
        action: String,
        text: String? = null,
    )

    /** Host-provided fork seq for a message id, if the transcript carried one. */
    fun messageSeq(messageId: String): Int?

    /**
     * Forks a conversation at [atSeq] (null = latest completed turn). Host errors
     * (fork-unavailable/bad seq/unknown/unsupported) propagate for the UI to explain.
     */
    suspend fun forkSession(sessionId: String, atSeq: Int?): ForkResult

    suspend fun decideApproval(approvalId: String, allow: Boolean)

    suspend fun answerQuestion(questionId: String, answer: String, optionIndex: Int?)

    fun dispose()
}
