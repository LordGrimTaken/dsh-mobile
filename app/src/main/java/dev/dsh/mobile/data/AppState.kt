package dev.dsh.mobile.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import dev.dsh.mobile.BackgroundSyncService
import dev.dsh.mobile.DshNotifications
import dev.dsh.mobile.net.ConnectivityMonitor
import dev.dsh.mobile.net.DshApiClient
import dev.dsh.mobile.net.DshApiException
import dev.dsh.mobile.net.DshJson
import dev.dsh.mobile.net.HealthDto
import dev.dsh.mobile.net.PairMethodsDto
import dev.dsh.mobile.net.RecoveryPairRequest
import dev.dsh.mobile.net.TailscalePairRequest
import dev.dsh.mobile.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide state holder. Owns:
 * - persisted host profiles (base URL + encrypted device token per host) and the active host id,
 * - the active [DshRepository]: [MockRepository] while demo mode is on or no host is paired,
 *   [RemoteRepository] for the active live host,
 * - navigation state, pairing flow state and the welcome/onboarding flag.
 *
 * Screens read plain properties; data lists they see are Compose snapshot lists from the
 * repository, so live SSE updates recompose exactly like before.
 */
class AppState(context: Context) {

    private val appContext = context.applicationContext
    private val legacyStore = PairingStore(appContext)
    private val hostStore = HostProfileStore(
        store = SharedPreferencesHostKeyValueStore(appContext),
        cipher = AndroidKeystoreTokenCipher(),
    )
    private val draftStore = DraftStore(appContext)
    private val promptHistory = PromptHistoryStore(appContext)
    private val promptSnippets = PromptSnippetStore(appContext)
    private val pinnedStore = PinnedSessionsStore(appContext)
    var pinnedSessionIds: Set<String> by mutableStateOf(pinnedStore.load())
        private set
    private val presentationPrefs = appContext.getSharedPreferences("dsh-presentation", Context.MODE_PRIVATE)
    var darkTheme: Boolean by mutableStateOf(presentationPrefs.getBoolean("dark_theme", true))
        private set
    var amoledDark: Boolean by mutableStateOf(
        presentationPrefs.getBoolean("amoled_dark", false),
    )
        private set
    var themeAccent: ThemeAccent by mutableStateOf(
        themeAccentFromStorage(presentationPrefs.getString(ACCENT_PREF_KEY, null)),
    )
        private set
    var chatFontScale: Float by mutableStateOf(
        presentationPrefs.getFloat("chat_font_scale", 1f),
    )
        private set

    /** App-wide interface text scale (0.9 / 1.0 / 1.15 / 1.3); chat text scales on top of this. */
    var appFontScale: Float by mutableStateOf(
        presentationPrefs.getFloat("app_font_scale", 1f),
    )
        private set
    var wrapCodeBlocks: Boolean by mutableStateOf(
        presentationPrefs.getBoolean("wrap_code_blocks", false),
    )
        private set
    var notifyInput: Boolean by mutableStateOf(presentationPrefs.getBoolean("notify_input", true))
        private set
    var notifyDone: Boolean by mutableStateOf(presentationPrefs.getBoolean("notify_done", true))
        private set
    var notifyGoal: Boolean by mutableStateOf(presentationPrefs.getBoolean("notify_goal", true))
        private set
    var lowBalanceAlertsEnabled: Boolean by mutableStateOf(
        presentationPrefs.getBoolean("low_balance_alerts", true),
    )
        private set
    var lowBalanceThreshold: Double by mutableStateOf(
        presentationPrefs.getFloat("low_balance_threshold", 5f).toDouble(),
    )
        private set
    private var mutedNotificationSessions by mutableStateOf(
        presentationPrefs.getStringSet("muted_notifications", emptySet())?.toSet() ?: emptySet(),
    )
    var voicePreferOffline: Boolean by mutableStateOf(
        presentationPrefs.getBoolean("voice_prefer_offline", false),
    )
        private set
    var voiceLanguageTag: String by mutableStateOf(
        presentationPrefs.getString("voice_language", "").orEmpty(),
    )
        private set
    var quietHoursEnabled: Boolean by mutableStateOf(
        presentationPrefs.getBoolean("quiet_hours", false),
    )
        private set
    var quietStartHour: Int by mutableIntStateOf(
        presentationPrefs.getInt("quiet_start_hour", 22),
    )
        private set
    var quietEndHour: Int by mutableIntStateOf(
        presentationPrefs.getInt("quiet_end_hour", 8),
    )
        private set
    var appLockEnabled: Boolean by mutableStateOf(presentationPrefs.getBoolean("app_lock", false))
        private set
    var appLockDelaySeconds: Int by mutableIntStateOf(
        presentationPrefs.getInt("app_lock_delay_seconds", 0),
    )
        private set
    var backgroundSyncEnabled: Boolean by mutableStateOf(presentationPrefs.getBoolean("background_sync", false))
        private set
    var backgroundSyncIntervalSeconds: Int by mutableIntStateOf(
        presentationPrefs.getInt("background_sync_interval", 20),
    )
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val demo = DemoState()
    private val mockRepository = MockRepository(demo)
    private var remoteRepository: RemoteRepository? = null
    private var goalEventsJob: Job? = null

    /** Sideload auto-updater: feed check, download, SHA-256 verify, package-installer handoff. */
    val updates = UpdateManager(appContext, scope)

    private val initialHosts: HostProfilesSnapshot = bootstrapHosts()

    // region hosts / connection

    /** All paired hosts, newest pairing first (the active one may be anywhere in the list). */
    var profiles: List<HostProfile> by mutableStateOf(initialHosts.profiles)
        private set

    var activeHostId: String? by mutableStateOf(initialHosts.activeId)
        private set

    /** First-run welcome screen: no host paired yet and onboarding not dismissed. */
    var showWelcome: Boolean by mutableStateOf(
        initialHosts.profiles.isEmpty() && !hostStore.isWelcomeDismissed(),
    )
        private set

    var showHosts: Boolean by mutableStateOf(false)
        private set

    /** Label pre-filled by the welcome screen ("Desktop" / "Cloud"); empty for a plain add. */
    var pairingLabelDefault: String by mutableStateOf("")
        private set

    var showPairing: Boolean by mutableStateOf(false)
        private set

    var pairingBusy: Boolean by mutableStateOf(false)
        private set

    var pairingError: String? by mutableStateOf(null)
        private set

    /** Version reported by the active host's `GET /health` (null until reachable). */
    var activeHostVersion: String? by mutableStateOf(null)
        private set

    /** Explicit "use demo data" toggle. Forced on whenever no host is paired. */
    var useDemoData: Boolean by mutableStateOf(
        if (initialHosts.profiles.isEmpty()) true else legacyStore.loadUseDemoData(),
    )
        private set

    /** Last non-fatal sync/action failure, shown above the composer in live mode. */
    var syncError: String? by mutableStateOf(null)
        private set

    /**
     * Approvals the user just tried to answer but the host already answered elsewhere
     * (first-answer-wins). Filtered out of [pendingApproval]/[inboxApprovals] immediately so a
     * stale card can't be answered twice; the following refresh is authoritative.
     */
    private val dismissedApprovalIds = mutableStateListOf<String>()

    /**
     * Questions the user just tried to answer but the host already answered elsewhere (or the
     * batch row expired). Only the failed id is hidden; the remaining questions of a
     * multi-question batch stay visible. The following refresh is authoritative.
     */
    private val dismissedQuestionIds = mutableStateListOf<String>()

    /** Question ids with an answer request in flight (double-send guard + Send button state). */
    private val answeringQuestionIds = mutableStateListOf<String>()

    /** Historical attachment ids with a download/open in flight (dedupe + chip spinner). */
    private val openingAttachmentIds = mutableStateListOf<String>()

    /** Cache file names currently being opened/downloaded; the prune must never delete them. */
    private val protectedAttachmentFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True while a user-initiated refresh is in flight (drives the pull-to-refresh spinner). */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    /** Latest usage snapshot for the active host (token totals + optional API balance). */
    var usage: HostUsage? by mutableStateOf(null)
        private set
    var usageLoading: Boolean by mutableStateOf(false)
        private set
    var usageError: String? by mutableStateOf(null)
        private set

    /** First token total observed today; today's usage is the growth since this baseline. */
    private var usageDayBaseline by mutableLongStateOf(
        presentationPrefs.getLong("usage_day_baseline", 0L),
    )
    private var usageDayDate: String by mutableStateOf(
        presentationPrefs.getString("usage_day_date", "").orEmpty(),
    )

    /** Approximate tokens used today (growth of host totals since the first sync today). */
    val todayTokens: Long
        get() = ((usage?.totals?.totalTokens ?: 0L) - usageDayBaseline).coerceAtLeast(0L)

    /** Prompts queued while the agent was working, keyed by session id. */
    private val queuedPromptStore = QueuedPromptStore(appContext)
    private val queuedPrompts = mutableStateMapOf<String, List<QueuedPrompt>>().apply {
        putAll(queuedPromptStore.load())
    }
    private val flushingSessions = mutableSetOf<String>()

    /** Local display-name aliases; the host title is never modified. */
    private val aliasStore = SessionAliasStore(appContext)
    private val sessionAliases = mutableStateMapOf<String, String>().apply {
        putAll(aliasStore.load())
    }

    private fun persistQueuedPrompts() {
        queuedPromptStore.save(queuedPrompts)
    }

    val activeProfile: HostProfile?
        get() = activeHostId?.let { id -> profiles.firstOrNull { it.id == id } }

    val isLive: Boolean get() = activeProfile != null && !useDemoData
    val connection: ConnectionStatus get() = if (isLive) repository.connection else ConnectionStatus.Demo
    val hostLabel: String? get() = activeProfile?.label?.takeIf { it.isNotBlank() }
    val pairedAtLabel: String? get() = activeProfile?.pairedAt?.takeIf { it.isNotBlank() }
    val hostCount: Int get() = profiles.size

    /** Epoch millis of the last successful background-sync poll, if the service has run. */
    val backgroundSyncLastAt: Long?
        get() = presentationPrefs.getLong("background_sync_last_at", 0L).takeIf { it > 0L }

    val backgroundSyncLastError: String?
        get() = presentationPrefs.getString("background_sync_last_error", null)

    /** Last successful HTTP snapshot refresh; used for the "updated HH:mm" hint. */
    val lastRefreshedAt: Long? get() = if (isLive) remoteRepository?.lastSyncedAt else null

    // region connection resilience / diagnostics (read-only for ConnectionDiagnosticsScreen)

    /** Whether the system currently has a default network (VPN included). */
    var networkAvailable: Boolean by mutableStateOf(false)
        private set

    /** Default-network transport: "VPN" / "Wi-Fi" / "Cellular" / "Ethernet" / "Other" / "None". */
    var networkTransport: String by mutableStateOf("None")
        private set

    /** Automatic SSE reconnects since launch (resume heal, network change, watchdog). */
    val reconnectAttempts: Int get() = if (isLive) remoteRepository?.reconnectAttempts ?: 0 else 0

    /** Wall-clock time of the most recent SSE reconnect attempt, or null before the first one. */
    val lastReconnectAt: Long? get() = if (isLive) remoteRepository?.lastReconnectAt else null

    /** Wall-clock time of the last SSE frame, including the host's 20 s heartbeat pings. */
    val lastStreamEventAt: Long? get() = if (isLive) remoteRepository?.lastEventAt else null

    /** True between Activity ON_START and ON_STOP; used to avoid duplicate foreground notifications. */
    var appInForeground: Boolean by mutableStateOf(false)
        private set

    /** Most recent connection lifecycle events, newest first, capped for the diagnostics screen. */
    private val connectionEventState = mutableStateListOf<ConnectionEvent>()
    val connectionEvents: List<ConnectionEvent> get() = connectionEventState

    private fun recordConnectionEvent(message: String) {
        connectionEventState.add(0, ConnectionEvent(System.currentTimeMillis(), message))
        while (connectionEventState.size > MAX_CONNECTION_EVENTS) {
            connectionEventState.removeAt(connectionEventState.lastIndex)
        }
    }

    private val connectivityMonitor = ConnectivityMonitor(appContext) { onNetworkChanged() }
    private var networkReconnectJob: Job? = null
    private var lastAutoReconnectAt = 0L
    private var backgroundedAt: Long? = null

    // endregion

    // endregion

    // region navigation

    var activeSessionId: String? by mutableStateOf(null)
        private set
    var searchQuery: String by mutableStateOf("")
        private set
    var statusFilter: String? by mutableStateOf(presentationPrefs.getString("status_filter", null))
        private set
    var sessionSort: SessionSort by mutableStateOf(
        runCatching {
            SessionSort.valueOf(
                presentationPrefs.getString("session_sort", SessionSort.ACTIVITY.name).orEmpty(),
            )
        }.getOrDefault(SessionSort.ACTIVITY),
    )
    var selectedWorkspace: String? by mutableStateOf(null)
        private set
    var showSettings: Boolean by mutableStateOf(false)
        private set
    var showUsage: Boolean by mutableStateOf(false)
        private set
    var showInbox: Boolean by mutableStateOf(false)
        private set
    var showGoals: Boolean by mutableStateOf(false)
        private set
    var showSchedules: Boolean by mutableStateOf(false)
        private set
    var showHiddenSessions: Boolean by mutableStateOf(false)
        private set
    var showArchivedSessions: Boolean by mutableStateOf(false)
        private set
    var showHostSearch: Boolean by mutableStateOf(false)
        private set
    var showConnectionDiagnostics: Boolean by mutableStateOf(false)
        private set
    var hiddenSessionIds: Set<String> by mutableStateOf(emptySet())
        private set
    var showNewSession: Boolean by mutableStateOf(false)
        private set

    // These delegated properties are written by startRemote(), which the init block below calls
    // when a host is already paired. Kotlin initializes properties in declaration order, so any
    // delegated property touched during startRemote() MUST be declared before the init block;
    // otherwise its delegate is still null and construction crashes with an NPE.
    /** Most recent live goal change; observed by App.kt to post goal notifications. */
    var lastGoalChange: GoalChanged? by mutableStateOf(null)
        private set

    /** Host preset roster; null until the new-session sheet first loads it. */
    var presetRoster: PresetRoster? by mutableStateOf(null)
        private set
    var presetsLoading: Boolean by mutableStateOf(false)
        private set

    /** Armed by a host-search hit; consumed by the chat once the jump is applied/fails. */
    var messageJumpTarget: MessageJumpTarget? by mutableStateOf(null)
        private set

    // endregion

    init {
        if (isLive) startRemote()
        if (activeProfile != null) fetchActiveHostHealth()
        connectivityMonitor.start()
        syncNetworkDiagnostics()
        // Trim the read-back attachment cache off the main thread; failures stay silent.
        pruneAttachmentCacheAsync()
    }

    // region data

    private val repository: DshRepository
        get() = if (isLive) remoteRepository ?: mockRepository else mockRepository

    val workspaces: List<Workspace> get() = repository.workspaces
    val sessions: List<Session> get() = repository.sessions
    val pendingApproval: Approval?
        get() = repository.approvals.firstOrNull {
            it.sessionId == activeSessionId && it.id !in dismissedApprovalIds
        }
    val pendingQuestion: Question?
        get() = repository.questions.firstOrNull {
            it.sessionId == activeSessionId && it.id !in dismissedQuestionIds
        }

    fun workspaceFor(id: String): Workspace =
        workspaces.firstOrNull { it.id == id }
            ?: workspaces.firstOrNull()
            ?: Workspace(id = id, name = "Unknown workspace", path = "")

    fun activeSession(): Session? =
        activeSessionId?.let { id -> sessions.firstOrNull { it.id == id } }

    /** Local display name for a session, or null when the host title should be shown. */
    fun aliasFor(sessionId: String): String? = sessionAliases[sessionId]?.takeIf { it.isNotBlank() }

    /** Title shown in lists and the chat header (alias if set, host title otherwise). */
    fun displayTitle(session: Session): String = aliasFor(session.id) ?: session.title

    /** Sets/clears a local display name; blank clears the alias. */
    fun setSessionAlias(sessionId: String, alias: String) {
        if (sessionId.isBlank()) return
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) {
            sessionAliases.remove(sessionId)
        } else {
            sessionAliases[sessionId] = trimmed
        }
        aliasStore.save(sessionAliases.toMap())
    }

    fun filteredSessions(): List<Session> {
        val query = searchQuery.trim().lowercase()
        val filtered = visibleSessions().filter { session ->
            val workspace = workspaceFor(session.workspaceId)
            val matchesWorkspace =
                selectedWorkspace == null || session.workspaceId == selectedWorkspace
            val matchesQuery = query.isEmpty() ||
                session.title.lowercase().contains(query) ||
                session.preview.lowercase().contains(query) ||
                aliasFor(session.id)?.lowercase()?.contains(query) == true ||
                workspace.name.lowercase().contains(query)
            val matchesStatus = when (statusFilter) {
                "working" -> session.status == SessionStatus.Working
                "idle" -> session.status != SessionStatus.Working
                "unread" -> session.unread
                else -> true
            }
            matchesWorkspace && matchesQuery && matchesStatus
        }
        val ordered = when (sessionSort) {
            SessionSort.ACTIVITY -> filtered
            SessionSort.TITLE -> filtered.sortedBy { displayTitle(it).lowercase() }
            SessionSort.STATUS -> filtered.sortedWith(
                compareBy<Session> {
                    when {
                        it.status == SessionStatus.Working -> 0
                        it.unread -> 1
                        else -> 2
                    }
                }.thenBy { displayTitle(it).lowercase() },
            )
        }
        // Pinned sessions stay at the top; the stable sort keeps the chosen order inside each group.
        return ordered.sortedByDescending { it.id in pinnedSessionIds }
    }

    fun updateSessionSort(sort: SessionSort) {
        sessionSort = sort
        presentationPrefs.edit().putString("session_sort", sort.name).apply()
    }

    fun togglePinnedSession(sessionId: String) {
        if (sessionId.isBlank()) return
        val next = if (sessionId in pinnedSessionIds) {
            pinnedSessionIds - sessionId
        } else {
            pinnedSessionIds + sessionId
        }
        pinnedSessionIds = next
        pinnedStore.save(next)
    }

    /**
     * Sessions the user may see: the v0.2.3 host hides subagent/internal sessions, and this
     * client-side safety net keeps an older host from reintroducing bare-UUID entries into any
     * list (ledger, drawer, counts).
     */
    fun visibleSessions(): List<Session> =
        sessions.filter { SessionVisibility.isUserSession(it.id) }

    // endregion

    // region navigation actions

    fun setSearch(query: String) {
        searchQuery = query
    }

    fun selectWorkspace(id: String?) {
        selectedWorkspace = id
    }

    fun updateStatusFilter(filter: String?) {
        statusFilter = filter
        presentationPrefs.edit().putString("status_filter", filter).apply()
    }

    var transcriptLoadingSessionId: String? by mutableStateOf(null)
        private set

    fun openSession(id: String) {
        // Opening a chat always leaves auxiliary screens (e.g. Goals) so a deep link or
        // notification cannot leave the chat hidden behind an overlay.
        showGoals = false
        // A target only ever applies to the session it came from.
        if (!messageJumpTargetMatches(messageJumpTarget, id)) messageJumpTarget = null
        activeSessionId = id
        repository.openSession(id)
        if (isLive) {
            transcriptLoadingSessionId = id
            scope.launch {
                try {
                    runCatching { repository.loadMessages(id) }
                        .onFailure { syncError = errorMessage(it) }
                } finally {
                    if (transcriptLoadingSessionId == id) transcriptLoadingSessionId = null
                }
            }
        }
    }

    /** True while the first transcript page for a session is being fetched. */
    fun isTranscriptLoading(sessionId: String): Boolean = transcriptLoadingSessionId == sessionId

    /** Opens a session from a host-search hit and arms the bounded jump-to-message. */
    fun openSearchHit(sessionId: String, seq: Int?, messageId: String) {
        val target = messageJumpTargetForSearchHit(sessionId, seq, messageId)
        closeHostSearch()
        messageJumpTarget = target
        openSession(sessionId)
    }

    /** Target belonging to [sessionId] only (never leaks across sessions). */
    fun messageJumpTargetFor(sessionId: String): MessageJumpTarget? =
        messageJumpTarget?.takeIf { it.sessionId == sessionId }

    /** Clears the target after applying it, or after giving up (not found/failed). */
    fun consumeMessageJumpTarget() {
        messageJumpTarget = null
    }

    /** Whether the host reported another older transcript page for this session. */
    fun hasOlderMessages(sessionId: String): Boolean =
        isLive && repository.hasOlderMessages(sessionId)

    // region goal controls

    private var goalStateSessionId: String? = null
    var goalState: SessionGoalState? by mutableStateOf(null)
        private set

    /** Most recent live goal change; observed by App.kt to post goal notifications. */
    // Declared near the top of the class because startRemote() (called from init) writes it.

    var goalLoading: Boolean by mutableStateOf(false)
        private set
    var goalError: String? by mutableStateOf(null)
        private set

    /** Goal state only applies to the session it was loaded for. */
    fun goalFor(sessionId: String): SessionGoalState? =
        if (goalStateSessionId == sessionId) goalState else null

    fun goalErrorFor(sessionId: String): String? =
        if (goalStateSessionId == sessionId) goalError else null

    fun goalLoadingFor(sessionId: String): Boolean =
        goalStateSessionId == sessionId && goalLoading

    fun offlineCacheStats(): OfflineCacheStats = HostTranscriptCache.stats(appContext)

    /** Clears cache-first transcript and session-list caches; host data itself is untouched. */
    fun clearOfflineCaches() {
        HostTranscriptCache.clearAll(appContext)
        HostSnapshotCache.clearAll(appContext)
    }

    fun refreshGoal(sessionId: String) {
        if (!isLive) {
            goalStateSessionId = sessionId
            goalState = SessionGoalState.Unsupported
            return
        }
        if (goalLoading && goalStateSessionId == sessionId) return
        goalStateSessionId = sessionId
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.goal(sessionId)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    fun resumeGoal(sessionId: String) {
        if (!isLive) return
        goalStateSessionId = sessionId
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.resumeGoal(sessionId)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    fun pauseGoal(sessionId: String) {
        if (!isLive) return
        goalStateSessionId = sessionId
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.pauseGoal(sessionId)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    /**
     * Marks the current goal complete, mirroring [pauseGoal]'s per-session loading/error handling.
     * [goalId]/[revision] are the ref the banner saw so the host can reject a stale action.
     */
    fun completeGoal(sessionId: String, goalId: String? = null, revision: Int? = null) {
        if (!isLive) return
        goalStateSessionId = sessionId
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.completeGoal(sessionId, goalId, revision)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    fun createGoal(sessionId: String, objective: String, maxGoalRounds: Int? = null) {
        if (!isLive) return
        val trimmed = objective.trim()
        if (trimmed.isEmpty()) {
            goalError = "Goal objective must not be empty"
            return
        }
        goalStateSessionId = sessionId
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.createGoal(sessionId, trimmed, maxGoalRounds)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    /**
     * Edits the current goal (objective and/or max rounds) with the same loading/error pattern as
     * [createGoal]. Null/non-positive values are rejected before the request is sent.
     */
    fun editGoal(sessionId: String, objective: String?, maxGoalRounds: Int?) {
        if (!isLive) return
        val trimmedObjective = objective?.trim()
        goalStateSessionId = sessionId
        if (trimmedObjective != null && trimmedObjective.isEmpty()) {
            goalError = "Goal objective must not be empty"
            return
        }
        if (maxGoalRounds != null && maxGoalRounds < 1) {
            goalError = "Max rounds must be a positive number"
            return
        }
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.editGoal(sessionId, trimmedObjective, maxGoalRounds)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    /** Clears the current goal; a successful response carries `goal = null`. */
    fun clearGoal(sessionId: String) {
        if (!isLive) return
        goalStateSessionId = sessionId
        goalLoading = true
        goalError = null
        scope.launch {
            try {
                val state = repository.clearGoal(sessionId)
                if (goalStateSessionId == sessionId) goalState = state
            } catch (error: Exception) {
                if (goalStateSessionId == sessionId) goalError = errorMessage(error)
            } finally {
                if (goalStateSessionId == sessionId) goalLoading = false
            }
        }
    }

    // endregion
    fun continueSession(sessionId: String) {
        if (sessionId.isBlank()) return
        if (sessions.firstOrNull { it.id == sessionId }?.status == SessionStatus.Working) return
        openSession(sessionId)
        scope.launch {
            delay(150)
            try {
                repository.sendPrompt(sessionId, "continue", emptyList())
                syncError = null
            } catch (error: Exception) {
                syncError = errorMessage(error)
            }
        }
    }

    /** Loads the next older transcript page (pagination is one page at a time). */
    suspend fun loadOlderMessages(sessionId: String): Boolean {
        if (!isLive) return false
        return try {
            repository.loadOlderMessages(sessionId)
        } catch (error: Exception) {
            syncError = errorMessage(error)
            false
        }
    }

    fun closeSession() {
        messageJumpTarget = null
        activeSessionId = null
    }

    /** Hides a session from the host's curated list, then closes it if it was open. */
    fun hideSession(sessionId: String) {
        scope.launch {
            try {
                repository.hideSession(sessionId)
                if (activeSessionId == sessionId) activeSessionId = null
                hiddenSessionIds = hiddenSessionIds + sessionId
                syncError = null
                refreshNow()
            } catch (error: Exception) {
                syncError = errorMessage(error)
            }
        }
    }

    fun openSettings() {
        showSettings = true
    }

    fun closeSettings() {
        showSettings = false
        // One-time recovery material must not survive leaving Settings.
        recoveryCode = null
        recoveryError = null
    }

    fun openUsage() {
        showSettings = false
        showUsage = true
        refreshUsage()
    }

    fun closeUsage() {
        showUsage = false
    }

    /** Pending approvals and questions across sessions, for the inbox triage screen. */
    val inboxApprovals: List<Approval>
        get() = repository.approvals.filter { it.id !in dismissedApprovalIds }
    val inboxQuestions: List<Question>
        get() = questionsAfterLocalDismissals(repository.questions, dismissedQuestionIds.toSet())
    val pendingInputCount: Int get() = inboxApprovals.size + inboxQuestions.size
    val approvalsSupported: Boolean get() = repository.approvalsSupported
    val questionsSupported: Boolean get() = repository.questionsSupported

    /** True when the paired host exposes approvals read-only; the desktop stays the answerer. */
    val approvalsReadOnly: Boolean get() = repository.approvalsReadOnly

    /** True when the paired host exposes questions read-only; the desktop stays the answerer. */
    val questionsReadOnly: Boolean get() = repository.questionsReadOnly

    fun openInbox() {
        showInbox = true
    }

    fun closeInbox() {
        showInbox = false
    }

    fun openGoals() {
        showGoals = true
    }

    fun closeGoals() {
        showGoals = false
    }

    /** Sessions that currently have a goal, ordered for the goals dashboard. */
    fun goalSessions(): List<Session> = GoalDashboard.sessionsWithGoals(visibleSessions())

    /** Count shown on the session-list goals entry points. */
    val goalSessionCount: Int get() = GoalDashboard.count(visibleSessions())

    // region schedules

    var scheduleListState: ScheduleListState? by mutableStateOf(null)
        private set
    var schedulesLoading: Boolean by mutableStateOf(false)
        private set
    var schedulesActionLoading: Boolean by mutableStateOf(false)
        private set
    var schedulesError: String? by mutableStateOf(null)
        private set

    /** Count for the session-list entry; null until the list has been loaded once. */
    val scheduleCount: Int? get() = scheduleListState?.schedules?.size

    fun openSchedules() {
        showSchedules = true
        refreshSchedules()
    }

    fun closeSchedules() {
        showSchedules = false
    }

    fun clearSchedulesError() {
        schedulesError = null
    }

    fun refreshSchedules() {
        if (!isLive) {
            scheduleListState = ScheduleListState.Unsupported
            schedulesError = null
            return
        }
        if (schedulesLoading) return
        schedulesLoading = true
        schedulesError = null
        scope.launch {
            try {
                scheduleListState = repository.schedules()
            } catch (error: Exception) {
                schedulesError = errorMessage(error)
            } finally {
                schedulesLoading = false
            }
        }
    }

    fun createSchedule(
        sessionId: String,
        title: String,
        instruction: String,
        timing: ScheduleTimingInput,
    ) {
        if (!isLive) {
            schedulesError = "Schedules need a paired host."
            return
        }
        val trimmedTitle = title.trim()
        val trimmedInstruction = instruction.trim()
        when {
            sessionId.isBlank() -> {
                schedulesError = "Choose a session for the schedule."
                return
            }

            trimmedTitle.isEmpty() -> {
                schedulesError = "Title must not be empty."
                return
            }

            trimmedInstruction.isEmpty() -> {
                schedulesError = "Instruction must not be empty."
                return
            }

            !isScheduleTimingValid(timing) -> {
                schedulesError = "Choose a valid time for the schedule."
                return
            }
        }
        if (schedulesActionLoading) return
        schedulesActionLoading = true
        schedulesError = null
        scope.launch {
            try {
                repository.createSchedule(sessionId, trimmedTitle, trimmedInstruction, timing)
                scheduleListState = repository.schedules()
            } catch (error: Exception) {
                schedulesError = scheduleErrorMessage(error)
            } finally {
                schedulesActionLoading = false
            }
        }
    }

    fun deleteSchedule(scheduleId: String) {
        if (!isLive) {
            schedulesError = "Schedules need a paired host."
            return
        }
        if (schedulesActionLoading) return
        schedulesActionLoading = true
        schedulesError = null
        scope.launch {
            try {
                repository.deleteSchedule(scheduleId)
                scheduleListState = repository.schedules()
            } catch (error: Exception) {
                schedulesError = scheduleErrorMessage(error)
            } finally {
                schedulesActionLoading = false
            }
        }
    }

    /**
     * Edit an existing schedule. [timing] is null for host kinds whose timing can't be edited from
     * the phone (the dialog then only changes title/instruction).
     */
    fun updateSchedule(
        scheduleId: String,
        title: String,
        instruction: String,
        timing: ScheduleTimingInput?,
    ) {
        if (!isLive) {
            schedulesError = "Schedules need a paired host."
            return
        }
        val trimmedTitle = title.trim()
        val trimmedInstruction = instruction.trim()
        when {
            trimmedTitle.isEmpty() -> {
                schedulesError = "Title must not be empty."
                return
            }

            trimmedInstruction.isEmpty() -> {
                schedulesError = "Instruction must not be empty."
                return
            }

            timing != null && !isScheduleTimingValid(timing) -> {
                schedulesError = "Choose a valid time for the schedule."
                return
            }
        }
        if (schedulesActionLoading) return
        schedulesActionLoading = true
        schedulesError = null
        scope.launch {
            try {
                repository.updateSchedule(scheduleId, trimmedTitle, trimmedInstruction, timing)
                scheduleListState = repository.schedules()
            } catch (error: Exception) {
                schedulesError = scheduleErrorMessage(error)
            } finally {
                schedulesActionLoading = false
            }
        }
    }

    /**
     * Awaitable "Schedule this prompt" path from chat: creates the schedule with the draft as its
     * instruction, refreshes the list and reports failures in [syncError] (chat's error surface).
     */
    suspend fun schedulePrompt(
        sessionId: String,
        title: String,
        instruction: String,
        timing: ScheduleTimingInput,
    ): Boolean {
        if (!isLive) {
            syncError = "Schedules need a paired host."
            return false
        }
        val trimmedTitle = title.trim()
        val trimmedInstruction = instruction.trim()
        if (sessionId.isBlank() || trimmedTitle.isEmpty() || trimmedInstruction.isEmpty()) {
            syncError = "Choose a title and write a prompt first."
            return false
        }
        if (!isScheduleTimingValid(timing)) {
            syncError = "Choose a valid time for the schedule."
            return false
        }
        return try {
            repository.createSchedule(sessionId, trimmedTitle, trimmedInstruction, timing)
            runCatching { repository.schedules() }.getOrNull()?.let { scheduleListState = it }
            syncError = null
            true
        } catch (error: Exception) {
            syncError = scheduleErrorMessage(error)
            false
        }
    }

    private fun isScheduleTimingValid(timing: ScheduleTimingInput): Boolean = when (timing) {
        is ScheduleTimingInput.AfterMinutes ->
            timing.minutes in 1..ScheduleTiming.MAX_AFTER_MINUTES

        is ScheduleTimingInput.Daily -> ScheduleTiming.parseDailyTime(timing.time) != null
    }

    private fun scheduleErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support schedules yet. Update the desktop host plugin."

        is DshApiException.InvalidRequest -> error.message ?: "The host rejected that schedule."
        else -> errorMessage(error)
    }

    // endregion

    fun openHiddenSessions() {
        showSettings = false
        showHiddenSessions = true
        refreshHiddenSessions()
    }

    fun closeHiddenSessions() {
        showHiddenSessions = false
    }

    fun openConnectionDiagnostics() {
        showSettings = false
        showConnectionDiagnostics = true
        refreshNow()
    }

    fun closeConnectionDiagnostics() {
        showConnectionDiagnostics = false
    }

    fun refreshHiddenSessions() {
        if (!isLive) {
            hiddenSessionIds = emptySet()
            return
        }
        scope.launch {
            try {
                hiddenSessionIds = repository.hiddenSessions()
            } catch (error: Exception) {
                syncError = errorMessage(error)
            }
        }
    }

    fun unhideSession(sessionId: String) {
        scope.launch {
            try {
                repository.unhideSession(sessionId)
                hiddenSessionIds = hiddenSessionIds - sessionId
                closeHiddenSessions()
                refreshNow()
            } catch (error: Exception) {
                syncError = errorMessage(error)
            }
        }
    }

    // region archived sessions + host session management

    var archivedSessionsState: ArchivedSessionsState? by mutableStateOf(null)
        private set
    var archivedLoading: Boolean by mutableStateOf(false)
        private set
    var sessionActionLoading: Boolean by mutableStateOf(false)
        private set
    var archivedError: String? by mutableStateOf(null)
        private set

    fun openArchivedSessions() {
        showSettings = false
        showArchivedSessions = true
        refreshArchivedSessions()
    }

    fun closeArchivedSessions() {
        showArchivedSessions = false
    }

    fun clearArchivedError() {
        archivedError = null
    }

    fun refreshArchivedSessions() {
        if (!isLive) {
            archivedSessionsState = ArchivedSessionsState.Unsupported
            archivedError = null
            return
        }
        if (archivedLoading) return
        archivedLoading = true
        archivedError = null
        scope.launch {
            try {
                archivedSessionsState = repository.archivedSessions()
            } catch (error: Exception) {
                archivedError = errorMessage(error)
            } finally {
                archivedLoading = false
            }
        }
    }

    /** Host-side rename: the session title changes on every paired device. */
    fun renameSessionOnHost(sessionId: String, title: String) {
        if (!isLive) {
            syncError = "Session management needs a paired host."
            return
        }
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            syncError = "Session title must not be empty."
            return
        }
        if (sessionActionLoading) return
        sessionActionLoading = true
        scope.launch {
            try {
                repository.renameSessionOnHost(sessionId, trimmed)
                runCatching { repository.refresh() }
                syncError = null
            } catch (error: Exception) {
                syncError = sessionManagementError(error)
            } finally {
                sessionActionLoading = false
            }
        }
    }

    /** Archive on the host: the session leaves the active list on every device. */
    fun archiveSessionOnHost(sessionId: String) {
        if (!isLive) {
            syncError = "Session management needs a paired host."
            return
        }
        if (sessionActionLoading) return
        sessionActionLoading = true
        scope.launch {
            try {
                repository.archiveSessionOnHost(sessionId)
                runCatching { repository.refresh() }
                // Keep the archived screen in step when it was already loaded.
                runCatching { repository.archivedSessions() }.getOrNull()?.let {
                    archivedSessionsState = it
                }
                syncError = null
            } catch (error: Exception) {
                syncError = sessionManagementError(error)
            } finally {
                sessionActionLoading = false
            }
        }
    }

    /** Restore an archived session; refreshes both the active and archived lists. */
    fun unarchiveSessionOnHost(sessionId: String) {
        if (!isLive) {
            archivedError = "Session management needs a paired host."
            return
        }
        if (sessionActionLoading) return
        sessionActionLoading = true
        scope.launch {
            try {
                repository.unarchiveSessionOnHost(sessionId)
                runCatching { repository.refresh() }
                archivedSessionsState = repository.archivedSessions()
                syncError = null
            } catch (error: Exception) {
                archivedError = sessionManagementError(error)
            } finally {
                sessionActionLoading = false
            }
        }
    }

    private fun sessionManagementError(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support session management yet. Update the desktop host plugin."

        is DshApiException.NotFound -> "That session is no longer available on the host."
        is DshApiException.InvalidRequest -> error.message ?: "The host rejected that change."
        else -> errorMessage(error)
    }

    // endregion

    // region push (ntfy)

    var pushConfig: PushConfig? by mutableStateOf(null)
        private set
    var pushConfigLoading: Boolean by mutableStateOf(false)
        private set
    var pushConfigSaving: Boolean by mutableStateOf(false)
        private set
    var pushTestLoading: Boolean by mutableStateOf(false)
        private set
    var pushStatus: String? by mutableStateOf(null)
        private set

    fun clearPushStatus() {
        pushStatus = null
    }

    fun refreshPushConfig() {
        if (!isLive) {
            pushConfig = PushConfig.Unsupported
            pushStatus = null
            return
        }
        if (pushConfigLoading) return
        pushConfigLoading = true
        scope.launch {
            try {
                pushConfig = repository.pushConfig()
            } catch (error: Exception) {
                pushStatus = pushErrorMessage(error)
            } finally {
                pushConfigLoading = false
            }
        }
    }

    fun savePushConfig(
        enabled: Boolean,
        server: String,
        topic: String,
        token: String,
        clearToken: Boolean = false,
    ) {
        if (!isLive) {
            pushStatus = "Push notifications need a paired host."
            return
        }
        if (!pushConfigValid(enabled, server, topic)) {
            pushStatus = "Check the topic and server URL before saving."
            return
        }
        if (pushConfigSaving) return
        pushConfigSaving = true
        pushStatus = null
        scope.launch {
            try {
                val updated = repository.updatePushConfig(
                    enabled = enabled,
                    server = server.trim(),
                    topic = topic.trim(),
                    // null keeps the host token; "" clears it; a typed token replaces it.
                    token = if (clearToken) "" else token.trim().takeIf { it.isNotBlank() },
                )
                pushConfig = updated
                pushStatus = if (updated.enabled) {
                    "Push notifications saved."
                } else {
                    "Push notifications disabled."
                }
            } catch (error: Exception) {
                pushStatus = pushErrorMessage(error)
            } finally {
                pushConfigSaving = false
            }
        }
    }

    fun sendPushTestNotification() {
        if (!isLive) {
            pushStatus = "Push notifications need a paired host."
            return
        }
        if (pushTestLoading) return
        pushTestLoading = true
        pushStatus = null
        scope.launch {
            try {
                val ok = repository.sendPushTest()
                pushStatus = if (ok) {
                    "Test notification sent."
                } else {
                    "The host rejected the test notification."
                }
            } catch (error: Exception) {
                pushStatus = pushErrorMessage(error)
            } finally {
                pushTestLoading = false
            }
        }
    }

    private fun pushErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support push notifications yet. Update the desktop host plugin."

        is DshApiException.InvalidRequest -> error.message ?: "The host rejected these push settings."
        else -> errorMessage(error)
    }

    // Push suppression sync (quiet hours + per-session mutes) is additive and best-effort:
    // old hosts/404/network means a silent no-op, never a blocking error or retry loop.
    private var pushSuppressJob: Job? = null
    private var lastPushSuppressionPayload: PushSuppressionPayload? = null

    /** Debounced one-shot sync; coalesces rapid quiet-hours/mute changes into one PUT. */
    private fun schedulePushSuppressionSync(immediate: Boolean = false) {
        if (!isLive) return
        pushSuppressJob?.cancel()
        pushSuppressJob = scope.launch {
            if (!immediate) delay(PUSH_SUPPRESS_DEBOUNCE_MS)
            val payload = buildPushSuppressionPayload(
                quietHoursEnabled = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietEndHour = quietEndHour,
                timeZone = deviceTimeZoneId(),
                mutedSessionIds = mutedNotificationSessions.toList(),
            )
            if (payload == lastPushSuppressionPayload) return@launch
            val state = runCatching { repository.updatePushSuppression(payload) }.getOrNull()
                ?: return@launch
            // Only a host that accepted the payload is remembered; unsupported/network/no host is
            // retried by the next change or host start, never in a loop.
            if (state.supported) lastPushSuppressionPayload = payload
        }
    }

    // endregion

    // region approval policy

    var approvalPolicySessionId: String? by mutableStateOf(null)
        private set
    var approvalPolicy: ApprovalPolicy? by mutableStateOf(null)
        private set
    var approvalPolicyLoading: Boolean by mutableStateOf(false)
        private set
    var approvalPolicySaving: Boolean by mutableStateOf(false)
        private set
    var approvalPolicyError: String? by mutableStateOf(null)
        private set

    fun openApprovalPolicy(sessionId: String) {
        if (sessionId.isBlank()) return
        approvalPolicySessionId = sessionId
        approvalPolicy = null
        approvalPolicyError = null
        loadApprovalPolicy()
    }

    fun closeApprovalPolicy() {
        approvalPolicySessionId = null
        approvalPolicy = null
        approvalPolicyError = null
        approvalPolicyLoading = false
    }

    fun loadApprovalPolicy() {
        val sessionId = approvalPolicySessionId ?: return
        if (!isLive) {
            // No host to query: the dialog shows the live-session note instead of an error.
            approvalPolicy = ApprovalPolicy(supported = true, live = false)
            approvalPolicyLoading = false
            return
        }
        if (approvalPolicyLoading) return
        approvalPolicyLoading = true
        approvalPolicyError = null
        scope.launch {
            try {
                approvalPolicy = repository.approvalPolicy(sessionId)
            } catch (error: Exception) {
                approvalPolicyError = approvalPolicyErrorMessage(error)
            } finally {
                approvalPolicyLoading = false
            }
        }
    }

    fun retryApprovalPolicy() = loadApprovalPolicy()

    fun saveApprovalPolicy(policy: String) {
        val sessionId = approvalPolicySessionId ?: return
        if (!approvalPolicyValid(policy)) {
            approvalPolicyError = "Pick Ask or Never."
            return
        }
        if (!isLive) {
            approvalPolicyError = "Changes require a live session."
            return
        }
        if (approvalPolicySaving) return
        approvalPolicySaving = true
        approvalPolicyError = null
        scope.launch {
            try {
                // The PUT response carries the refreshed live/policy fields.
                approvalPolicy = repository.updateApprovalPolicy(sessionId, policy)
            } catch (error: Exception) {
                approvalPolicyError = approvalPolicyErrorMessage(error)
            } finally {
                approvalPolicySaving = false
            }
        }
    }

    private fun approvalPolicyErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support approval policy changes yet. " +
                "Update the desktop host plugin."

        is DshApiException.NotFound -> "That session is no longer available on the host."
        is DshApiException.InvalidRequest -> error.message ?: "The host rejected that policy."
        else -> errorMessage(error)
    }

    // endregion

    // region paired devices

    var pairedDevices: PairedDevices? by mutableStateOf(null)
        private set
    var pairedDevicesDialogOpen: Boolean by mutableStateOf(false)
        private set
    var pairedDevicesLoading: Boolean by mutableStateOf(false)
        private set
    var pairedDevicesError: String? by mutableStateOf(null)
        private set
    var revokingDeviceId: String? by mutableStateOf(null)
        private set
    var pairedDevicesStatus: String? by mutableStateOf(null)
        private set

    fun openPairedDevices() {
        pairedDevicesDialogOpen = true
        pairedDevicesStatus = null
        refreshPairedDevices()
    }

    fun closePairedDevices() {
        pairedDevicesDialogOpen = false
        revokingDeviceId = null
        pairedDevicesError = null
        pairedDevicesStatus = null
    }

    fun refreshPairedDevices() {
        if (pairedDevicesLoading) return
        pairedDevicesLoading = true
        pairedDevicesError = null
        scope.launch {
            try {
                pairedDevices = repository.devices()
            } catch (error: Exception) {
                pairedDevicesError = pairedDevicesErrorMessage(error)
            } finally {
                pairedDevicesLoading = false
            }
        }
    }

    /** Revokes another device, then refreshes the list so it disappears. */
    fun revokePairedDevice(device: PairedDevice) {
        val current = pairedDevices
        if (current != null && pairedDeviceIsCurrent(current, device)) return
        if (device.deviceId.isBlank() || revokingDeviceId != null) return
        revokingDeviceId = device.deviceId
        pairedDevicesError = null
        pairedDevicesStatus = null
        scope.launch {
            try {
                repository.revokeDevice(device.deviceId)
                pairedDevicesStatus =
                    "Revoked ${pairedDeviceName(device)}. It will need to pair again."
                pairedDevices = repository.devices()
            } catch (error: Exception) {
                pairedDevicesError = pairedDevicesErrorMessage(error)
            } finally {
                revokingDeviceId = null
            }
        }
    }

    private fun pairedDevicesErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support managing paired devices yet. " +
                "Update the desktop host plugin."

        is DshApiException.NotFound -> "That device is no longer paired with the host."
        is DshApiException.InvalidRequest ->
            error.message ?: "The host rejected that request (the current device can't be revoked)."

        else -> errorMessage(error)
    }

    // endregion

    // region recovery access (Settings → Security)

    var recoveryAccess: RecoveryAccess? by mutableStateOf(null)
        private set
    var recoveryAccessLoading: Boolean by mutableStateOf(false)
        private set
    var recoveryCodeGenerating: Boolean by mutableStateOf(false)
        private set

    /** One-time code for the current Settings session; never persisted and cleared on close. */
    var recoveryCode: RecoveryCode? by mutableStateOf(null)
        private set
    var recoveryError: String? by mutableStateOf(null)
        private set

    /** Rotated code shown once after a successful restore (copy/share/Done completion step). */
    var recoveryCompletion: RecoveryCompletion? by mutableStateOf(null)
        private set

    fun dismissRecoveryCompletion() {
        recoveryCompletion = null
    }

    /** Prefilled restore dialog when a `dshmobile://recover` link is opened from outside. */
    var pendingRecoveryLink: RecoveryLink? by mutableStateOf(null)
        private set

    fun handleRecoveryDeepLink(rawLink: String): Boolean {
        val link = parseRecoveryLink(rawLink) ?: return false
        pendingRecoveryLink = link
        return true
    }

    fun consumeRecoveryDeepLink() {
        pendingRecoveryLink = null
    }

    fun loadRecoveryAccess() {
        if (recoveryAccessLoading) return
        if (!isLive) {
            recoveryAccess = RecoveryAccess.Unsupported
            return
        }
        recoveryAccessLoading = true
        recoveryError = null
        scope.launch {
            try {
                recoveryAccess = repository.recoveryAccess()
            } catch (error: Exception) {
                recoveryError = recoveryErrorMessage(error)
            } finally {
                recoveryAccessLoading = false
            }
        }
    }

    fun generateRecoveryAccessCode() {
        if (recoveryCodeGenerating) return
        if (!isLive) {
            recoveryError = "Recovery access needs a paired host."
            return
        }
        recoveryCodeGenerating = true
        recoveryError = null
        scope.launch {
            try {
                val generated = repository.generateRecoveryCode()
                recoveryCode = generated
                recoveryAccess = RecoveryAccess(
                    supported = true,
                    configured = true,
                    createdAt = generated.createdAt,
                )
            } catch (error: Exception) {
                recoveryError = recoveryErrorMessage(error)
            } finally {
                recoveryCodeGenerating = false
            }
        }
    }

    fun clearRecoveryCode() {
        recoveryCode = null
    }

    fun clearRecoveryError() {
        recoveryError = null
    }

    private fun recoveryErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support recovery codes yet. Update the desktop host plugin."

        is DshApiException.Unauthorized ->
            "This device is no longer paired with the host. Pair again before generating a code."

        is DshApiException.Network -> recoveryFailureMessage(RecoveryFailureKind.NETWORK)
        is DshApiException.RateLimited -> recoveryFailureMessage(RecoveryFailureKind.RATE_LIMITED)
        else -> error.message ?: "Couldn't update recovery access."
    }

    // endregion

    // region model + thinking picker

    var modelPickerSessionId: String? by mutableStateOf(null)
        private set
    var modelCatalog: ModelCatalog? by mutableStateOf(null)
        private set
    var modelCatalogLoading: Boolean by mutableStateOf(false)
        private set
    var modelCatalogError: String? by mutableStateOf(null)
        private set
    var sessionModel: SessionModel? by mutableStateOf(null)
        private set
    var sessionModelLoading: Boolean by mutableStateOf(false)
        private set
    var sessionModelError: String? by mutableStateOf(null)
        private set
    var modelSaving: Boolean by mutableStateOf(false)
        private set
    var modelStatus: String? by mutableStateOf(null)
        private set

    /** Lightweight chat-chip preview of the session's model; no dialog/error UI attached. */
    var modelPreview: SessionModel? by mutableStateOf(null)
        private set
    private var modelPreviewSessionId: String? = null

    fun loadModelPreview(sessionId: String) {
        if (sessionId.isBlank()) return
        if (modelPreviewSessionId == sessionId && modelPreview != null) return
        modelPreviewSessionId = sessionId
        modelPreview = null
        scope.launch {
            val loaded = runCatching { repository.sessionModel(sessionId) }.getOrNull()
            if (loaded != null && modelPreviewSessionId == sessionId) modelPreview = loaded
        }
    }

    fun openModelPicker(sessionId: String) {
        if (sessionId.isBlank()) return
        modelPickerSessionId = sessionId
        modelCatalog = null
        modelCatalogError = null
        sessionModel = null
        sessionModelError = null
        modelStatus = null
        loadModelCatalog()
        loadSessionModel()
    }

    fun closeModelPicker() {
        modelPickerSessionId = null
        modelCatalog = null
        modelCatalogError = null
        sessionModel = null
        sessionModelError = null
        modelStatus = null
    }

    fun refreshModelPicker() {
        modelStatus = null
        loadModelCatalog()
        loadSessionModel()
    }

    fun loadModelCatalog() {
        if (modelCatalogLoading) return
        modelCatalogLoading = true
        modelCatalogError = null
        scope.launch {
            try {
                modelCatalog = repository.modelCatalog()
            } catch (error: Exception) {
                modelCatalogError = modelPickerErrorMessage(error)
            } finally {
                modelCatalogLoading = false
            }
        }
    }

    fun loadSessionModel() {
        val sessionId = modelPickerSessionId ?: return
        if (sessionModelLoading) return
        sessionModelLoading = true
        sessionModelError = null
        scope.launch {
            try {
                sessionModel = repository.sessionModel(sessionId)
            } catch (error: Exception) {
                sessionModelError = modelPickerErrorMessage(error)
            } finally {
                sessionModelLoading = false
            }
        }
    }

    fun saveSessionModel(provider: String, model: String, reasoningEffort: String?) {
        val sessionId = modelPickerSessionId ?: return
        if (!modelSelectionValid(ModelSelection(provider, model))) {
            sessionModelError = "Pick a model first."
            return
        }
        if (modelSaving) return
        modelSaving = true
        sessionModelError = null
        modelStatus = null
        scope.launch {
            try {
                // Never send an effort the selected model doesn't advertise (M6).
                val safeEffort = sanitizedModelEffort(
                    modelCatalog,
                    ModelSelection(provider.trim(), model.trim(), reasoningEffort),
                )
                sessionModel = repository.updateSessionModel(
                    sessionId = sessionId,
                    provider = provider,
                    model = model,
                    reasoningEffort = safeEffort,
                )
                modelStatus = "Model updated for this session."
                modelPreview = sessionModel
                // Saving also changes the host default for new sessions; refresh the catalog default.
                runCatching { repository.modelCatalog() }.getOrNull()?.let { modelCatalog = it }
            } catch (error: Exception) {
                sessionModelError = modelPickerErrorMessage(error)
            } finally {
                modelSaving = false
            }
        }
    }

    private fun modelPickerErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support model selection yet. Update the desktop host plugin."

        is DshApiException.NotFound -> "That session or model is no longer available on the host."
        is DshApiException.InvalidRequest ->
            error.message ?: "The host rejected that model (it may be unavailable right now)."

        else -> errorMessage(error)
    }

    // endregion

    // region host queue

    var hostQueueSessionId: String? by mutableStateOf(null)
        private set
    var hostQueue: HostQueue? by mutableStateOf(null)
        private set
    var hostQueueDialogOpen: Boolean by mutableStateOf(false)
        private set
    var hostQueueLoading: Boolean by mutableStateOf(false)
        private set
    var hostQueueError: String? by mutableStateOf(null)
        private set
    var hostQueueStatus: String? by mutableStateOf(null)
        private set
    var hostQueueSavingItemId: String? by mutableStateOf(null)
        private set

    /** Loads the host queue for the chat strip; safe to call on every chat open. */
    fun loadHostQueue(sessionId: String) {
        if (sessionId.isBlank()) return
        if (hostQueueSessionId != sessionId) {
            hostQueueSessionId = sessionId
            hostQueue = null
            hostQueueError = null
        }
        if (!isLive) {
            hostQueue = HostQueue(supported = true, live = false)
            hostQueueLoading = false
            return
        }
        if (hostQueueLoading) return
        hostQueueLoading = true
        hostQueueError = null
        scope.launch {
            try {
                hostQueue = repository.hostQueue(sessionId)
            } catch (error: Exception) {
                hostQueueError = hostQueueErrorMessage(error)
            } finally {
                hostQueueLoading = false
            }
        }
    }

    fun openHostQueue(sessionId: String) {
        hostQueueDialogOpen = true
        hostQueueStatus = null
        loadHostQueue(sessionId)
    }

    fun closeHostQueue() {
        hostQueueDialogOpen = false
        hostQueueStatus = null
        hostQueueError = null
    }

    fun refreshHostQueue() {
        val sessionId = hostQueueSessionId ?: return
        hostQueueStatus = null
        loadHostQueue(sessionId)
    }

    fun editHostQueueItem(itemId: String, text: String) {
        if (!hostQueueEditTextValid(text)) {
            hostQueueError = "Type the replacement prompt."
            return
        }
        runHostQueueAction(itemId, QUEUE_ACTION_EDIT, text.trim())
    }

    fun removeHostQueueItem(itemId: String) =
        runHostQueueAction(itemId, QUEUE_ACTION_REMOVE)

    fun steerHostQueueItem(itemId: String) =
        runHostQueueAction(itemId, QUEUE_ACTION_STEER)

    private fun runHostQueueAction(itemId: String, action: String, text: String? = null) {
        val sessionId = hostQueueSessionId ?: return
        if (hostQueueSavingItemId != null) return
        hostQueueSavingItemId = itemId
        hostQueueError = null
        hostQueueStatus = null
        scope.launch {
            try {
                repository.hostQueueAction(sessionId, itemId, action, text)
                hostQueueStatus = when (action) {
                    QUEUE_ACTION_EDIT -> "Queued prompt updated."
                    QUEUE_ACTION_REMOVE -> "Queued prompt removed."
                    else -> "Prompt sent now."
                }
                // Refresh after every action so the panel reflects the host truth.
                hostQueue = repository.hostQueue(sessionId)
            } catch (error: Exception) {
                // Q1: a vanished/consumed item is not an error screen: refresh and explain.
                if (error is DshApiException.NotFound) {
                    hostQueueStatus = "That item already started or was removed."
                    runCatching { repository.hostQueue(sessionId) }.getOrNull()?.let {
                        hostQueue = it
                    }
                } else {
                    hostQueueError = hostQueueErrorMessage(error)
                }
            } finally {
                hostQueueSavingItemId = null
            }
        }
    }

    private fun hostQueueErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support the queue panel yet. Update the desktop host plugin."

        is DshApiException.NotFound -> "That queued prompt is no longer on the host."
        is DshApiException.InvalidRequest ->
            error.message ?: "The host rejected that action (cold session or steering unavailable)."

        else -> errorMessage(error)
    }

    // endregion

    // region session skills

    var skillsSheetOpen: Boolean by mutableStateOf(false)
        private set
    var skillsSessionId: String? by mutableStateOf(null)
        private set
    var skills: HostSkills? by mutableStateOf(null)
        private set
    var skillsLoading: Boolean by mutableStateOf(false)
        private set
    var skillsError: String? by mutableStateOf(null)
        private set
    private data class CachedSkills(val result: HostSkills, val fetchedAtMs: Long)
    private val skillsCache = mutableMapOf<String, CachedSkills>()
    private var skillsJob: Job? = null

    /** Opens the Skills sheet and serves the per-session cache immediately when present. */
    fun openSkills(sessionId: String) {
        skillsSheetOpen = true
        skillsError = null
        loadSessionSkills(sessionId)
    }

    fun closeSkills() {
        skillsSheetOpen = false
        skillsError = null
        skillsJob?.cancel()
        skillsJob = null
        skillsLoading = false
    }

    /**
     * Fetches skills once per session with a bounded TTL (5 min); failures never crash chat. A
     * cached snapshot stays visible while a stale cache refreshes, and only a hard failure with
     * nothing cached surfaces an error.
     */
    fun loadSessionSkills(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (skillsSessionId != sessionId) {
            skillsSessionId = sessionId
            skills = skillsCache[sessionId]?.result
            skillsError = null
        }
        val cached = skillsCache[sessionId]
        if (!shouldLoadSessionSkills(
                cached = cached?.result ?: skills,
                loading = skillsLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            return
        }
        if (!isLive) {
            skills = HostSkills.Unsupported
            skillsLoading = false
            return
        }
        skillsJob?.cancel()
        skillsLoading = true
        skillsError = null
        skillsJob = scope.launch {
            try {
                val result = repository.sessionSkills(sessionId)
                skillsCache[sessionId] = CachedSkills(result, System.currentTimeMillis())
                if (skillsSessionId == sessionId) skills = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (skillsSessionId == sessionId) {
                    // Failure-safe: keep cached skills; only show an error when nothing to show.
                    if (skills == null) skillsError = skillsErrorMessage(error)
                }
            } finally {
                if (skillsSessionId == sessionId) skillsLoading = false
            }
        }
    }

    fun retrySessionSkills() {
        skillsSessionId?.let { sessionId -> loadSessionSkills(sessionId, force = true) }
    }

    private fun skillsErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.NotFound -> SKILLS_SESSION_UNAVAILABLE_COPY
        is DshApiException.Unsupported -> SKILLS_UNSUPPORTED_COPY
        else -> errorMessage(error)
    }

    // endregion

    // region session plan (todos)

    var planSheetOpen: Boolean by mutableStateOf(false)
        private set
    var planSessionId: String? by mutableStateOf(null)
        private set
    var plan: HostPlan? by mutableStateOf(null)
        private set
    var planLoading: Boolean by mutableStateOf(false)
        private set
    var planError: String? by mutableStateOf(null)
        private set
    private data class CachedPlan(val result: HostPlan, val fetchedAtMs: Long)
    private val planCache = mutableMapOf<String, CachedPlan>()
    private var planJob: Job? = null

    /** Opens the Plan sheet; the 30 s cache is served immediately when fresh. */
    fun openPlan(sessionId: String) {
        planSheetOpen = true
        planError = null
        loadSessionPlan(sessionId)
    }

    fun closePlan() {
        planSheetOpen = false
        planError = null
        planJob?.cancel()
        planJob = null
        planLoading = false
    }

    /**
     * Fetches the plan with a 30 s TTL; cached rows stay visible while stale data refreshes.
     * Unsupported/old hosts and failures never crash the chat.
     */
    fun loadSessionPlan(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (planSessionId != sessionId) {
            planSessionId = sessionId
            plan = planCache[sessionId]?.result
            planError = null
        }
        val cached = planCache[sessionId]
        if (!shouldLoadSessionPlan(
                cached = cached?.result ?: plan,
                loading = planLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            return
        }
        if (!isLive) {
            plan = HostPlan.Unsupported
            planLoading = false
            return
        }
        planJob?.cancel()
        planLoading = true
        planError = null
        planJob = scope.launch {
            try {
                val result = repository.sessionTodos(sessionId)
                planCache[sessionId] = CachedPlan(result, System.currentTimeMillis())
                if (planSessionId == sessionId) plan = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (planSessionId == sessionId) {
                    // Keep a cached plan visible; only surface an error when there is nothing to show.
                    if (plan == null) planError = planErrorMessage(error)
                }
            } finally {
                if (planSessionId == sessionId) planLoading = false
            }
        }
    }

    fun retrySessionPlan() {
        planSessionId?.let { sessionId -> loadSessionPlan(sessionId, force = true) }
    }

    private fun planErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported, is DshApiException.NotFound -> PLAN_LOAD_FAILED_COPY
        else -> errorMessage(error)
    }

    // endregion

    // region session team

    var teamSheetOpen: Boolean by mutableStateOf(false)
        private set
    var teamSessionId: String? by mutableStateOf(null)
        private set
    var team: HostTeam? by mutableStateOf(null)
        private set
    var teamLoading: Boolean by mutableStateOf(false)
        private set
    var teamError: String? by mutableStateOf(null)
        private set
    private data class CachedTeam(val result: HostTeam, val fetchedAtMs: Long)
    private val teamCache = mutableMapOf<String, CachedTeam>()
    private var teamJob: Job? = null

    fun openTeam(sessionId: String) {
        teamSheetOpen = true
        teamError = null
        loadSessionTeam(sessionId)
    }

    fun closeTeam() {
        teamSheetOpen = false
        teamError = null
        teamJob?.cancel()
        teamJob = null
        teamLoading = false
    }

    /** 30 s TTL cache; cached rows stay visible while a stale board refreshes. */
    fun loadSessionTeam(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (teamSessionId != sessionId) {
            teamSessionId = sessionId
            team = teamCache[sessionId]?.result
            teamError = null
        }
        val cached = teamCache[sessionId]
        if (!shouldLoadSessionTeam(
                cached = cached?.result ?: team,
                loading = teamLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            return
        }
        if (!isLive) {
            team = HostTeam.Unsupported
            teamLoading = false
            return
        }
        teamJob?.cancel()
        teamLoading = true
        teamError = null
        teamJob = scope.launch {
            try {
                val result = repository.sessionTeam(sessionId)
                teamCache[sessionId] = CachedTeam(result, System.currentTimeMillis())
                if (teamSessionId == sessionId) team = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (teamSessionId == sessionId) {
                    if (team == null) teamError = teamErrorMessage(error)
                }
            } finally {
                if (teamSessionId == sessionId) teamLoading = false
            }
        }
    }

    fun retrySessionTeam() {
        teamSessionId?.let { sessionId -> loadSessionTeam(sessionId, force = true) }
    }

    private fun teamErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported, is DshApiException.NotFound -> TEAM_LOAD_FAILED_COPY
        else -> errorMessage(error)
    }

    // endregion

    // region session trajectory

    var trajectorySheetOpen: Boolean by mutableStateOf(false)
        private set
    var trajectorySessionId: String? by mutableStateOf(null)
        private set
    var trajectory: HostTrajectoryPage? by mutableStateOf(null)
        private set
    var trajectoryLoading: Boolean by mutableStateOf(false)
        private set
    var trajectoryOlderLoading: Boolean by mutableStateOf(false)
        private set
    var trajectoryError: String? by mutableStateOf(null)
        private set
    private data class CachedTrajectory(val page: HostTrajectoryPage, val fetchedAtMs: Long)
    private val trajectoryCache = mutableMapOf<String, CachedTrajectory>()
    private var trajectoryJob: Job? = null
    private var trajectoryOlderJob: Job? = null

    fun openTrajectory(sessionId: String) {
        trajectorySheetOpen = true
        trajectoryError = null
        loadSessionTrajectory(sessionId)
    }

    fun closeTrajectory() {
        trajectorySheetOpen = false
        trajectoryError = null
        trajectoryJob?.cancel()
        trajectoryJob = null
        trajectoryOlderJob?.cancel()
        trajectoryOlderJob = null
        trajectoryLoading = false
        trajectoryOlderLoading = false
    }

    /** Newest page with a 30 s TTL cache; stale cache stays visible while refreshing. */
    fun loadSessionTrajectory(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (trajectorySessionId != sessionId) {
            trajectorySessionId = sessionId
            trajectory = trajectoryCache[sessionId]?.page
            trajectoryError = null
        }
        val cached = trajectoryCache[sessionId]
        if (!shouldLoadSessionTrajectory(
                cached = cached?.page ?: trajectory,
                loading = trajectoryLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            return
        }
        if (!isLive) {
            trajectory = HostTrajectoryPage.Unsupported
            trajectoryLoading = false
            return
        }
        trajectoryJob?.cancel()
        trajectoryOlderJob?.cancel()
        trajectoryOlderLoading = false
        trajectoryLoading = true
        trajectoryError = null
        trajectoryJob = scope.launch {
            try {
                val page = repository.sessionTrajectory(sessionId, beforeSeq = null)
                trajectoryCache[sessionId] = CachedTrajectory(page, System.currentTimeMillis())
                if (trajectorySessionId == sessionId) trajectory = page
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (trajectorySessionId == sessionId && trajectory == null) {
                    trajectoryError = trajectoryErrorMessage(error)
                }
            } finally {
                if (trajectorySessionId == sessionId) trajectoryLoading = false
            }
        }
    }

    /** Appends the next older page with the same cursor/duplicate guards as the pure helper. */
    fun loadOlderTrajectory() {
        val current = trajectory ?: return
        val sessionId = trajectorySessionId ?: return
        if (!canLoadOlderTrajectory(current, trajectoryLoading, trajectoryOlderLoading)) return
        val requestedBeforeSeq = current.nextBeforeSeq ?: return
        trajectoryOlderLoading = true
        trajectoryError = null
        trajectoryOlderJob = scope.launch {
            try {
                val page = repository.sessionTrajectory(sessionId, beforeSeq = requestedBeforeSeq)
                if (trajectorySessionId == sessionId) {
                    val latest = trajectory
                    if (latest != null) {
                        val merged = appendTrajectoryPage(latest, page, requestedBeforeSeq)
                        trajectory = merged
                        trajectoryCache[sessionId] = CachedTrajectory(
                            merged,
                            System.currentTimeMillis(),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (trajectorySessionId == sessionId) {
                    trajectoryError = trajectoryErrorMessage(error)
                }
            } finally {
                if (trajectorySessionId == sessionId) trajectoryOlderLoading = false
            }
        }
    }

    fun refreshTrajectory() {
        trajectorySessionId?.let { sessionId -> loadSessionTrajectory(sessionId, force = true) }
    }

    fun retryTrajectory() = refreshTrajectory()

    private fun trajectoryErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported, is DshApiException.NotFound -> TRAJECTORY_LOAD_FAILED_COPY
        else -> errorMessage(error)
    }

    // endregion

    // region workspace files (read-only)

    var filesSheetOpen: Boolean by mutableStateOf(false)
        private set
    var filesSessionId: String? by mutableStateOf(null)
        private set
    var filesPath: String by mutableStateOf("")
        private set
    var filesPage: HostFilesPage? by mutableStateOf(null)
        private set
    var filesLoading: Boolean by mutableStateOf(false)
        private set
    var filesError: String? by mutableStateOf(null)
        private set
    var filesNotice: String? by mutableStateOf(null)
        private set
    var filesOpeningRawPath: String? by mutableStateOf(null)
        private set
    var filePreview: HostFileContent? by mutableStateOf(null)
        private set
    var filePreviewPath: String? by mutableStateOf(null)
        private set
    var filePreviewName: String by mutableStateOf("")
        private set
    var filePreviewLoading: Boolean by mutableStateOf(false)
        private set
    var filePreviewError: String? by mutableStateOf(null)
        private set

    private data class CachedFilesPage(
        val page: HostFilesPage,
        val path: String,
        val fetchedAtMs: Long,
    )

    private val filesCache = mutableMapOf<String, CachedFilesPage>()
    private var filesJob: Job? = null
    private var filePreviewJob: Job? = null
    private var filesLoadToken: Int = 0
    private val openingRawPaths = mutableSetOf<String>()

    fun openFiles(sessionId: String) {
        if (sessionId.isBlank()) return
        filesSheetOpen = true
        filesNotice = null
        if (filesSessionId != sessionId) {
            filesSessionId = sessionId
            filesPath = ""
            filesPage = null
            clearFilePreview()
        }
        loadWorkspacePath("")
    }

    fun closeFiles() {
        filesSheetOpen = false
        filesError = null
        filesNotice = null
        filesOpeningRawPath = null
        filesJob?.cancel()
        filesJob = null
        filesLoading = false
        clearFilePreview()
    }

    /** Navigates to one host-relative directory with stale-response and TTL guards. */
    fun loadWorkspacePath(path: String, force: Boolean = false) {
        val sessionId = filesSessionId ?: return
        val safePath = sanitizedRelativePath(path)
        val cacheKey = sessionId + "\u0000" + safePath
        val cached = filesCache[cacheKey]
        if (!shouldFetchWorkspacePath(
                cachedPage = cached?.page,
                cachedPath = cached?.path,
                requestedPath = safePath,
                loading = filesLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            if (filesPath == safePath && cached != null) filesPage = cached.page
            return
        }
        filesPath = safePath
        clearFilePreview()
        if (!isLive) {
            filesPage = HostFilesPage.Unsupported
            filesLoading = false
            return
        }
        filesJob?.cancel()
        filesLoading = true
        filesError = null
        filesNotice = null
        val token = ++filesLoadToken
        filesJob = scope.launch {
            try {
                val page = repository.sessionFiles(sessionId, safePath)
                filesCache[cacheKey] = CachedFilesPage(page, safePath, System.currentTimeMillis())
                if (filesSessionId == sessionId && filesPath == safePath && token == filesLoadToken) {
                    filesPage = page
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (filesSessionId == sessionId && filesPath == safePath && token == filesLoadToken) {
                    filesError = filesErrorMessage(error)
                    if (filesPage?.path != safePath) filesPage = null
                }
            } finally {
                if (filesSessionId == sessionId && token == filesLoadToken) filesLoading = false
            }
        }
    }

    fun refreshFiles() = loadWorkspacePath(filesPath, force = true)

    fun retryFiles() = refreshFiles()

    /** Up/parent navigation; refuses to leave the workspace root. */
    fun filesUp() {
        if (filesPath.isEmpty()) return
        val target = filesPage?.parent ?: workspaceParentPath(filesPath)
        if (isWorkspaceNavigationStale(target, filesPath)) loadWorkspacePath(target)
    }

    fun openWorkspaceEntry(entry: WorkspaceEntry) {
        if (entry.isDirectory) {
            loadWorkspacePath(entry.path)
        } else if (isTextPreviewable(entry.mimeType, entry.name)) {
            openFilePreview(entry.path, entry.name)
        } else {
            openWorkspaceFileExternally(entry.path, entry.name, entry.mimeType)
        }
    }

    /** Text preview with a stale-path guard; binary/oversize (415) becomes a clear notice. */
    fun openFilePreview(path: String, name: String) {
        val sessionId = filesSessionId ?: return
        val safePath = sanitizedRelativePath(path)
        if (safePath.isEmpty()) return
        filePreviewPath = safePath
        filePreviewName = name
        filePreview = null
        filePreviewError = null
        filePreviewLoading = true
        filePreviewJob?.cancel()
        filePreviewJob = scope.launch {
            try {
                val content = repository.sessionFileContent(sessionId, safePath)
                if (filesSessionId == sessionId && filePreviewPath == safePath) {
                    filePreview = content
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (filesSessionId == sessionId && filePreviewPath == safePath) {
                    filePreviewError = filePreviewErrorMessage(error)
                }
            } finally {
                if (filesSessionId == sessionId && filePreviewPath == safePath) {
                    filePreviewLoading = false
                }
            }
        }
    }

    fun retryFilePreview() {
        val path = filePreviewPath ?: return
        openFilePreview(path, filePreviewName)
    }

    fun clearFilePreview() {
        filePreviewJob?.cancel()
        filePreviewJob = null
        filePreview = null
        filePreviewPath = null
        filePreviewName = ""
        filePreviewLoading = false
        filePreviewError = null
    }

    /** Downloads raw bytes into cache and opens them through the FileProvider/share chooser. */
    fun openWorkspaceFileExternally(
        path: String,
        name: String,
        mimeType: String? = null,
    ) {
        val sessionId = filesSessionId ?: return
        val safePath = sanitizedRelativePath(path)
        if (safePath.isEmpty()) return
        if (!isLive) {
            filesNotice = "Workspace files need a paired host. Pair your desktop in Settings first."
            return
        }
        if (safePath in openingRawPaths) return
        openingRawPaths.add(safePath)
        filesNotice = null
        filesOpeningRawPath = safePath
        scope.launch {
            try {
                val file = repository.downloadWorkspaceFileRaw(
                    sessionId = sessionId,
                    path = safePath,
                    fileName = name,
                    cacheDir = appContext.cacheDir,
                )
                openCachedWorkspaceFile(file, name, mimeType)
            } catch (error: Exception) {
                filesNotice = filesRawErrorMessage(error)
            } finally {
                openingRawPaths.remove(safePath)
                if (filesOpeningRawPath == safePath) filesOpeningRawPath = null
            }
        }
    }

    private fun openCachedWorkspaceFile(file: File, name: String, mimeType: String?) {
        val safeMime = workspaceMimeType(name, mimeType)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.update_provider",
            file,
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, safeMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            view,
            "Open ${name.ifBlank { "file" }}",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(chooser)
        } catch (noViewer: Exception) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = safeMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(
                Intent.createChooser(share, "Share file")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun filesErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported -> FILES_UNSUPPORTED_COPY
        is DshApiException.NotFound -> "That folder is no longer available."
        is DshApiException.InvalidRequest -> "That folder can't be opened."
        else -> errorMessage(error)
    }

    private fun filePreviewErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Server -> if (error.httpStatus == 415) {
            FILES_PREVIEW_NOT_TEXT_COPY
        } else {
            errorMessage(error)
        }

        is DshApiException.Unsupported -> FILES_PREVIEW_NOT_TEXT_COPY
        is DshApiException.NotFound -> "That file is no longer available."
        is DshApiException.InvalidRequest -> FILES_PREVIEW_NOT_TEXT_COPY
        else -> errorMessage(error)
    }

    private fun filesRawErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Server -> if (error.httpStatus == 413 || error.httpStatus == 415) {
            FILES_RAW_TOO_LARGE_COPY
        } else {
            errorMessage(error)
        }

        is DshApiException.InvalidRequest -> FILES_RAW_TOO_LARGE_COPY
        is DshApiException.Unsupported -> FILES_UNSUPPORTED_COPY
        is DshApiException.NotFound -> "That file is no longer available."
        else -> errorMessage(error)
    }

    // endregion

    // region background jobs

    var jobsSheetOpen: Boolean by mutableStateOf(false)
        private set
    var jobsSessionId: String? by mutableStateOf(null)
        private set
    var jobs: HostJobs? by mutableStateOf(null)
        private set
    var jobsLoading: Boolean by mutableStateOf(false)
        private set
    var jobsError: String? by mutableStateOf(null)
        private set
    private data class CachedJobs(val result: HostJobs, val fetchedAtMs: Long)
    private val jobsCache = mutableMapOf<String, CachedJobs>()
    private var jobsJob: Job? = null

    fun openJobs(sessionId: String) {
        jobsSheetOpen = true
        jobsError = null
        loadSessionJobs(sessionId)
    }

    fun closeJobs() {
        jobsSheetOpen = false
        jobsError = null
        jobsJob?.cancel()
        jobsJob = null
        jobsLoading = false
    }

    /** 30 s TTL cache; cached jobs stay visible while a stale snapshot refreshes. */
    fun loadSessionJobs(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (jobsSessionId != sessionId) {
            jobsSessionId = sessionId
            jobs = jobsCache[sessionId]?.result
            jobsError = null
        }
        val cached = jobsCache[sessionId]
        if (!shouldLoadSessionJobs(
                cached = cached?.result ?: jobs,
                loading = jobsLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            return
        }
        if (!isLive) {
            jobs = HostJobs.Unsupported
            jobsLoading = false
            return
        }
        jobsJob?.cancel()
        jobsLoading = true
        jobsError = null
        jobsJob = scope.launch {
            try {
                val result = repository.sessionJobs(sessionId)
                jobsCache[sessionId] = CachedJobs(result, System.currentTimeMillis())
                if (jobsSessionId == sessionId) jobs = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (jobsSessionId == sessionId && jobs == null) {
                    jobsError = jobsErrorMessage(error)
                }
            } finally {
                if (jobsSessionId == sessionId) jobsLoading = false
            }
        }
    }

    fun refreshJobs() {
        jobsSessionId?.let { sessionId -> loadSessionJobs(sessionId, force = true) }
    }

    fun retryJobs() = refreshJobs()

    private fun jobsErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported, is DshApiException.NotFound -> JOBS_LOAD_FAILED_COPY
        else -> errorMessage(error)
    }

    // endregion

    // region context window

    var contextSessionId: String? by mutableStateOf(null)
        private set
    var context: HostContext? by mutableStateOf(null)
        private set
    var contextLoading: Boolean by mutableStateOf(false)
        private set
    var contextError: String? by mutableStateOf(null)
        private set
    private data class CachedContext(val result: HostContext, val fetchedAtMs: Long)
    private val contextCache = mutableMapOf<String, CachedContext>()
    private var contextJob: Job? = null

    /** Context for [sessionId] only (never leaks a previous session's meter). */
    fun contextFor(sessionId: String): HostContext? =
        context?.takeIf { contextSessionId == sessionId }

    /** 30 s TTL cache; silently unsupported/failed requests keep the chat usable. */
    fun loadSessionContext(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        if (contextSessionId != sessionId) {
            contextSessionId = sessionId
            context = contextCache[sessionId]?.result
            contextError = null
        }
        val cached = contextCache[sessionId]
        if (!shouldLoadSessionContext(
                cached = cached?.result ?: context,
                loading = contextLoading,
                force = force,
                fetchedAtMs = cached?.fetchedAtMs,
            )
        ) {
            return
        }
        if (!isLive) {
            context = HostContext.Unsupported
            contextLoading = false
            return
        }
        contextJob?.cancel()
        contextLoading = true
        contextError = null
        contextJob = scope.launch {
            try {
                val result = repository.sessionContext(sessionId)
                contextCache[sessionId] = CachedContext(result, System.currentTimeMillis())
                if (contextSessionId == sessionId) context = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (contextSessionId == sessionId && context == null) {
                    contextError = contextErrorMessage(error)
                }
            } finally {
                if (contextSessionId == sessionId) contextLoading = false
            }
        }
    }

    fun refreshContext() {
        contextSessionId?.let { sessionId -> loadSessionContext(sessionId, force = true) }
    }

    fun retryContext() = refreshContext()

    private fun contextErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported, is DshApiException.NotFound -> CONTEXT_LOAD_FAILED_COPY
        else -> errorMessage(error)
    }

    // endregion

    // region fork conversation

    var forkInProgressSessionId: String? by mutableStateOf(null)
        private set
    var forkStatus: String? by mutableStateOf(null)
        private set

    fun clearForkStatus() {
        forkStatus = null
    }

    /** Host fork boundary for a message id, if the transcript carried one. */
    fun messageSeqFor(messageId: String): Int? = repository.messageSeq(messageId)

    /**
     * Forks [sessionId] at [atSeq] (null = latest completed turn), refreshes the list, and opens
     * the new session when the host lists it.
     */
    fun forkConversation(sessionId: String, atSeq: Int? = null) {
        if (sessionId.isBlank() || forkInProgressSessionId != null) return
        if (atSeq != null && !forkSeqValid(atSeq)) {
            forkStatus = "That message can't be used as a fork point."
            return
        }
        forkInProgressSessionId = sessionId
        forkStatus = null
        scope.launch {
            try {
                val result = repository.forkSession(sessionId, forkAtSeqOrNull(atSeq))
                // Pull the new session into the list before selecting it.
                runCatching { repository.refresh() }
                val listed = repository.sessions.any { it.id == result.sessionId }
                if (listed) openSession(result.sessionId)
                forkStatus = forkResultStatus(result, listed)
            } catch (error: Exception) {
                forkStatus = forkErrorMessage(error)
            } finally {
                forkInProgressSessionId = null
            }
        }
    }

    private fun forkErrorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unsupported ->
            "This host doesn't support forking yet. Update the desktop host plugin."

        is DshApiException.InvalidRequest ->
            error.message ?: "No completed turn to fork from yet."

        is DshApiException.NotFound -> "That session is no longer available on the host."
        else -> errorMessage(error)
    }

    // endregion

    // region host-wide message search

    var hostSearchResult: HostSearchResult? by mutableStateOf(null)
        private set
    var hostSearchLoading: Boolean by mutableStateOf(false)
        private set
    var hostSearchError: String? by mutableStateOf(null)
        private set
    /** A Load more page is in flight; existing hits stay visible. */
    var hostSearchLoadingMore: Boolean by mutableStateOf(false)
        private set
    var hostSearchLoadMoreError: String? by mutableStateOf(null)
        private set
    private var hostSearchJob: Job? = null
    private var hostSearchLoadMoreJob: Job? = null
    private var hostSearchActiveQuery: String = ""
    /** S6: identity of the in-flight request = query + normalized roles + date preset. */
    private var hostSearchActiveKey: String = ""

    /** Role filters (empty = all roles) and date preset for the host-wide search. */
    var hostSearchRoles: Set<String> by mutableStateOf(emptySet())
        private set
    var hostSearchDatePreset: SearchDatePreset by mutableStateOf(SearchDatePreset.ANY)
        private set

    fun openHostSearch() {
        showSettings = false
        showHostSearch = true
        clearHostSearch()
    }

    fun closeHostSearch() {
        showHostSearch = false
        hostSearchJob?.cancel()
        hostSearchJob = null
        hostSearchLoadMoreJob?.cancel()
        hostSearchLoadMoreJob = null
        hostSearchLoading = false
        hostSearchLoadingMore = false
        hostSearchLoadMoreError = null
        // Leaving the screen resets filters so a later search never silently inherits them.
        hostSearchRoles = emptySet()
        hostSearchDatePreset = SearchDatePreset.ANY
        hostSearchActiveKey = ""
    }

    /** Clears the current query/result; in-flight requests are cancelled. Filters persist. */
    fun clearHostSearch() {
        hostSearchJob?.cancel()
        hostSearchJob = null
        hostSearchLoadMoreJob?.cancel()
        hostSearchLoadMoreJob = null
        hostSearchActiveQuery = ""
        hostSearchActiveKey = ""
        hostSearchResult = null
        hostSearchError = null
        hostSearchLoading = false
        hostSearchLoadingMore = false
        hostSearchLoadMoreError = null
    }

    /**
     * Runs a host-wide search. [force] re-runs the same query (Retry / IME action); otherwise a
     * duplicate query with a result already in hand is ignored. Queries shorter than two chars
     * clear the state without a request.
     */
    fun runHostSearch(rawQuery: String, force: Boolean = false) {
        val query = hostSearchQuery(rawQuery)
        if (query == null) {
            clearHostSearch()
            return
        }
        val roles = hostSearchRoles
        val datePreset = hostSearchDatePreset
        val key = hostSearchKey(query, roles, datePreset)
        if (!force && key == hostSearchActiveKey &&
            (hostSearchLoading || hostSearchResult != null)
        ) {
            return
        }
        hostSearchJob?.cancel()
        hostSearchLoadMoreJob?.cancel()
        hostSearchLoadMoreJob = null
        hostSearchActiveQuery = query
        hostSearchActiveKey = key
        if (!isLive) {
            hostSearchLoading = false
            hostSearchLoadingMore = false
            hostSearchLoadMoreError = null
            hostSearchError = null
            hostSearchResult = HostSearchResult.Unsupported
            return
        }
        hostSearchLoading = true
        hostSearchLoadingMore = false
        hostSearchLoadMoreError = null
        hostSearchResult = null
        hostSearchError = null
        hostSearchJob = scope.launch {
            try {
                val result = repository.searchMessages(query, roles, datePreset, offset = 0)
                if (hostSearchActiveKey == key) {
                    hostSearchResult = result
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (hostSearchActiveKey == key) {
                    hostSearchError = when (error) {
                        is DshApiException.InvalidRequest ->
                            error.message ?: "That search query was rejected by the host."

                        else -> errorMessage(error)
                    }
                }
            } finally {
                if (hostSearchActiveKey == key) hostSearchLoading = false
            }
        }
    }

    /**
     * Loads the next page for the current query/filters. Guarded by the same query/filter key as the
     * fresh search (offset is not part of the key) and by a separate in-flight flag; existing hits
     * stay visible if the page fails, so Retry can simply ask for the same cursor again.
     */
    fun loadMoreHostSearch() {
        val current = hostSearchResult ?: return
        val query = hostSearchActiveQuery.takeIf { it.isNotBlank() } ?: return
        val key = hostSearchActiveKey
        if (!canLoadMoreHostSearch(current, hostSearchLoading, hostSearchLoadingMore)) return
        val offset = current.nextOffset ?: return
        hostSearchLoadMoreError = null
        hostSearchLoadingMore = true
        hostSearchLoadMoreJob?.cancel()
        hostSearchLoadMoreJob = scope.launch {
            try {
                val page = repository.searchMessages(
                    query,
                    hostSearchRoles,
                    hostSearchDatePreset,
                    offset = offset,
                )
                if (hostSearchActiveKey == key) {
                    // A fresh search may have replaced the result while this page was in flight.
                    val latest = hostSearchResult
                    if (latest != null && latest.nextOffset == offset) {
                        hostSearchResult = appendHostSearchPage(
                            latest,
                            page,
                            requestedOffset = offset,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (hostSearchActiveKey == key) {
                    hostSearchLoadMoreError = when (error) {
                        is DshApiException.InvalidRequest ->
                            error.message ?: "The host rejected that page."

                        else -> errorMessage(error)
                    }
                }
            } finally {
                if (hostSearchActiveKey == key) hostSearchLoadingMore = false
            }
        }
    }

    fun retryHostSearchLoadMore() {
        if (hostSearchLoadMoreError == null) return
        hostSearchLoadMoreError = null
        loadMoreHostSearch()
    }

    fun retryHostSearch() {
        if (hostSearchActiveQuery.isNotBlank()) runHostSearch(hostSearchActiveQuery, force = true)
    }

    /** Multi-select role chip; selecting all roles collapses back to "All". */
    fun toggleHostSearchRole(role: String) {
        val next = toggleSearchRole(hostSearchRoles, role)
        if (next == hostSearchRoles) return
        hostSearchRoles = next
        rerunHostSearchWithFilters()
    }

    fun selectAllHostSearchRoles() {
        if (hostSearchRoles.isEmpty()) return
        hostSearchRoles = emptySet()
        rerunHostSearchWithFilters()
    }

    fun updateHostSearchDatePreset(preset: SearchDatePreset) {
        if (hostSearchDatePreset == preset) return
        hostSearchDatePreset = preset
        rerunHostSearchWithFilters()
    }

    fun clearHostSearchFilters() {
        if (!searchHasFilters(hostSearchRoles, hostSearchDatePreset)) return
        hostSearchRoles = emptySet()
        hostSearchDatePreset = SearchDatePreset.ANY
        rerunHostSearchWithFilters()
    }

    /** Filters change re-runs the current query immediately (cancelling the in-flight one). */
    private fun rerunHostSearchWithFilters() {
        if (hostSearchActiveQuery.isNotBlank()) {
            runHostSearch(hostSearchActiveQuery, force = true)
        }
    }

    /** Host switches must not leak a query/result from the previous host. */
    private fun resetHostSearchForHostChange() {
        hostSearchJob?.cancel()
        hostSearchJob = null
        hostSearchLoadMoreJob?.cancel()
        hostSearchLoadMoreJob = null
        hostSearchActiveQuery = ""
        hostSearchActiveKey = ""
        hostSearchRoles = emptySet()
        hostSearchDatePreset = SearchDatePreset.ANY
        hostSearchResult = null
        hostSearchError = null
        hostSearchLoading = false
        hostSearchLoadingMore = false
        hostSearchLoadMoreError = null
    }

    // endregion

    fun updateDarkTheme(enabled: Boolean) {
        darkTheme = enabled
        presentationPrefs.edit().putBoolean("dark_theme", enabled).apply()
    }

    fun updateAmoledDark(enabled: Boolean) {
        amoledDark = enabled
        presentationPrefs.edit().putBoolean("amoled_dark", enabled).apply()
    }

    /** Applies and persists the accent pack immediately; unknown values never reach this setter. */
    fun updateThemeAccent(accent: ThemeAccent) {
        themeAccent = accent
        presentationPrefs.edit()
            .putString(ACCENT_PREF_KEY, themeAccentStorageValue(accent))
            .apply()
    }

    fun updateChatFontScale(scale: Float) {
        if (scale !in setOf(0.9f, 1.0f, 1.15f, 1.3f)) return
        chatFontScale = scale
        presentationPrefs.edit().putFloat("chat_font_scale", scale).apply()
    }

    /** Updates the app-wide interface text scale; ignores values outside the supported set. */
    fun updateAppFontScale(scale: Float) {
        if (scale !in setOf(0.9f, 1.0f, 1.15f, 1.3f)) return
        appFontScale = scale
        presentationPrefs.edit().putFloat("app_font_scale", scale).apply()
    }

    fun updateWrapCodeBlocks(enabled: Boolean) {
        wrapCodeBlocks = enabled
        presentationPrefs.edit().putBoolean("wrap_code_blocks", enabled).apply()
    }

    fun updateNotifyInput(enabled: Boolean) {
        notifyInput = enabled
        presentationPrefs.edit().putBoolean("notify_input", enabled).apply()
    }

    fun updateNotifyDone(enabled: Boolean) {
        notifyDone = enabled
        presentationPrefs.edit().putBoolean("notify_done", enabled).apply()
    }

    fun updateNotifyGoal(enabled: Boolean) {
        notifyGoal = enabled
        presentationPrefs.edit().putBoolean("notify_goal", enabled).apply()
    }

    fun updateLowBalanceAlerts(enabled: Boolean) {
        lowBalanceAlertsEnabled = enabled
        presentationPrefs.edit().putBoolean("low_balance_alerts", enabled).apply()
    }

    fun updateLowBalanceThreshold(amount: Double) {
        if (amount !in setOf(5.0, 10.0, 20.0)) return
        lowBalanceThreshold = amount
        presentationPrefs.edit().putFloat("low_balance_threshold", amount.toFloat()).apply()
    }

    /** Local per-session notification mute (turn-finished and input-needed). */
    fun isSessionMuted(sessionId: String): Boolean =
        sessionId.isNotBlank() && sessionId in mutedNotificationSessions

    fun toggleSessionMuted(sessionId: String) {
        if (sessionId.isBlank()) return
        mutedNotificationSessions = if (sessionId in mutedNotificationSessions) {
            mutedNotificationSessions - sessionId
        } else {
            mutedNotificationSessions + sessionId
        }
        presentationPrefs.edit()
            .putStringSet("muted_notifications", mutedNotificationSessions)
            .apply()
        schedulePushSuppressionSync()
    }

    fun updateVoicePreferOffline(enabled: Boolean) {
        voicePreferOffline = enabled
        presentationPrefs.edit().putBoolean("voice_prefer_offline", enabled).apply()
    }

    fun updateVoiceLanguage(tag: String) {
        voiceLanguageTag = tag.trim()
        presentationPrefs.edit().putString("voice_language", voiceLanguageTag).apply()
    }

    fun updateQuietHours(enabled: Boolean) {
        quietHoursEnabled = enabled
        presentationPrefs.edit().putBoolean("quiet_hours", enabled).apply()
        schedulePushSuppressionSync()
    }

    fun updateQuietStart(hour: Int) {
        quietStartHour = hour.coerceIn(0, 23)
        presentationPrefs.edit().putInt("quiet_start_hour", quietStartHour).apply()
        schedulePushSuppressionSync()
    }

    fun updateQuietEnd(hour: Int) {
        quietEndHour = hour.coerceIn(0, 23)
        presentationPrefs.edit().putInt("quiet_end_hour", quietEndHour).apply()
        schedulePushSuppressionSync()
    }

    fun updateAppLock(enabled: Boolean) {
        appLockEnabled = enabled
        presentationPrefs.edit().putBoolean("app_lock", enabled).apply()
    }

    fun updateAppLockDelay(seconds: Int) {
        if (seconds !in setOf(0, 15, 30, 60)) return
        appLockDelaySeconds = seconds
        presentationPrefs.edit().putInt("app_lock_delay_seconds", seconds).apply()
    }

    fun updateBackgroundSync(enabled: Boolean) {
        backgroundSyncEnabled = enabled
        presentationPrefs.edit().putBoolean("background_sync", enabled).apply()
        if (enabled) {
            BackgroundSyncService.start(appContext)
        } else {
            BackgroundSyncService.stop(appContext)
        }
    }

    fun updateBackgroundSyncInterval(seconds: Int) {
        if (seconds !in setOf(20, 60, 300)) return
        backgroundSyncIntervalSeconds = seconds
        presentationPrefs.edit().putInt("background_sync_interval", seconds).apply()
    }

    fun answerApprovalFor(approvalId: String, approved: Boolean) {
        // Read-only host: answers are made on the desktop; don't fire a doomed request.
        if (approvalsReadOnly) return
        scope.launch {
            try {
                repository.decideApproval(approvalId, approved)
                dismissedApprovalIds.remove(approvalId)
                syncError = null
            } catch (error: Exception) {
                handleApprovalDecisionFailure(approvalId, error)
            }
        }
    }

    /**
     * First-answer-wins handling: when the approval is already answered/expired (404/409) or the
     * host rejects mobile answering (501), drop the stale card, refresh snapshots and show a short
     * status instead of a raw error. A read-only host gets the read-only copy.
     */
    private fun handleApprovalDecisionFailure(approvalId: String, error: Exception) {
        val notice = approvalDecisionFailureNotice(error, readOnly = approvalsReadOnly)
        if (notice == null) {
            syncError = errorMessage(error)
            return
        }
        syncError = notice
        if (isAlreadyAnsweredApprovalError(error)) {
            if (approvalId !in dismissedApprovalIds) {
                dismissedApprovalIds.add(approvalId)
                while (dismissedApprovalIds.size > MAX_DISMISSED_APPROVALS) {
                    dismissedApprovalIds.removeAt(0)
                }
            }
        }
        scope.launch {
            // Snapshot refresh is authoritative: the stale approval disappears there too.
            runCatching { repository.refresh() }
        }
    }

    /** True while an answer for this question is in flight; also drives the Send button state. */
    fun isQuestionAnswering(questionId: String): Boolean = questionId in answeringQuestionIds

    fun answerQuestionFor(questionId: String, answer: String, optionIndex: Int?) {
        // Read-only host: answers are made on the desktop; don't fire a doomed request.
        if (questionsReadOnly) return
        // Double-send guard: one in-flight answer per question.
        if (questionId in answeringQuestionIds) return
        answeringQuestionIds.add(questionId)
        scope.launch {
            try {
                repository.answerQuestion(questionId, answer, optionIndex)
                // Success: the answered row disappears via question.resolved; keep the batch.
                dismissedQuestionIds.remove(questionId)
                syncError = null
            } catch (error: Exception) {
                handleQuestionAnswerFailure(questionId, error)
            } finally {
                answeringQuestionIds.remove(questionId)
            }
        }
    }

    /**
     * Lost-race handling for question answers: hide only the stale question, refresh snapshots and
     * show a short status. A 501/read-only host gets the read-only copy instead of a raw error.
     */
    private fun handleQuestionAnswerFailure(questionId: String, error: Exception) {
        val notice = questionAnswerFailureNotice(error, readOnly = questionsReadOnly)
        if (notice == null) {
            syncError = errorMessage(error)
            return
        }
        syncError = notice
        if (isAlreadyAnsweredApprovalError(error)) {
            if (questionId !in dismissedQuestionIds) {
                dismissedQuestionIds.add(questionId)
                while (dismissedQuestionIds.size > MAX_DISMISSED_QUESTIONS) {
                    dismissedQuestionIds.removeAt(0)
                }
            }
        }
        scope.launch {
            // Snapshot refresh is authoritative: the stale question disappears there too.
            runCatching { repository.refresh() }
        }
    }

    /** Fetches usage for the active host (demo data when no live host is active). */
    fun refreshUsage() {
        if (usageLoading) return
        usageLoading = true
        usageError = null
        scope.launch {
            try {
                usage = repository.usage()
                recordUsageBaseline()
                maybeNotifyLowBalance()
            } catch (error: Exception) {
                usageError = when (error) {
                    is DshApiException.NotFound, is DshApiException.Unsupported ->
                        "This host hasn't loaded the usage API yet. Restart the DSH host to enable it."
                    else -> errorMessage(error)
                }
            } finally {
                usageLoading = false
            }
        }
    }

    private fun recordUsageBaseline() {
        val current = usage?.totals?.totalTokens ?: return
        val today = java.time.LocalDate.now().toString()
        val reset = current < usageDayBaseline
        if (usageDayDate != today || reset) {
            usageDayDate = today
            usageDayBaseline = current
            presentationPrefs.edit()
                .putString("usage_day_date", today)
                .putLong("usage_day_baseline", current)
                .apply()
        }
    }

    /** Once per day, tell the user when the remaining credit drops below their threshold. */
    private fun maybeNotifyLowBalance() {        if (!lowBalanceAlertsEnabled) return
        val balances = usage?.balance?.balances.orEmpty()
        val lowest = balances
            .mapNotNull { info ->
                info.totalBalance.toDoubleOrNull()?.let { amount -> amount to info.currency }
            }
            .minByOrNull { it.first }
            ?: return
        if (lowest.first >= lowBalanceThreshold) return
        val today = java.time.LocalDate.now().toString()
        if (presentationPrefs.getString("last_low_balance_alert", null) == today) return
        DshNotifications.lowBalance(
            appContext,
            "Only ${formatCredit(lowest.first)} ${lowest.second} of DeepSeek credit remains. " +
                "Top up to avoid interrupted turns.",
        )
        presentationPrefs.edit().putString("last_low_balance_alert", today).apply()
    }

    private fun formatCredit(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toInt().toString() else String.format(java.util.Locale.US, "%.2f", amount)

    /** Bumped when an external share writes a draft, so an open chat refreshes it. */
    var draftRevision: Int by mutableStateOf(0)
        private set

    private var pendingSharedText: String? = null

    /**
     * "Share to DSH" from another app: append the shared text to the most relevant session's
     * draft. When no session exists yet, remember it and open the new-session picker; the chat
     * consumes it as soon as the session is created.
     */
    fun receiveSharedText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val target = activeSessionId ?: visibleSessions().firstOrNull()?.id
        if (target == null) {
            pendingSharedText = text
            showNewSession = true
            return
        }
        val existing = loadDraft(target)
        val combined = if (existing.isBlank()) text else existing.trimEnd() + "\n\n" + text
        saveDraft(target, combined)
        draftRevision += 1
        if (activeSessionId != target) openSession(target)
    }

    /** Claims the pending shared text for the chat that is opening. */
    fun consumePendingSharedText(): String? {
        val text = pendingSharedText
        pendingSharedText = null
        return text
    }

    private val pendingSharedAttachments = mutableListOf<SharedAttachment>()
    var sharedAttachmentsRevision: Int by mutableStateOf(0)
        private set

    /** Share-sheet images: attach to the current session, or ask for a new one. */
    fun receiveSharedAttachments(items: List<SharedAttachment>) {
        if (items.isEmpty()) return
        pendingSharedAttachments.addAll(items)
        sharedAttachmentsRevision += 1
        val target = activeSessionId ?: visibleSessions().firstOrNull()?.id
        if (target == null) {
            showNewSession = true
        } else if (activeSessionId != target) {
            openSession(target)
        }
    }

    fun consumeSharedAttachments(): List<SharedAttachment> {
        if (pendingSharedAttachments.isEmpty()) return emptyList()
        val items = pendingSharedAttachments.toList()
        pendingSharedAttachments.clear()
        return items
    }

    fun reportSharedAttachmentFailure() {
        syncError = "Couldn't read the shared image. Try sharing it from the Gallery app and allow access."
    }

    fun openHosts() {
        showHosts = true
    }

    fun closeHosts() {
        showHosts = false
    }

    fun openNewSession() {
        showNewSession = true
    }

    fun dismissNewSession() {
        showNewSession = false
    }

    // region agent presets

    /** Host preset roster; null until the new-session sheet first loads it. */
    // Declared near the top of the class because startRemote() (called from init) writes it.

    /** Loads the host preset roster once per active host (called when the sheet opens). */
    fun loadPresetsIfNeeded() {
        if (presetRoster != null || presetsLoading) return
        if (!isLive) {
            presetRoster = PresetRoster.Fallback
            return
        }
        presetsLoading = true
        scope.launch {
            presetRoster = runCatching { repository.presets() }
                .getOrElse { PresetRoster.Fallback }
            presetsLoading = false
        }
    }

    // endregion

    /**
     * Opens the pairing screen. [labelDefault] pre-fills the host label: "Desktop" from the
     * welcome screen, "Cloud" for the cloud-host path, empty for "Add host".
     */
    fun openPairing(labelDefault: String? = null) {
        pairingLabelDefault = labelDefault.orEmpty()
        pairingError = null
        showPairing = true
    }

    fun closePairing() {
        showPairing = false
        pairingError = null
    }

    fun clearSyncError() {
        syncError = null
    }

    /** Composer draft for one session: survives navigation, tab switches and process death. */
    fun loadDraft(sessionId: String): String = draftStore.load(sessionId)

    fun saveDraft(sessionId: String, text: String) = draftStore.save(sessionId, text)

    /**
     * Append a workspace-file mention to the composer draft (Files browser → "Mention in prompt").
     * Never sends; the chat only picks up the new draft through [draftRevision].
     */
    fun mentionWorkspaceFileInDraft(sessionId: String, relativePath: String): Boolean {
        val mention = workspaceMentionText(relativePath)
        if (mention.isEmpty()) return false
        val existing = loadDraft(sessionId)
        val combined = if (existing.isBlank()) mention else existing.trimEnd() + " " + mention
        saveDraft(sessionId, combined)
        draftRevision += 1
        return true
    }

    fun clearDraft(sessionId: String) = draftStore.clear(sessionId)

    // endregion

    // region local backup (export/import of local-only state)

    private val draftBackupPrefs
        get() = appContext.getSharedPreferences("session-drafts", Context.MODE_PRIVATE)
    private val pendingAttachmentBackupPrefs
        get() = appContext.getSharedPreferences("dsh-pending-attachments", Context.MODE_PRIVATE)
    private val snippetBackupPrefs
        get() = appContext.getSharedPreferences("prompt-snippets", Context.MODE_PRIVATE)

    /**
     * Snapshot of everything the local backup covers. Host profiles/tokens, app lock and caches are
     * deliberately absent.
     */
    fun buildLocalBackup(appVersion: String): LocalBackupData {
        val drafts = draftBackupPrefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("draft:") || value !is String || value.isBlank()) return@mapNotNull null
            key.removePrefix("draft:") to value
        }.toMap()
        val pendingAttachmentDrafts = pendingAttachmentBackupPrefs.all.mapNotNull { (sessionId, value) ->
            if (sessionId.isBlank() || value !is String) return@mapNotNull null
            val records = runCatching {
                DshJson.decodeFromString<List<PendingAttachmentRecord>>(value)
            }.getOrDefault(emptyList()).filter { it.attachmentId.isNotBlank() }
            if (records.isEmpty()) {
                null
            } else {
                sessionId to records.map { record ->
                    BackupAttachmentDraft(
                        name = record.name,
                        mimeType = record.mimeType,
                        sizeBytes = record.sizeBytes,
                        attachmentId = record.attachmentId,
                    )
                }
            }
        }.toMap()
        val queue = queuedPrompts
            .filterValues { prompts -> prompts.isNotEmpty() }
            .mapValues { (_, prompts) ->
                prompts.map { prompt ->
                    BackupQueuedPrompt(
                        id = prompt.id,
                        text = prompt.text,
                        attachmentIds = prompt.attachmentIds,
                    )
                }
            }
        return LocalBackupData(
            exportedAt = Instant.now().toString(),
            appVersion = appVersion,
            aliases = sessionAliases.filterValues { it.isNotBlank() },
            pinned = pinnedSessionIds.filter { it.isNotBlank() }.sorted(),
            muted = mutedNotificationSessions.filter { it.isNotBlank() }.sorted(),
            hidden = hiddenSessionIds.filter { it.isNotBlank() }.sorted(),
            drafts = drafts,
            pendingAttachmentDrafts = pendingAttachmentDrafts,
            queue = queue,
            snippets = promptSnippets.load().map { snippet ->
                BackupSnippet(id = snippet.id, title = snippet.title, prompt = snippet.prompt)
            },
            settings = BackupSettings(
                darkTheme = darkTheme,
                amoledDark = amoledDark,
                chatFontScale = chatFontScale,
                appFontScale = appFontScale,
                wrapCodeBlocks = wrapCodeBlocks,
                notifyInput = notifyInput,
                notifyDone = notifyDone,
                notifyGoal = notifyGoal,
                lowBalanceAlerts = lowBalanceAlertsEnabled,
                lowBalanceThreshold = lowBalanceThreshold,
                quietHours = quietHoursEnabled,
                quietStartHour = quietStartHour,
                quietEndHour = quietEndHour,
                voicePreferOffline = voicePreferOffline,
                voiceLanguage = voiceLanguageTag,
            ),
        )
    }

    /** Counts for the Settings summary (reads the current local state). */
    fun localBackupSnapshotCounts(): LocalBackupCounts = localBackupCounts(buildLocalBackup(""))

    /**
     * Applies an imported backup to the local stores/prefs. Host pairing, tokens, app lock and
     * caches are untouched; the host-persisted hidden list is only mirrored locally.
     */
    fun applyLocalBackup(data: LocalBackupData): LocalBackupCounts {
        val validAliases = data.aliases
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }
        sessionAliases.clear()
        sessionAliases.putAll(validAliases)
        aliasStore.save(validAliases)

        pinnedSessionIds = data.pinned.filter { it.isNotBlank() }.toSet()
        pinnedStore.save(pinnedSessionIds)

        mutedNotificationSessions = data.muted.filter { it.isNotBlank() }.toSet()
        presentationPrefs.edit()
            .putStringSet("muted_notifications", mutedNotificationSessions)
            .apply()
        schedulePushSuppressionSync()

        hiddenSessionIds = data.hidden.filter { it.isNotBlank() }.toSet()

        draftBackupPrefs.edit().clear().apply()
        if (data.drafts.isNotEmpty()) {
            val editor = draftBackupPrefs.edit()
            data.drafts.forEach { (sessionId, text) ->
                if (sessionId.isNotBlank() && text.isNotBlank()) {
                    editor.putString("draft:$sessionId", text)
                }
            }
            editor.apply()
        }

        pendingAttachmentBackupPrefs.edit().clear().apply()
        if (data.pendingAttachmentDrafts.isNotEmpty()) {
            val editor = pendingAttachmentBackupPrefs.edit()
            data.pendingAttachmentDrafts.forEach { (sessionId, records) ->
                if (sessionId.isBlank()) return@forEach
                val valid = records
                    .filter { it.attachmentId.isNotBlank() }
                    .map { record ->
                        PendingAttachmentRecord(
                            name = record.name,
                            mimeType = record.mimeType,
                            sizeBytes = record.sizeBytes,
                            attachmentId = record.attachmentId,
                        )
                    }
                if (valid.isEmpty()) return@forEach
                runCatching { editor.putString(sessionId, DshJson.encodeToString(valid)) }
            }
            editor.apply()
        }

        queuedPrompts.clear()
        data.queue.forEach { (sessionId, prompts) ->
            if (sessionId.isBlank() || prompts.isEmpty()) return@forEach
            queuedPrompts[sessionId] = prompts.map { prompt ->
                QueuedPrompt(
                    id = prompt.id,
                    text = prompt.text,
                    attachmentIds = prompt.attachmentIds,
                )
            }
        }
        persistQueuedPrompts()

        val snippets = data.snippets
            .filter { it.prompt.isNotBlank() }
            .map { snippet ->
                PromptSnippet(id = snippet.id, title = snippet.title, prompt = snippet.prompt)
            }
        snippetBackupPrefs.edit()
            .putString("snippets", DshJson.encodeToString(snippets))
            .apply()

        val settings = data.settings
        settings.darkTheme?.let(::updateDarkTheme)
        settings.amoledDark?.let(::updateAmoledDark)
        settings.chatFontScale?.let(::updateChatFontScale)
        settings.appFontScale?.let(::updateAppFontScale)
        settings.wrapCodeBlocks?.let(::updateWrapCodeBlocks)
        settings.notifyInput?.let(::updateNotifyInput)
        settings.notifyDone?.let(::updateNotifyDone)
        settings.notifyGoal?.let(::updateNotifyGoal)
        settings.lowBalanceAlerts?.let(::updateLowBalanceAlerts)
        settings.lowBalanceThreshold?.let(::updateLowBalanceThreshold)
        settings.quietHours?.let(::updateQuietHours)
        settings.quietStartHour?.let(::updateQuietStart)
        settings.quietEndHour?.let(::updateQuietEnd)
        settings.voicePreferOffline?.let(::updateVoicePreferOffline)
        settings.voiceLanguage?.let(::updateVoiceLanguage)

        return localBackupCounts(data)
    }

    // endregion

    // region actions

    fun newSession(workspaceId: String, preset: String) {
        scope.launch {
            try {
                val session = repository.createSession(workspaceId, preset)
                showNewSession = false
                openSession(session.id)
                syncError = null
            } catch (error: Exception) {
                syncError = errorMessage(error)
            }
        }
    }

    /**
     * Sends the composer content for the active session. Prompt text may be empty when at least
     * one attachment is included (contract v0.2.2). Throws so the composer can show inline errors.
     */
    suspend fun sendMessage(
        raw: String,
        attachmentIds: List<String> = emptyList(),
        sessionId: String? = activeSessionId,
    ) {
        val targetSessionId = sessionId ?: return
        val text = raw.trim()
        val ids = AttachmentIds.normalize(attachmentIds)
        if (text.isEmpty() && ids.isEmpty()) return
        if (text.isNotEmpty()) promptHistory.add(text)
        val wasWorking = repository.sessions.firstOrNull { it.id == targetSessionId }
            ?.status == SessionStatus.Working
        if (wasWorking) {
            // Hold the message on the phone while a turn is running so the user can cancel it.
            // The host's own queue has no removal API, so sending immediately made the "IN QUEUE"
            // chip undeletable and could leave stale queued prompts behind.
            queuedPrompts[targetSessionId] = queuedFor(targetSessionId) + QueuedPrompt(
                id = System.nanoTime(),
                text = text,
                attachmentIds = ids,
            )
            persistQueuedPrompts()
            syncError = null
            return
        }
        repository.sendPrompt(targetSessionId, text, ids)
        syncError = null
    }
    /** Prompts held locally until the current turn finishes; they are cancelable here. */
    fun queuedFor(sessionId: String): List<QueuedPrompt> = queuedPrompts[sessionId].orEmpty()

    /**
     * Searches messages in the offline transcript cache. Runs on IO; returns an empty list while
     * there is no paired host or when nothing cached matches.
     */
    suspend fun searchCachedMessages(query: String): List<TranscriptSearchHit> {
        if (query.trim().length < 2) return emptyList()
        val profile = activeProfile ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                HostTranscriptCache(appContext, profile.baseUrl).search(query)
            }.getOrDefault(emptyList())
        }
    }

    fun cancelQueued(sessionId: String, id: Long) {
        queuedPrompts[sessionId] = queuedFor(sessionId).filterNot { it.id == id }
        persistQueuedPrompts()
    }

    fun cancelAllQueued(sessionId: String) {
        queuedPrompts.remove(sessionId)
        persistQueuedPrompts()
    }

    /** Moves a queued prompt earlier/later so the next turn sends it in the chosen order. */
    fun moveQueued(sessionId: String, id: Long, offset: Int) {
        val queue = queuedFor(sessionId)
        val index = queue.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = index + offset
        if (target !in queue.indices) return
        val reordered = queue.toMutableList()
        val item = reordered.removeAt(index)
        reordered.add(target, item)
        queuedPrompts[sessionId] = reordered
        persistQueuedPrompts()
    }

    /** Removes a queued prompt and returns it so the composer can restore its text for editing. */
    fun takeQueuedForEdit(sessionId: String, id: Long): QueuedPrompt? {
        val queue = queuedFor(sessionId)
        val item = queue.firstOrNull { it.id == id } ?: return null
        queuedPrompts[sessionId] = queue.filterNot { it.id == id }
        persistQueuedPrompts()
        return item
    }

    /** Recent prompts sent from this phone, most recent first, for quick reuse. */
    fun recentPrompts(): List<String> = promptHistory.load()

    /** User-saved reusable prompts. */
    fun savedSnippets(): List<PromptSnippet> = promptSnippets.load()

    fun saveSnippet(prompt: String) {
        promptSnippets.add(prompt)
    }

    fun deleteSnippet(id: String) {
        promptSnippets.delete(id)
    }

    /**
     * Sends the next queued prompt once the agent reports idle. The queue is drained one message at
     * a time; a failed send restores the prompt and surfaces the error.
     */
    fun flushQueued(sessionId: String) {
        if (sessionId in flushingSessions) return
        val queue = queuedFor(sessionId)
        if (queue.isEmpty()) return
        if (sessions.firstOrNull { it.id == sessionId }?.status == SessionStatus.Working) return
        val next = queue.first()
        queuedPrompts[sessionId] = queue.drop(1)
        persistQueuedPrompts()
        flushingSessions += sessionId
        scope.launch {
            try {
                repository.sendPrompt(sessionId, next.text, next.attachmentIds)
                syncError = null
            } catch (error: Exception) {
                queuedPrompts[sessionId] = listOf(next) + queuedFor(sessionId)
                syncError = errorMessage(error)
            } finally {
                persistQueuedPrompts()
                flushingSessions -= sessionId
            }
        }
    }

    /** Uploads one attachment for the active session so it can be referenced by the next prompt. */
    suspend fun uploadAttachment(
        name: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit = {},
        sessionId: String? = null,
    ): UploadedAttachment {
        val targetSessionId = sessionId ?: activeSessionId
            ?: throw DshApiException.Protocol("Open a session before attaching files.")
        return repository.uploadAttachment(targetSessionId, name, mimeType, bytes, onProgress)
    }

    /** True while this historical attachment is being downloaded for opening. */
    fun isAttachmentOpening(attachmentId: String): Boolean = attachmentId in openingAttachmentIds

    /**
     * Cached/on-demand file for a historical attachment. Thumbnails and the open flow share this
     * cache, so a thumbnail decode also warms the file used by the full-view intent.
     */
    suspend fun historicalAttachmentFile(
        sessionId: String,
        attachment: MessageAttachment,
    ): File = repository.downloadAttachment(
        sessionId = sessionId,
        attachmentId = attachment.id,
        fileName = attachment.name,
        cacheDir = appContext.cacheDir,
    )

    /**
     * Downloads a historical message attachment (once; cached under cacheDir/attachments) and opens
     * it through the app FileProvider. Errors land in [syncError]; duplicate taps are ignored.
     */
    fun openAttachment(sessionId: String, attachment: MessageAttachment) {
        if (!isLive) {
            syncError = "Attachments need a paired host. Pair your desktop in Settings first."
            return
        }
        if (attachment.id.isBlank()) return
        if (attachment.id in openingAttachmentIds) return
        val cachedFileName = attachmentCacheFileName(attachment.id, attachment.name)
        openingAttachmentIds.add(attachment.id)
        scope.launch {
            protectedAttachmentFiles.add(cachedFileName)
            try {
                val file = historicalAttachmentFile(sessionId, attachment)
                openCachedAttachment(file, attachment)
                syncError = null
            } catch (error: Exception) {
                syncError = when (error) {
                    is DshApiException.NotFound ->
                        "That attachment is no longer available on the host."

                    is DshApiException.InvalidRequest ->
                        error.message ?: "That attachment is too large to open on the phone."

                    is DshApiException.Unsupported ->
                        "Attachments aren't supported by this host yet. Update the desktop host plugin."

                    else -> errorMessage(error)
                }
            } finally {
                protectedAttachmentFiles.remove(cachedFileName)
                openingAttachmentIds.remove(attachment.id)
            }
        }
    }

    private fun openCachedAttachment(file: File, attachment: MessageAttachment) {
        val mimeType = attachment.mimeType.ifBlank {
            if (attachment.isImage) "image/*" else "*/*"
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.update_provider",
            file,
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            view,
            "Open ${attachment.name.ifBlank { "attachment" }}",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(chooser)
        } catch (noViewer: Exception) {
            // No viewer for the mime type: fall back to the share sheet with the cached file.
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            appContext.startActivity(
                Intent.createChooser(share, "Share attachment")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun pruneAttachmentCacheAsync() {
        scope.launch(Dispatchers.IO) {
            // Pruning is best-effort: never surface errors and never block the UI.
            runCatching { pruneAttachmentCache(File(appContext.cacheDir, "attachments")) }
        }
    }

    private fun pruneAttachmentCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        val plan = attachmentCachePrunePlan(
            entries = files.map {
                AttachmentCacheEntry(
                    fileName = it.name,
                    bytes = it.length(),
                    lastModifiedMs = it.lastModified(),
                )
            },
            nowMs = System.currentTimeMillis(),
            protectedFileNames = protectedAttachmentFiles.toSet(),
        )
        plan.forEach { name -> runCatching { File(dir, name).delete() } }
    }

    fun stopWorking() {
        val sessionId = activeSessionId ?: return
        scope.launch {
            try {
                repository.interrupt(sessionId)
            } catch (error: Exception) {
                syncError = errorMessage(error)
            }
        }
    }

    fun answerApproval(approved: Boolean) {
        // Read-only host: answers are made on the desktop; don't fire a doomed request.
        if (approvalsReadOnly) return
        val approval = pendingApproval ?: return
        scope.launch {
            try {
                repository.decideApproval(approval.id, approved)
                dismissedApprovalIds.remove(approval.id)
                syncError = null
            } catch (error: Exception) {
                handleApprovalDecisionFailure(approval.id, error)
            }
        }
    }

    fun answerQuestion(answer: String, optionIndex: Int?) {
        // Read-only host: answers are made on the desktop; don't fire a doomed request.
        if (questionsReadOnly) return
        val question = pendingQuestion ?: return
        if (question.id in answeringQuestionIds) return
        answeringQuestionIds.add(question.id)
        scope.launch {
            try {
                repository.answerQuestion(question.id, answer, optionIndex)
                dismissedQuestionIds.remove(question.id)
                syncError = null
            } catch (error: Exception) {
                handleQuestionAnswerFailure(question.id, error)
            } finally {
                answeringQuestionIds.remove(question.id)
            }
        }
    }

    fun refreshNow() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                repository.refresh()
                // The refreshed approval/question lists are authoritative; local race dismissals
                // can go.
                dismissedApprovalIds.clear()
                dismissedQuestionIds.clear()
                syncError = null
            } catch (error: Exception) {
                syncError = errorMessage(error)
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Reconnect path for the connection banner: restarts the SSE loop (it replays from the last
     * event id) and refetches snapshots + loaded transcripts. Existing buffered data stays visible
     * while reconnecting.
     */
    fun reconnect() {
        if (!isLive) return
        val remote = remoteRepository ?: return
        lastAutoReconnectAt = System.currentTimeMillis()
        syncError = null
        remote.forceReconnect("manual")
    }

    // region lifecycle / auto-heal

    /** Activity ON_START: heal a stream that went stale while the app was in the background. */
    fun onAppForegrounded() {
        appInForeground = true
        presentationPrefs.edit().putBoolean("app_foreground", true).apply()
        val awayMillis = backgroundedAt?.let { System.currentTimeMillis() - it }
        backgroundedAt = null
        remoteRepository?.setForeground(true)
        if (awayMillis != null && awayMillis >= RESUME_HEAL_AFTER_MILLIS) {
            recordConnectionEvent("App foreground after ${awayMillis / 1000}s")
        }
        if (!isLive) return
        // A quick app switch keeps the socket; only a real pause can strand it.
        if (awayMillis == null || awayMillis < RESUME_HEAL_AFTER_MILLIS) return
        autoReconnect(force = false, reason = "foreground resume")
    }

    /** Activity ON_STOP: pause the stale-socket watchdog while nobody is looking. */
    fun onAppBackgrounded() {
        appInForeground = false
        presentationPrefs.edit().putBoolean("app_foreground", false).apply()
        backgroundedAt = System.currentTimeMillis()
        remoteRepository?.setForeground(false)
        recordConnectionEvent("App backgrounded")
    }

    /**
     * Default-network change (Wi-Fi/VPN/route hand-off): let the system install routes, then
     * restart the stream that the old network was bound to.
     */
    private fun onNetworkChanged() {
        syncNetworkDiagnostics()
        if (!networkAvailable || !isLive) return
        networkReconnectJob?.cancel()
        networkReconnectJob = scope.launch {
            delay(NETWORK_RECONNECT_DEBOUNCE_MILLIS)
            autoReconnect(force = true, reason = "network change")
        }
    }

    private fun syncNetworkDiagnostics() {
        val wasAvailable = networkAvailable
        val wasTransport = networkTransport
        networkAvailable = connectivityMonitor.available
        networkTransport = connectivityMonitor.transport
        remoteRepository?.setNetworkState(networkAvailable, networkTransport)
        if (wasAvailable != networkAvailable || wasTransport != networkTransport) {
            recordConnectionEvent(
                if (networkAvailable) "Network: $networkTransport available" else "Network: offline",
            )
        }
    }

    /**
     * Debounced self-heal: restarts the SSE loop from the retained replay cursor and refetches
     * snapshots + loaded transcripts in the repository. A dead Tailscale route never leaves the
     * manual reconnect banner as the only way back.
     */
    private fun autoReconnect(force: Boolean, reason: String) {
        if (!isLive) return
        val remote = remoteRepository ?: return
        val now = System.currentTimeMillis()
        // Shared storm guard: resume and network callbacks may fire together.
        if (now - lastAutoReconnectAt < AUTO_RECONNECT_MIN_INTERVAL_MILLIS) return
        if (!force) {
            val lastSync = remote.lastSyncedAt
            val lastFrame = remote.lastEventAt
            val freshnessCutoff = now - FRESH_SYNC_MILLIS
            val liveAndFresh = remote.connection == ConnectionStatus.Live &&
                ((lastFrame != null && lastFrame >= freshnessCutoff) ||
                    (lastSync != null && lastSync >= freshnessCutoff))
            if (liveAndFresh) return
        }
        lastAutoReconnectAt = now
        syncError = null
        remote.forceReconnect(reason)
    }

    // endregion

    // endregion

    // region host management

    /** Pair (or re-pair) a host, then persist it as the active profile. */
    fun pair(
        baseUrlInput: String,
        codeInput: String,
        deviceNameInput: String,
        labelInput: String = pairingLabelDefault,
    ) {
        if (pairingBusy) return
        val parsed = HostUrl.parse(baseUrlInput)
        if (parsed is HostUrlResult.Invalid) {
            pairingError = parsed.message
            return
        }
        val baseUrl = (parsed as HostUrlResult.Valid).root
        val code = codeInput.trim()
        if (!code.matches(SIX_DIGITS)) {
            pairingError = "Enter the 6-digit pairing code shown by the host."
            return
        }
        val deviceName = deviceNameInput.trim().ifBlank {
            Build.MODEL.ifBlank { "Android device" }
        }

        pairingBusy = true
        pairingError = null
        scope.launch {
            try {
                val response = DshApiClient(baseUrl).pairComplete(code, deviceName)
                if (response.token.isBlank() || response.deviceId.isBlank()) {
                    throw DshApiException.Protocol("The host returned an incomplete pairing response.")
                }
                val now = Instant.now().toString()
                val existing = profiles.firstOrNull { it.baseUrl == baseUrl }
                val label = labelInput.trim()
                    .ifBlank { existing?.label.orEmpty() }
                    .ifBlank { response.host?.name?.takeIf { it.isNotBlank() }.orEmpty() }
                    .ifBlank { HostUrl.displayHost(baseUrl) }
                val profile = if (existing != null) {
                    existing.copy(
                        label = label,
                        deviceId = response.deviceId,
                        token = response.token,
                        lastUsedAt = now,
                    )
                } else {
                    HostProfile(
                        id = HostIds.newId(),
                        label = label,
                        baseUrl = baseUrl,
                        deviceId = response.deviceId,
                        token = response.token,
                        pairedAt = now,
                        lastUsedAt = now,
                    )
                }
                profiles = profiles.filterNot { it.id == profile.id || it.baseUrl == baseUrl } +
                    profile
                activeHostId = profile.id
                hostStore.save(profiles, activeHostId)
                hostStore.dismissWelcome()
                legacyStore.saveUseDemoData(false)
                useDemoData = false
                showPairing = false
                showWelcome = false
                activeSessionId = null
                selectedWorkspace = null
                syncError = null
                activeHostVersion = response.host?.version?.takeIf { it.isNotBlank() }
                startRemote()
                fetchActiveHostHealth()
            } catch (error: DshApiException) {
                pairingError = pairingErrorMessage(error)
            } catch (error: Exception) {
                pairingError = "Could not pair: ${error.message ?: "unknown error"}"
            } finally {
                pairingBusy = false
            }
        }
    }

    // region recovery restore (welcome flow)

    var recoveryRestoreBusy: Boolean by mutableStateOf(false)
        private set
    var recoveryRestoreError: String? by mutableStateOf(null)
        private set

    fun clearRecoveryRestoreError() {
        recoveryRestoreError = null
    }

    /**
     * Re-pairs the phone with a recovery code (or the parsed code from a recovery link), then
     * stores the token/profile exactly like normal pairing and connects.
     */
    fun restoreWithRecovery(baseUrlInput: String, codeInput: String) {
        if (recoveryRestoreBusy) return
        val parsed = HostUrl.parse(baseUrlInput)
        if (parsed is HostUrlResult.Invalid) {
            recoveryRestoreError = parsed.message
            return
        }
        val baseUrl = (parsed as HostUrlResult.Valid).root
        val code = normalizeRecoveryCode(codeInput)
        if (code.isEmpty()) {
            recoveryRestoreError = "Enter the recovery code or paste the recovery link."
            return
        }
        // Fail fast before touching the network: 52 chars of RFC4648 base32.
        if (!recoveryCodeLooksValid(code)) {
            recoveryRestoreError = RECOVERY_CODE_FORMAT_MESSAGE
            return
        }
        recoveryRestoreBusy = true
        recoveryRestoreError = null
        scope.launch {
            try {
                val deviceName = Build.MODEL.ifBlank { "Android device" }
                val response = DshApiClient(baseUrl).pairWithRecovery(
                    RecoveryPairRequest(
                        code = code,
                        deviceName = deviceName,
                        platform = "Android",
                    ),
                )
                val deviceId = response.device?.deviceId.orEmpty()
                if (response.token.isBlank() || deviceId.isBlank()) {
                    throw DshApiException.Protocol(
                        "The host returned an incomplete recovery response.",
                    )
                }
                val now = Instant.now().toString()
                val existing = profiles.firstOrNull { it.baseUrl == baseUrl }
                val label = existing?.label?.takeIf { it.isNotBlank() }
                    ?: HostUrl.displayHost(baseUrl)
                val profile = if (existing != null) {
                    existing.copy(
                        label = label,
                        deviceId = deviceId,
                        token = response.token,
                        lastUsedAt = now,
                    )
                } else {
                    HostProfile(
                        id = HostIds.newId(),
                        label = label,
                        baseUrl = baseUrl,
                        deviceId = deviceId,
                        token = response.token,
                        pairedAt = now,
                        lastUsedAt = now,
                    )
                }
                profiles = profiles.filterNot { it.id == profile.id || it.baseUrl == baseUrl } +
                    profile
                activeHostId = profile.id
                hostStore.save(profiles, activeHostId)
                // The host rotates on use; surface the rotated code immediately (once).
                recoveryCompletion = RecoveryCompletion(
                    baseUrl = baseUrl,
                    rotatedCode = response.recoveryCode?.takeIf { it.isNotBlank() },
                )
                hostStore.dismissWelcome()
                legacyStore.saveUseDemoData(false)
                useDemoData = false
                showPairing = false
                showWelcome = false
                activeSessionId = null
                selectedWorkspace = null
                syncError = null
                activeHostVersion = null
                resetHostSearchForHostChange()
                startRemote()
                fetchActiveHostHealth()
            } catch (error: DshApiException) {
                recoveryRestoreError = recoveryRestoreErrorMessage(error)
            } catch (error: Exception) {
                recoveryRestoreError =
                    "Could not restore the host: ${error.message ?: "unknown error"}"
            } finally {
                recoveryRestoreBusy = false
            }
        }
    }

    private fun recoveryRestoreErrorMessage(error: DshApiException): String = when (error) {
        is DshApiException.InvalidCode,
        is DshApiException.Unauthorized,
        is DshApiException.InvalidRequest,
        -> recoveryFailureMessage(RecoveryFailureKind.INVALID_CODE)

        is DshApiException.RateLimited -> recoveryFailureMessage(RecoveryFailureKind.RATE_LIMITED)
        is DshApiException.Unsupported ->
            recoveryFailureMessage(RecoveryFailureKind.UNSUPPORTED)

        is DshApiException.Network -> recoveryFailureMessage(RecoveryFailureKind.NETWORK)
        is DshApiException.NotFound -> recoveryFailureMessage(RecoveryFailureKind.NOT_FOUND)
        else -> error.message ?: recoveryFailureMessage(RecoveryFailureKind.OTHER)
    }

    // endregion

    // region tailscale one-tap pairing

    var tailscaleMethods: PairMethods? by mutableStateOf(null)
        private set
    var tailscaleMethodsLoading: Boolean by mutableStateOf(false)
        private set
    private var tailscaleMethodsUrl: String = ""
    var tailscaleSignInBusy: Boolean by mutableStateOf(false)
        private set
    var tailscaleSignInError: String? by mutableStateOf(null)
        private set

    /** Increments after a successful sign-in so host-management dialogs can close themselves. */
    var tailscaleSignInSuccessNonce: Int by mutableIntStateOf(0)
        private set

    fun clearTailscaleSignInError() {
        tailscaleSignInError = null
    }

    /** Methods-aware hint support for the URL field (public GET /pair/methods). */
    fun loadTailscaleMethods(baseUrlInput: String) {
        val parsed = HostUrl.parse(baseUrlInput)
        if (parsed is HostUrlResult.Invalid) {
            tailscaleMethods = null
            tailscaleMethodsUrl = ""
            return
        }
        val baseUrl = (parsed as HostUrlResult.Valid).root
        if (tailscaleMethodsUrl == baseUrl && tailscaleMethods != null) return
        if (tailscaleMethodsLoading) return
        tailscaleMethodsUrl = baseUrl
        tailscaleMethodsLoading = true
        scope.launch {
            try {
                tailscaleMethods = DshApiClient(baseUrl).pairMethods().toPairMethods()
            } catch (error: Exception) {
                tailscaleMethods = null
            } finally {
                tailscaleMethodsLoading = false
            }
        }
    }

    /**
     * One-tap Tailscale Serve sign-in from onboarding: no code is entered; the host verifies the
     * tailnet identity. On success the token is stored exactly like normal pairing and the app
     * connects (and the welcome flow is dismissed).
     */
    fun signInWithTailscale(baseUrlInput: String) {
        tailscaleSignIn(baseUrlInput, fromWelcome = true)
    }

    /**
     * Add-host variant used by Settings → Hosts: same pairing plumbing, but it does not touch
     * onboarding flags; the new profile is stored, selected and connected, and the host list reacts.
     */
    fun addHostWithTailscale(baseUrlInput: String) {
        tailscaleSignIn(baseUrlInput, fromWelcome = false)
    }

    private fun tailscaleSignIn(baseUrlInput: String, fromWelcome: Boolean) {
        if (tailscaleSignInBusy) return
        val parsed = HostUrl.parse(baseUrlInput)
        if (parsed is HostUrlResult.Invalid) {
            tailscaleSignInError = parsed.message
            return
        }
        val baseUrl = (parsed as HostUrlResult.Valid).root
        tailscaleSignInBusy = true
        tailscaleSignInError = null
        scope.launch {
            try {
                val client = DshApiClient(baseUrl)
                val methods = runCatching { client.pairMethods() }.getOrNull()?.toPairMethods()
                tailscaleMethods = methods
                tailscaleMethodsUrl = baseUrl
                if (methods != null && !methods.tailscaleAutoPair) {
                    tailscaleSignInError = tailscaleMethodsHint(methods)
                    return@launch
                }
                val deviceName = Build.MODEL.ifBlank { "Android device" }
                val response = client.pairWithTailscale(
                    TailscalePairRequest(deviceName = deviceName, platform = "android"),
                )
                if (response.token.isBlank() || response.deviceId.isBlank()) {
                    throw DshApiException.Protocol(
                        "The host returned an incomplete Tailscale pairing response.",
                    )
                }
                val now = Instant.now().toString()
                val existing = profiles.firstOrNull { it.baseUrl == baseUrl }
                val label = existing?.label?.takeIf { it.isNotBlank() }
                    ?: response.host?.name?.takeIf { it.isNotBlank() }
                    ?: HostUrl.displayHost(baseUrl)
                val profile = if (existing != null) {
                    existing.copy(
                        label = label,
                        deviceId = response.deviceId,
                        token = response.token,
                        lastUsedAt = now,
                    )
                } else {
                    HostProfile(
                        id = HostIds.newId(),
                        label = label,
                        baseUrl = baseUrl,
                        deviceId = response.deviceId,
                        token = response.token,
                        pairedAt = now,
                        lastUsedAt = now,
                    )
                }
                profiles = profiles.filterNot { it.id == profile.id || it.baseUrl == baseUrl } +
                    profile
                activeHostId = profile.id
                hostStore.save(profiles, activeHostId)
                if (fromWelcome) {
                    hostStore.dismissWelcome()
                    showWelcome = false
                }
                legacyStore.saveUseDemoData(false)
                useDemoData = false
                showPairing = false
                activeSessionId = null
                selectedWorkspace = null
                syncError = null
                activeHostVersion = response.host?.version?.takeIf { it.isNotBlank() }
                resetHostSearchForHostChange()
                startRemote()
                fetchActiveHostHealth()
                tailscaleSignInSuccessNonce += 1
            } catch (error: DshApiException) {
                tailscaleSignInError = tailscaleSignInErrorMessage(error)
            } catch (error: Exception) {
                tailscaleSignInError =
                    "Tailscale sign-in failed: ${error.message ?: "unknown error"}"
            } finally {
                tailscaleSignInBusy = false
            }
        }
    }

    private fun PairMethodsDto.toPairMethods(): PairMethods = PairMethods(
        desktopCode = desktopCode,
        recovery = recovery,
        tailscaleAutoPair = tailscaleAutoPair,
        tailscaleLoginRequired = tailscaleLoginRequired,
    )

    private fun tailscaleSignInErrorMessage(error: DshApiException): String = when (error) {
        is DshApiException.InvalidRequest ->
            if (error.code == TAILSCALE_IDENTITY_MISSING_CODE) {
                tailscaleFailureMessage(TailscaleFailureKind.IDENTITY_MISSING)
            } else {
                error.message ?: tailscaleFailureMessage(TailscaleFailureKind.OTHER)
            }

        is DshApiException.Forbidden -> tailscaleFailureMessage(TailscaleFailureKind.FORBIDDEN)
        is DshApiException.Unsupported ->
            tailscaleFailureMessage(TailscaleFailureKind.UNSUPPORTED)

        is DshApiException.RateLimited ->
            tailscaleFailureMessage(TailscaleFailureKind.RATE_LIMITED)

        is DshApiException.Network -> tailscaleFailureMessage(TailscaleFailureKind.NETWORK)
        is DshApiException.NotFound -> tailscaleFailureMessage(TailscaleFailureKind.NOT_FOUND)
        else -> error.message ?: tailscaleFailureMessage(TailscaleFailureKind.OTHER)
    }

    // endregion

    /** Switch to another persisted host: rebuild client/repository, restart SSE, refetch. */
    fun switchHost(targetId: String) {
        when (val outcome = HostSwitcher.plan(profiles, activeHostId, targetId)) {
            HostSwitchOutcome.AlreadyActive -> return

            HostSwitchOutcome.UnknownHost -> {
                syncError = "That host is no longer saved."
            }

            is HostSwitchOutcome.Rebuild -> activateProfile(outcome.profile)
        }
    }

    fun renameHost(id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty() || profiles.none { it.id == id }) return
        profiles = profiles.map { profile ->
            if (profile.id == id) profile.copy(label = trimmed) else profile
        }
        hostStore.save(profiles, activeHostId)
    }

    /**
     * Removes one host profile (unpairs only that host). Removing the active host activates the
     * next saved host, or falls back to demo mode when none remain.
     */
    fun removeHost(id: String) {
        if (profiles.none { it.id == id }) return
        val wasActive = id == activeHostId
        val remaining = profiles.filterNot { it.id == id }
        profiles = remaining
        if (!wasActive) {
            hostStore.save(profiles, activeHostId)
            return
        }

        remoteRepository?.dispose()
        remoteRepository = null
        activeHostId = remaining.firstOrNull()?.id
        activeSessionId = null
        selectedWorkspace = null
        syncError = null
        activeHostVersion = null
        resetHostSearchForHostChange()
        if (activeHostId != null) {
            useDemoData = false
            legacyStore.saveUseDemoData(false)
            hostStore.save(profiles, activeHostId)
            startRemote()
            fetchActiveHostHealth()
        } else {
            useDemoData = true
            legacyStore.saveUseDemoData(true)
            hostStore.save(profiles, null)
        }
    }

    /** Welcome screen "Use demo data": dismisses onboarding and enters demo mode. */
    fun chooseDemoData() {
        hostStore.dismissWelcome()
        showWelcome = false
        useDemoData = true
        legacyStore.saveUseDemoData(true)
        resetHostSearchForHostChange()
    }

    /** Persisted hosts are never modified in demo mode; only relevant when one exists. */
    fun toggleUseDemoData(enabled: Boolean) {
        if (profiles.isEmpty()) return
        if (useDemoData == enabled) return
        useDemoData = enabled
        legacyStore.saveUseDemoData(enabled)
        activeSessionId = null
        syncError = null
        resetHostSearchForHostChange()
        if (enabled) {
            remoteRepository?.stop()
        } else {
            startRemote()
        }
    }

    /** `GET /health` against any saved profile (used by Settings → Hosts → Test connection). */
    suspend fun testConnection(profile: HostProfile): Result<HealthDto> =
        runCatching {
            // Reuse the live repository's connection pool for the active host; constructing a
            // new client here forces a fresh DNS/TCP/TLS setup and inflates the diagnostics time.
            if (profile.id == activeHostId) {
                remoteRepository?.health()
                    ?: DshApiClient(profile.baseUrl, profile.token).health()
            } else {
                DshApiClient(profile.baseUrl, profile.token).health()
            }
        }
            .onSuccess { health ->
                if (activeHostId == profile.id) {
                    activeHostVersion = health.version.takeIf { it.isNotBlank() }
                }
            }

    /** Checks an arbitrary base URL before pairing; /health does not need a device token. */
    suspend fun testHostUrl(baseUrl: String): Result<HealthDto> =
        runCatching { DshApiClient(baseUrl.trim()).health() }

    private fun activateProfile(profile: HostProfile) {
        remoteRepository?.dispose()
        remoteRepository = null
        val now = Instant.now().toString()
        profiles = profiles.map { existing ->
            if (existing.id == profile.id) existing.copy(lastUsedAt = now) else existing
        }
        activeHostId = profile.id
        useDemoData = false
        legacyStore.saveUseDemoData(false)
        hostStore.save(profiles, activeHostId)
        // Clear everything tied to the previous host so stale sessions/approvals can't leak over.
        activeSessionId = null
        selectedWorkspace = null
        syncError = null
        activeHostVersion = null
        resetHostSearchForHostChange()
        startRemote()
        fetchActiveHostHealth()
    }

    private fun startRemote() {
        val profile = activeProfile ?: return
        remoteRepository?.dispose()
        goalEventsJob?.cancel()
        // New host: push suppression must be re-synced even if it equals the previous host's body.
        lastPushSuppressionPayload = null
        // Jump targets are host/session specific and must never leak across hosts.
        messageJumpTarget = null
        lastGoalChange = null
        // Presets are host-specific; reload them for the newly active host on next sheet open.
        presetRoster = null
        presetsLoading = false
        val repository = RemoteRepository(
            api = DshApiClient(profile.baseUrl, profile.token),
            scope = scope,
            initialWorkspaces = emptyList(),
            cache = HostSnapshotCache(appContext, profile.baseUrl),
            onConnectionEvent = ::recordConnectionEvent,
            transcriptCache = HostTranscriptCache(appContext, profile.baseUrl),
        )
        remoteRepository = repository
        repository.setNetworkState(connectivityMonitor.available, connectivityMonitor.transport)
        goalEventsJob = scope.launch {
            repository.goalChanges.collect { change ->
                lastGoalChange = change
                if (goalStateSessionId == change.sessionId) {
                    goalState = SessionGoalState(
                        supported = true,
                        live = true,
                        goal = change.goal,
                    )
                    goalError = null
                }
            }
        }
        repository.start()
        repository.setForeground(backgroundedAt == null)
        // Best-effort once the profile is ready; silent no-op for old hosts/network failures.
        schedulePushSuppressionSync(immediate = true)
    }

    private fun fetchActiveHostHealth() {
        val profile = activeProfile ?: return
        scope.launch {
            runCatching {
                remoteRepository?.health()
                    ?: DshApiClient(profile.baseUrl, profile.token).health()
            }
                .onSuccess { health ->
                    if (activeHostId == profile.id) {
                        activeHostVersion = health.version.takeIf { it.isNotBlank() }
                    }
                }
        }
    }

    // endregion

    // region lifecycle

    fun dispose() {
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        connectivityMonitor.stop()
        connectionEventState.clear()
        remoteRepository?.dispose()
        remoteRepository = null
        updates.dispose()
        demo.dispose()
        scope.cancel()
    }

    // endregion

    // region helpers

    /**
     * Loads host profiles, migrating a v0.2.x single pairing into a "Desktop" profile the first
     * time. The legacy token is only cleared after the new encrypted profile is persisted.
     */
    private fun bootstrapHosts(): HostProfilesSnapshot {
        val snapshot = hostStore.load()
        if (snapshot.profiles.isNotEmpty()) return snapshot
        val legacy = legacyStore.loadPairing() ?: return snapshot
        val migrated = HostProfileMigration.fromLegacy(legacy) ?: return snapshot
        hostStore.save(listOf(migrated), migrated.id)
        val persisted = hostStore.load()
        // Only drop the legacy token once the encrypted profile is actually readable back; a
        // Keystore failure must never lose the only copy of the device token.
        if (persisted.profiles.any { it.id == migrated.id }) {
            legacyStore.clearPairing()
            return persisted
        }
        return snapshot
    }

    private fun pairingErrorMessage(error: DshApiException): String = when (error) {
        is DshApiException.InvalidCode ->
            "That code is invalid or has expired. Generate a new pairing code on the host."
        is DshApiException.RateLimited ->
            "Too many pairing attempts. Wait a minute, then try again."
        is DshApiException.Network ->
            "Can't reach the host at that URL. Check the address and that the host is running."
        is DshApiException.NotFound ->
            "That URL doesn't look like a DSH host. Check the address and port."
        is DshApiException.Unauthorized ->
            "The host rejected the request. Generate a fresh code and try again."
        is DshApiException.Unsupported ->
            "This host doesn't support mobile pairing yet. Update the host plugin."
        else -> error.message ?: "Pairing failed."
    }

    fun errorMessage(error: Throwable): String = when (error) {
        is DshApiException.Unauthorized ->
            "This device is no longer paired with the host. Pair the host again."
        is DshApiException.Network ->
            "Can't reach the host. Check your network and that the host is running."
        is DshApiException.RateLimited ->
            "The host is rate limiting requests. Try again in a moment."
        is DshApiException.NotFound ->
            "The host no longer knows this resource."
        is DshApiException.Protocol ->
            "The host sent an unexpected response."
        is DshApiException.Unsupported ->
            "This host doesn't support that yet."
        is DshApiException.Server ->
            "The host reported an error: ${error.message}"
        is DshApiException -> error.message ?: "The host request failed."
        else -> error.message ?: "Something went wrong."
    }

    // endregion

    private companion object {
        val SIX_DIGITS = Regex("\\d{6}")

        /** Resume heal only after a real absence; quick app switches keep the live socket. */
        const val RESUME_HEAL_AFTER_MILLIS = 3_000L

        /** A snapshot newer than this counts as fresh, so a resume does not force a reconnect. */
        const val FRESH_SYNC_MILLIS = 5_000L

        /** Storm guard shared by the resume and network-change paths. */
        const val AUTO_RECONNECT_MIN_INTERVAL_MILLIS = 2_000L

        /** Diagnostics keep a short rolling history, newest first. */
        const val MAX_CONNECTION_EVENTS = 24

        /** Let the system install VPN/DNS routes before restarting the stream. */
        const val NETWORK_RECONNECT_DEBOUNCE_MILLIS = 700L
    }
}

/** Bound for locally dismissed approvals so a long race streak can't grow unbounded. */
private const val MAX_DISMISSED_APPROVALS = 64

/** Status copy when the host can't answer approvals from the phone (read-only / 501 bridge). */
internal const val READ_ONLY_APPROVAL_NOTICE =
    "Approvals are read-only on this host. Resolve them on the desktop."

/** Status copy when another device/desktop won the first-answer-wins race. */
internal const val ALREADY_ANSWERED_APPROVAL_NOTICE = "Already answered on the desktop."

/**
 * True when an approval decision failed because the approval is gone (answered or expired
 * elsewhere). The host may report this as 404/409 or an `already_answered`-style error code.
 */
internal fun isAlreadyAnsweredApprovalError(error: Throwable): Boolean = when (error) {
    is DshApiException.NotFound -> true
    is DshApiException.InvalidRequest -> isAlreadyAnsweredCode(error.code)
    is DshApiException.Server ->
        error.httpStatus == 404 || error.httpStatus == 409 || isAlreadyAnsweredCode(error.code)
    else -> false
}

private fun isAlreadyAnsweredCode(code: String?): Boolean =
    code != null && code in ALREADY_ANSWERED_CODES

/**
 * Short status for a failed approval decision; null means "show the generic error message".
 * A read-only/501 host gets the read-only copy instead of a raw protocol error.
 */
internal fun approvalDecisionFailureNotice(error: Throwable, readOnly: Boolean): String? = when {
    error is DshApiException.Unsupported || readOnly -> READ_ONLY_APPROVAL_NOTICE
    isAlreadyAnsweredApprovalError(error) -> ALREADY_ANSWERED_APPROVAL_NOTICE
    else -> null
}

private val ALREADY_ANSWERED_CODES = setOf(
    "already_answered",
    "already_resolved",
    "approval_resolved",
    "conflict",
)

/** Bound for locally dismissed questions so a long race streak can't grow unbounded. */
private const val MAX_DISMISSED_QUESTIONS = 64

/** Status copy when the host can't answer questions from the phone (read-only / 501 bridge). */
internal const val READ_ONLY_QUESTION_NOTICE =
    "Questions are read-only on this host. Answer them on the desktop."

/** Status copy when the question was already answered on another device/desktop. */
internal const val ALREADY_ANSWERED_QUESTION_NOTICE = "Already answered on the desktop."

/**
 * Short status for a failed question answer; null means "show the generic error message".
 * A read-only/501 host gets the read-only copy instead of a raw protocol error.
 */
internal fun questionAnswerFailureNotice(error: Throwable, readOnly: Boolean): String? = when {
    error is DshApiException.Unsupported || readOnly -> READ_ONLY_QUESTION_NOTICE
    isAlreadyAnsweredApprovalError(error) -> ALREADY_ANSWERED_QUESTION_NOTICE
    else -> null
}

/**
 * Filters locally dismissed questions after a lost race. Only the failed row is hidden, so the
 * remaining rows of a multi-question batch stay visible and answerable.
 */
internal fun questionsAfterLocalDismissals(
    questions: List<Question>,
    dismissedIds: Set<String>,
): List<Question> = questions.filter { it.id !in dismissedIds }

/** Cap for free-text question answers; the host accepts custom answers, this keeps them sane. */
internal const val MAX_QUESTION_ANSWER_CHARS = 2_000

/**
 * Normalizes a free-text question answer: trims whitespace and caps the length. Returns null when
 * the draft is blank so the Send action can stay disabled. Used for both optionless questions and
 * the "Other…" custom answer path (optionIndex=null).
 */
internal fun normalizeQuestionAnswer(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.take(MAX_QUESTION_ANSWER_CHARS)
}

/**
 * Filtering/ordering for the Goals dashboard, kept outside [AppState] so it can be unit tested
 * without an Android Context. Sessions with a goal sort paused/blocked first, then active, then
 * complete, then any unknown phase; ties are stable by title.
 */
object GoalDashboard {

    fun sessionsWithGoals(sessions: List<Session>): List<Session> =
        sessions
            .filter { session -> !session.goalPhase.isNullOrBlank() }
            .sortedWith(
                compareBy(
                    { phaseRank(it.goalPhase) },
                    { it.title.lowercase() },
                ),
            )

    fun count(sessions: List<Session>): Int = sessionsWithGoals(sessions).size

    fun phaseRank(phase: String?): Int = when (phase?.trim()?.lowercase()) {
        "paused", "blocked" -> 0
        "active" -> 1
        "complete" -> 2
        else -> 3
    }
}
