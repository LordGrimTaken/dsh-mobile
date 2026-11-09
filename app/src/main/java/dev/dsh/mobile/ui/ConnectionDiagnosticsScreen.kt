package dev.dsh.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.ConnectionStatus
import dev.dsh.mobile.data.activeRoute
import dev.dsh.mobile.data.ensureRoutes
import dev.dsh.mobile.ui.theme.Warning
import kotlinx.coroutines.launch
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Read-only host/SSE/usage health view with a live /health probe. */
@Composable
fun ConnectionDiagnosticsScreen(state: AppState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = state.activeProfile
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf<Boolean?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "CONNECTION",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "diagnostics",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.padding(horizontal = 8.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HadalCaps("HOST")
            HadalCard {
                if (profile == null) {
                    Text(
                        "No host is paired. Add one from Settings → Hosts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    DiagRow("label", profile.label)
                    DiagRow("url", profile.baseUrl)
                    DiagRow("device", profile.deviceId)
                    DiagRow("paired", profile.pairedAt)
                    if (profile.baseUrl.contains(".ts.net") && state.networkTransport != "VPN") {
                        Text(
                            "This looks like a Tailscale Serve URL, but Android doesn't report the " +
                                "VPN as active. Turn Tailscale on / enable Always-on VPN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Warning,
                        )
                    }
                }
            }

            if (profile != null) {
                val normalized = profile.ensureRoutes()
                HadalCaps("ROUTES")
                HadalCard {
                    Text(
                        "Every endpoint saved for this host. The client probes them in " +
                            "parallel and reconnects through the fastest reachable route when " +
                            "the current connection drops.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.padding(vertical = 2.dp))
                    normalized.routes.forEach { route ->
                        val probe = state.routeProbes[route.id]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (route.id == normalized.activeRouteId) {
                                        "● ${route.label} (active)"
                                    } else {
                                        "○ ${route.label}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (route.id == normalized.activeRouteId) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    route.baseUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                probe?.summary() ?: if (state.routeProbing) "probing…" else "not probed",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    probe == null -> MaterialTheme.colorScheme.tertiary
                                    probe.reachable -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                                maxLines = 2,
                            )
                        }
                    }
                    Spacer(Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HadalPrimaryButton(
                            text = if (state.routeProbing) "Probing…" else "Probe routes",
                            enabled = !state.routeProbing,
                            onClick = { state.probeRoutes(profile.id) },
                            modifier = Modifier.weight(1f),
                        )
                        HadalPrimaryButton(
                            text = "Use fastest",
                            enabled = !state.routeProbing && normalized.routes.size > 1,
                            onClick = { state.useFastestRoute(profile.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            HadalCaps("STATE")
            HadalCard {
                DiagRow("connection", connectionName(state.connection))
                DiagRow(
                    "network",
                    if (state.networkAvailable) state.networkTransport else "offline",
                )
                DiagRow("host version", state.activeHostVersion ?: "unknown")
                DiagRow("last snapshot", relativeTime(state.lastRefreshedAt))
                DiagRow("last stream frame", relativeTime(state.lastStreamEventAt))
                DiagRow("reconnects", state.reconnectAttempts.toString())
                DiagRow("last reconnect", relativeTime(state.lastReconnectAt))
                DiagRow("usage", if (state.usage != null) "loaded" else (state.usageError ?: "not loaded"))
                DiagRow("approvals bridge", if (state.approvalsSupported) "available" else "unsupported")
                DiagRow("questions bridge", if (state.questionsSupported) "available" else "unsupported")
                DiagRow("background sync", if (state.backgroundSyncEnabled) "enabled" else "off")
                DiagRow(
                    "last sync",
                    state.backgroundSyncLastAt?.let { at ->
                        val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
                        when {
                            seconds < 60 -> "${seconds}s ago"
                            seconds < 3600 -> "${seconds / 60}m ago"
                            else -> "${seconds / 3600}h ago"
                        }
                    } ?: "not run yet",
                )
                state.backgroundSyncLastError?.let { error ->
                    DiagRow("sync error", error.take(80))
                }
            }

            HadalCaps("LIVE CHECK")
            HadalCard {
                Text(
                    "Probe the paired host's /health endpoint from this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.padding(vertical = 4.dp))
                result?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Spacer(Modifier.padding(vertical = 4.dp))
                }
                HadalPrimaryButton(
                    text = if (testing) "Checking…" else "Run health check",
                    onClick = {
                        if (testing || profile == null) return@HadalPrimaryButton
                        testing = true
                        result = null
                        ok = null
                        scope.launch {
                            val started = SystemClock.elapsedRealtime()
                            val response = state.testConnection(profile)
                            val millis = SystemClock.elapsedRealtime() - started
                            response.fold(
                                onSuccess = { health ->
                                    ok = health.ok
                                    result = if (health.ok) {
                                        "healthy · v${health.version.ifBlank { "?" }} · ${millis}ms"
                                    } else {
                                        "host answered unhealthy · ${millis}ms"
                                    }
                                },
                                onFailure = { error ->
                                    ok = false
                                    result = "failed · ${describeHealthFailure(error)} · ${millis}ms"
                                },
                            )
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (result != null) {
                    HadalGhostButton(
                        text = "Copy diagnostics",
                        onClick = {
                            copyDiagnostics(context, state, result.orEmpty())
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HadalGhostButton(
                        text = "Share diagnostics",
                        onClick = { shareDiagnostics(context, state, result.orEmpty()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HadalCaps("RECENT EVENTS")
            HadalCard {
                if (state.connectionEvents.isEmpty()) {
                    Text(
                        "No connection events recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    state.connectionEvents.take(12).forEach { event ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                relativeTime(event.at),
                                modifier = Modifier.weight(0.28f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                event.message,
                                modifier = Modifier.weight(0.72f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun connectionName(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.Live -> "live"
    ConnectionStatus.Connecting -> "connecting"
    ConnectionStatus.Offline -> "offline"
    ConnectionStatus.Demo -> "demo"
}

private fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "never"
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.SECOND_IN_MILLIS,
    ).toString()
}

private fun describeHealthFailure(error: Throwable): String = when (error) {
    is UnknownHostException -> "DNS failed — check Tailscale/MagicDNS or the host URL"
    is SSLException -> "TLS failed — check the Tailscale Serve certificate/URL"
    is SocketTimeoutException, is InterruptedIOException ->
        "timed out — host unreachable or the VPN route is stale"
    is ConnectException -> "connection refused — is the host plugin running on 3543?"
    else -> error.message?.replace('\n', ' ')?.take(110) ?: (error::class.simpleName ?: "error")
}

private fun diagnosticsText(state: AppState, liveCheck: String): String = buildString {
    appendLine("DSH Mobile connection diagnostics")
    state.activeProfile?.let { profile ->
        appendLine("host: ${profile.label} · ${profile.baseUrl}")
        appendLine("device: ${profile.deviceId}")
    }
    appendLine("connection: ${connectionName(state.connection)}")
    appendLine("network: ${if (state.networkAvailable) state.networkTransport else "offline"}")
    appendLine("host version: ${state.activeHostVersion ?: "unknown"}")
    appendLine("reconnects: ${state.reconnectAttempts}")
    appendLine("last stream frame: ${relativeTime(state.lastStreamEventAt)}")
    appendLine("background sync: ${if (state.backgroundSyncEnabled) "enabled" else "off"}")
    appendLine("live check: $liveCheck")
    appendLine("recent events:")
    state.connectionEvents.forEach { event -> appendLine("- $event") }
}

private fun copyDiagnostics(context: Context, state: AppState, liveCheck: String) {
    val text = diagnosticsText(state, liveCheck)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH diagnostics", text))
}

private fun shareDiagnostics(context: Context, state: AppState, liveCheck: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "DSH Mobile connection diagnostics")
        putExtra(Intent.EXTRA_TEXT, diagnosticsText(state, liveCheck))
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
    }.onFailure {
        Toast.makeText(context, "No app available to share diagnostics", Toast.LENGTH_SHORT).show()
    }
}
