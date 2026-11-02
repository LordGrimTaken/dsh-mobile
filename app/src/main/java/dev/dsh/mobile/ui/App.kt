package dev.dsh.mobile.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.dsh.mobile.BuildConfig
import dev.dsh.mobile.CrashReporter
import dev.dsh.mobile.SafeModeActivity
import dev.dsh.mobile.data.StartupGuard
import dev.dsh.mobile.DshNotifications
import dev.dsh.mobile.BackgroundSyncService
import dev.dsh.mobile.SessionShortcuts
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.RECOVERY_LINK_PREFIX
import dev.dsh.mobile.data.SESSION_RAIL_WIDTH_DP
import dev.dsh.mobile.data.SessionStatus
import dev.dsh.mobile.data.SharedAttachment
import dev.dsh.mobile.data.startupFailureSummary
import dev.dsh.mobile.data.usesTwoPaneLayout
import dev.dsh.mobile.ui.theme.DshTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DshMobileApp(
    sharedText: String? = null,
    shareNonce: Int = 0,
    sharedImages: List<Uri> = emptyList(),
    shareImageNonce: Int = 0,
    deepLink: String? = null,
    deepLinkNonce: Int = 0,
) {
    val context = LocalContext.current
    // Construction can fail on a corrupt snapshot/preference/keystore row. Catch it here so a
    // startup exception shows a recovery screen instead of a crash loop; the pre-init delegate
    // ordering bug in 0.2.137 is guarded separately by AppStateInitializationOrderTest.
    var bootstrapAttempt by remember { mutableStateOf(0) }
    val bootstrap = remember(bootstrapAttempt) { runCatching { AppState(context) } }
    val startupError = bootstrap.exceptionOrNull()
    val state = bootstrap.getOrNull()
    if (state == null) {
        LaunchedEffect(bootstrapAttempt) {
            runCatching { StartupGuard.recordCrash(context) }
            startupError?.let { CrashReporter.record(context, it) }
        }
        DshTheme(darkTheme = true) {
            StartupFailureScreen(
                summary = startupFailureSummary(startupError),
                onRetry = { bootstrapAttempt += 1 },
                onOpenSafeMode = {
                    runCatching {
                        context.startActivity(Intent(context, SafeModeActivity::class.java))
                    }
                },
                onShareReport = {
                    val report = CrashReporter.read(context) ?: startupFailureSummary(startupError)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share error report"))
                    }
                },
            )
        }
        return
    }

    LaunchedEffect(shareNonce) {
        if (!sharedText.isNullOrBlank()) state.receiveSharedText(sharedText)
    }

    LaunchedEffect(shareImageNonce) {
        if (sharedImages.isNotEmpty()) {
            val items = readSharedImages(context, sharedImages)
            if (items.isEmpty()) {
                state.reportSharedAttachmentFailure()
                Toast.makeText(
                    context,
                    "Couldn't read the shared image",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                state.receiveSharedAttachments(items)
            }
        }
    }

    LaunchedEffect(deepLinkNonce) {
        when (deepLink) {
            "dshmobile://usage" -> state.openUsage()
            "dshmobile://new" -> state.openNewSession()
            "dshmobile://inbox" -> state.openInbox()
            else -> {
                val link = deepLink.orEmpty()
                when {
                    link.startsWith(RECOVERY_LINK_PREFIX) -> {
                        // dshmobile://recover?base=...&code=... opens the prefilled restore dialog.
                        if (state.handleRecoveryDeepLink(link)) {
                            state.closeSettings()
                            state.closeUsage()
                            state.closeInbox()
                            state.closeSession()
                        }
                    }

                    link.startsWith("dshmobile://session/") -> {
                        val sessionId = link.removePrefix("dshmobile://session/").trim()
                        if (sessionId.isNotEmpty()) {
                            state.closeSettings()
                            state.closeUsage()
                            state.closeInbox()
                            state.openSession(sessionId)
                        }
                    }

                    link.startsWith("dshmobile://approval/") -> {
                        val parts = link.removePrefix("dshmobile://approval/").split("/")
                        val approvalId = parts.getOrNull(0).orEmpty()
                        val decision = parts.getOrNull(1).orEmpty()
                        if (approvalId.isNotEmpty() && decision in setOf("allow", "deny")) {
                            if (state.approvalsReadOnly) {
                                // Read-only host: the desktop is the answerer. Never fire a
                                // decision we know will be rejected; show the inbox instead.
                                state.closeSettings()
                                state.closeUsage()
                                state.closeSession()
                                state.openInbox()
                            } else {
                                state.answerApprovalFor(approvalId, decision == "allow")
                            }
                        }
                    }
                }
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        DshNotifications.ensureChannels(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !DshNotifications.hasPermission(context) &&
            (state.notifyInput || state.notifyDone)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Local notifications while the app process is alive (SSE keeps the state current).
    LaunchedEffect(Unit) {
        var ready = false
        var previousStatuses = emptyMap<String, SessionStatus>()
        snapshotFlow {
            state.sessions.map { it.id to it.status }.toMap() to
                state.sessions.associate { it.id to it.title }
        }.collect { (statuses, titles) ->
            if (ready && state.notifyDone && !state.appInForeground) {
                statuses.forEach { (sessionId, status) ->
                    if (status == SessionStatus.Idle &&
                        previousStatuses[sessionId] == SessionStatus.Working
                    ) {
                        DshNotifications.turnFinished(
                            context,
                            sessionId,
                            titles[sessionId] ?: "session",
                        )
                    }
                }
            }
            previousStatuses = statuses
            ready = true
        }
    }
    // Deliver locally queued prompts when any session turns idle, even if that chat is not open.
    // ChatScreen also flushes the visible session; AppState's in-flight guard prevents doubles.
    LaunchedEffect(Unit) {
        snapshotFlow { state.sessions.map { it.id to it.status } }.collect { statuses ->
            statuses.forEach { (sessionId, status) ->
                if (status == SessionStatus.Idle && state.queuedFor(sessionId).isNotEmpty()) {
                    state.flushQueued(sessionId)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        var ready = false
        var previousApprovals = emptySet<String>()
        var previousQuestions = emptySet<String>()
        snapshotFlow {
            state.inboxApprovals.map { it.id }.toSet() to
                state.inboxQuestions.map { it.id }.toSet()
        }.collect { (approvals, questions) ->
            if (ready && state.notifyInput && !state.appInForeground) {
                (approvals - previousApprovals).forEach { id ->
                    val approval = state.inboxApprovals.firstOrNull { it.id == id }
                    DshNotifications.inputNeeded(
                        context,
                        approval?.sessionId.orEmpty(),
                        state.sessions.firstOrNull { it.id == approval?.sessionId }?.title ?: "session",
                        "Approval needed",
                        approvalId = id,
                        readOnlyApproval = state.approvalsReadOnly,
                    )
                }
                (questions - previousQuestions).forEach { id ->
                    val question = state.inboxQuestions.firstOrNull { it.id == id }
                    val sessionId = question?.sessionId
                    DshNotifications.inputNeeded(
                        context,
                        sessionId.orEmpty(),
                        state.sessions.firstOrNull { it.id == sessionId }?.title ?: "session",
                        "Question waiting",
                        questionId = id,
                        questionPrompt = question?.prompt,
                        questionsReadOnly = state.questionsReadOnly,
                    )
                }
            }
            previousApprovals = approvals
            previousQuestions = questions
            ready = true
        }
    }

    // Goal alerts: only paused/blocked/complete transitions, and only while the app is not
    // visible (the in-app GOAL banner covers the foreground). Deduped per session/revision/phase.
    LaunchedEffect(Unit) {
        val previousPhases = mutableMapOf<String, String>()
        val notified = mutableSetOf<String>()
        snapshotFlow { state.lastGoalChange }.collect { change ->
            if (change == null) return@collect
            val goal = change.goal
            val phase = goal?.phase?.lowercase().orEmpty()
            val previous = previousPhases.put(change.sessionId, phase)
            // First observation is a baseline; an unchanged phase is not a transition.
            if (previous == null || previous == phase) return@collect
            if (phase != "paused" && phase != "blocked" && phase != "complete") return@collect
            val key = "${change.sessionId}:${goal?.revision ?: -1}:$phase"
            if (!notified.add(key)) return@collect
            if (!state.notifyGoal) return@collect
            if (state.appInForeground) return@collect
            val sessionTitle = state.sessions
                .firstOrNull { it.id == change.sessionId }
                ?.let { state.displayTitle(it) }
                ?: "session"
            val objective = goal?.objective?.trim().orEmpty().ifBlank { sessionTitle }
            val title = when (phase) {
                "paused" -> "Goal paused"
                "blocked" -> "Goal blocked"
                else -> "Goal complete"
            }
            val text = when (phase) {
                "blocked" -> buildString {
                    append(
                        goal?.blockedReason?.takeIf { it.isNotBlank() }
                            ?: "Agent needs attention",
                    )
                    append(" · ")
                    append(sessionTitle)
                }

                else -> "$sessionTitle · $objective"
            }
            DshNotifications.goalChanged(context, title, text, change.sessionId)
        }
    }

    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }

    // Sideload update check on launch, throttled to once per day by UpdateManager.
    // Google Play builds (PLAY_BUILD) never contact the sideload feed; UpdateManager also no-ops.
    LaunchedEffect(Unit) {
        if (!BuildConfig.PLAY_BUILD) state.updates.checkOnLaunch()
    }

    // Kick a usage refresh so the once-per-day low-credit alert can fire without opening Usage.
    LaunchedEffect(Unit) {
        if (state.isLive && state.lowBalanceAlertsEnabled) state.refreshUsage()
    }

    LaunchedEffect(Unit) {
        if (state.backgroundSyncEnabled) {
            BackgroundSyncService.start(context)
        }
    }

    // Pinned sessions appear as dynamic launcher shortcuts for one-tap access.
    LaunchedEffect(
        state.pinnedSessionIds,
        state.sessions.map { session -> session.id to state.displayTitle(session) },
    ) {
        SessionShortcuts.sync(context, state)
    }

    // Optional app lock: immediate by default, or after a configurable background grace period.
    var appUnlocked by remember { mutableStateOf(!state.appLockEnabled) }
    var lockBackgroundAt by remember { mutableStateOf(0L) }
    LaunchedEffect(state.appLockEnabled, state.appLockDelaySeconds) {
        if (!state.appLockEnabled) {
            appUnlocked = true
            lockBackgroundAt = 0L
        }
    }
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner, state.appLockEnabled, state.appLockDelaySeconds) {
        val observer = LifecycleEventObserver { _, event ->
            if (!state.appLockEnabled) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (state.appLockDelaySeconds <= 0) {
                        appUnlocked = false
                    } else {
                        lockBackgroundAt = SystemClock.elapsedRealtime()
                    }
                }

                Lifecycle.Event.ON_START -> {
                    val since = lockBackgroundAt
                    if (since > 0L) {
                        val elapsed = SystemClock.elapsedRealtime() - since
                        if (elapsed >= state.appLockDelaySeconds * 1000L) {
                            appUnlocked = false
                        }
                        lockBackgroundAt = 0L
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // Reconnect resilience: heal a stale SSE stream when the app returns to the foreground and
    // pause the stale-socket watchdog while it is backgrounded.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> state.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // App-wide interface text scale: multiply the system font scale, then let chat text scale
    // additionally on top inside ChatScreen.
    val density = LocalDensity.current
    DshTheme(darkTheme = state.darkTheme, amoled = state.amoledDark, accent = state.themeAccent) {
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = density.density,
                fontScale = density.fontScale * state.appFontScale,
            ),
        ) {
            if (state.appLockEnabled && !appUnlocked) {
            AppLockScreen(onUnlock = { appUnlocked = true })
        } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val session = state.activeSession()
            // Central overlay gate: any of these full-screen screens keeps the compact, single-pane
            // composition even on wide windows. Modal surfaces (new-session sheet, update/recovery
            // dialogs, crash dialog) are intentionally NOT part of this gate: they are full-window
            // overlays rendered below, so the new-session sheet still covers the two-pane layout.
            val overlayShowing = state.showPairing || state.showHosts || state.showWelcome ||
                state.showSettings || state.showUsage || state.showConnectionDiagnostics ||
                state.showGoals || state.showSchedules || state.showInbox ||
                state.showHiddenSessions || state.showArchivedSessions || state.showHostSearch
            val twoPane = usesTwoPaneLayout(
                widthDp = maxWidth.value,
                hasSession = session != null,
                hasOverlay = overlayShowing,
            )
            val currentScreen = when {
                state.showPairing -> "pairing"
                state.showHosts -> "hosts"
                state.showWelcome -> "welcome"
                state.showSettings -> "settings"
                state.showUsage -> "usage"
                state.showConnectionDiagnostics -> "connection-diagnostics"
                state.showGoals -> "goals"
                state.showSchedules -> "schedules"
                session != null -> "chat:${session.id}"
                state.showInbox -> "inbox"
                state.showHiddenSessions -> "hidden-sessions"
                state.showArchivedSessions -> "archived"
                state.showHostSearch -> "host-search"
                else -> "session-list"
            }
            SideEffect { CrashReporter.enterScreen(currentScreen) }

            BackHandler(
                enabled = state.showPairing || state.showHosts || state.showSettings ||
                    state.showUsage || state.showInbox || state.showGoals ||
                    state.showSchedules ||
                    state.showHiddenSessions || state.showArchivedSessions ||
                    state.showHostSearch ||
                    state.showConnectionDiagnostics ||
                    session != null,
            ) {
                when {
                    state.showPairing -> state.closePairing()
                    state.showHosts -> state.closeHosts()
                    state.showSettings -> state.closeSettings()
                    state.showUsage -> state.closeUsage()
                    state.showInbox -> state.closeInbox()
                    state.showGoals -> state.closeGoals()
                    state.showSchedules -> state.closeSchedules()
                    state.showHiddenSessions -> state.closeHiddenSessions()
                    state.showArchivedSessions -> state.closeArchivedSessions()
                    state.showHostSearch -> state.closeHostSearch()
                    state.showConnectionDiagnostics -> state.closeConnectionDiagnostics()
                    else -> state.closeSession()
                }
            }

            if (twoPane && session != null) {
                Row(Modifier.fillMaxSize()) {
                    SessionRail(
                        state = state,
                        currentSessionId = session.id,
                        onOpenSettings = state::openSettings,
                        onOpenInbox = state::openInbox,
                        onOpenUsage = state::openUsage,
                        modifier = Modifier
                            .width(SESSION_RAIL_WIDTH_DP.dp)
                            .fillMaxHeight(),
                    )
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        ChatScreen(state = state, session = session)
                    }
                }
            } else {
            when {
                state.showPairing -> PairingScreen(state = state)
                state.showHosts -> HostsScreen(state = state)

                state.showWelcome -> WelcomeScreen(state = state)

                state.showSettings -> SettingsScreen(
                    state = state,
                    darkTheme = state.darkTheme,
                    onToggleTheme = state::updateDarkTheme,
                    onBack = state::closeSettings,
                    onOpenPairing = { state.openPairing() },
                    onOpenHosts = state::openHosts,
                    onOpenUsage = state::openUsage,
                    onOpenHiddenSessions = state::openHiddenSessions,
                    onOpenConnectionDiagnostics = state::openConnectionDiagnostics,
                )

                state.showUsage -> UsageScreen(state = state, onBack = state::closeUsage)

                state.showConnectionDiagnostics -> ConnectionDiagnosticsScreen(
                    state = state,
                    onBack = state::closeConnectionDiagnostics,
                )

                state.showHiddenSessions -> HiddenSessionsScreen(
                    state = state,
                    onBack = state::closeHiddenSessions,
                )

                state.showInbox -> InboxScreen(state = state, onBack = state::closeInbox)

                state.showGoals -> GoalsScreen(state = state, onBack = state::closeGoals)

                state.showSchedules -> SchedulesScreen(state = state, onBack = state::closeSchedules)

                state.showArchivedSessions -> ArchivedSessionsScreen(
                    state = state,
                    onBack = state::closeArchivedSessions,
                )

                state.showHostSearch -> HostSearchScreen(
                    state = state,
                    onOpenSession = state::openSession,
                    onBack = state::closeHostSearch,
                )

                session != null -> ChatScreen(state = state, session = session)

                else -> SessionListScreen(
                    state = state,
                    onOpenSession = state::openSession,
                    onOpenSettings = state::openSettings,
                    onOpenUsage = state::openUsage,
                    onOpenInbox = state::openInbox,
                    onOpenGoals = state::openGoals,
                    onOpenSchedules = state::openSchedules,
                    onOpenArchivedSessions = state::openArchivedSessions,
                    onOpenHostSearch = state::openHostSearch,
                )
            }
            }

            if (state.showNewSession) {
                NewSessionSheet(
                    state = state,
                    onDismiss = state::dismissNewSession,
                )
            }

            if (!BuildConfig.PLAY_BUILD) {
                UpdateDialog(manager = state.updates)
            }

            // Recovery flows are app-level: the deep link may arrive while any screen is open,
            // and the completion step must survive leaving Welcome after a successful restore.
            state.pendingRecoveryLink?.let { link ->
                RecoveryRestoreDialog(
                    state = state,
                    onDismiss = {
                        state.consumeRecoveryDeepLink()
                        state.clearRecoveryRestoreError()
                    },
                    initialHostUrl = link.baseUrl,
                    initialCode = link.code,
                )
            }
            if (state.recoveryCompletion != null) {
                RecoveryCompletionDialog(
                    state = state,
                    onDismiss = state::dismissRecoveryCompletion,
                )
            }

            var crashReport by remember { mutableStateOf(CrashReporter.read(context)) }
            crashReport?.let { report ->
                AlertDialog(
                    onDismissRequest = {
                        CrashReporter.clear(context)
                        crashReport = null
                    },
                    title = { Text("Last launch crashed") },
                    text = {
                        Text(
                            report.takeLast(1200),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                shareCrash(context, report)
                                CrashReporter.clear(context)
                                crashReport = null
                            },
                        ) { Text("Share log") }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                CrashReporter.clear(context)
                                crashReport = null
                            },
                        ) { Text("Dismiss") }
                    },
                )
            }
            }
        }
        }
        }
    }
}

private suspend fun readSharedImages(context: Context, uris: List<Uri>): List<SharedAttachment> =
    withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/*"
                if (!mime.startsWith("image/")) return@runCatching null
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: return@runCatching null
                if (bytes.size.toLong() > dev.dsh.mobile.data.AttachmentLimits.MAX_DECODED_BYTES) return@runCatching null
                val name = runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "shared-image"
                SharedAttachment(name = name, mimeType = mime, bytes = bytes)
            }.getOrNull()
        }
    }

private fun shareCrash(context: Context, report: String) {    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "DSH Mobile crash report")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share crash log")) }
}
