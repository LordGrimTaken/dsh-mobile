package dev.dsh.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.data.SessionSort
import dev.dsh.mobile.data.SessionStatus
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shell API — shared by ChatScreen and SessionListScreen.
// ---------------------------------------------------------------------------

enum class ShellCommand {
    NewSession, Search, Sessions, Settings, Appearance, ToggleTheme,
    Refresh, Connection, About, ToolActivity, SessionInfo, Approvals, Goals, Schedules,
    Archived, HostSearch,
}

data class DeckCard(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val command: ShellCommand,
)

data class ShellStatus(
    val left: String,                        // e.g. "❯ .dsh"
    val items: List<String>,                 // e.g. listOf("● Live", "12 msgs", "3 tools")
    val right: List<String> = emptyList(),   // e.g. listOf("MONO", "v0.2.1")
)

/** One destination row in the cockpit drawer's `[ navigate ]` section. */
data class ShellDrawerAction(
    val icon: ImageVector,
    val label: String,
    val badge: String? = null,
    val onClick: () -> Unit,
)

/** One richer session row in the cockpit drawer's `[ sessions ]` section. */
data class ShellDrawerSession(
    val id: String,
    val title: String,
    val workspace: String,
    val status: SessionStatus,
    val unread: Boolean,
    val updated: String,
    val current: Boolean,
)

internal const val DRAWER_NAV_HEADING: String = "[ navigate ]"
internal const val DRAWER_SESSIONS_HEADING: String = "[ sessions ]"
internal const val DRAWER_NEW_SESSION_LABEL: String = "New session"
internal const val DRAWER_SEARCH_LABEL: String = "Search"
internal const val DRAWER_REFRESH_LABEL: String = "Refresh"
internal const val DRAWER_SETTINGS_LABEL: String = "Settings"
internal const val DRAWER_ABOUT_LABEL: String = "About"
internal const val DRAWER_VIEW_ALL_LABEL: String = "view all"
internal const val DRAWER_NO_SESSIONS_LABEL: String = "No sessions in this workspace yet."
internal const val DRAWER_MAX_SESSION_ROWS: Int = 8

/** Default cockpit order for Home/Chat navigation actions (badge lives on Inbox). */
internal val DRAWER_NAV_LABELS: List<String> = listOf(
    "Goals",
    "Schedules",
    "Archived sessions",
    "Search all sessions",
    "Inbox",
    "Usage",
)

/** Normalized live/offline/demo chip text derived from the shell status line. */
internal fun drawerStatusChipText(status: ShellStatus): String {
    val haystack = (status.items + status.left).joinToString(" ").lowercase()
    val state = when {
        "offline" in haystack -> "offline"
        "connecting" in haystack -> "connecting"
        "demo" in haystack -> "demo"
        "live" in haystack -> "live"
        else -> "idle"
    }
    return "[ ● $state ]"
}

/** Second dim header line: host/sync from subtitle, otherwise the status items. */
internal fun drawerHeaderMeta(subtitle: String?, status: ShellStatus): String {
    val subtitleText = subtitle?.trim()?.takeIf { it.isNotEmpty() }
    if (subtitleText != null) return subtitleText
    return status.items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        .ifBlank { status.left.trim() }
}

/** Footer sync/version text from the status' right-hand items (falls back to the meta line). */
internal fun drawerFooterText(status: ShellStatus, subtitle: String?): String {
    val right = status.right.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
    if (right.isNotEmpty()) return right
    return drawerHeaderMeta(subtitle, status)
}

internal fun drawerStatusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Working -> "working"
    SessionStatus.NeedsInput -> "needs input"
    SessionStatus.Idle -> "idle"
}

/** One-line session meta: workspace · status · updated (blank pieces skipped). */
internal fun drawerSessionMeta(workspace: String, status: SessionStatus, updated: String): String =
    listOf(workspace.trim(), drawerStatusLabel(status), updated.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" · ")

/** Badge formatting: hidden at 0, capped at 99+. */
internal fun drawerBadgeLabel(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** "Sessions (12)" header count; hidden when there are none. */
/** TalkBack label for one cockpit session row. */
internal fun drawerSessionA11y(session: ShellDrawerSession): String = buildString {
    append(session.title.trim().ifBlank { "Untitled session" })
    append(", ")
    append(session.workspace.trim().ifBlank { "no workspace" })
    append(", ")
    append(drawerStatusLabel(session.status))
    if (session.unread) append(", unread")
    if (session.current) append(", current")
}

internal fun drawerSessionsHeading(total: Int): String =
    if (total <= 0) DRAWER_SESSIONS_HEADING else "$DRAWER_SESSIONS_HEADING · $total"

/** Overflow line when more sessions exist than the drawer lists. */
internal fun drawerSessionsOverflow(total: Int, shown: Int = DRAWER_MAX_SESSION_ROWS): String? {
    val hidden = total - shown
    return if (hidden > 0) "+$hidden" else null
}

private data class ShellMenu(val label: String, val commands: List<ShellCommand>)

private val SHELL_MENUS = listOf(
    ShellMenu(
        "File",
        listOf(ShellCommand.NewSession, ShellCommand.Sessions, ShellCommand.Settings),
    ),
    ShellMenu(
        "Edit",
        listOf(ShellCommand.Search, ShellCommand.Refresh),
    ),
    ShellMenu(
        "View",
        listOf(ShellCommand.SessionInfo, ShellCommand.ToolActivity, ShellCommand.Approvals),
    ),
    ShellMenu(
        "Window",
        listOf(ShellCommand.ToggleTheme, ShellCommand.Connection),
    ),
    ShellMenu(
        "Help",
        listOf(ShellCommand.About),
    ),
)

private fun commandLabel(command: ShellCommand): String = when (command) {
    ShellCommand.NewSession -> "New session"
    ShellCommand.Search -> "Search sessions"
    ShellCommand.Sessions -> "Sessions"
    ShellCommand.Settings -> "Settings"
    ShellCommand.Appearance -> "Appearance"
    ShellCommand.ToggleTheme -> "Toggle theme"
    ShellCommand.Refresh -> "Refresh"
    ShellCommand.Connection -> "Connection"
    ShellCommand.About -> "About DSH"
    ShellCommand.ToolActivity -> "Tool activity"
    ShellCommand.SessionInfo -> "Session info"
    ShellCommand.Approvals -> "Approvals"
    ShellCommand.Goals -> "Goals"
    ShellCommand.Schedules -> "Schedules"
    ShellCommand.Archived -> "Archived sessions"
    ShellCommand.HostSearch -> "Search all sessions"
}

/**
 * App-wide mobile shell: top bar + File/Edit/View/Window/Help command strip +
 * content slot + 24dp status strip, plus the left rail drawer and the workspace
 * deck bottom sheet.
 *
 * The shell owns all system-bar padding: the top bar applies statusBarsPadding,
 * the status strip applies navigationBarsPadding, and [content] gets neither
 * horizontal padding nor insets — screens pad themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellFrame(
    title: String,
    subtitle: String?,
    status: ShellStatus,
    deckCards: List<DeckCard>,
    onCommand: (ShellCommand) -> Unit,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    showDeck: Boolean = true,
    /** Terminal-style compact shell: no command strip, no workspace deck, shorter bar, one-line status. */
    compact: Boolean = false,
    /** Hide the bottom status strip entirely (home uses the bottom nav instead). */
    showStatus: Boolean = true,
    /** Cockpit drawer content supplied by the host screen. */
    drawerActions: List<ShellDrawerAction> = emptyList(),
    drawerSessions: List<ShellDrawerSession> = emptyList(),
    onOpenSession: ((String) -> Unit)? = null,
    onViewAllSessions: (() -> Unit)? = null,
    onNewSession: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var deckOpen by rememberSaveable { mutableStateOf(false) }

    fun openDrawer() {
        val custom = onMenu
        if (custom != null) {
            custom()
        } else {
            scope.launch { drawerState.open() }
        }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ShellDrawerRail(
                title = title,
                subtitle = subtitle,
                status = status,
                actions = drawerActions,
                sessions = drawerSessions,
                onClose = { closeDrawer() },
                onCommand = onCommand,
                onOpenSession = onOpenSession,
                onViewAllSessions = onViewAllSessions,
                onNewSession = onNewSession,
                onSearch = onSearch,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onOpenAbout = onOpenAbout,
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ShellTopBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                onMenuClick = { openDrawer() },
                showDeck = showDeck && !compact,
                onOpenDeck = { deckOpen = true },
                compact = compact,
                trailing = trailing,
            )
            if (!compact) {
                ShellCommandStrip(onCommand = onCommand)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                content()
            }
            if (showStatus) {
                ShellStatusStrip(status = status, compact = compact)
            }
        }
    }

    if (deckOpen && !compact) {
        WorkspaceDeckSheet(
            deckCards = deckCards,
            onCommand = onCommand,
            onDismiss = { deckOpen = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar + command strip + status strip
// ---------------------------------------------------------------------------

@Composable
private fun ShellTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onMenuClick: () -> Unit,
    showDeck: Boolean,
    onOpenDeck: () -> Unit,
    compact: Boolean,
    trailing: (@Composable RowScope.() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 48.dp else 56.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "Navigation",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                "❯",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = (if (compact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    }).copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact && subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
            if (showDeck) {
                IconButton(onClick = onOpenDeck) {
                    Icon(
                        Icons.Filled.GridView,
                        contentDescription = "Workspace deck",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellCommandStrip(onCommand: (ShellCommand) -> Unit) {
    var openMenu by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 26.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SHELL_MENUS.forEachIndexed { index, menu ->
            if (index > 0) {
                Text(
                    "·",
                    modifier = Modifier.padding(horizontal = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            Box {
                Text(
                    menu.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { openMenu = if (openMenu == menu.label) null else menu.label }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (openMenu == menu.label) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                DropdownMenu(
                    expanded = openMenu == menu.label,
                    onDismissRequest = { openMenu = null },
                ) {
                    menu.commands.forEach { command ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    commandLabel(command),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                openMenu = null
                                onCommand(command)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellStatusStrip(status: ShellStatus, compact: Boolean) {
    val promptColor = MaterialTheme.colorScheme.primary
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val liveColor = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        if (compact) {
            // Single-line status: no wrapping, no crowded right-aligned version block.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    shellCompactStatusLine(status),
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    promptAnnotated(status.left, promptColor),
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                if (status.items.isNotEmpty()) {
                    Text(
                        statusItemsAnnotated(status.items, liveColor),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (status.right.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status.right.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Compact shell mode keeps the status to one honest, truncated line. */
internal fun shellCompactStatusLine(status: ShellStatus): String {
    val parts = buildList {
        status.left.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
        status.items.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
        status.right.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
    }
    return parts.joinToString(" · ")
}

private fun promptAnnotated(text: String, promptColor: Color): AnnotatedString =
    buildAnnotatedString {
        if (text.startsWith("❯")) {
            pushStyle(SpanStyle(color = promptColor))
            append("❯")
            pop()
            append(text.removePrefix("❯"))
        } else {
            append(text)
        }
    }

private fun statusItemsAnnotated(items: List<String>, liveColor: Color): AnnotatedString =
    buildAnnotatedString {
        items.forEachIndexed { index, item ->
            if (index > 0) append(" · ")
            if (item.startsWith("●")) {
                pushStyle(SpanStyle(color = liveColor))
                append("●")
                pop()
                append(item.removePrefix("●"))
            } else {
                append(item)
            }
        }
    }

// ---------------------------------------------------------------------------
// Left rail drawer
// ---------------------------------------------------------------------------

@Composable
private fun ShellDrawerRail(
    title: String,
    subtitle: String?,
    status: ShellStatus,
    actions: List<ShellDrawerAction>,
    sessions: List<ShellDrawerSession>,
    onClose: () -> Unit,
    onCommand: (ShellCommand) -> Unit,
    onOpenSession: ((String) -> Unit)?,
    onViewAllSessions: (() -> Unit)?,
    onNewSession: (() -> Unit)?,
    onSearch: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onOpenAbout: (() -> Unit)?,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .widthIn(max = 340.dp),
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            // Header: brand + compact live chip + 48dp close.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "❯ dsh",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    drawerStatusChipText(status),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                drawerHeaderMeta(subtitle, status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            // Compact primary actions (not a giant hero button).
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HadalPrimaryButton(
                    text = DRAWER_NEW_SESSION_LABEL,
                    onClick = {
                        onClose()
                        onNewSession?.invoke() ?: onCommand(ShellCommand.NewSession)
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = DRAWER_NEW_SESSION_LABEL },
                )
                HadalGhostButton(
                    text = DRAWER_SEARCH_LABEL,
                    onClick = {
                        onClose()
                        onSearch?.invoke() ?: onCommand(ShellCommand.Search)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = DRAWER_SEARCH_LABEL },
                )
                HadalGhostButton(
                    text = DRAWER_REFRESH_LABEL,
                    onClick = {
                        onClose()
                        onRefresh?.invoke() ?: onCommand(ShellCommand.Refresh)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = DRAWER_REFRESH_LABEL },
                )
            }
            Spacer(Modifier.height(14.dp))
            DrawerSectionHeading(DRAWER_NAV_HEADING)
            if (actions.isEmpty()) {
                DrawerActionRow(
                    icon = Icons.Filled.History,
                    label = "Sessions",
                    badge = null,
                    onClick = {
                        onClose()
                        onCommand(ShellCommand.Sessions)
                    },
                )
            }
            actions.forEach { action ->
                DrawerActionRow(action) {
                    onClose()
                    action.onClick()
                }
            }
            Spacer(Modifier.height(14.dp))
            DrawerSectionHeading(
                text = drawerSessionsHeading(sessions.size),
                trailing = {
                    drawerSessionsOverflow(sessions.size)?.let { overflow ->
                        Text(
                            overflow,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            DRAWER_VIEW_ALL_LABEL,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    onClose()
                                    onViewAllSessions?.invoke()
                                        ?: onCommand(ShellCommand.Sessions)
                                }
                                .semantics { contentDescription = DRAWER_VIEW_ALL_LABEL }
                                .padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            if (sessions.isEmpty()) {
                Text(
                    DRAWER_NO_SESSIONS_LABEL,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                sessions.take(DRAWER_MAX_SESSION_ROWS).forEach { session ->
                    DrawerSessionItem(session) {
                        onClose()
                        onOpenSession?.invoke(session.id)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                drawerFooterText(status, subtitle),
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            DrawerActionRow(
                icon = Icons.Filled.Settings,
                label = DRAWER_SETTINGS_LABEL,
                badge = null,
                onClick = {
                    onClose()
                    onOpenSettings?.invoke() ?: onCommand(ShellCommand.Settings)
                },
            )
            DrawerActionRow(
                icon = Icons.Filled.Info,
                label = DRAWER_ABOUT_LABEL,
                badge = null,
                onClick = {
                    onClose()
                    onOpenAbout?.invoke() ?: onCommand(ShellCommand.About)
                },
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun DrawerSectionHeading(
    text: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
        trailing?.invoke()
    }
}

@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    label: String,
    badge: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = badge?.let { "$label, $it pending" } ?: label
                role = Role.Button
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        badge?.let { value ->
            Text(
                value,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "›",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun DrawerActionRow(action: ShellDrawerAction, onClick: () -> Unit) {
    DrawerActionRow(
        icon = action.icon,
        label = action.label,
        badge = action.badge,
        onClick = onClick,
    )
}

@Composable
private fun DrawerSessionItem(session: ShellDrawerSession, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (session.current) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = drawerSessionA11y(session)
                selected = session.current
                role = Role.Button
            },
    ) {
        if (session.current) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(session.status, 7.dp)
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
                        session.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (session.current) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    drawerSessionMeta(session.workspace, session.status, session.updated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Workspace deck bottom sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceDeckSheet(
    deckCards: List<DeckCard>,
    onCommand: (ShellCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            HadalCaps("Workspace")
            Spacer(Modifier.height(10.dp))
            deckCards.forEach { card ->
                DeckCardRow(card = card) {
                    onDismiss()
                    onCommand(card.command)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onDismiss()
                        onCommand(ShellCommand.Settings)
                    }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "More tools",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun DeckCardRow(card: DeckCard, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HadalIconTile(icon = card.icon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                card.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

// ---------------------------------------------------------------------------
// Reusable Hadal pieces
// ---------------------------------------------------------------------------

/** All-caps tracked section label (11sp / 0.08em). */
@Composable
fun HadalCaps(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.88.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        maxLines = 1,
    )
}

/** Layer2 card with a 1dp kelp hairline and 14dp of internal padding. */
@Composable
fun HadalCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        content = content,
    )
}

/** 34–38dp mint-wash tile with a mint glyph. */
@Composable
fun HadalIconTile(
    icon: ImageVector,
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Mint CTA with a soft mint glow; wrap-content unless the caller stretches it. */
@Composable
fun HadalPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .then(
                if (enabled) {
                    Modifier.shadow(
                        elevation = 12.dp,
                        shape = shape,
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 42.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Hairline-bordered quiet action; wrap-content unless the caller stretches it. */
@Composable
fun HadalGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 42.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One-line connection recovery bar: shown when the paired host's SSE/health drops, auto-hidden
 * again as soon as the connection is Live. "Reconnect" restarts the SSE loop + refetches.
 */
@Composable
fun CompactReconnectBar(
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "offline · retrying automatically",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Retry now",
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onReconnect)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Shared bottom sheet + compact-home copy/filter helpers
// ---------------------------------------------------------------------------

/** Shared shell-style bottom sheet: 20dp top radius, drawer-toned surface, drag handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            content = content,
        )
    }
}

internal const val HOME_FILTERS_LABEL: String = "Filters"
internal const val HOME_FILTERS_RESET_LABEL: String = "Reset"
internal const val HOME_FILTERS_DONE_LABEL: String = "Done"
internal const val HOME_NEW_SESSION_LABEL: String = "New session"
internal const val HOME_MORE_CONTENT_DESCRIPTION: String = "More"

/** Compact Home overflow: secondary destinations that used to live in the Hero. */
internal val HOME_MORE_MENU_ITEMS: List<String> = listOf(
    "Goals",
    "Schedules",
    "Archived sessions",
    "Search all sessions",
    "Inbox",
    "Usage",
)

internal const val CHAT_MORE_CONTENT_DESCRIPTION: String = "More actions"
internal const val CHAT_OVERFLOW_SKILLS_LABEL: String = "Skills"
internal const val CHAT_OVERFLOW_PLAN_LABEL: String = "Plan"
internal const val CHAT_OVERFLOW_TEMPLATES_LABEL: String = "Prompt templates"
internal const val CHAT_OVERFLOW_SESSION_INFO_LABEL: String = "Session info"
internal const val CHAT_OVERFLOW_TOOL_ACTIVITY_LABEL: String = "Tool activity"

/** Chat overflow keeps every former top-bar/deck action reachable. */
internal val CHAT_MORE_MENU_ITEMS: List<String> = listOf(
    CHAT_OVERFLOW_SKILLS_LABEL,
    CHAT_OVERFLOW_PLAN_LABEL,
    CHAT_OVERFLOW_TEMPLATES_LABEL,
    CHAT_OVERFLOW_SESSION_INFO_LABEL,
    CHAT_OVERFLOW_TOOL_ACTIVITY_LABEL,
)

/** "dsh@desk" terminal prompt; falls back to "dsh@local". */
internal fun homeTerminalPrompt(hostLabel: String?): String {
    val host = hostLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "local"
    return "dsh@$host"
}

/** Filters that differ from the home defaults (all workspaces / all statuses / activity). */
internal fun homeActiveFilterCount(
    workspaceId: String?,
    statusFilter: String?,
    sort: SessionSort,
): Int =
    (if (workspaceId != null) 1 else 0) +
        (if (statusFilter != null) 1 else 0) +
        (if (sort != SessionSort.ACTIVITY) 1 else 0)

internal fun homeFilterActionLabel(activeCount: Int): String =
    if (activeCount <= 0) HOME_FILTERS_LABEL else "$HOME_FILTERS_LABEL · $activeCount"

/** One-line active-filter summary used inside the Filters sheet and compact header. */
internal fun homeFilterSummary(
    workspaceName: String?,
    statusFilter: String?,
    sort: SessionSort,
): String {
    val workspace = workspaceName?.trim()?.takeIf { it.isNotEmpty() } ?: "All workspaces"
    val status = when (statusFilter?.trim()?.lowercase()) {
        "working" -> "Working"
        "idle" -> "Idle"
        "unread" -> "Unread"
        else -> "All statuses"
    }
    val sortLabel = when (sort) {
        SessionSort.ACTIVITY -> "Activity"
        SessionSort.TITLE -> "Title"
        SessionSort.STATUS -> "Status"
    }
    return "$workspace · $status · $sortLabel"
}
