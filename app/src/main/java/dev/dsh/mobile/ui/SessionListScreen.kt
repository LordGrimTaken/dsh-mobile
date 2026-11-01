package dev.dsh.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.dsh.mobile.BuildConfig
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.APPROVAL_POLICY_ASK
import dev.dsh.mobile.data.APPROVAL_POLICY_NEVER
import dev.dsh.mobile.data.ConnectionStatus
import dev.dsh.mobile.data.delegationBadgeLabel
import dev.dsh.mobile.data.delegationContentDescription
import dev.dsh.mobile.data.GoalPhaseBadge
import dev.dsh.mobile.data.Session
import dev.dsh.mobile.data.SessionStatus
import dev.dsh.mobile.data.SessionSort
import dev.dsh.mobile.data.TranscriptSearchHit
import dev.dsh.mobile.data.approvalPolicyLabel
import dev.dsh.mobile.data.Workspace
import dev.dsh.mobile.ui.theme.Danger
import dev.dsh.mobile.ui.theme.Warning
import dev.dsh.mobile.widget.DshWidgetProvider
import dev.dsh.mobile.update.UpdateUiState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Deck cards for the ShellFrame workspace-deck sheet (right-hand desktop button). */
private val sessionDeckCards = listOf(
    DeckCard(
        icon = Icons.Filled.Add,
        title = "New Session",
        description = "Start a fresh agent session on a workspace",
        command = ShellCommand.NewSession,
    ),
    DeckCard(
        icon = Icons.Filled.Search,
        title = "Search",
        description = "Filter sessions by title, preview or workspace",
        command = ShellCommand.Search,
    ),
    DeckCard(
        icon = Icons.Filled.Search,
        title = "Search all sessions",
        description = "Search message text across every session on the host",
        command = ShellCommand.HostSearch,
    ),
    DeckCard(
        icon = Icons.Filled.Terminal,
        title = "Sessions",
        description = "Clear filters and show every session",
        command = ShellCommand.Sessions,
    ),
    DeckCard(
        icon = Icons.Filled.Settings,
        title = "Settings",
        description = "Pairing, appearance, notifications and updates",
        command = ShellCommand.Settings,
    ),
    DeckCard(
        icon = Icons.Filled.Info,
        title = "About",
        description = "Build, protocol and connection details",
        command = ShellCommand.About,
    ),
)

/** Goal phase now comes from the session-list summary, so no extra per-widget polling is needed. */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: AppState,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenArchivedSessions: () -> Unit,
    onOpenHostSearch: () -> Unit,
) {
    var aboutOpen by remember { mutableStateOf(false) }
    var actionSession by remember { mutableStateOf<Session?>(null) }
    var aliasSession by remember { mutableStateOf<Session?>(null) }
    var hostRenameSession by remember { mutableStateOf<Session?>(null) }
    var archiveOnHostSession by remember { mutableStateOf<Session?>(null) }
    var forkSession by remember { mutableStateOf<Session?>(null) }
    var focusSearchSignal by remember { mutableStateOf(0) }
    var showHomeFilters by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // The footer count must reflect what the user can actually see (filters + safety net).
    val filteredSessions = state.filteredSessions()
    val widgetContext = LocalContext.current
    val mostRecent = state.visibleSessions().firstOrNull()
    val mostRecentQueued = mostRecent?.let { state.queuedFor(it.id).size } ?: 0
    val pendingInput = state.pendingInputCount
    // The list summary carries the goal phase and goal.changed keeps it current; no extra poll.
    val mostRecentGoalPhase = mostRecent?.goalPhase?.takeIf { it.isNotBlank() }
    LaunchedEffect(
        mostRecent?.id,
        mostRecent?.title,
        mostRecent?.status,
        mostRecentQueued,
        pendingInput,
        mostRecentGoalPhase,
    ) {
        if (mostRecent != null) {
            val status = buildString {
                append(statusLabel(mostRecent.status).lowercase())
                if (mostRecentQueued > 0) append(" · $mostRecentQueued queued")
                if (pendingInput > 0) append(" · $pendingInput waiting")
                mostRecentGoalPhase?.let { phase -> append(" · goal ${phase.lowercase()}") }
            }
            DshWidgetProvider.refresh(widgetContext, mostRecent.title, status)
        }
    }

    LaunchedEffect(focusSearchSignal) {
        if (focusSearchSignal > 0) {
            searchFocusRequester.requestFocus()
        }
    }

    ShellFrame(
        title = "DeepSeek Harness",
        subtitle = connectionSubtitle(state),
        status = ShellStatus(
            left = "❯ dsh",
            items = buildList {
                add(connectionLabel(state))
                add("${filteredSessions.size} sessions")
                add("${state.workspaces.size} workspaces")
                state.activeProfile?.label?.takeIf { it.isNotBlank() }?.let { add(it) }
                lastUpdatedLabel(state)?.let { add(it) }
            },
            right = listOfNotNull(
                "v${BuildConfig.VERSION_NAME}",
                state.activeHostVersion?.takeIf { it.isNotBlank() }?.let { "host v$it" },
            ),
        ),
        deckCards = sessionDeckCards + listOf(
            DeckCard(
                icon = Icons.Filled.Flag,
                title = "Goals (${state.goalSessionCount})",
                description = "Every session goal at a glance",
                command = ShellCommand.Goals,
            ),
            DeckCard(
                icon = Icons.Filled.Schedule,
                title = state.scheduleCount?.let { "Schedules ($it)" } ?: "Schedules",
                description = "Scheduled prompts across sessions",
                command = ShellCommand.Schedules,
            ),
            DeckCard(
                icon = Icons.Filled.Archive,
                title = state.archivedSessionsState?.sessions?.size
                    ?.let { "Archived ($it)" }
                    ?: "Archived",
                description = "Restore sessions archived on the host",
                command = ShellCommand.Archived,
            ),
        ),
        onCommand = { command ->
            when (command) {
                ShellCommand.NewSession -> state.openNewSession()
                ShellCommand.Search -> focusSearchSignal += 1
                ShellCommand.Sessions -> {
                    state.selectWorkspace(null)
                    state.setSearch("")
                }

                ShellCommand.Settings -> onOpenSettings()
                ShellCommand.About -> aboutOpen = true
                ShellCommand.Refresh -> state.refreshNow()
                ShellCommand.Connection -> if (state.hostCount > 0) {
                    state.openHosts()
                } else {
                    state.openPairing()
                }
                ShellCommand.Goals -> onOpenGoals()
                ShellCommand.Schedules -> onOpenSchedules()
                ShellCommand.Archived -> onOpenArchivedSessions()
                ShellCommand.HostSearch -> onOpenHostSearch()
                ShellCommand.ToggleTheme, ShellCommand.Appearance -> onOpenSettings()
                else -> Unit
            }
        },
        drawerSessions = state.visibleSessions().map { it.title },
        onOpenSessionTitle = { title ->
            state.visibleSessions().firstOrNull { it.title == title }?.let { session ->
                onOpenSession(session.id)
            }
        },
        compact = true,
    ) {
        Column(Modifier.fillMaxSize()) {
            CompactHomeHeader(
                state = state,
                sessionCount = filteredSessions.size,
                onNewSession = state::openNewSession,
                onOpenGoals = onOpenGoals,
                onOpenSchedules = onOpenSchedules,
                onOpenArchivedSessions = onOpenArchivedSessions,
                onOpenHostSearch = onOpenHostSearch,
                onOpenInbox = onOpenInbox,
                onOpenUsage = onOpenUsage,
            )
            (state.updates.state as? UpdateUiState.Available)?.let { update ->
                HadalGhostButton(
                    text = "Update ${update.update.versionName} available · view",
                    onClick = state.updates::showUpdateDialog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            SearchField(
                value = state.searchQuery,
                onValueChange = state::setSearch,
                focusRequester = searchFocusRequester,
            )
            Spacer(Modifier.height(8.dp))
            HomeFilterBar(
                workspaceName = state.workspaces
                    .firstOrNull { it.id == state.selectedWorkspace }
                    ?.name,
                statusFilter = state.statusFilter,
                sort = state.sessionSort,
                activeCount = homeActiveFilterCount(
                    workspaceId = state.selectedWorkspace,
                    statusFilter = state.statusFilter,
                    sort = state.sessionSort,
                ),
                onOpenFilters = { showHomeFilters = true },
            )
            Spacer(Modifier.height(8.dp))
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = state::refreshNow,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                SessionLedger(
                    state = state,
                    onOpenSession = onOpenSession,
                    onLongClickSession = { session -> actionSession = session },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showHomeFilters) {
                HomeFiltersSheet(
                    state = state,
                    onDismiss = { showHomeFilters = false },
                )
            }
        }
    }

    if (aboutOpen) {
        AboutDialog(state = state, onDismiss = { aboutOpen = false })
    }
    actionSession?.let { session ->
        SessionActionsDialog(
            session = session,
            title = state.displayTitle(session),
            pinned = session.id in state.pinnedSessionIds,
            muted = state.isSessionMuted(session.id),
            onTogglePin = {
                state.togglePinnedSession(session.id)
                actionSession = null
            },
            onToggleMute = {
                state.toggleSessionMuted(session.id)
                actionSession = null
            },
            onContinue = {
                state.continueSession(session.id)
                actionSession = null
            },
            onRename = {
                aliasSession = session
                actionSession = null
            },
            onRenameOnHost = {
                hostRenameSession = session
                actionSession = null
            },
            onArchiveOnHost = {
                archiveOnHostSession = session
                actionSession = null
            },
            onApprovalPolicy = {
                state.openApprovalPolicy(session.id)
                actionSession = null
            },
            onForkConversation = {
                state.clearForkStatus()
                forkSession = session
                actionSession = null
            },
            onHide = {
                state.hideSession(session.id)
                actionSession = null
            },
            onOpen = {
                onOpenSession(session.id)
                actionSession = null
            },
            onDismiss = { actionSession = null },
        )
    }
    aliasSession?.let { session ->
        RenameSessionDialog(
            session = session,
            currentAlias = state.aliasFor(session.id),
            onSave = { alias ->
                state.setSessionAlias(session.id, alias)
                aliasSession = null
            },
            onDismiss = { aliasSession = null },
        )
    }
    hostRenameSession?.let { session ->
        HostRenameDialog(
            session = session,
            loading = state.sessionActionLoading,
            onSave = { title ->
                state.renameSessionOnHost(session.id, title)
                hostRenameSession = null
            },
            onDismiss = { if (!state.sessionActionLoading) hostRenameSession = null },
        )
    }
    archiveOnHostSession?.let { session ->
        ArchiveSessionDialog(
            session = session,
            loading = state.sessionActionLoading,
            onConfirm = {
                state.archiveSessionOnHost(session.id)
                archiveOnHostSession = null
            },
            onDismiss = { if (!state.sessionActionLoading) archiveOnHostSession = null },
        )
    }
    if (state.approvalPolicySessionId != null) {
        ApprovalPolicyDialog(state = state, onDismiss = state::closeApprovalPolicy)
    }
    forkSession?.let { session ->
        val busy = state.forkInProgressSessionId == session.id
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    forkSession = null
                    state.clearForkStatus()
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Fork conversation?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Create a new session from this conversation's latest completed turn?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (busy) {
                        Text(
                            "Forking...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    state.forkStatus?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.startsWith("Forked")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            },
            confirmButton = {
                Text(
                    if (busy) "Forking..." else "Fork conversation",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) { state.forkConversation(session.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (busy) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (!busy) {
                                forkSession = null
                                state.clearForkStatus()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

// region hero / search / filters

/** Compact terminal header: prompt line, one-line stats, New session and the More overflow. */
@Composable
private fun CompactHomeHeader(
    state: AppState,
    sessionCount: Int,
    onNewSession: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenArchivedSessions: () -> Unit,
    onOpenHostSearch: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            homeTerminalPrompt(state.hostLabel),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sessionCount == 1) "1 session" else "$sessionCount sessions",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HadalPrimaryButton(
                text = HOME_NEW_SESSION_LABEL,
                onClick = onNewSession,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = HOME_NEW_SESSION_LABEL },
            )
            var moreOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { moreOpen = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = HOME_MORE_CONTENT_DESCRIPTION,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = moreOpen,
                    onDismissRequest = { moreOpen = false },
                ) {
                    HOME_MORE_MENU_ITEMS.forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = label },
                            onClick = {
                                moreOpen = false
                                when (label) {
                                    "Goals" -> onOpenGoals()
                                    "Schedules" -> onOpenSchedules()
                                    "Archived sessions" -> onOpenArchivedSessions()
                                    "Search all sessions" -> onOpenHostSearch()
                                    "Inbox" -> onOpenInbox()
                                    "Usage" -> onOpenUsage()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One-line active-filter summary plus the single Filters action that opens the sheet. */
@Composable
private fun HomeFilterBar(
    workspaceName: String?,
    statusFilter: String?,
    sort: SessionSort,
    activeCount: Int,
    onOpenFilters: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            homeFilterSummary(workspaceName, statusFilter, sort),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HadalGhostButton(
            text = homeFilterActionLabel(activeCount),
            onClick = onOpenFilters,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = if (activeCount > 0) {
                        "$HOME_FILTERS_LABEL, $activeCount active"
                    } else {
                        HOME_FILTERS_LABEL
                    }
                },
        )
    }
}

/** All filters live in one sheet: workspace, status and sort with Reset/Done. */
@Composable
private fun HomeFiltersSheet(
    state: AppState,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
        HadalCaps("Filters")
        Spacer(Modifier.height(8.dp))
        Text(
            "Refine the session ledger. Changes apply immediately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(10.dp))
        Text("Workspace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        WorkspaceChips(
            workspaces = state.workspaces,
            selected = state.selectedWorkspace,
            onSelect = state::selectWorkspace,
        )
        Spacer(Modifier.height(10.dp))
        Text("Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        StatusChips(
            selected = state.statusFilter,
            onSelect = state::updateStatusFilter,
        )
        Spacer(Modifier.height(10.dp))
        Text("Sort", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        SortChips(
            selected = state.sessionSort,
            onSelect = state::updateSessionSort,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HadalGhostButton(
                text = HOME_FILTERS_RESET_LABEL,
                onClick = {
                    state.selectWorkspace(null)
                    state.updateStatusFilter(null)
                    state.updateSessionSort(SessionSort.ACTIVITY)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
            HadalGhostButton(
                text = HOME_FILTERS_DONE_LABEL,
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
        }
        }
    }
}

@Composable
private fun Hero(
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenGoals: () -> Unit,
    pendingInputCount: Int,
    goalCount: Int,
) {
    val wordmark = MaterialTheme.typography.headlineSmall.copy(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("❯", style = wordmark, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                "DEEPSEEK HARNESS",
                modifier = Modifier.weight(1f),
                style = wordmark,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Let's get to work",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HadalPrimaryButton(
                text = "New Session",
                onClick = onNewSession,
                modifier = Modifier.weight(1f),
            )
            HadalGhostButton(text = "Settings", onClick = onOpenSettings)
            HadalGhostButton(text = "Usage", onClick = onOpenUsage)
        }
        if (pendingInputCount > 0) {
            HadalGhostButton(
                text = "Inbox · $pendingInputCount waiting",
                onClick = onOpenInbox,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (goalCount > 0) {
            HadalGhostButton(
                text = "Goals · $goalCount with goals",
                onClick = onOpenGoals,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "❯",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "search sessions…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun WorkspaceChips(
    workspaces: List<Workspace>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceChip("[all]", selected == null) { onSelect(null) }
        workspaces.forEach { workspace ->
            WorkspaceChip("[${workspace.name}]", selected == workspace.id) {
                onSelect(workspace.id)
            }
        }
    }
}

@Composable
private fun SortChips(
    selected: SessionSort,
    onSelect: (SessionSort) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "sort",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        WorkspaceChip("activity", selected == SessionSort.ACTIVITY) {
            onSelect(SessionSort.ACTIVITY)
        }
        WorkspaceChip("title", selected == SessionSort.TITLE) {
            onSelect(SessionSort.TITLE)
        }
        WorkspaceChip("status", selected == SessionSort.STATUS) {
            onSelect(SessionSort.STATUS)
        }
    }
}

@Composable
private fun StatusChips(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceChip("all", selected == null) { onSelect(null) }
        WorkspaceChip("working", selected == "working") { onSelect("working") }
        WorkspaceChip("idle", selected == "idle") { onSelect("idle") }
        WorkspaceChip("unread", selected == "unread") { onSelect("unread") }
    }
}

@Composable
private fun WorkspaceChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .border(
                width = 1.dp,
                color = if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            maxLines = 1,
        )
    }
}

// endregion

// region session ledger

@Composable
private fun SessionLedger(
    state: AppState,
    onOpenSession: (String) -> Unit,
    onLongClickSession: (Session) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions = state.filteredSessions()
    val grouped = sessions.groupBy { it.day }
    val showConnectionBanner = !state.isLive || state.connection == ConnectionStatus.Offline
    val query = state.searchQuery.trim()
    var messageHits by remember { mutableStateOf<List<TranscriptSearchHit>>(emptyList()) }
    var searchingMessages by remember { mutableStateOf(false) }
    LaunchedEffect(query, state.activeHostId) {
        if (query.length < 2 || !state.isLive) {
            messageHits = emptyList()
            searchingMessages = false
            return@LaunchedEffect
        }
        delay(250)
        searchingMessages = true
        messageHits = state.searchCachedMessages(query)
        searchingMessages = false
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showConnectionBanner) {
            item(key = "connection-banner") {
                ConnectionBanner(state = state, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (query.length >= 2 && (messageHits.isNotEmpty() || searchingMessages)) {
            item(key = "messages-header") {
                HadalCaps(
                    "CACHED MESSAGES",
                    modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            if (searchingMessages) {
                item(key = "messages-searching") {
                    Text(
                        "Searching cached transcripts…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                    )
                }
            } else {
                items(messageHits, key = { "hit-${it.sessionId}" }) { hit ->
                    TranscriptHitRow(hit = hit, onClick = { onOpenSession(hit.sessionId) })
                }
            }
        }
        listOf("Today", "Yesterday", "Earlier").forEach { day ->
            val daySessions = grouped[day].orEmpty()
            if (daySessions.isNotEmpty()) {
                item(key = "header-$day") {
                    HadalCaps(
                        day.uppercase(),
                        modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(daySessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        title = state.displayTitle(session),
                        workspace = state.workspaceFor(session.workspaceId),
                        pinned = session.id in state.pinnedSessionIds,
                        queuedCount = state.queuedFor(session.id).size,
                        muted = state.isSessionMuted(session.id),
                        onTogglePin = { state.togglePinnedSession(session.id) },
                        onClick = { onOpenSession(session.id) },
                        onLongClick = { onLongClickSession(session) },
                    )
                }
            }
        }
        if (sessions.isEmpty()) {
            item(key = "empty") {
                val hasFilters = state.searchQuery.isNotBlank() || state.selectedWorkspace != null
                val title = when {
                    hasFilters -> "no sessions match your filters"
                    state.isLive && state.connection != ConnectionStatus.Live ->
                        "no sessions yet · connecting to your desktop"
                    else -> "no sessions yet"
                }
                val body = if (hasFilters) {
                    "Try another search or workspace, or start a fresh session."
                } else {
                    "Start a session on your desktop and it will appear here. Pull down to refresh."
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "❯",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        body,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasFilters) {
                            HadalGhostButton(
                                text = "clear filters",
                                onClick = {
                                    state.setSearch("")
                                    state.selectWorkspace(null)
                                },
                            )
                        }
                        HadalPrimaryButton(
                            text = "New Session",
                            onClick = state::openNewSession,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptHitRow(hit: TranscriptSearchHit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            hit.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            hit.snippet,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Session ledger line: status dot, 14sp semibold title, 12sp preview, 10sp relative
 * time + chevron; a mint left rule marks the session that is actively working, an
 * unread session gets a mint dot next to its title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: Session,
    title: String,
    workspace: Workspace,
    pinned: Boolean,
    queuedCount: Int,
    muted: Boolean,
    onTogglePin: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val working = session.status == SessionStatus.Working
    val shape = RoundedCornerShape(10.dp)
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (working) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .border(
                width = 1.dp,
                color = if (working) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (working) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 5.dp)) {
                    StatusDot(session.status, 6.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.unread) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = titleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (pinned) "★" else "☆",
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onTogglePin)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (pinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            session.updated,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "›",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            statusLabel(session.status).lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(session.status),
                            maxLines = 1,
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            workspace.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        delegationBadgeLabel(
                            session.delegationTotal,
                            session.delegationWorking,
                            "agent",
                        )?.let { label ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                modifier = Modifier.semantics {
                                    contentDescription = delegationContentDescription(
                                        session.delegationTotal,
                                        session.delegationWorking,
                                        "agent",
                                    ) ?: label
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (muted) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "muted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                            )
                        }
                        if (queuedCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$queuedCount queued",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        GoalPhaseBadge.label(session.goalPhase)?.let { goalTag ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                goalTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                maxLines = 1,
                            )
                        }
                        if (working) {
                            Spacer(Modifier.width(8.dp))
                            WorkingDots(dotSize = 3.dp)
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region connection banner

/**
 * Compact connection recovery: shows only while a paired host is unreachable (SSE/health down)
 * and disappears by itself once the connection is Live again.
 */
@Composable
private fun ConnectionBanner(
    state: AppState,
    modifier: Modifier = Modifier,
) {
    if (state.isLive) {
        if (state.connection == ConnectionStatus.Offline) {
            CompactReconnectBar(
                onReconnect = state::reconnect,
                modifier = modifier,
            )
        }
        return
    }

    HadalCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Warning),
            )
            Spacer(Modifier.width(8.dp))
            HadalCaps("not paired", modifier = Modifier.weight(1f))
            HadalGhostButton(text = "Pair device", onClick = state::openPairing)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Connect to your desktop",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "You're viewing demo data. Pair this device to see live DSH sessions, approvals " +
                "and questions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

// endregion

// region about

@Composable
private fun SessionActionsDialog(
    session: Session,
    title: String,
    pinned: Boolean,
    muted: Boolean,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onContinue: () -> Unit,
    onRename: () -> Unit,
    onRenameOnHost: () -> Unit,
    onArchiveOnHost: () -> Unit,
    onApprovalPolicy: () -> Unit,
    onForkConversation: () -> Unit,
    onHide: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SessionActionRow(if (pinned) "Unpin from top" else "Pin to top", onTogglePin)
                if (session.status != SessionStatus.Working) {
                    SessionActionRow("Continue working", onContinue)
                }
                SessionActionRow("Rename locally…", onRename)
                SessionActionRow("Rename on host…", onRenameOnHost)
                SessionActionRow("Archive on host…", onArchiveOnHost)
                SessionActionRow("Approval policy…", onApprovalPolicy)
                SessionActionRow("Fork conversation", onForkConversation)
                SessionActionRow(
                    if (muted) "Unmute notifications" else "Mute notifications",
                    onToggleMute,
                )
                SessionActionRow("Hide from session list", onHide)
                SessionActionRow(
                    "Copy session ID",
                    {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("DSH session", session.id))
                        Toast.makeText(context, "Session ID copied", Toast.LENGTH_SHORT).show()
                    },
                )
                SessionActionRow("Open session", onOpen)
            }
        },
        confirmButton = {
            Text(
                "Close",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
private fun SessionActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun RenameSessionDialog(
    session: Session,
    currentAlias: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var alias by remember(session.id) { mutableStateOf(currentAlias.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Rename locally",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Display name on this phone only; the desktop session title is unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Display name") },
                    placeholder = { Text(session.title.take(60)) },
                )
            }
        },
        confirmButton = {
            Text(
                "Save",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSave(alias.trim()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        dismissButton = {
            if (currentAlias != null) {
                Text(
                    "Reset",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSave("") }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        },
    )
}

/** Rename on the host: changes the stored title, so every paired device sees the new name. */
@Composable
private fun HostRenameDialog(
    session: Session,
    loading: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(session.id) { mutableStateOf(session.title) }
    val canSave = !loading && title.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Rename on host",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This changes the session title on the host and every paired device. The local " +
                        "display name stays separate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Host title") },
                )
            }
        },
        confirmButton = {
            Text(
                if (loading) "Renaming…" else "Rename",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canSave) { onSave(title.trim()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (canSave) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !loading, onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

/** Archive on the host: removes the session from the list on every device (restorable). */
@Composable
private fun ArchiveSessionDialog(
    session: Session,
    loading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Archive on host?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                "\"${session.title}\" will be removed from the session list on every device. " +
                    "You can restore it later from Archived sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Text(
                if (loading) "Archiving…" else "Archive",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !loading, onClick = onConfirm)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (loading) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        dismissButton = {
            Text(
                "Cancel",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !loading, onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

@Composable
private fun AboutDialog(
    state: AppState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "❯",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "ABOUT",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AboutRow("build", "DSH Mobile ${state.updates.currentVersionName}")
                AboutRow("protocol", "DSH Mobile API v1 (HTTP + SSE)")
                AboutRow("theme", "hadal mint")
                AboutRow(
                    "mode",
                    if (state.isLive) "live · ${state.hostLabel ?: "desktop"}" else "demo · not paired",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Paired builds stream sessions over HTTP + SSE and check the release " +
                        "feed for sideload updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        },
        confirmButton = {
            HadalGhostButton(text = "Close", onClick = onDismiss)
        },
    )
}

@Composable
private fun AboutRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// endregion

// region labels

private fun connectionLabel(state: AppState): String = when {
    !state.isLive -> "Demo"
    state.connection == ConnectionStatus.Live -> "● Live"
    state.connection == ConnectionStatus.Connecting -> "Connecting"
    else -> "Offline"
}

private fun connectionSubtitle(state: AppState): String = when {
    !state.isLive -> "Demo data · not paired"
    state.connection == ConnectionStatus.Live -> "Live · ${state.hostLabel ?: "desktop"}"
    state.connection == ConnectionStatus.Connecting ->
        "Connecting · ${state.hostLabel ?: "desktop"}"

    else -> "Offline · ${state.hostLabel ?: "desktop"}"
}

private val UPDATED_HINT_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "updated 08:55" from the last successful snapshot refresh; null while unpaired. */
private fun lastUpdatedLabel(state: AppState): String? {
    val refreshedAt = state.lastRefreshedAt ?: return if (state.isLive) "not synced yet" else null
    val time = Instant.ofEpochMilli(refreshedAt).atZone(ZoneId.systemDefault()).toLocalTime()
    return "updated ${UPDATED_HINT_CLOCK.format(time)}"
}

// region approval policy dialog

/**
 * Per-session approval policy (server-side). Ask keeps approvals flowing to the phone/desktop;
 * Never auto-rejects anything that needs approval, so it asks for a second confirmation.
 */
@Composable
private fun ApprovalPolicyDialog(state: AppState, onDismiss: () -> Unit) {
    val policy = state.approvalPolicy
    val loading = state.approvalPolicyLoading
    val saving = state.approvalPolicySaving
    val unsupported = policy?.supported == false
    val live = policy?.live == true
    var selected by remember { mutableStateOf(policy?.policy ?: APPROVAL_POLICY_ASK) }
    var confirmNever by remember { mutableStateOf(false) }
    LaunchedEffect(policy) {
        val currentPolicy = policy
        if (currentPolicy != null && currentPolicy.supported) selected = currentPolicy.policy
    }
    val canSave = policy != null && policy.supported && live && !saving &&
        selected != policy.policy
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Approval policy",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading && policy == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Loading approval policy…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    policy == null && state.approvalPolicyError != null -> Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            state.approvalPolicyError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Retry",
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { state.retryApprovalPolicy() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    unsupported -> Text(
                        "This host doesn't support approval policy changes yet. Update the " +
                            "desktop host plugin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    else -> {
                        Text(
                            "Current: ${approvalPolicyLabel(policy?.policy ?: APPROVAL_POLICY_ASK)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Ask: approvals may be requested and answered on the phone or " +
                                "desktop. Never: actions that need approval are rejected " +
                                "automatically (unattended runs).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        ApprovalPolicyOptionRow(
                            title = "Ask",
                            description = "Approvals may be requested; answer them on the " +
                                "phone or desktop.",
                            selected = selected == APPROVAL_POLICY_ASK,
                            enabled = !saving,
                            onClick = { selected = APPROVAL_POLICY_ASK },
                        )
                        ApprovalPolicyOptionRow(
                            title = "Never",
                            description = "Actions that need approval are rejected " +
                                "automatically (unattended).",
                            selected = selected == APPROVAL_POLICY_NEVER,
                            enabled = !saving,
                            onClick = { selected = APPROVAL_POLICY_NEVER },
                        )
                        if (selected == APPROVAL_POLICY_NEVER) {
                            Text(
                                "Actions that need approval will be rejected automatically.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Warning,
                            )
                        }
                        if (!live) {
                            Text(
                                "Changes require a live session. Open the session on the " +
                                    "desktop first, then change this policy here.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (saving) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Saving…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        state.approvalPolicyError?.let { policyError ->
                            Text(
                                policyError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (policy != null && policy.supported) {
                Text(
                    if (saving) "Saving…" else "Save",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = canSave) {
                            if (selected == APPROVAL_POLICY_NEVER) {
                                confirmNever = true
                            } else {
                                state.saveApprovalPolicy(selected)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        },
        dismissButton = {
            Text(
                "Close",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { if (!saving) onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
    if (confirmNever) {
        AlertDialog(
            onDismissRequest = { confirmNever = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Turn off approvals for this session?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "Actions that need approval will be rejected automatically. Continue only " +
                        "if this session should run unattended.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Reject approvals automatically",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            confirmNever = false
                            state.saveApprovalPolicy(APPROVAL_POLICY_NEVER)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Danger,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { confirmNever = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

@Composable
private fun ApprovalPolicyOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            if (selected) "●" else "○",
            modifier = Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

// endregion
