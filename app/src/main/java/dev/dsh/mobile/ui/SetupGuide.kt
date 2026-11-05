package dev.dsh.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * First-run setup guide. It opens automatically once, then stays available from the welcome
 * screen. Every route ends with an action that drops the user into the matching flow.
 */
@Composable
internal fun SetupGuideScreen(
    onClose: () -> Unit,
    onPairDesktop: () -> Unit,
    onPairCloud: () -> Unit,
    onTailscale: () -> Unit,
    onRecovery: () -> Unit,
    onDemo: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to welcome",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                "SETUP GUIDE",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            HadalCaps("mobile · start here")
            Text(
                "How to set up DSH Mobile",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "DSH Mobile connects to a host running DeepSeek Harness. Pick the route that fits " +
                    "your setup. You can change hosts later in Settings → Hosts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )

            QuickStartCard(onDemo = onDemo)

            SetupMethodCard(
                icon = Icons.Filled.Computer,
                title = "Desktop host",
                summary = "The most common setup: DeepSeek Harness runs on your computer.",
                steps = listOf(
                    "On your computer, install DeepSeek Harness and the DSH Mobile host plugin. " +
                        "Start the host and leave it running.",
                    "Make the host reachable from your phone: use the same Wi-Fi/LAN, or install " +
                        "Tailscale on both devices. Do not expose the raw loopback port to the internet.",
                    "On the host, open the DSH Mobile helper and choose Pair a phone. It shows a " +
                        "6-digit code that expires after 5 minutes.",
                    "In this app tap Pair your desktop host, enter the host URL and the code, then " +
                        "tap Pair host.",
                    "Your phone appears under Settings → Hosts. The device token is stored encrypted " +
                        "on the phone.",
                ),
                note = "Example host URL: http://192.168.1.20:3543 or the HTTPS name from Tailscale Serve.",
                action = "Pair desktop host",
                onAction = onPairDesktop,
            )

            SetupMethodCard(
                icon = Icons.Filled.Cloud,
                title = "Cloud host",
                summary = "A headless DSH server that stays online when your computer is off.",
                steps = listOf(
                    "Run DeepSeek Harness on a server you control, with the DSH Mobile host plugin.",
                    "Put an HTTPS reverse proxy (or Tailscale Funnel/Serve) in front of the host and " +
                        "use a real certificate. The app needs an https:// base URL.",
                    "Create a pairing code from the server's host helper or admin flow.",
                    "In this app tap Connect to a cloud host, enter the HTTPS base URL and the " +
                        "6-digit code, then test the URL and pair.",
                    "The host is saved as Cloud. It works like a desktop host, but the computer can " +
                        "be offline.",
                ),
                note = "Never pair over plain HTTP to the public internet. Use HTTPS or a private tailnet.",
                action = "Pair cloud host",
                onAction = onPairCloud,
            )

            SetupMethodCard(
                icon = Icons.Filled.Link,
                title = "Tailscale",
                summary = "No pairing code on a Tailscale Serve URL; the tailnet verifies this device.",
                steps = listOf(
                    "Install Tailscale on the phone and on the host, and sign both into the same tailnet.",
                    "On the host, enable Tailscale Serve for the DSH Mobile port and copy its HTTPS URL.",
                    "In this app tap Sign in with Tailscale, paste the Serve URL, and tap Sign in.",
                    "No 6-digit code is needed when the host advertises tailnet auto-pairing. If it " +
                        "does not, go back and use the pairing code instead.",
                ),
                note = "Tailscale is optional. Any base URL your phone can reach also works.",
                action = "Open Tailscale sign-in",
                onAction = onTailscale,
            )

            SetupMethodCard(
                icon = Icons.Filled.Lock,
                title = "Reinstall or new phone",
                summary = "Recovery lets you restore a host without generating a new pairing code.",
                steps = listOf(
                    "Before clearing app data or uninstalling, open Settings → Security → Recovery " +
                        "access and copy the recovery link.",
                    "After reinstalling, tap Restore with recovery code on the welcome screen.",
                    "Paste the recovery link, or enter the host URL and the recovery code, then restore.",
                    "A fresh recovery link is shown once after restoring — save it somewhere safe.",
                ),
                note = "Recovery links are secrets. Do not share them or commit them to a repository.",
                action = "Restore a host",
                onAction = onRecovery,
            )

            SetupMethodCard(
                icon = Icons.Filled.PlayCircle,
                title = "Just looking around",
                summary = "Use demo data to browse the interface without a host.",
                steps = listOf(
                    "Tap Try demo data. The app loads sample sessions, messages, approvals, plans " +
                        "and settings.",
                    "Nothing is sent to a host or developer server; it is all local sample content.",
                    "When you are ready to connect for real, open Settings → Hosts and pair a host.",
                ),
                note = "Demo mode is the best place to start if you are only reviewing the app.",
                action = "Try demo data",
                onAction = onDemo,
            )

            HadalCard {
                Text(
                    "Pairing safety",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pairing codes expire after 5 minutes and can be used once. The app stores the " +
                        "device token encrypted and never sends it to a developer cloud. If a code " +
                        "expires, generate a new one on the host.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** Persistent entry point shown on the welcome screen after the guide has been dismissed. */
@Composable
internal fun SetupGuideEntryCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), shape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HadalIconTile(icon = Icons.AutoMirrored.Filled.HelpOutline, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "New here? Read the setup guide",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Step-by-step help for desktop, cloud, Tailscale, recovery and demo mode.",
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

@Composable
private fun QuickStartCard(onDemo: () -> Unit) {
    HadalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HadalIconTile(icon = Icons.Filled.PlayCircle, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Not sure where to start?",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Open demo data first. It needs no host or account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HadalPrimaryButton(
            text = "Try demo data",
            onClick = onDemo,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupMethodCard(
    icon: ImageVector,
    title: String,
    summary: String,
    steps: List<String>,
    note: String,
    action: String,
    onAction: () -> Unit,
) {
    HadalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HadalIconTile(icon = icon, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        steps.forEachIndexed { index, step ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    step,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index < steps.lastIndex) Spacer(Modifier.height(9.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(12.dp))
        HadalPrimaryButton(
            text = action,
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
