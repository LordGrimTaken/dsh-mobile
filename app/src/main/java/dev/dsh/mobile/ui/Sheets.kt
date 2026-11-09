package dev.dsh.mobile.ui

import android.app.Activity
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.BuildConfig
import dev.dsh.mobile.CrashReporter
import dev.dsh.mobile.DshNotifications
import dev.dsh.mobile.SafeModeActivity
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.ConnectionStatus
import dev.dsh.mobile.data.LocalBackupData
import dev.dsh.mobile.data.LocalBackupDecodeResult
import dev.dsh.mobile.data.LOCAL_BACKUP_MAX_FILE_BYTES
import dev.dsh.mobile.data.PairedDevice
import dev.dsh.mobile.data.ThemeAccent
import dev.dsh.mobile.data.activeRoute
import org.json.JSONObject
import dev.dsh.mobile.net.PerfBus
import dev.dsh.mobile.data.accentChipColumns
import dev.dsh.mobile.data.ensureRoutes
import dev.dsh.mobile.data.accentSwatchArgb
import dev.dsh.mobile.data.themeAccentChipContentDescription
import dev.dsh.mobile.data.pairedDeviceIsCurrent
import dev.dsh.mobile.data.pairedDeviceMetadataLabel
import dev.dsh.mobile.data.pairedDeviceName
import dev.dsh.mobile.data.pairedDevicesSummary
import dev.dsh.mobile.data.RECOVERY_SETUP_COPY
import dev.dsh.mobile.data.recoveryAccessSummary
import dev.dsh.mobile.data.recoveryLink
import dev.dsh.mobile.data.relativeDeviceTime
import dev.dsh.mobile.data.PresetRoster
import dev.dsh.mobile.data.Workspace
import dev.dsh.mobile.data.decodeLocalBackup
import dev.dsh.mobile.data.encodeLocalBackup
import dev.dsh.mobile.data.localBackupCounts
import dev.dsh.mobile.data.localBackupFileName
import dev.dsh.mobile.data.generatePushTopic
import dev.dsh.mobile.data.pushConfigValid
import dev.dsh.mobile.data.pushServerError
import dev.dsh.mobile.data.pushServerValid
import dev.dsh.mobile.data.pushTopicValid
import dev.dsh.mobile.data.PUSH_TOPIC_MAX_CHARS
import dev.dsh.mobile.ui.theme.Danger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

// region new session sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    state: AppState,
    onDismiss: () -> Unit,
) {
    val workspaces = state.workspaces
    var workspaceId by remember { mutableStateOf(workspaces.firstOrNull()?.id.orEmpty()) }
    LaunchedEffect(Unit) { state.loadPresetsIfNeeded() }

    val roster = state.presetRoster
    val presets = roster?.presets?.takeIf { it.isNotEmpty() } ?: PresetRoster.Fallback.presets
    var selectedPresetId by remember(roster) {
        mutableStateOf(
            (roster?.takeIf { it.presets.isNotEmpty() } ?: PresetRoster.Fallback)
                .initialSelection()?.id.orEmpty(),
        )
    }
    val selectedPreset = presets.firstOrNull { it.id == selectedPresetId }
    val sendablePreset = presets.firstOrNull { it.id == selectedPresetId && !it.broken }
        ?: presets.firstOrNull { !it.broken }
    val presetDescription = when {
        selectedPreset?.broken == true -> "This preset is unavailable on the host; pick another."
        !selectedPreset?.description.isNullOrBlank() -> selectedPreset?.description.orEmpty()
        roster?.isFallback == true && !state.presetsLoading ->
            "This host doesn't list presets; the default set still works."

        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "❯",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "NEW SESSION",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                if (state.isLive) {
                    "Pick a desktop workspace and a preset."
                } else {
                    "Pick a workspace and a preset. The agent runs on your desktop."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(2.dp))
            HadalCaps("workspace")
            if (workspaces.isEmpty()) {
                Text(
                    "waiting for workspaces from the host…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            workspaces.forEach { workspace ->
                WorkspaceOption(
                    workspace = workspace,
                    selected = workspaceId == workspace.id,
                    onClick = { workspaceId = workspace.id },
                )
            }
            Spacer(Modifier.height(2.dp))
            HadalCaps("preset")
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { option ->
                    PresetChip(
                        label = option.label,
                        active = selectedPresetId == option.id,
                        enabled = !option.broken,
                        onClick = { selectedPresetId = option.id },
                    )
                }
            }
            if (state.presetsLoading && roster == null) {
                Text(
                    "loading presets…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            presetDescription?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            HadalPrimaryButton(
                text = "Start session",
                onClick = {
                    state.newSession(workspaceId, sendablePreset?.id.orEmpty())
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = workspaceId.isNotBlank(),
            )
        }
    }
}

@Composable
private fun WorkspaceOption(
    workspace: Workspace,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                workspace.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                workspace.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                "●",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val chipAlpha = if (enabled) 1f else 0.45f
    Box(
        Modifier
            .clip(shape)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.background
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
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            "[${label.uppercase()}]",
            style = MaterialTheme.typography.labelSmall,
            color = (if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            }).copy(alpha = chipAlpha),
        )
    }
}

// endregion

// region settings

private enum class SettingsSubPage {
    APPEARANCE,
    CONNECTION,
    DATA_SOURCE,
    SESSIONS,
    RECOVERY,
    NOTIFICATIONS,
    VOICE,
    ADVANCED,
}

@Composable
fun SettingsScreen(
    state: AppState,
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenHosts: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenHiddenSessions: () -> Unit,
    onOpenConnectionDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voiceLanguageDialog by remember { mutableStateOf(false) }
    var clearCacheDialog by remember { mutableStateOf(false) }
    var cacheRevision by remember { mutableStateOf(0) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var pendingBackupImport by remember { mutableStateOf<LocalBackupData?>(null) }
    var backupRevision by remember { mutableIntStateOf(0) }
    var pushEnabled by remember { mutableStateOf(false) }
    var pushServer by remember { mutableStateOf("") }
    var pushTopic by remember { mutableStateOf("") }
    var pushToken by remember { mutableStateOf("") }
    var pushClearToken by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }
    var advancedExpanded by remember { mutableStateOf(false) }
    var subPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    var showTailscaleAdd by remember { mutableStateOf(false) }

    // Load the host push config once per live switch, then mirror it into the editable fields.
    LaunchedEffect(state.isLive) { state.refreshPushConfig() }
    LaunchedEffect(state.isLive) { state.loadRecoveryAccess() }
    LaunchedEffect(state.pushConfig) {
        val config = state.pushConfig
        if (config != null && config.supported) {
            pushEnabled = config.enabled
            pushServer = config.server
            pushTopic = config.topic
            // The token is never displayed or echoed; blank keeps the stored host token.
            pushToken = ""
            pushClearToken = false
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            backupStatus = "Backup export cancelled."
        } else {
            val payload = state.buildLocalBackup(BuildConfig.VERSION_NAME)
            val json = encodeLocalBackup(payload)
            scope.launch {
                val written = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open the destination file")
                    }.isSuccess
                }
                backupStatus = if (written) {
                    "Backup exported · ${localBackupCounts(payload).summaryLine()}"
                } else {
                    "Couldn't export the backup."
                }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            backupStatus = "Backup import cancelled."
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { readBackupTextLimited(context, uri) }
                }
                result.fold(
                    onSuccess = { raw ->
                        when (val decoded = decodeLocalBackup(raw)) {
                            is LocalBackupDecodeResult.Success -> {
                                pendingBackupImport = decoded.data
                                backupStatus = null
                            }

                            is LocalBackupDecodeResult.UnsupportedVersion -> backupStatus =
                                "Unsupported backup version ${decoded.version ?: "?"}; " +
                                    "update DSH Mobile."

                            is LocalBackupDecodeResult.Invalid -> backupStatus = decoded.message
                        }
                    },
                    onFailure = { error ->
                        backupStatus = error.message ?: "Couldn't read that file."
                    },
                )
            }
        }
    }
    when (subPage) {
        SettingsSubPage.APPEARANCE -> {
            AppearanceSettingsPage(
                state = state,
                darkTheme = darkTheme,
                onToggleTheme = onToggleTheme,
                onBack = { subPage = null },
            )
            return
        }

        SettingsSubPage.CONNECTION -> {
            ConnectionSettingsPage(
                state = state,
                onBack = { subPage = null },
                onOpenDiagnostics = onOpenConnectionDiagnostics,
                onOpenHosts = onOpenHosts,
            )
            return
        }

        null, SettingsSubPage.DATA_SOURCE, SettingsSubPage.SESSIONS, SettingsSubPage.RECOVERY,
        SettingsSubPage.NOTIFICATIONS, SettingsSubPage.VOICE, SettingsSubPage.ADVANCED -> Unit
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        SettingsTopBar(state = state, onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            subPage?.let { page ->
                SettingsSecondaryPageHeader(
                    title = settingsPageTitle(page),
                    onBack = { subPage = null },
                )
            }
            if (subPage == null) {
            SettingsHeroCard(
                state = state,
                onOpenHosts = onOpenHosts,
                onOpenPair = onOpenPairing,
                onOpenConnection = { subPage = SettingsSubPage.CONNECTION },
            )
            SettingsSearchField(
                query = settingsQuery,
                onQueryChange = {
                    settingsQuery = it
                    if (it.isNotBlank()) advancedExpanded = true
                },
            )
            }
            SettingsGroup("host & connection", visible = settingsGroupVisible(settingsQuery, "host & connection") && subPage == null) {
                val profile = state.activeProfile
                val routeCount = profile?.ensureRoutes()?.routes?.size ?: 0
                SettingsIconRow(
                    glyph = "▤",
                    title = "Hosts (${state.hostCount})",
                    subtitle = profile?.label?.takeIf { it.isNotBlank() } ?: "No host paired yet",
                    onClick = onOpenHosts,
                )
                Hairline()
                SettingsIconRow(
                    glyph = "⇄",
                    title = "Routes & failover",
                    subtitle = "$routeCount routes · auto-failover on",
                    onClick = { subPage = SettingsSubPage.CONNECTION },
                    accessory = { if (routeCount > 1) SettingsPill("fastest") },
                )
                Hairline()
                SettingsIconRow(
                    glyph = "◈",
                    title = "Pair a device",
                    subtitle = "QR code or six-digit pairing code",
                    onClick = onOpenPairing,
                )
                Hairline()
                SettingsIconRow(
                    glyph = "◌",
                    title = "Sign in with Tailscale",
                    subtitle = "Add a tailnet host without a QR code",
                    onClick = {
                        state.clearTailscaleSignInError()
                        showTailscaleAdd = true
                    },
                )
            }
            SettingsGroup("usage", visible = settingsGroupVisible(settingsQuery, "usage")) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenUsage)
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "DeepSeek usage",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Token totals across sessions and remaining API balance",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            SettingsGroup("data source", visible = settingsGroupVisible(settingsQuery, "data source") && subPage == SettingsSubPage.DATA_SOURCE) {
                if (state.hostCount == 0) {
                    Text(
                        "Demo data is always on while no host is paired. Pair a desktop or cloud " +
                            "host to switch to live sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    ToggleRow(
                        title = "Use demo data",
                        subtitle = if (state.useDemoData) {
                            "Showing mock sessions instead of ${state.hostLabel ?: "your host"}"
                        } else {
                            "Showing live sessions from ${state.hostLabel ?: "your host"}"
                        },
                        checked = state.useDemoData,
                        onCheckedChange = state::toggleUseDemoData,
                    )
                }
            }
            SettingsGroup("appearance", visible = settingsGroupVisible(settingsQuery, "appearance") && subPage == null) {
                SettingsIconRow(
                    glyph = "◐",
                    title = "Theme",
                    subtitle = "${state.themeAccent.label} · ${if (darkTheme) "dark" else "light"}" +
                        if (darkTheme && state.amoledDark) " · AMOLED" else "",
                    onClick = { subPage = SettingsSubPage.APPEARANCE },
                    accessory = { SettingsThemeSwatches(state) },
                )
                Hairline()
                SettingsIconRow(
                    glyph = "Aa",
                    title = "Text & density",
                    subtitle = "Chat ${fontScaleLabel(state.chatFontScale)} · interface " +
                        "${fontScaleLabel(state.appFontScale)}",
                    onClick = { subPage = SettingsSubPage.APPEARANCE },
                )
            }
            if (subPage == null) {
                SettingsGroup("sections", visible = settingsGroupVisible(settingsQuery, "sections")) {
                    SettingsIconRow("◔", "Usage", "Tokens, balance and API cost", onClick = onOpenUsage)
                    Hairline()
                    SettingsIconRow(
                        "⇆",
                        "Data source",
                        if (state.useDemoData) "Showing demo data" else "Showing live host sessions",
                        onClick = { subPage = SettingsSubPage.DATA_SOURCE },
                    )
                    Hairline()
                    SettingsIconRow(
                        "▤",
                        "Sessions",
                        "Hidden/archived sessions and lists",
                        onClick = { subPage = SettingsSubPage.SESSIONS },
                    )
                    Hairline()
                    SettingsIconRow(
                        "⛨",
                        "Recovery & security",
                        "Recovery code, paired devices, app lock",
                        onClick = { subPage = SettingsSubPage.RECOVERY },
                    )
                    Hairline()
                    SettingsIconRow(
                        "◎",
                        "Notifications",
                        "Push, quiet hours, background sync",
                        onClick = { subPage = SettingsSubPage.NOTIFICATIONS },
                    )
                    Hairline()
                    SettingsIconRow(
                        "◌",
                        "Voice",
                        "Dictation language and prompts",
                        onClick = { subPage = SettingsSubPage.VOICE },
                    )
                    Hairline()
                    SettingsIconRow(
                        "⚙",
                        "Advanced",
                        "Diagnostics, storage, build info",
                        onClick = { subPage = SettingsSubPage.ADVANCED },
                    )
                }
            }
            SettingsGroup("sessions", visible = settingsGroupVisible(settingsQuery, "sessions") && subPage == SettingsSubPage.SESSIONS) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenHiddenSessions)
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        "Hidden sessions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Restore sessions removed from the list",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            SettingsGroup("recovery & security", visible = settingsGroupVisible(settingsQuery, "recovery & security") && subPage == SettingsSubPage.RECOVERY) {
                ToggleRow(
                    title = "App lock",
                    subtitle = "Require biometrics when opening DSH Mobile",
                    checked = state.appLockEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            state.updateAppLock(false)
                            (context as? Activity)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                            if (keyguard?.isDeviceSecure == true) {
                                state.updateAppLock(true)
                                (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Set a device PIN, pattern, or password before enabling app lock",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                )
                if (state.appLockEnabled) {
                    Hairline()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Lock after",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        listOf(0 to "now", 15 to "15s", 30 to "30s", 60 to "1m").forEach { (seconds, label) ->
                            val selected = state.appLockDelaySeconds == seconds
                            Text(
                                label,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    )
                                    .clickable { state.updateAppLockDelay(seconds) }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { state.openPairedDevices() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Paired devices",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            state.pairedDevices?.let { pairedDevicesSummary(it) }
                                ?: "Review and revoke other devices paired with this host",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "manage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Hairline()
                Text(
                    "Recovery access",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    recoveryAccessSummary(state.recoveryAccess),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (state.recoveryAccess?.supported == false) {
                    Text(
                        "This host doesn't support recovery codes yet. Update the desktop " +
                            "host plugin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Text(
                        "Re-add this host after clearing app data or reinstalling — no " +
                            "desktop pairing code needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    state.recoveryAccess?.createdAt?.let { createdAt ->
                        Text(
                            "Configured ${relativeDeviceTime(createdAt) ?: createdAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        if (state.recoveryCodeGenerating) {
                            "Generating..."
                        } else if (state.recoveryAccess?.configured == true) {
                            "Regenerate recovery code"
                        } else {
                            "Generate recovery code"
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = !state.recoveryCodeGenerating) {
                                state.generateRecoveryAccessCode()
                            }
                            .padding(vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.recoveryCode?.let { generated ->
                        val baseUrl = state.activeProfile?.baseUrl.orEmpty()
                        val link = recoveryLink(baseUrl, generated.code)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Recovery link (shown once)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                generated.code,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                link,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(
                                    "Copy link",
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val clipboard = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE,
                                            ) as? ClipboardManager
                                            clipboard?.setPrimaryClip(
                                                ClipData.newPlainText("DSH recovery link", link),
                                            )
                                            Toast.makeText(
                                                context,
                                                "Recovery link copied",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        .padding(vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Share recovery link",
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, link)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(intent, "Share recovery link")
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                        .padding(vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                RECOVERY_SETUP_COPY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                "Done",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { state.clearRecoveryCode() }
                                    .padding(vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    state.recoveryError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            SettingsGroup("notifications", visible = settingsGroupVisible(settingsQuery, "notifications") && subPage == SettingsSubPage.NOTIFICATIONS) {
                ToggleRow(
                    title = "Agent needs input",
                    subtitle = "Notify when an approval or question is waiting",
                    checked = state.notifyInput,
                    onCheckedChange = state::updateNotifyInput,
                )
                Hairline()
                ToggleRow(
                    title = "Turn finished",
                    subtitle = "Notify when the agent stops working",
                    checked = state.notifyDone,
                    onCheckedChange = state::updateNotifyDone,
                )
                Hairline()
                ToggleRow(
                    title = "Goal updates",
                    subtitle = "Notify when a goal pauses, blocks, or completes",
                    checked = state.notifyGoal,
                    onCheckedChange = state::updateNotifyGoal,
                )
                Hairline()
                ToggleRow(
                    title = "Background sync",
                    subtitle = "Keep watching for turns and approvals when DSH Mobile is closed",
                    checked = state.backgroundSyncEnabled,
                    onCheckedChange = state::updateBackgroundSync,
                )
                if (state.backgroundSyncEnabled) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Check every",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        listOf(20 to "20s", 60 to "1m", 300 to "5m").forEach { (seconds, label) ->
                            val selected = state.backgroundSyncIntervalSeconds == seconds
                            Text(
                                label,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    )
                                    .clickable { state.updateBackgroundSyncInterval(seconds) }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Hairline()
                ToggleRow(
                    title = "Quiet hours",
                    subtitle = "Mute turn and input notifications overnight",
                    checked = state.quietHoursEnabled,
                    onCheckedChange = state::updateQuietHours,
                )
                if (state.quietHoursEnabled) {
                    QuietTimeRow(
                        label = "Quiet from",
                        hour = state.quietStartHour,
                        onHour = state::updateQuietStart,
                    )
                    QuietTimeRow(
                        label = "Quiet until",
                        hour = state.quietEndHour,
                        onHour = state::updateQuietEnd,
                    )
                }
                Text(
                    "When a host is connected, its ntfy pushes also honor quiet hours and " +
                        "muted sessions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Hairline()
                ToggleRow(
                    title = "Low balance alert",
                    subtitle = "Notify once a day when DeepSeek credit is low",
                    checked = state.lowBalanceAlertsEnabled,
                    onCheckedChange = state::updateLowBalanceAlerts,
                )
                if (state.lowBalanceAlertsEnabled) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Alert below",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        listOf(5.0 to "$5", 10.0 to "$10", 20.0 to "$20").forEach { (amount, label) ->
                            val selected = state.lowBalanceThreshold == amount
                            Text(
                                label,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    )
                                    .clickable { state.updateLowBalanceThreshold(amount) }
                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Hairline()
                Text(
                    "Push notifications (ntfy)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val pushConfig = state.pushConfig
                when {
                    !state.isLive -> Text(
                        "Pair a host to configure ntfy push notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    pushConfig == null && state.pushConfigLoading -> Text(
                        "Loading push settings…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    pushConfig?.supported == false -> Text(
                        "This host doesn't support push notifications yet. Update the desktop " +
                            "host plugin to enable ntfy push.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    else -> {
                        Text(
                            "Install the ntfy app and subscribe to this topic. The topic is " +
                                "effectively a secret — anyone who knows it can read pushes. The " +
                                "optional access token is stored in plaintext on the host and " +
                                "never shown in this app. ntfy pushes may arrive while DSH Mobile " +
                                "is in the foreground, and they do not respect this app's quiet " +
                                "hours or per-session mute.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        ToggleRow(
                            title = "Enable ntfy push",
                            subtitle = "Send notifications through ntfy while the host is idle",
                            checked = pushEnabled,
                            onCheckedChange = { pushEnabled = it },
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = pushServer,
                            onValueChange = { pushServer = it.take(200) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("ntfy server") },
                            placeholder = { Text("https://ntfy.sh (blank = host default)") },
                            isError = pushServer.isNotBlank() && !pushServerValid(pushServer),
                        )
                        pushServerError(pushServer)?.let { serverError ->
                            Text(
                                serverError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = pushTopic,
                            onValueChange = { pushTopic = it.take(PUSH_TOPIC_MAX_CHARS) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Topic") },
                            placeholder = { Text("my-secret-topic") },
                            isError = pushEnabled && !pushTopicValid(pushTopic),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (pushEnabled && !pushTopicValid(pushTopic)) {
                                Text(
                                    "Topic is required when enabled: 1–64 letters, digits, _ or -.",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                Text(
                                    "The topic is the secret others must not learn.",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            Text(
                                "Generate topic",
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { pushTopic = generatePushTopic() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = pushToken,
                            onValueChange = {
                                pushToken = it.take(200)
                                if (it.isNotBlank()) pushClearToken = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Access token (optional)") },
                            placeholder = {
                                Text(
                                    if (pushConfig?.hasToken == true) {
                                        "saved on host — blank keeps it"
                                    } else {
                                        "not set"
                                    },
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        if (pushConfig?.hasToken == true) {
                            Text(
                                if (pushClearToken) {
                                    "Saved token will be removed when you Save. Tap to undo."
                                } else {
                                    "Clear saved token"
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { pushClearToken = !pushClearToken }
                                    .padding(vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pushClearToken) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                        val pushSaveEnabled = pushConfigValid(pushEnabled, pushServer, pushTopic) &&
                            !state.pushConfigSaving
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HadalPrimaryButton(
                                text = if (state.pushConfigSaving) "Saving…" else "Save",
                                onClick = {
                                    state.savePushConfig(
                                        enabled = pushEnabled,
                                        server = pushServer,
                                        topic = pushTopic,
                                        token = pushToken,
                                        clearToken = pushClearToken &&
                                            state.pushConfig?.hasToken == true,
                                    )
                                },
                                enabled = pushSaveEnabled,
                                modifier = Modifier.weight(1f),
                            )
                            HadalGhostButton(
                                text = if (state.pushTestLoading) "Sending…" else "Send test",
                                onClick = {
                                    if (!state.pushTestLoading) state.sendPushTestNotification()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        state.pushStatus?.let { status ->
                            Text(
                                status,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { state.clearPushStatus() }
                                    .padding(vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (status.startsWith("Push notifications saved") ||
                                    status.startsWith("Test notification sent")
                                ) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
            SettingsGroup("voice", visible = settingsGroupVisible(settingsQuery, "voice") && subPage == SettingsSubPage.VOICE) {
                ToggleRow(
                    title = "Prefer offline recognition",
                    subtitle = "Use an installed on-device speech model; avoids network/VPN audio stalls",
                    checked = state.voicePreferOffline,
                    onCheckedChange = state::updateVoicePreferOffline,
                )
                Text(
                    "If your device has no offline speech pack for the system language, dictation " +
                        "may fail; turn this off to use the online recognizer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { voiceLanguageDialog = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Recognition language",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            voiceLanguageLabel(state.voiceLanguageTag),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "change",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!BuildConfig.PLAY_BUILD) {
                UpdateSettingsRow(manager = state.updates)
            }
            if (subPage == null) {
                SettingsAdvancedRow(
                    expanded = advancedExpanded || settingsQuery.isNotBlank(),
                    onToggle = { advancedExpanded = !advancedExpanded },
                    hint = "Diagnostics, storage & build info",
                )
            }
            if (subPage == SettingsSubPage.ADVANCED || advancedExpanded || settingsQuery.isNotBlank()) {
            SettingsGroup("diagnostics", visible = settingsGroupVisible(settingsQuery, "diagnostics")) {
                Text(
                    "Connection diagnostics",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onOpenConnectionDiagnostics)
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (DshNotifications.hasPermission(context)) {
                        "Notifications are allowed for this app."
                    } else {
                        "Notifications are blocked in Android settings; enable them to receive turn and approval alerts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    "Send test notification",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val sent = DshNotifications.test(context)
                            Toast.makeText(
                                context,
                                if (sent) "Test notification sent" else "Notifications are blocked",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Clear notifications",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            DshNotifications.clearAll(context)
                            Toast.makeText(context, "Notifications cleared", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Hairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(context, SafeModeActivity::class.java))
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Safe mode",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Open recovery tools if the app keeps crashing. Host pairing, " +
                                "drafts and queued prompts are preserved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        "open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                CrashDiagnostics()
            }
            SettingsGroup("storage", visible = settingsGroupVisible(settingsQuery, "storage")) {
                val cacheStats = remember(cacheRevision) { state.offlineCacheStats() }
                Text(
                    "Offline cache",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (cacheStats.transcriptCount == 0) {
                        "No cached transcripts; the next session-list load needs the host."
                    } else {
                        "${cacheStats.transcriptCount} cached transcripts · " +
                            formatCacheBytes(cacheStats.bytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    "Clear offline caches",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { clearCacheDialog = true }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Local backup",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val backupCounts = remember(backupRevision) { state.localBackupSnapshotCounts() }
                Text(
                    "${backupCounts.summaryLine()}. Aliases, pins, drafts, queue, snippets and " +
                        "appearance settings only — pairing tokens, app lock and caches are never " +
                        "exported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    "Export backup",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            exportBackupLauncher.launch(localBackupFileName(LocalDate.now()))
                        }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Import backup",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            importBackupLauncher.launch(arrayOf("application/json"))
                        }
                        .padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                backupStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.startsWith("Backup export") ||
                            status.startsWith("Backup imported")
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
            }
            SettingsGroup("this build", visible = settingsGroupVisible(settingsQuery, "this build")) {
                Text(
                    "Multi-host preview: pair several desktop or cloud hosts, switch between " +
                        "them, and stream sessions over the DSH Mobile API v1 (HTTP + SSE). " +
                        "Host tokens are encrypted with the Android Keystore. Notifications work " +
                        "while DSH Mobile is running, and background sync keeps watching while " +
                        "it is closed. This build adds QR pairing and multi-route hosts; " +
                        "Settings now has search, a live connection hero and a collapsed " +
                        "Advanced section.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            }
            Text(
                buildFooter(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
    }
    if (showTailscaleAdd) {
        TailscaleSignInDialog(
            state = state,
            initialHostUrl = "",
            onHostUrlChange = {},
            onSignIn = state::addHostWithTailscale,
            onSignedIn = { showTailscaleAdd = false },
            onDismiss = {
                showTailscaleAdd = false
                state.clearTailscaleSignInError()
            },
        )
    }
    if (state.pairedDevicesDialogOpen) {
        PairedDevicesDialog(state = state)
    }
    if (voiceLanguageDialog) {
        VoiceLanguageDialog(
            selected = state.voiceLanguageTag,
            onSelect = {
                state.updateVoiceLanguage(it)
                voiceLanguageDialog = false
            },
            onDismiss = { voiceLanguageDialog = false },
        )
    }
    pendingBackupImport?.let { backup ->
        val counts = localBackupCounts(backup)
        AlertDialog(
            onDismissRequest = { pendingBackupImport = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Replace local data?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The backup contains: ${counts.summaryLine()}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "This replaces aliases, pins, mutes, hidden lists, drafts, queued prompts, " +
                            "snippets and safe appearance/notification settings on this phone. " +
                            "Host pairing, tokens, app lock and caches are not affected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            },
            confirmButton = {
                Text(
                    "Import",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val applied = state.applyLocalBackup(backup)
                            pendingBackupImport = null
                            backupRevision += 1
                            backupStatus = "Backup imported · ${applied.summaryLine()}"
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { pendingBackupImport = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }

    if (clearCacheDialog) {
        AlertDialog(
            onDismissRequest = { clearCacheDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Clear offline cache?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "This removes cached transcripts and the session-list snapshot from this " +
                        "phone. Host sessions and messages are not deleted; the next open loads " +
                        "them over the network again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Clear",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.clearOfflineCaches()
                            cacheRevision += 1
                            clearCacheDialog = false
                            Toast.makeText(context, "Offline cache cleared", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { clearCacheDialog = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val VOICE_LANGUAGES = listOf(
    "" to "System default",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "es-ES" to "Español",
    "fr-FR" to "Français",
    "de-DE" to "Deutsch",
    "it-IT" to "Italiano",
    "pt-BR" to "Português (Brasil)",
    "ja-JP" to "日本語",
    "ko-KR" to "한국어",
    "zh-CN" to "中文 (简体)",
    "hi-IN" to "हिन्दी",
)

private fun voiceLanguageLabel(tag: String): String =
    VOICE_LANGUAGES.firstOrNull { it.first == tag }?.second ?: tag

@Composable
private fun VoiceLanguageDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Recognition language",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VOICE_LANGUAGES.forEach { (tag, label) ->
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(tag) }
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (tag == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
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
private fun CrashDiagnostics() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val report = remember(refresh) { CrashReporter.read(context) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (report == null) {
            Text(
                "No crash recorded. If the app closes unexpectedly, reopen it and this screen " +
                    "will offer the stack trace with device, Android and build details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Text(
                "A crash was recorded. Share or copy this report so the session-open crash can " +
                    "be diagnosed on the device where it reproduces.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Share log",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { shareCrashReport(context, report) }
                        .padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Copy",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { copyCrashReport(context, report) }
                        .padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Clear",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            CrashReporter.clear(context)
                            refresh += 1
                        }
                        .padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun shareCrashReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "DSH Mobile crash report")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share crash log")) }
}

private fun copyCrashReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH Mobile crash", report))
    Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun SettingsTopBar(
    state: AppState,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "❯",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "SETTINGS",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ConnectionPill(state.connection)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private data class FontScaleChoice(val scale: Float, val label: String)

private val FONT_SCALE_CHOICES = listOf(
    FontScaleChoice(0.9f, "Small"),
    FontScaleChoice(1.0f, "Default"),
    FontScaleChoice(1.15f, "Large"),
    FontScaleChoice(1.3f, "X-Large"),
)

/** Compact Small/Default/Large/X-Large selector used for chat and (app-wide) interface text. */
@Composable
private fun AccentColorChips(
    selected: ThemeAccent,
    darkTheme: Boolean,
    onSelected: (ThemeAccent) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A7: 2 chips per row on narrow sheets so labels like "Amethyst" never clip.
        val columns = accentChipColumns(maxWidth.value)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Accent color",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            ThemeAccent.entries.chunked(columns).forEach { rowAccents ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowAccents.forEach { accent ->
                        val isSelected = selected == accent
                        Row(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable(role = Role.Button) { onSelected(accent) }
                                .semantics {
                                    contentDescription = themeAccentChipContentDescription(accent, isSelected)
                                    this.selected = isSelected
                                    role = Role.Button
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        // A1: swatch matches the applied dark/light scheme.
                                        Color(accentSwatchArgb(accent, dark = darkTheme)),
                                    ),
                            )
                            Text(
                                accent.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Non-color-only selected indicator.
                            if (isSelected) {
                                Text(
                                    "✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    repeat(columns - rowAccents.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FontScaleChips(
    label: String,
    selectedScale: Float,
    onScaleSelected: (Float) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        FONT_SCALE_CHOICES.forEach { choice ->
            val selected = selectedScale == choice.scale
            Text(
                choice.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .clickable { onScaleSelected(choice.scale) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * Settings overview hero: live connection state, the active route and quick entry points.
 * Mirrors the design-proposal mockup so users see host health before the long settings list.
 */
@Composable
private fun SettingsHeroCard(
    state: AppState,
    onOpenHosts: () -> Unit,
    onOpenPair: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val profile = state.activeProfile
    val normalized = profile?.ensureRoutes()
    val route = normalized?.activeRoute
    val probe = route?.let { state.routeProbes[it.id] }
    HadalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionPill(state.connection)
            Spacer(Modifier.width(8.dp))
            Text(
                profile?.label?.takeIf { it.isNotBlank() } ?: "No host paired",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.activeHostVersion?.let { version ->
                Text(
                    "host v$version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (profile == null) {
                "Pair a desktop or cloud host to stream live sessions."
            } else {
                "active ${route?.label ?: "route"} · ${route?.baseUrl ?: profile.baseUrl}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (profile != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsMetric(
                    label = "latency",
                    value = probe?.latencyMs?.let { "$it ms" }
                        ?: if (state.routeProbing) "…" else "—",
                    modifier = Modifier.weight(1f),
                )
                SettingsMetric(
                    label = "routes",
                    value = (normalized?.routes?.size ?: 1).toString(),
                    modifier = Modifier.weight(1f),
                )
                SettingsMetric(
                    label = "synced",
                    value = settingsAgo(state.lastRefreshedAt),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HadalPrimaryButton(
                text = "Connection",
                onClick = onOpenConnection,
                modifier = Modifier.weight(1f),
            )
            HadalGhostButton(
                text = "Pair device",
                onClick = onOpenPair,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingsMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Filter-as-you-type field for the settings overview. */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        label = { Text("Search settings") },
        placeholder = { Text("host, theme, notifications, storage…") },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
        trailingIcon = if (query.isNotBlank()) {
            {
                Text(
                    "×",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onQueryChange("") }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else {
            null
        },
    )
}

/** Release-hatch row for diagnostics, storage and build info. */
@Composable
private fun SettingsAdvancedRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    hint: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⚙",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Advanced",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            if (expanded) "−" else "+",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun settingsAgo(at: Long?): String {
    if (at == null) return "—"
    val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}h"
    }
}

private val SETTINGS_GROUP_KEYWORDS = mapOf(
    "host & connection" to "host connection paired routes failover qr pairing tailscale url",
    "usage" to "usage tokens balance api cost",
    "data source" to "demo data live source",
    "appearance" to "theme dark light amoled accent colour color text size font density",
    "sessions" to "sessions hidden archived",
    "recovery & security" to "security recovery code devices app lock paired",
    "notifications" to "notifications push ntfy quiet hours alerts sync background",
    "voice" to "voice dictation speech language",
    "diagnostics" to "diagnostics logs crash report safe mode",
    "storage" to "storage cache backup export import",
    "this build" to "build version about changelog",
)

/** True when the search box is empty or every query word matches the group's keywords. */
private fun settingsGroupVisible(query: String, title: String): Boolean {
    val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val haystack = (title + " " + SETTINGS_GROUP_KEYWORDS[title].orEmpty()).lowercase()
    return words.all { haystack.contains(it) }
}

/** Mint icon-tile row used across the settings overview (mockup-style). */
@Composable
private fun SettingsIconRow(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    accessory: @Composable RowScope.() -> Unit = {},
) {
    val base = Modifier.fillMaxWidth()
    val modifier = if (onClick != null) {
        base
            .clip(RoundedCornerShape(10.dp))
            .semantics { contentDescription = "$title. $subtitle" }
            .clickable(onClick = onClick)
    } else {
        base.semantics { contentDescription = "$title. $subtitle" }
    }
    Row(modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        accessory()
        if (onClick != null) {
            Spacer(Modifier.width(2.dp))
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Tiny three-bar signal indicator for route rows (mockup Connection page). */
@Composable
private fun RouteSignalBars(reachable: Boolean, latencyMs: Long?) {
    val heights = listOf(5.dp, 9.dp, 13.dp)
    val color = when {
        !reachable -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
        latencyMs == null -> MaterialTheme.colorScheme.primary
        latencyMs <= 1500 -> MaterialTheme.colorScheme.primary
        latencyMs <= 3000 -> MaterialTheme.colorScheme.tertiary
        else -> Danger
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEach { height ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun SettingsPill(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsThemeSwatches(state: AppState) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiary,
        ).forEach { color ->
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
    }
}

/** Two-column accent gallery with gradient previews (Appearance page). */
@Composable
private fun AccentGalleryCards(
    selected: ThemeAccent,
    darkTheme: Boolean,
    onSelected: (ThemeAccent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeAccent.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { accent ->
                    val active = accent == selected
                    val swatch = Color(accentSwatchArgb(accent, darkTheme).toInt())
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .border(
                                if (active) 1.5.dp else 1.dp,
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(14.dp),
                            )
                            .clickable { onSelected(accent) }
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(swatch),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                accent.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (active) {
                                Text(
                                    "✓",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Settings overview row that opens a dedicated sub-page. */
@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

private fun fontScaleLabel(scale: Float): String =
    FONT_SCALE_CHOICES.firstOrNull { it.scale == scale }?.label ?: "Default"

/** Shared chrome for the Settings sub-pages (Appearance, Connection). */
@Composable
private fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "❯",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

/** Dedicated appearance editor: theme gallery, preview, text scale, motion-adjacent toggles. */
@Composable
private fun AppearanceSettingsPage(
    state: AppState,
    darkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPageScaffold(title = "APPEARANCE", onBack = onBack) {
        SettingsGroup("theme") {
            Text(
                "Preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "Tests are green — tagging the release now.",
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Hairline()
            AccentGalleryCards(
                selected = state.themeAccent,
                darkTheme = darkTheme,
                onSelected = state::updateThemeAccent,
            )
            Hairline()
            ToggleRow(
                title = "Dark theme",
                subtitle = "Match the desktop app's dark UI",
                checked = darkTheme,
                onCheckedChange = onToggleTheme,
            )
            if (darkTheme) {
                Hairline()
                ToggleRow(
                    title = "AMOLED black",
                    subtitle = "Pure black background for OLED screens",
                    checked = state.amoledDark,
                    onCheckedChange = state::updateAmoledDark,
                )
            }
        }
        SettingsGroup("text & density") {
            FontScaleChips(
                label = "Chat text",
                selectedScale = state.chatFontScale,
                onScaleSelected = state::updateChatFontScale,
            )
            Hairline()
            FontScaleChips(
                label = "Interface text",
                selectedScale = state.appFontScale,
                onScaleSelected = state::updateAppFontScale,
            )
            Hairline()
            ToggleRow(
                title = "Wrap code blocks",
                subtitle = "Soft-wrap long code instead of scrolling sideways",
                checked = state.wrapCodeBlocks,
                onCheckedChange = state::updateWrapCodeBlocks,
            )
        }
        val clipboard = LocalClipboardManager.current
        var transferStatus by remember { mutableStateOf<String?>(null) }
        SettingsGroup("theme backup") {
            Text(
                "Copy this device's theme as JSON, or import a theme copied on another phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalGhostButton(
                    text = "Copy JSON",
                    onClick = {
                        clipboard.setText(AnnotatedString(themeSettingsJson(state, darkTheme)))
                        transferStatus = "Theme JSON copied to clipboard"
                    },
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = "Import",
                    onClick = {
                        transferStatus = importThemeSettings(clipboard.getText()?.text.orEmpty(), state, onToggleTheme)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            transferStatus?.let { status ->
                Spacer(Modifier.height(6.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** Dedicated connection page: live status, route health, failover actions and transport info. */
@Composable
private fun ConnectionSettingsPage(
    state: AppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHosts: () -> Unit,
) {
    val profile = state.activeProfile
    val normalized = profile?.ensureRoutes()
    val routes = normalized?.routes.orEmpty()
    val activeRoute = normalized?.activeRoute
    val probe = activeRoute?.let { state.routeProbes[it.id] }
    SettingsSubPageScaffold(title = "CONNECTION", onBack = onBack) {
        HadalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(state.connection)
                Spacer(Modifier.width(8.dp))
                Text(
                    profile?.label?.takeIf { it.isNotBlank() } ?: "No host paired",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.activeHostVersion?.let { version ->
                    Text(
                        "host v$version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                activeRoute?.let { "active ${it.label} · ${it.baseUrl}" }
                    ?: "Pair a host to manage routes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsMetric(
                        label = "first byte",
                        value = PerfBus.lastFirstByteMillis?.let { "$it ms" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    SettingsMetric(
                        label = "reconnects",
                        value = state.reconnectAttempts.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    SettingsMetric(
                        label = "synced",
                        value = settingsAgo(state.lastRefreshedAt),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HadalPrimaryButton(
                        text = if (state.speedTestingHostId == profile.id) "Testing…" else "Run speed test",
                        onClick = { state.runSpeedTest(profile.id) },
                        modifier = Modifier.weight(1f),
                    )
                    HadalGhostButton(
                        text = "Use fastest",
                        onClick = { state.useFastestRoute(profile.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SettingsGroup("routes") {
            if (profile == null || routes.isEmpty()) {
                Text(
                    "No routes yet. Pair a host or add an endpoint from Hosts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            routes.forEach { route ->
                val routeProbe = state.routeProbes[route.id]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { if (profile != null) state.selectRoute(profile.id, route.id) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (route.id == activeRoute?.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (route.id == activeRoute?.id) "${route.label} · active" else route.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            route.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        routeProbe?.error?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RouteSignalBars(
                            reachable = routeProbe?.reachable == true,
                            latencyMs = state.lastSpeedTest[route.id]?.p50Ms ?: routeProbe?.latencyMs,
                        )
                        Text(
                            state.lastSpeedTest[route.id]?.summary() ?: (routeProbe?.summary() ?: "not probed"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (routeProbe?.reachable == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                            maxLines = 1,
                        )
                    }
                }
                Hairline()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalGhostButton(
                    text = "Manage hosts",
                    onClick = onOpenHosts,
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = "Diagnostics",
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SettingsGroup("buffering") {
            SettingsIconRow(
                glyph = "⇣",
                title = "gzip payloads",
                subtitle = "host compresses JSON ≥ 1 KB",
                accessory = { SettingsPill("on") },
            )
            Hairline()
            SettingsIconRow(
                glyph = "≋",
                title = "stale cache",
                subtitle = "cached sessions render instantly",
                accessory = { SettingsPill("on") },
            )
            Hairline()
            SettingsIconRow(
                glyph = "↻",
                title = "warm connect",
                subtitle = "stream opens with the app",
                accessory = { SettingsPill("auto") },
            )
        }
        SettingsGroup("transport") {
            Text(
                "SSE over HTTP · host API v1. When the stream drops, the app probes every route " +
                    "and reconnects through the fastest reachable one (60 s storm guard).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Header used by the secondary settings pages (data source, sessions, …). */
@Composable
private fun SettingsSecondaryPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun settingsPageTitle(page: SettingsSubPage): String = when (page) {
    SettingsSubPage.DATA_SOURCE -> "Data source"
    SettingsSubPage.SESSIONS -> "Sessions"
    SettingsSubPage.RECOVERY -> "Recovery & security"
    SettingsSubPage.NOTIFICATIONS -> "Notifications"
    SettingsSubPage.VOICE -> "Voice"
    SettingsSubPage.ADVANCED -> "Advanced"
    SettingsSubPage.APPEARANCE -> "Appearance"
    SettingsSubPage.CONNECTION -> "Connection"
}

/** Theme settings as a compact transferable JSON document. */
private fun themeSettingsJson(state: AppState, darkTheme: Boolean): String = JSONObject()
    .put("version", 1)
    .put("accent", state.themeAccent.key)
    .put("dark", darkTheme)
    .put("amoled", state.amoledDark)
    .put("chatScale", state.chatFontScale.toDouble())
    .put("appScale", state.appFontScale.toDouble())
    .put("wrapCode", state.wrapCodeBlocks)
    .toString()

/** Applies a theme JSON blob; returns a short human status (success or a readable failure). */
private fun importThemeSettings(
    raw: String,
    state: AppState,
    onToggleTheme: (Boolean) -> Unit,
): String = runCatching {
    val obj = JSONObject(raw.trim())
    val accentKey = obj.optString("accent").takeIf { it.isNotBlank() }
    ThemeAccent.entries.firstOrNull { it.key == accentKey }?.let(state::updateThemeAccent)
    if (obj.has("dark")) onToggleTheme(obj.optBoolean("dark", true))
    if (obj.has("amoled")) state.updateAmoledDark(obj.optBoolean("amoled", state.amoledDark))
    if (obj.has("chatScale")) {
        state.updateChatFontScale(obj.optDouble("chatScale", state.chatFontScale.toDouble()).toFloat())
    }
    if (obj.has("appScale")) {
        state.updateAppFontScale(obj.optDouble("appScale", state.appFontScale.toDouble()).toFloat())
    }
    if (obj.has("wrapCode")) state.updateWrapCodeBlocks(obj.optBoolean("wrapCode", state.wrapCodeBlocks))
    "Theme imported"
}.getOrElse { error -> "Couldn't import: ${error.message?.take(70) ?: "invalid theme JSON"}" }

@Composable
private fun SettingsGroup(
    title: String,
    visible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HadalCaps(title.uppercase(), modifier = Modifier.padding(start = 2.dp))
        HadalCard { content() }
    }
}

@Composable
private fun HostCard(
    state: AppState,
    onOpenHosts: () -> Unit,
    onOpenPairing: () -> Unit,
    /** Compact mode: the settings hero already shows host/route status, so only actions remain. */
    compact: Boolean = false,
) {
    val profile = state.activeProfile
    var showTailscaleAdd by remember { mutableStateOf(false) }
    var tailscaleAddUrl by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(state.connection)
                Spacer(Modifier.width(8.dp))
                Text(
                    profile?.label?.takeIf { it.isNotBlank() } ?: "No host paired",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.activeHostVersion?.let { version ->
                    Text(
                        "host v$version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        if (profile == null) {
            if (!compact) {
                Text(
                    "Pair a DSH host (desktop or cloud) with a base URL and a one-time pairing code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalPrimaryButton(
                    text = "Pair host",
                    onClick = onOpenPairing,
                    modifier = Modifier.weight(1f),
                )
                if (state.hostCount > 0) {
                    HadalGhostButton(
                        text = "Hosts (${state.hostCount})",
                        onClick = onOpenHosts,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Sign in with Tailscale",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        state.clearTailscaleSignInError()
                        showTailscaleAdd = true
                    }
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            if (!compact) {
                Text(
                    profile.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ConnectionDetail(label = "device", value = profile.deviceId)
                ConnectionDetail(
                    label = "paired",
                    value = profile.pairedAt
                        .take(16)
                        .replace('T', ' ')
                        .ifBlank { "unknown" },
                )
            }
            state.syncError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalGhostButton(
                    text = "Hosts (${state.hostCount})",
                    onClick = onOpenHosts,
                    modifier = Modifier.weight(1f),
                )
                if (state.connection == ConnectionStatus.Offline) {
                    HadalGhostButton(
                        text = "Retry",
                        onClick = state::reconnect,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    HadalGhostButton(
                        text = "Add host",
                        onClick = onOpenPairing,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Sign in with Tailscale",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        state.clearTailscaleSignInError()
                        showTailscaleAdd = true
                    }
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showTailscaleAdd) {
            TailscaleSignInDialog(
                state = state,
                initialHostUrl = tailscaleAddUrl,
                onHostUrlChange = { tailscaleAddUrl = it },
                onSignIn = state::addHostWithTailscale,
                onSignedIn = { showTailscaleAdd = false },
                onDismiss = {
                    showTailscaleAdd = false
                    state.clearTailscaleSignInError()
                },
            )
        }
    }
}

/** Footer: app version + active host label and its `/health` version (when known). */
@Composable
private fun buildFooter(state: AppState): String {
    val host = state.activeProfile
    val hostVersion = state.activeHostVersion
    return buildString {
        append("DSH Mobile  ·  ")
        append(state.updates.currentVersionName)
        if (host != null) {
            append("  ·  ")
            append(host.label)
            if (!hostVersion.isNullOrBlank()) {
                append(" v")
                append(hostVersion)
            }
        } else {
            append("  ·  no host")
        }
        append("  ·  hadal mint")
    }
}

@Composable
private fun ConnectionDetail(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuietTimeRow(
    label: String,
    hour: Int,
    onHour: (Int) -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "%02d:00".format(hour),
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable {
                    TimePickerDialog(
                        context,
                        { _, selectedHour, _ -> onHour(selectedHour) },
                        hour,
                        0,
                        true,
                    ).show()
                }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                checkedIconColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedIconColor = MaterialTheme.colorScheme.tertiary,
            ),
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Paired-device list for the active host: shows name/platform and relative paired/last-seen times,
 * tags this device, and only offers Revoke for other devices (with confirmation). Tokens/hashes are
 * never part of the model or the UI.
 */
@Composable
private fun PairedDevicesDialog(state: AppState) {
    var deviceToRevoke by remember { mutableStateOf<PairedDevice?>(null) }
    val list = state.pairedDevices
    AlertDialog(
        onDismissRequest = { state.closePairedDevices() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Paired devices",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    state.pairedDevicesLoading && list == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Loading paired devices…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    list == null && state.pairedDevicesError != null -> Text(
                        state.pairedDevicesError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    list == null -> Text(
                        "Loading paired devices…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    !list.supported -> Text(
                        "This host doesn't support managing paired devices yet. Update the " +
                            "desktop host plugin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    list.devices.isEmpty() -> Text(
                        "No paired devices yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    else -> list.devices.forEach { device ->
                        PairedDeviceRow(
                            device = device,
                            isCurrent = pairedDeviceIsCurrent(list, device),
                            revoking = state.revokingDeviceId == device.deviceId,
                            onRevoke = { deviceToRevoke = device },
                        )
                        if (device != list.devices.last()) Hairline()
                    }
                }
                if (list != null && state.pairedDevicesError != null) {
                    Text(
                        state.pairedDevicesError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.pairedDevicesStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "Revoked devices must pair again. Token material is never shown here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        },
        confirmButton = {
            Text(
                "Refresh",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { state.refreshPairedDevices() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        dismissButton = {
            Text(
                "Close",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { state.closePairedDevices() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
    deviceToRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToRevoke = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Revoke ${pairedDeviceName(device)}?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "That device will need to pair again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Revoke",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            deviceToRevoke = null
                            state.revokePairedDevice(device)
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
                        .clickable { deviceToRevoke = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

@Composable
private fun PairedDeviceRow(
    device: PairedDevice,
    isCurrent: Boolean,
    revoking: Boolean,
    onRevoke: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pairedDeviceName(device),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "This device",
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (device.platform.isNotBlank()) {
                Text(
                    device.platform,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            pairedDeviceMetadataLabel(device)?.let { metadata ->
                Text(
                    metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (!isCurrent) {
            Text(
                if (revoking) "Revoking…" else "Revoke",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !revoking) { onRevoke() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Danger,
            )
        }
    }
}

/**
 * Streams an imported backup into memory with a hard byte cap. The file is rejected while reading
 * (never buffered whole first), so a huge/corrupt file can't OOM the app.
 */
private fun readBackupTextLimited(context: Context, uri: android.net.Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Couldn't open the selected backup file.")
    input.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > LOCAL_BACKUP_MAX_FILE_BYTES) {
                throw IllegalArgumentException(
                    "The backup file is too large to import (limit " +
                        "${LOCAL_BACKUP_MAX_FILE_BYTES / (1024 * 1024)} MB).",
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().decodeToString()
    }
}

// endregion
