package dev.dsh.mobile.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.LOCAL_HOST_BASE_URL
import dev.dsh.mobile.data.LocalHostProbeState

private const val TERMUX_TERMUX_PACKAGE = "com.termux"
private const val TERMUX_PLAY_URL = "market://details?id=$TERMUX_TERMUX_PACKAGE"
private const val TERMUX_PLAY_FALLBACK = "https://play.google.com/store/apps/details?id=$TERMUX_TERMUX_PACKAGE"

/** Commands to paste in Termux; the script handles plugin registration and starts the harness. */
private val TERMUX_COMMANDS = """
# In Termux:
pkg update -y && pkg install -y nodejs git
git clone https://github.com/LordGrimTaken/dsh-mobile
cd dsh-mobile && bash scripts/termux-host.sh
""".trimIndent()

/**
 * Settings → Hosts → “On this phone”: detects and pairs a DeepSeek Harness running inside
 * Termux on this device over loopback (`127.0.0.1:3543`).
 */
@Composable
fun TermuxHostSection(state: AppState) {
    val paired = state.localHostProfile
    val probe = state.localHostProbe
    LaunchedEffect(Unit) { state.probeLocalHost() }

    Text(
        "ON THIS PHONE",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
    HadalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            paired != null -> MaterialTheme.colorScheme.primary
                            probe is LocalHostProbeState.Found -> MaterialTheme.colorScheme.primary
                            probe is LocalHostProbeState.Checking -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Termux host",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    paired != null -> "paired"
                    probe is LocalHostProbeState.Checking -> "checking…"
                    probe is LocalHostProbeState.Found -> "reachable"
                    else -> "not detected"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    paired != null || probe is LocalHostProbeState.Found -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                paired != null -> "On this phone · ${paired.baseUrl} · loopback only. " +
                    "Sessions come from the harness running in Termux."
                probe is LocalHostProbeState.Found -> buildString {
                    append("127.0.0.1:3543 · host v")
                    append(probe.version.ifBlank { "?" })
                    if (probe.hostName.isNotBlank()) append(" · ").append(probe.hostName)
                    append(" · ").append(probe.latencyMs).append(" ms")
                }
                probe is LocalHostProbeState.Checking -> "Looking for a harness on 127.0.0.1:3543…"
                probe is LocalHostProbeState.NotFound -> probe.message
                else -> "Run DeepSeek Harness in Termux on this phone — no desktop needed."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(10.dp))
        when {
            paired != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalPrimaryButton(
                    text = "Open sessions",
                    onClick = {
                        if (state.activeHostId != paired.id) state.switchHost(paired.id)
                        state.closeHosts()
                    },
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = "Re-detect",
                    onClick = state::probeLocalHost,
                    modifier = Modifier.weight(1f),
                )
            }

            probe is LocalHostProbeState.Found -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalPrimaryButton(
                    text = "Pair this host",
                    onClick = state::openLocalHostPairing,
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = "Re-detect",
                    onClick = state::probeLocalHost,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalPrimaryButton(
                    text = "Set up Termux",
                    onClick = state::openLocalHostSetup,
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = "Detect host",
                    onClick = state::probeLocalHost,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "The app connects over loopback only — nothing is exposed to the network. Android may " +
                "stop Termux in the background: keep it wake-locked for long turns.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/** Setup guide for running the harness inside Termux on this phone. */
@Composable
fun TermuxHostSetupScreen(state: AppState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }
    val probe = state.localHostProbe
    LaunchedEffect(Unit) { state.probeLocalHost() }

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
            IconButton(onClick = state::closeLocalHostSetup) {
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
                "SET UP TERMUX HOST",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "The app can’t install code into Termux for you. Do these three steps on the phone " +
                    "once, then come back and pair — the harness then runs entirely on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            SetupStepCard(number = "1", title = "Install Termux from Google Play") {
                Text(
                    "Use the Play Store build (current, works on Android 11+ with modern targetSdk).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                HadalGhostButton(
                    text = "Open Play Store",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(TERMUX_PLAY_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(TERMUX_PLAY_FALLBACK))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SetupStepCard(number = "2", title = "Install Node 24 + the harness") {
                Text(
                    "Paste this in Termux. It installs Node/git, the harness, registers the mobile " +
                        "host plugin and starts it with a wake lock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    TERMUX_COMMANDS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                HadalGhostButton(
                    text = if (copied == "commands") "Copied" else "Copy commands",
                    onClick = {
                        clipboard.setText(AnnotatedString(TERMUX_COMMANDS))
                        copied = "commands"
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "~1.5 GB · first install takes a few minutes on Wi-Fi. The first harness start " +
                        "asks for a provider API key.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SetupStepCard(number = "3", title = "Detect and pair") {
                Text(
                    when (val current = probe) {
                        is LocalHostProbeState.Checking -> "Checking 127.0.0.1:3543…"
                        is LocalHostProbeState.Found -> "Found host v" +
                            current.version.ifBlank { "?" } + " · " + current.latencyMs + " ms"
                        is LocalHostProbeState.NotFound ->
                            "Not detected yet. Make sure the harness is running in Termux."
                        else -> "When Termux says the host is running, tap Detect host."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(8.dp))
                if (probe is LocalHostProbeState.Found) {
                    HadalPrimaryButton(
                        text = "Pair this host",
                        onClick = state::openLocalHostPairing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                HadalGhostButton(
                    text = "Detect host",
                    onClick = state::probeLocalHost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "Preview instructions: run the script once and check the output. If Termux reports a " +
                    "missing command, update Termux’s packages and run it again.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun SetupStepCard(
    number: String,
    title: String,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(22.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}
