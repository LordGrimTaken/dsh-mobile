package dev.dsh.mobile.data

import java.io.File

/**
 * Demo backend: a thin adapter over the pre-existing [DemoState] mock engine so the rest of the app
 * (and the repository abstraction) treats demo data exactly like a paired host.
 */
class MockRepository(private val demo: DemoState) : DshRepository {

    override val mode: RepositoryMode = RepositoryMode.Mock
    override val workspaces: List<Workspace> get() = demo.workspaces
    override val sessions: List<Session> get() = demo.sessions
    override val approvals: List<Approval> get() = listOfNotNull(demo.pendingApproval)
    override val questions: List<Question> get() = emptyList()
    override val connection: ConnectionStatus get() = ConnectionStatus.Demo

    /** Demo approvals are always answerable on the phone. */
    override val approvalsReadOnly: Boolean get() = false

    /** Demo questions (if any) are always answerable on the phone. */
    override val questionsReadOnly: Boolean get() = false

    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun refresh() = Unit

    override fun openSession(id: String) {
        demo.openSession(id)
    }

    override suspend fun loadMessages(sessionId: String): Boolean = false

    override suspend fun loadOlderMessages(sessionId: String): Boolean = false

    override fun hasOlderMessages(sessionId: String): Boolean = false

    override suspend fun hiddenSessions(): Set<String> = emptySet()

    override suspend fun hideSession(sessionId: String) = Unit

    override suspend fun unhideSession(sessionId: String) = Unit

    override suspend fun renameSessionOnHost(sessionId: String, title: String) =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Session management needs a paired host.",
        )

    override suspend fun archiveSessionOnHost(sessionId: String) =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Session management needs a paired host.",
        )

    override suspend fun unarchiveSessionOnHost(sessionId: String) =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Session management needs a paired host.",
        )

    override suspend fun archivedSessions(): ArchivedSessionsState = ArchivedSessionsState.Unsupported

    override suspend fun usage(): HostUsage = HostUsage(
        generatedAt = "demo",
        totals = TokenUsage(
            uncachedInputTokens = 42_000,
            outputTokens = 9_500,
            cacheReadTokens = 210_000,
            cacheWriteTokens = 0,
            totalTokens = 261_500,
        ),
        sessions = sessions.take(5).map { session ->
            SessionUsage(
                sessionId = session.id,
                title = session.title,
                updatedAt = session.updated,
                usage = TokenUsage(
                    uncachedInputTokens = 4_200,
                    outputTokens = 900,
                    cacheReadTokens = 21_000,
                    totalTokens = 26_100,
                ),
            )
        },
        balance = null,
        balanceError = null,
    )

    override suspend fun sendPrompt(
        sessionId: String,
        text: String,
        attachmentIds: List<String>,
    ) {
        if (attachmentIds.isNotEmpty()) {
            // Attachments are a paired-host feature; the UI disables them in demo mode.
            throw dev.dsh.mobile.net.DshApiException.Unsupported(
                "Attachments need a paired host.",
            )
        }
        demo.openSession(sessionId)
        demo.sendMessage(text)
    }

    override suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit,
    ): UploadedAttachment = throw dev.dsh.mobile.net.DshApiException.Unsupported(
        "Attachments need a paired host.",
    )

    override suspend fun downloadAttachment(
        sessionId: String,
        attachmentId: String,
        fileName: String,
        cacheDir: java.io.File,
    ): java.io.File = throw dev.dsh.mobile.net.DshApiException.Unsupported(
        "Attachments need a paired host.",
    )

    override suspend fun interrupt(sessionId: String) {
        demo.stopWorking()
    }

    override suspend fun goal(sessionId: String): SessionGoalState = SessionGoalState.Unsupported

    override suspend fun resumeGoal(sessionId: String): SessionGoalState =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Goal controls need a paired host.",
        )

    override suspend fun pauseGoal(sessionId: String): SessionGoalState =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Goal controls need a paired host.",
        )

    override suspend fun completeGoal(
        sessionId: String,
        goalId: String?,
        revision: Int?,
    ): SessionGoalState =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Goal controls need a paired host.",
        )

    override suspend fun createGoal(
        sessionId: String,
        objective: String,
        maxGoalRounds: Int?,
    ): SessionGoalState =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Goal controls need a paired host.",
        )

    override suspend fun editGoal(
        sessionId: String,
        objective: String?,
        maxGoalRounds: Int?,
    ): SessionGoalState =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Goal controls need a paired host.",
        )

    override suspend fun clearGoal(sessionId: String): SessionGoalState =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Goal controls need a paired host.",
        )

    override suspend fun schedules(): ScheduleListState = ScheduleListState.Unsupported

    override suspend fun createSchedule(
        sessionId: String,
        title: String,
        instruction: String,
        timing: ScheduleTimingInput,
    ): SessionSchedule =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Schedules need a paired host.",
        )

    override suspend fun deleteSchedule(scheduleId: String) =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Schedules need a paired host.",
        )

    override suspend fun updateSchedule(
        scheduleId: String,
        title: String?,
        instruction: String?,
        timing: ScheduleTimingInput?,
    ): SessionSchedule =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Schedules need a paired host.",
        )

    override suspend fun createSession(workspaceId: String, preset: String?): Session {
        // Presets are machine ids in the real app; the demo text prefers the display label.
        val label = when (preset) {
            "minimal" -> "Minimal"
            "ptc" -> "PTC"
            "standard", null, "" -> "Standard"
            else -> preset
        }
        demo.newSession(workspaceId, label)
        return demo.sessions.first()
    }

    override suspend fun presets(): PresetRoster = PresetRoster.Fallback

    override suspend fun pluginCatalog(): PluginCatalog = PluginCatalog(
        supported = true,
        profile = "demo",
        packages = listOf(
            ProfilePlugin("@deepseek-ai/dsh-base", "0.3.0", "^0.3.0"),
            ProfilePlugin("@deepseek-ai/dsh-web-app", "0.3.0", "^0.3.0"),
            ProfilePlugin("@local/harness-deck", "", "link:/plugins/harness-deck", local = true),
            ProfilePlugin("@local/game-lab", "", "link:/plugins/game-lab", local = true),
            ProfilePlugin("dsh-mobile-host", "0.3.13", "link:/host-plugin", local = true),
        ),
        bundles = listOf("dsh-base", "dsh-web-app", "dsh-experimental-plugin-market-bundle"),
    )

    override suspend fun searchMessages(
        query: String,
        roles: Set<String>,
        datePreset: SearchDatePreset,
        offset: Int,
    ): HostSearchResult = HostSearchResult.Unsupported

    override suspend fun pushConfig(): PushConfig = PushConfig.Unsupported

    override suspend fun updatePushConfig(
        enabled: Boolean,
        server: String,
        topic: String,
        token: String?,
    ): PushConfig =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Push notifications need a paired host.",
        )

    override suspend fun sendPushTest(): Boolean =
        throw dev.dsh.mobile.net.DshApiException.Unsupported(
            "Push notifications need a paired host.",
        )

    override suspend fun updatePushSuppression(
        payload: PushSuppressionPayload,
    ): PushSuppressionState = PushSuppressionState.Unsupported

    override suspend fun approvalPolicy(sessionId: String): ApprovalPolicy = ApprovalPolicy.Unsupported

    override suspend fun updateApprovalPolicy(
        sessionId: String,
        policy: String,
    ): ApprovalPolicy = ApprovalPolicy.Unsupported

    /** Demo host: one current device plus one other phone so the revoke flow is explorable. */
    override suspend fun devices(): PairedDevices {
        val nowIso = java.time.Instant.now().toString()
        val devices = listOf(
            PairedDevice(
                deviceId = "demo-this-device",
                deviceName = "Demo phone",
                platform = "Android",
                pairedAt = nowIso,
                lastSeenAt = nowIso,
            ),
            PairedDevice(
                deviceId = "demo-other-device",
                deviceName = "Old tablet",
                platform = "Android",
                pairedAt = java.time.Instant.now().minusSeconds(3 * 86_400L).toString(),
                lastSeenAt = java.time.Instant.now().minusSeconds(2 * 3_600L).toString(),
            ),
        )
        return PairedDevices(
            supported = true,
            devices = devices,
            currentDeviceId = "demo-this-device",
        )
    }

    override suspend fun revokeDevice(deviceId: String) {
        // Demo host accepts revokes; the UI refreshes the (static) demo list afterwards.
    }

    override suspend fun recoveryAccess(): RecoveryAccess = RecoveryAccess(
        supported = true,
        configured = true,
        createdAt = java.time.Instant.now().minusSeconds(86_400L).toString(),
    )

    override suspend fun generateRecoveryCode(): RecoveryCode = RecoveryCode(
        code = "DEMO-RECOVERY-CODE",
        createdAt = java.time.Instant.now().toString(),
    )

    private val demoModelSelection = ModelSelection(
        provider = "deepseek",
        model = "deepseek-v4",
        reasoningEffort = "medium",
    )

    override suspend fun modelCatalog(): ModelCatalog = ModelCatalog(
        supported = true,
        default = demoModelSelection,
        groups = listOf(
            ModelGroup(
                id = "deepseek",
                name = "DeepSeek",
                models = listOf(
                    ModelInfo(
                        id = "deepseek-v4",
                        name = "DeepSeek V4",
                        description = "Balanced general model",
                        reasoning = ModelReasoning(
                            efforts = listOf(
                                ModelEffort("off", "Off"),
                                ModelEffort("low", "Low"),
                                ModelEffort("medium", "Medium"),
                                ModelEffort("high", "High"),
                            ),
                            defaultEffort = "medium",
                        ),
                    ),
                    ModelInfo(
                        id = "deepseek-v4-flash",
                        name = "DeepSeek V4 Flash",
                        description = "Faster, cheaper",
                    ),
                ),
            ),
            ModelGroup(
                id = "anthropic",
                name = "Anthropic",
                models = listOf(
                    ModelInfo(
                        id = "claude-sonnet",
                        name = "Claude Sonnet",
                        reasoning = ModelReasoning(
                            efforts = listOf(ModelEffort("low", "Low"), ModelEffort("high", "High")),
                            defaultEffort = "high",
                        ),
                    ),
                ),
            ),
        ),
    )

    override suspend fun sessionModel(sessionId: String): SessionModel = SessionModel(
        supported = true,
        live = true,
        selection = demoModelSelection,
        source = MODEL_SOURCE_DEFAULT,
    )

    override suspend fun updateSessionModel(
        sessionId: String,
        provider: String,
        model: String,
        reasoningEffort: String?,
    ): SessionModel = SessionModel(
        supported = true,
        live = true,
        selection = ModelSelection(
            provider = provider,
            model = model,
            reasoningEffort = reasoningEffort?.takeIf { it.isNotBlank() },
        ),
        source = MODEL_SOURCE_SESSION,
    )

    private val demoHostQueue = mutableListOf(
        HostQueueItem(
            itemId = "demo-q1",
            text = "Run the release checks and fix any failures before tagging.",
            target = QUEUE_TARGET_NEXT_TURN,
            attachmentCount = 0,
        ),
        HostQueueItem(
            itemId = "demo-q2",
            text = "Then update the changelog with the user-facing changes.",
            target = QUEUE_TARGET_NEXT_STEP,
            attachmentCount = 2,
        ),
    )

    override suspend fun hostQueue(sessionId: String): HostQueue = HostQueue(
        supported = true,
        live = true,
        items = demoHostQueue.toList(),
    )

    override suspend fun sessionSkills(sessionId: String): HostSkills = HostSkills.Unsupported

    override suspend fun sessionTodos(sessionId: String): HostPlan = HostPlan.Unsupported

    override suspend fun sessionTeam(sessionId: String): HostTeam = HostTeam.Unsupported

    override suspend fun sessionTrajectory(
        sessionId: String,
        beforeSeq: Int?,
    ): HostTrajectoryPage = HostTrajectoryPage.Unsupported

    override suspend fun sessionFiles(sessionId: String, path: String): HostFilesPage =
        HostFilesPage.Unsupported

    override suspend fun sessionJobs(sessionId: String): HostJobs = HostJobs.Unsupported

    override suspend fun sessionContext(sessionId: String): HostContext = HostContext.Unsupported

    override suspend fun sessionFileContent(sessionId: String, path: String): HostFileContent =
        throw dev.dsh.mobile.net.DshApiException.Unsupported("No workspace files in demo mode.")

    override suspend fun downloadWorkspaceFileRaw(
        sessionId: String,
        path: String,
        fileName: String,
        cacheDir: File,
    ): File = throw dev.dsh.mobile.net.DshApiException.Unsupported("No workspace files in demo mode.")

    override suspend fun hostQueueAction(
        sessionId: String,
        itemId: String,
        action: String,
        text: String?,
    ) {
        val index = demoHostQueue.indexOfFirst { it.itemId == itemId }
        if (index < 0) {
            throw dev.dsh.mobile.net.DshApiException.NotFound("Queued prompt not found.")
        }
        when (action) {
            QUEUE_ACTION_EDIT -> {
                val replacement = text?.trim().orEmpty()
                if (replacement.isEmpty()) {
                    throw dev.dsh.mobile.net.DshApiException.InvalidRequest(
                        "An edited queued prompt can't be empty.",
                    )
                }
                demoHostQueue[index] = demoHostQueue[index].copy(text = replacement)
            }

            QUEUE_ACTION_REMOVE, QUEUE_ACTION_STEER -> demoHostQueue.removeAt(index)
        }
    }

    /** Demo fork seqs: message position in its session (1-based). */
    override fun messageSeq(messageId: String): Int? {
        if (messageId.isBlank()) return null
        demo.sessions.forEach { session ->
            session.messages.forEachIndexed { index, message ->
                if (message.id == messageId) return index + 1
            }
        }
        return null
    }

    override suspend fun forkSession(sessionId: String, atSeq: Int?): ForkResult {
        val source = demo.sessions.firstOrNull { it.id == sessionId }
            ?: throw dev.dsh.mobile.net.DshApiException.NotFound("Session not found.")
        if (atSeq != null && !forkSeqValid(atSeq)) {
            throw dev.dsh.mobile.net.DshApiException.InvalidRequest("Invalid fork boundary.")
        }
        demo.newSession(source.workspaceId, "Fork - ${source.title}")
        val created = demo.sessions.first()
        return ForkResult(
            sessionId = created.id,
            forkedFrom = sessionId,
            atSeq = atSeq,
        )
    }

    override suspend fun decideApproval(approvalId: String, allow: Boolean) {
        demo.answerApproval(allow)
    }

    override suspend fun answerQuestion(questionId: String, answer: String, optionIndex: Int?) = Unit

    override fun dispose() = Unit
}
