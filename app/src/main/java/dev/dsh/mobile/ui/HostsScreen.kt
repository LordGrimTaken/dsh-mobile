package dev.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.ConnectionStatus
import dev.dsh.mobile.data.HostProfile
import dev.dsh.mobile.data.HostRoute
import dev.dsh.mobile.data.RouteProbe
import dev.dsh.mobile.data.activeRoute
import dev.dsh.mobile.data.ensureRoutes
import dev.dsh.mobile.data.hasMultipleRoutes
import dev.dsh.mobile.ui.theme.Danger
import kotlinx.coroutines.launch

private sealed interface HostTestUi {
    data object Testing : HostTestUi
    data class Done(val text: String, val failed: Boolean = false) : HostTestUi
}

/**
 * Settings → Hosts: saved host profiles with the active indicator, per-host connection test,
 * rename, remove and the add-host flow (which reuses pairing).
 */
@Composable
fun HostsScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<HostProfile?>(null) }
    var removeTarget by remember { mutableStateOf<HostProfile?>(null) }
    var testStates by remember { mutableStateOf<Map<String, HostTestUi>>(emptyMap()) }
    var showTailscaleAdd by remember { mutableStateOf(false) }
    var tailscaleAddUrl by remember { mutableStateOf("") }
    var showRecoveryRestore by remember { mutableStateOf(false) }
    var routesTargetId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        HostsTopBar(state = state)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Hosts",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (state.profiles.isEmpty()) {
                    "No hosts paired yet."
                } else {
                    "${state.profiles.size} saved · active ${state.activeProfile?.label ?: "none"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            if (state.profiles.isEmpty()) {
                HadalCard {
                    Text(
                        "Pair a DSH host to stream live sessions. Any reachable base URL works - " +
                            "desktop or cloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    HadalPrimaryButton(
                        text = "Pair a host",
                        onClick = { state.openPairing() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    HadalGhostButton(
                        text = "Sign in with Tailscale",
                        onClick = {
                            state.clearTailscaleSignInError()
                            showTailscaleAdd = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    HadalGhostButton(
                        text = "Restore with recovery code",
                        onClick = {
                            state.clearRecoveryRestoreError()
                            showRecoveryRestore = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                state.profiles.forEach { profile ->
                    HostProfileCard(
                        profile = profile,
                        active = profile.id == state.activeHostId,
                        connection = state.connection,
                        activeVersion = state.activeHostVersion,
                        testState = testStates[profile.id],
                        onSwitch = { state.switchHost(profile.id) },
                        onRename = { renameTarget = profile },
                        onRemove = { removeTarget = profile },
                        onRoutes = { routesTargetId = profile.id },
                        onTest = {
                            testStates = testStates + (profile.id to HostTestUi.Testing)
                            scope.launch {
                                val result = state.testConnection(profile)
                                testStates = testStates + (
                                    profile.id to result.fold(
                                        onSuccess = { health ->
                                            HostTestUi.Done(
                                                if (health.ok) {
                                                    "healthy · v${health.version.ifBlank { "?" }}"
                                                } else {
                                                    "host reported unhealthy"
                                                },
                                            )
                                        },
                                        onFailure = { error ->
                                            HostTestUi.Done(state.errorMessage(error), failed = true)
                                        },
                                    )
                                    )
                            }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                HadalPrimaryButton(
                    text = "Add host",
                    onClick = { state.openPairing() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                HadalGhostButton(
                    text = "Sign in with Tailscale",
                    onClick = {
                        state.clearTailscaleSignInError()
                        showTailscaleAdd = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                HadalGhostButton(
                    text = "Restore with recovery code",
                    onClick = {
                        state.clearRecoveryRestoreError()
                        showRecoveryRestore = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TermuxHostSection(state = state)

            Text(
                "Switching hosts rebuilds the live connection and reloads sessions for that host. " +
                    "Removing a host unpairs only that host.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    renameTarget?.let { target ->
        RenameHostDialog(
            profile = target,
            onDismiss = { renameTarget = null },
            onRename = { label ->
                state.renameHost(target.id, label)
                renameTarget = null
            },
        )
    }
    removeTarget?.let { target ->
        RemoveHostDialog(
            profile = target,
            onDismiss = { removeTarget = null },
            onRemove = {
                state.removeHost(target.id)
                removeTarget = null
            },
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
    routesTargetId?.let { hostId ->
        HostRoutesDialog(
            state = state,
            hostId = hostId,
            onDismiss = { routesTargetId = null },
        )
    }
    if (showRecoveryRestore) {
        RecoveryRestoreDialog(
            state = state,
            onDismiss = {
                showRecoveryRestore = false
                state.clearRecoveryRestoreError()
            },
        )
    }
}

@Composable
private fun HostsTopBar(state: AppState) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = state::closeHosts) {
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
                "HOSTS",
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

@Composable
private fun HostProfileCard(
    profile: HostProfile,
    active: Boolean,
    connection: ConnectionStatus,
    activeVersion: String?,
    testState: HostTestUi?,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
    onRoutes: () -> Unit,
) {
    val normalized = profile.ensureRoutes()
    val routeCount = normalized.routes.size
    HadalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (active) {
                Text(
                    "active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                HostAction(text = "switch", onClick = onSwitch)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (routeCount > 1) {
                "${normalized.activeRoute.label}: ${profile.baseUrl} · $routeCount routes"
            } else {
                profile.baseUrl
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "device ${profile.deviceId.take(14)} · paired ${shortTimestamp(profile.pairedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
        if (active) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionPill(connection)
                activeVersion?.let { version ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "host v$version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        when (testState) {
            HostTestUi.Testing -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "testing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            is HostTestUi.Done -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    testState.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (testState.failed) {
                        Danger
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 2,
                )
            }

            null -> Unit
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HostAction(
                text = if (routeCount > 1) "routes ($routeCount)" else "routes",
                onClick = onRoutes,
            )
            HostAction(text = "test connection", onClick = onTest)
            HostAction(text = "rename", onClick = onRename)
            HostAction(text = "remove", onClick = onRemove, danger = true)
        }
    }
}

@Composable
private fun HostAction(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (danger) Danger else MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
}

/**
 * Settings → Hosts → Routes: probe, switch, add and remove the endpoints of one host.
 * The dialog reads the live profile from [AppState] so edits made here re-render immediately.
 */
@Composable
private fun HostRoutesDialog(
    state: AppState,
    hostId: String,
    onDismiss: () -> Unit,
) {
    val profile = state.profiles.firstOrNull { it.id == hostId }
    if (profile == null) {
        onDismiss()
        return
    }
    val normalized = profile.ensureRoutes()
    val routes = normalized.routes
    var adding by remember { mutableStateOf(false) }
    var addLabel by remember { mutableStateOf("") }
    var addUrl by remember { mutableStateOf("https://") }
    var addError by remember { mutableStateOf<String?>(null) }
    val probing = state.routeProbing

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Routes · ${profile.label}",
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "The phone uses the active route for every request. When the connection " +
                        "drops it probes all routes and reconnects through the fastest reachable " +
                        "one; the pairing token is shared by every route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                routes.forEach { route ->
                    HostRouteRow(
                        route = route,
                        active = route.id == normalized.activeRouteId,
                        probe = state.routeProbes[route.id],
                        probing = probing,
                        canRemove = routes.size > 1,
                        onUse = { state.selectRoute(profile.id, route.id) },
                        onRemove = {
                            addError = state.removeRoute(profile.id, route.id)
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HadalPrimaryButton(
                        text = if (probing) "Probing…" else "Probe all",
                        onClick = { state.probeRoutes(profile.id) },
                        enabled = !probing,
                        modifier = Modifier.weight(1f),
                    )
                    HadalPrimaryButton(
                        text = "Use fastest",
                        onClick = { state.useFastestRoute(profile.id) },
                        enabled = !probing && routes.size > 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!adding) {
                    HadalGhostButton(
                        text = "Add route",
                        onClick = {
                            addError = null
                            adding = true
                        },
                    )
                } else {
                    Text(
                        "New route",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = addLabel,
                        onValueChange = { addLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Label (optional)") },
                        placeholder = { Text("Tailscale / LAN / Cloudflare") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = {
                            addUrl = it
                            addError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        placeholder = { Text("https://pc.tailnet.ts.net") },
                        singleLine = true,
                    )
                    addError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HadalPrimaryButton(
                            text = "Add route",
                            enabled = addUrl.isNotBlank(),
                            onClick = {
                                val error = state.addRoute(
                                    profile.id,
                                    addLabel,
                                    addUrl,
                                    HostRoute.kindForUrl(addUrl),
                                )
                                if (error != null) {
                                    addError = error
                                } else {
                                    adding = false
                                    addLabel = ""
                                    addUrl = "https://"
                                    addError = null
                                    state.probeRoutes(profile.id)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HadalGhostButton(
                            text = "Cancel",
                            onClick = {
                                adding = false
                                addError = null
                            },
                        )
                    }
                }
                addError?.takeIf { !adding }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                    )
                }
            }
        },
        confirmButton = {
            HadalGhostButton(text = "Done", onClick = onDismiss)
        },
    )
}

/** One route row: label, kind, URL, last probe result and the per-row actions. */
@Composable
private fun HostRouteRow(
    route: HostRoute,
    active: Boolean,
    probe: RouteProbe?,
    probing: Boolean,
    canRemove: Boolean,
    onUse: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                route.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            Text(
                HostRoute.labelForKind(route.kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (active) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            route.baseUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
        Text(
            probe?.summary() ?: if (probing) "probing…" else "not probed",
            style = MaterialTheme.typography.labelSmall,
            color = when {
                probe == null -> MaterialTheme.colorScheme.tertiary
                probe.reachable -> MaterialTheme.colorScheme.primary
                else -> Danger
            },
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!active) HostAction(text = "use", onClick = onUse)
            if (canRemove) HostAction(text = "remove", onClick = onRemove, danger = true)
        }
    }
}

@Composable
private fun RenameHostDialog(
    profile: HostProfile,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember(profile.id) { mutableStateOf(profile.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Rename host", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Host label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            HadalPrimaryButton(
                text = "Save",
                enabled = label.isNotBlank(),
                onClick = { onRename(label) },
            )
        },
        dismissButton = {
            HadalGhostButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

@Composable
private fun RemoveHostDialog(
    profile: HostProfile,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Remove host?", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Text(
                "This unpairs \"${profile.label}\" (${profile.baseUrl}) from this phone. " +
                    "Other hosts are unaffected; you can pair it again later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
        confirmButton = {
            Text(
                "Remove",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Danger,
            )
        },
        dismissButton = {
            HadalGhostButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

private fun shortTimestamp(iso: String): String =
    iso.take(16).replace('T', ' ').ifBlank { "unknown" }
