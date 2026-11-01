package dev.dsh.mobile.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Workspace(
    val id: String,
    val name: String,
    val path: String,
)

enum class SessionStatus { Working, NeedsInput, Idle }

enum class ToolState { Running, Done, Failed }

data class ToolActivity(
    val name: String,
    val detail: String,
    val state: ToolState,
    /** Optional host-capped text output; blank for hosts/tools without one. */
    val output: String = "",
)

sealed interface Message {
    val id: String
    val time: String

    /** Optional host event sequence (for jump-to-message); null for local/old rows. */
    val eventSeq: Int? get() = null

    /** Host attachments for a historical message; empty for local/optimistic rows. */
    val messageAttachments: List<MessageAttachment> get() = emptyList()
}

data class UserMessage(
    override val id: String,
    override val time: String,
    val text: String,
    /** Display names of attachments sent with this prompt (v0.2.2; client-side only). */
    val attachments: List<String> = emptyList(),
    /** Host attachment metadata for read-back; empty for locally composed messages. */
    override val messageAttachments: List<MessageAttachment> = emptyList(),
    override val eventSeq: Int? = null,
    /** True only when the host marked this prompt with source="schedule". */
    val scheduled: Boolean = false,
) : Message

data class AssistantMessage(
    override val id: String,
    override val time: String,
    val text: String = "",
    val streaming: Boolean = false,
    val tool: ToolActivity? = null,
    /** Host attachment metadata for read-back; empty for plain assistant text. */
    override val messageAttachments: List<MessageAttachment> = emptyList(),
    override val eventSeq: Int? = null,
) : Message

data class SystemNote(
    override val id: String,
    override val time: String,
    val text: String,
    override val eventSeq: Int? = null,
) : Message

data class Session(
    val id: String,
    val title: String,
    val workspaceId: String,
    val preview: String,
    val updated: String,
    val day: String,
    val status: SessionStatus = SessionStatus.Idle,
    val unread: Boolean = false,
    val messages: List<Message> = emptyList(),
    /** Optional session-list goal badge (frozen contract `SessionSummary.goal`). */
    val goalPhase: String? = null,
    val goalRoundsStarted: Int = 0,
    val goalMaxRounds: Int = 0,
    /** Optional goal objective for the Goals dashboard; null when the summary omits it. */
    val goalObjective: String? = null,
    /** Optional lineage/preset context (host 0.3.0+); null/blank for old hosts. */
    val parentSessionId: String? = null,
    val agentPreset: String? = null,
    /** Optional child-session delegation counts (host 0.3.4+); 0/0 for old hosts. */
    val delegationTotal: Int = 0,
    val delegationWorking: Int = 0,
)

data class Approval(
    val id: String,
    val sessionId: String,
    val title: String,
    val description: String,
    val command: String,
)

private val APPROVAL_TRIGGER = Regex(
    "deploy|publish|push|delete|remove|rm -rf|force|release",
    RegexOption.IGNORE_CASE,
)

class DemoState {
    val workspaces = listOf(
        Workspace("dsh", "DeepSeek Harness", "C:\\.dsh"),
        Workspace("demo", "Demo Project", "C:\\demo\\project-alpha"),
        Workspace("notes", "Research Notes", "C:\\demo\\notes"),
    )

    val sessions = mutableStateListOf<Session>()

    var activeSessionId by mutableStateOf<String?>(null)
        private set
    var searchQuery by mutableStateOf("")
        private set
    var selectedWorkspace by mutableStateOf<String?>(null)
        private set
    var showSettings by mutableStateOf(false)
        private set
    var showNewSession by mutableStateOf(false)
        private set
    var pendingApproval by mutableStateOf<Approval?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var replyJob: Job? = null
    private var counter = 0
    private var timeCursor = 0
    private val timeLabels = listOf(
        "09:41", "10:12", "11:03", "12:26", "13:48", "14:05", "15:12", "16:40", "17:22",
    )

    init {
        seed()
    }

    fun dispose() {
        scope.cancel()
    }

    // region navigation

    fun setSearch(query: String) {
        searchQuery = query
    }

    fun selectWorkspace(id: String?) {
        selectedWorkspace = id
    }

    fun openSession(id: String) {
        activeSessionId = id
        updateSession(id) { it.copy(unread = false) }
    }

    fun closeSession() {
        activeSessionId = null
    }

    fun openSettings() {
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
    }

    fun openNewSession() {
        showNewSession = true
    }

    fun dismissNewSession() {
        showNewSession = false
    }

    // endregion

    // region queries

    fun workspaceFor(id: String): Workspace =
        workspaces.firstOrNull { it.id == id } ?: workspaces.first()

    fun activeSession(): Session? =
        activeSessionId?.let { id -> sessions.firstOrNull { it.id == id } }

    fun filteredSessions(): List<Session> {
        val query = searchQuery.trim().lowercase()
        return sessions.filter { session ->
            val workspace = workspaceFor(session.workspaceId)
            val matchesWorkspace = selectedWorkspace == null || session.workspaceId == selectedWorkspace
            val matchesQuery = query.isEmpty() ||
                session.title.lowercase().contains(query) ||
                session.preview.lowercase().contains(query) ||
                workspace.name.lowercase().contains(query)
            matchesWorkspace && matchesQuery
        }
    }

    // endregion

    // region actions

    fun newSession(workspaceId: String, preset: String) {
        val id = nextId("s")
        val workspace = workspaceFor(workspaceId)
        val greeting = AssistantMessage(
            id = nextId("a"),
            time = nextTime(),
            text = "New **$preset** session in ${workspace.name}.\n\n" +
                "I'm running on your desktop with full access to `${workspace.path}`. " +
                "What should I work on?",
        )
        sessions.add(
            0,
            Session(
                id = id,
                title = "New session",
                workspaceId = workspaceId,
                preview = "Ready when you are.",
                updated = "Just now",
                day = "Today",
                messages = listOf(greeting),
            ),
        )
        showNewSession = false
        openSession(id)
    }

    fun sendMessage(raw: String) {
        val sessionId = activeSessionId ?: return
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        val text = raw.trim()
        if (text.isEmpty()) return

        appendMessage(sessionId, UserMessage(nextId("u"), nextTime(), text))
        updateSession(sessionId) {
            it.copy(
                status = SessionStatus.Working,
                preview = text,
                updated = "Just now",
                day = "Today",
            )
        }

        if (APPROVAL_TRIGGER.containsMatchIn(text)) {
            replyJob?.cancel()
            replyJob = scope.launch {
                delay(500)
                appendMessage(
                    sessionId,
                    AssistantMessage(
                        id = nextId("a"),
                        time = nextTime(),
                        text = "That command changes something outside the workspace, so I need your approval before I run it.",
                    ),
                )
                pendingApproval = Approval(
                    id = nextId("approval"),
                    sessionId = sessionId,
                    title = "Permission needed: run command",
                    description = "The agent wants to run a command in ${workspaceFor(session.workspaceId).path}.",
                    command = if (text.contains("deploy", true) || text.contains("publish", true)) {
                        "pwsh -File tools/publish.mjs --patch"
                    } else {
                        "git push origin main"
                    },
                )
                updateSession(sessionId) { it.copy(status = SessionStatus.NeedsInput) }
            }
        } else {
            streamReply(sessionId, text)
        }
    }

    fun answerApproval(approved: Boolean) {
        val approval = pendingApproval ?: return
        pendingApproval = null
        val sessionId = approval.sessionId

        appendMessage(
            sessionId,
            SystemNote(
                id = nextId("sys"),
                time = nextTime(),
                text = if (approved) "You approved: ${approval.command}" else "You denied: ${approval.command}",
            ),
        )

        if (!approved) {
            appendMessage(
                sessionId,
                AssistantMessage(
                    id = nextId("a"),
                    time = nextTime(),
                    text = "Understood - I won't run it. I can prepare a dry run instead if you want.",
                ),
            )
            updateSession(sessionId) { it.copy(status = SessionStatus.Idle) }
            return
        }

        replyJob?.cancel()
        replyJob = scope.launch {
            appendMessage(
                sessionId,
                AssistantMessage(id = nextId("a"), time = nextTime(), text = "Thanks - running it now."),
            )
            delay(400)
            val toolId = nextId("tool")
            appendMessage(
                sessionId,
                AssistantMessage(
                    id = toolId,
                    time = nextTime(),
                    tool = ToolActivity("Bash", approval.command, ToolState.Running),
                ),
            )
            delay(1500)
            replaceMessage(sessionId, toolId) { message ->
                if (message is AssistantMessage) {
                    message.copy(tool = ToolActivity("Bash", approval.command, ToolState.Done))
                } else {
                    message
                }
            }
            appendMessage(
                sessionId,
                AssistantMessage(
                    id = nextId("a"),
                    time = nextTime(),
                    text = "Done. Published **v0.4.7** and updated the changelog.\n\n" +
                        "```\npublished v0.4.7   ->   3 assets\nchangelog           ->   2 entries\n```",
                ),
            )
            updateSession(sessionId) {
                it.copy(
                    status = SessionStatus.Idle,
                    preview = "Done. Published v0.4.7 and updated the changelog.",
                    updated = "Just now",
                )
            }
        }
    }

    fun stopWorking() {
        replyJob?.cancel()
        replyJob = null
        val sessionId = activeSessionId ?: return
        updateSession(sessionId) { session ->
            session.copy(
                status = SessionStatus.Idle,
                messages = session.messages.map { message ->
                    if (message is AssistantMessage && message.streaming) {
                        message.copy(streaming = false)
                    } else {
                        message
                    }
                },
            )
        }
        appendMessage(
            sessionId,
            SystemNote(nextId("sys"), nextTime(), "You stopped this turn."),
        )
    }

    // endregion

    // region mock reply engine

    private fun streamReply(sessionId: String, prompt: String) {
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(300)
            val messageId = nextId("a")
            appendMessage(
                sessionId,
                AssistantMessage(messageId, nextTime(), streaming = true),
            )

            delay(450)
            val toolId = nextId("tool")
            appendMessage(
                sessionId,
                AssistantMessage(
                    id = toolId,
                    time = nextTime(),
                    tool = ToolActivity("Read", "package.json - 42 lines", ToolState.Running),
                ),
            )
            delay(850)
            replaceMessage(sessionId, toolId) { message ->
                if (message is AssistantMessage) {
                    message.copy(tool = ToolActivity("Read", "package.json - 42 lines", ToolState.Done))
                } else {
                    message
                }
            }

            val reply = replyFor(prompt)
            val builder = StringBuilder()
            for (chunk in reply.chunked(5)) {
                builder.append(chunk)
                val current = builder.toString()
                replaceMessage(sessionId, messageId) { message ->
                    if (message is AssistantMessage) message.copy(text = current) else message
                }
                delay(22)
            }

            replaceMessage(sessionId, messageId) { message ->
                if (message is AssistantMessage) message.copy(streaming = false) else message
            }
            updateSession(sessionId) {
                it.copy(
                    status = SessionStatus.Idle,
                    preview = reply.lineSequence().firstOrNull().orEmpty().take(90),
                    updated = "Just now",
                )
            }
        }
    }

    private fun replyFor(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            "test" in lower -> {
                "Ran the suite - 128 passed, 0 failed. The flaky one from yesterday didn't reappear.\n\n" +
                    "```\nTest Files  14 passed (14)\nTests       128 passed (128)\nDuration    41.2s\n```\n\n" +
                    "Want me to commit this and open a PR, or keep going on the economy page?"
            }

            "status" in lower || "where" in lower -> {
                "Here's where things stand: the working tree is clean, the last build passed, and the only open question " +
                    "is whether to cut the release now or after the economy page lands.\n\n" +
                    "I'd lean toward shipping the release today and folding the page into the next patch."
            }

            else -> {
                "On it. I'll work in your workspace on the desktop and keep this session updated as I go.\n\n" +
                    "Plan:\n1. Inspect the current state\n2. Make the change and run the checks\n3. Report back with a short diff summary\n\n" +
                    "You can close the app - I'll keep going, and I'll ask before doing anything destructive."
            }
        }
    }

    // endregion

    // region helpers

    private fun nextId(prefix: String): String = "$prefix-${++counter}"

    private fun nextTime(): String {
        val value = timeLabels[timeCursor % timeLabels.size]
        timeCursor++
        return value
    }

    private fun updateSession(id: String, transform: (Session) -> Session) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index >= 0) {
            sessions[index] = transform(sessions[index])
        }
    }

    private fun appendMessage(sessionId: String, message: Message) {
        updateSession(sessionId) { it.copy(messages = it.messages + message) }
    }

    private fun replaceMessage(sessionId: String, messageId: String, transform: (Message) -> Message) {
        updateSession(sessionId) { session ->
            session.copy(
                messages = session.messages.map { message ->
                    if (message.id == messageId) transform(message) else message
                },
            )
        }
    }

    private fun seed() {
        sessions.addAll(
            listOf(
                Session(
                    id = "s-release",
                    title = "Cut a patch release",
                    workspaceId = "dsh",
                    preview = "All 128 tests passed. The changelog is drafted - publish now?",
                    updated = "2m",
                    day = "Today",
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "09:41",
                            "Cut a patch release and update the changelog. Push when green.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "09:42",
                            "On it - bumping the version, running the full suite, then I'll draft the changelog entry before anything gets published.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "09:44",
                            "",
                            tool = ToolActivity("Bash", "pwsh -File tools/test.ps1 -Jobs 2", ToolState.Done),
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "09:47",
                            "All **128 tests passed**. The changelog entry is drafted and the release is staged - say the word and I'll publish.",
                        ),
                    ),
                ),
                Session(
                    id = "s-economy",
                    title = "Economy page copy pass",
                    workspaceId = "demo",
                    preview = "Every currency row now names one source and one sink.",
                    updated = "18m",
                    day = "Today",
                    status = SessionStatus.Working,
                    unread = true,
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "09:02",
                            "Tighten the economy page copy. One sentence per direction, no placeholders.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "09:15",
                            "Done. Six currencies, each row names where it comes from and what takes it. I also removed the last two placeholder lines and added a test that proves every currency is covered exactly once.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "09:16",
                            "",
                            tool = ToolActivity("Edit", "src/ui/economy.ts - +18 -11", ToolState.Done),
                        ),
                    ),
                ),
                Session(
                    id = "s-android",
                    title = "Android stream teardown",
                    workspaceId = "notes",
                    preview = "The emulator stream needs a bounded reconnect loop.",
                    updated = "1h",
                    day = "Today",
                    status = SessionStatus.NeedsInput,
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "08:37",
                            "The device stream keeps dropping after the screen rotates. Find the root cause but don't change anything yet.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "08:52",
                            "Found it: the stream reconnect loop has no backoff and races the activity recreation. I can fix it with a bounded, cancellable retry, but I need direction on whether to keep the stream alive across rotation or restart it cleanly.",
                        ),
                    ),
                ),
                Session(
                    id = "s-search",
                    title = "Session search bug",
                    workspaceId = "dsh",
                    preview = "Search misses titles after the workspace rename.",
                    updated = "3h",
                    day = "Today",
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "07:59",
                            "Search for \"release\" doesn't find the session I renamed. Fix it.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "08:10",
                            "Fixed. The index cached the old title and never invalidated on rename. Added a regression test that renames a session and searches for the new title.",
                        ),
                    ),
                ),
                Session(
                    id = "s-worktree",
                    title = "Worktree cleanup",
                    workspaceId = "demo",
                    preview = "Removed 3 stale worktrees, kept the codex branch.",
                    updated = "Yesterday",
                    day = "Yesterday",
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "Yesterday",
                            "Clean up the stale worktrees but keep the one with uncommitted changes.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "Yesterday",
                            "Removed 3 stale worktrees. Kept `codex/help-search` because it has one uncommitted file; I left a note in the session.",
                        ),
                    ),
                ),
                Session(
                    id = "s-notes",
                    title = "Release notes draft",
                    workspaceId = "dsh",
                    preview = "Drafted notes for v0.4.7 - two paragraphs and a changelog block.",
                    updated = "Yesterday",
                    day = "Yesterday",
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "Yesterday",
                            "Draft the release notes for v0.4.7. Keep it short.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "Yesterday",
                            "Drafted. Two paragraphs and a changelog block, no marketing fluff. It's sitting in `build/release-notes.md`.",
                        ),
                    ),
                ),
                Session(
                    id = "s-world",
                    title = "Fix world_sim test",
                    workspaceId = "notes",
                    preview = "The seeded RNG drift is fixed; suite is green again.",
                    updated = "Mon",
                    day = "Earlier",
                    messages = listOf(
                        UserMessage(
                            nextId("u"),
                            "Mon",
                            "The world_sim test is flaky under -Jobs 2. Fix it.",
                        ),
                        AssistantMessage(
                            nextId("a"),
                            "Mon",
                            "Root cause was shared mutable RNG state across parallel cases. Each case now gets its own seeded state; 20 consecutive runs are green.",
                        ),
                    ),
                ),
            ),
        )
    }

    // endregion
}
