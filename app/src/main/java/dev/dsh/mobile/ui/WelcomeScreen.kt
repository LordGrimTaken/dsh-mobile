package dev.dsh.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.RECOVERY_CODE_FORMAT_MESSAGE
import dev.dsh.mobile.data.RECOVERY_SETUP_COPY
import dev.dsh.mobile.data.TAILSCALE_SIGNIN_COPY
import dev.dsh.mobile.data.parseRecoveryLink
import dev.dsh.mobile.data.recoveryCodeLooksValid
import dev.dsh.mobile.data.recoveryCompletionLink
import dev.dsh.mobile.data.recoveryCompletionMessage
import dev.dsh.mobile.data.tailscaleMethodsHint
import dev.dsh.mobile.data.tailscaleSignInAvailable
import kotlinx.coroutines.delay

/**
 * First screen when no host is paired. Phone-first onboarding: pair a desktop host, connect a
 * cloud/headless host, or explore with demo data. The app never assumes Tailscale or a desktop.
 */
@Composable
fun WelcomeScreen(state: AppState) {
    var showRecoveryRestore by remember { mutableStateOf(false) }
    var showTailscaleSignIn by remember { mutableStateOf(false) }
    var tailscaleUrl by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "❯",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "DEEPSEEK HARNESS",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        HadalCaps("mobile · first run")
        Spacer(Modifier.height(4.dp))
        Text(
            "Connect a DSH host",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "A host is any machine running the DSH mobile plugin - your computer or a cloud " +
                "server. The app only needs a reachable base URL and a one-time pairing code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(8.dp))

        WelcomeOption(
            icon = Icons.Filled.Computer,
            title = "Pair your desktop host",
            body = "The DSH app on your computer shows a 6-digit code for this phone.",
            accent = true,
            onClick = { state.openPairing("Desktop") },
        )
        WelcomeOption(
            icon = Icons.Filled.Cloud,
            title = "Connect to a cloud host",
            body = "Point at a headless DSH server over HTTPS; it saves as \"Cloud\".",
            onClick = { state.openPairing("Cloud") },
        )
        WelcomeOption(
            icon = Icons.Filled.Link,
            title = "Sign in with Tailscale",
            body = "No pairing code needed on a Tailscale Serve URL; the tailnet verifies this " +
                "device.",
            onClick = { showTailscaleSignIn = true },
        )
        WelcomeOption(
            icon = Icons.Filled.Lock,
            title = "Restore with recovery code",
            body = "Use a saved recovery link after clearing app data or reinstalling - no " +
                "desktop pairing code needed.",
            onClick = { showRecoveryRestore = true },
        )
        WelcomeOption(
            icon = Icons.Filled.PlayCircle,
            title = "Use demo data",
            body = "Explore the app with mock sessions. Pair a host later in Settings.",
            onClick = state::chooseDemoData,
        )

        Spacer(Modifier.height(6.dp))
        Text(
            "No Tailscale-specific setup required - any base URL the phone can reach works. " +
                "Hosts can be switched later in Settings → Hosts.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
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
    if (showTailscaleSignIn) {
        TailscaleSignInDialog(
            state = state,
            initialHostUrl = tailscaleUrl,
            onHostUrlChange = { tailscaleUrl = it },
            onDismiss = {
                showTailscaleSignIn = false
                state.clearTailscaleSignInError()
            },
        )
    }
}

/**
 * One-tap Tailscale Serve sign-in: a host URL (prefilled with the last typed value), no code.
 * The methods hint explains when the host isn't set up for tailnet auto-pairing.
 */
@Composable
internal fun TailscaleSignInDialog(
    state: AppState,
    initialHostUrl: String,
    onHostUrlChange: (String) -> Unit,
    onSignIn: (String) -> Unit = { state.signInWithTailscale(it) },
    onSignedIn: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var hostUrl by remember { mutableStateOf(initialHostUrl) }
    LaunchedEffect(hostUrl) {
        onHostUrlChange(hostUrl)
        if (hostUrl.isNotBlank()) {
            delay(400)
            state.loadTailscaleMethods(hostUrl)
        }
    }
    // Host-management dialogs close themselves after the shared pairing succeeds.
    val startNonce = remember { state.tailscaleSignInSuccessNonce }
    LaunchedEffect(state.tailscaleSignInSuccessNonce) {
        if (state.tailscaleSignInSuccessNonce != startNonce) onSignedIn()
    }
    val busy = state.tailscaleSignInBusy
    val methodsHint = tailscaleMethodsHint(state.tailscaleMethods)
    val canSubmit = !busy && hostUrl.isNotBlank() &&
        tailscaleSignInAvailable(state.tailscaleMethods)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Sign in with Tailscale",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    TAILSCALE_SIGNIN_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedTextField(
                    value = hostUrl,
                    onValueChange = { hostUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Tailscale Serve host URL") },
                    placeholder = { Text("https://host.tailnet.ts.net") },
                    enabled = !busy,
                )
                if (state.tailscaleMethodsLoading) {
                    Text(
                        "Checking host pairing methods...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (state.tailscaleMethods?.tailscaleAutoPair == true &&
                    state.tailscaleMethods?.tailscaleLoginRequired == false
                ) {
                    Text(
                        "Tailscale sign-in is available for this host.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                methodsHint?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                state.tailscaleSignInError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Signing in...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Text(
                if (busy) "Signing in..." else "Sign in with Tailscale",
                modifier = Modifier
                    .clickable(enabled = canSubmit) { onSignIn(hostUrl) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (canSubmit) {
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
                    .clickable { if (!busy) onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

/**
 * Restore pairing from a saved `dshmobile://recover` link or a host URL + recovery code.
 * Pasting the link into either field auto-fills both values.
 */
@Composable
internal fun RecoveryRestoreDialog(
    state: AppState,
    onDismiss: () -> Unit,
    initialHostUrl: String = "",
    initialCode: String = "",
) {
    var hostUrl by remember { mutableStateOf(initialHostUrl) }
    var code by remember { mutableStateOf(initialCode) }
    var linkRecognized by remember { mutableStateOf(initialCode.isNotBlank()) }
    val busy = state.recoveryRestoreBusy
    val codeInvalid = code.isNotBlank() && !recoveryCodeLooksValid(code)
    val canSubmit = !busy && hostUrl.isNotBlank() && recoveryCodeLooksValid(code)

    fun applyInput(raw: String, target: String) {
        val link = parseRecoveryLink(raw)
        if (link != null) {
            hostUrl = link.baseUrl
            code = link.code
            linkRecognized = true
        } else {
            if (target == "url") hostUrl = raw else code = raw
            linkRecognized = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Restore with recovery code",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste the recovery link saved from Settings → Security, or enter the " +
                        "host URL and the recovery code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedTextField(
                    value = hostUrl,
                    onValueChange = { applyInput(it, "url") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Host URL") },
                    placeholder = { Text("https://host.example.com") },
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { applyInput(it, "code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Recovery code or pasted link") },
                    enabled = !busy,
                )
                if (linkRecognized) {
                    Text(
                        "Recovery link recognized.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (codeInvalid) {
                    Text(
                        RECOVERY_CODE_FORMAT_MESSAGE,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "The code is single-use; the host rotates it after use.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                state.recoveryRestoreError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Restoring...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Text(
                if (busy) "Restoring..." else "Restore host",
                modifier = Modifier
                    .clickable(enabled = canSubmit) {
                        state.restoreWithRecovery(hostUrl, code)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (canSubmit) {
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
                    .clickable { if (!busy) onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

/**
 * One-time completion step after a successful restore. The host rotates the code on use, so the
 * new link is surfaced here immediately (copy/share + password-manager warning) instead of making
 * the user open Settings to generate another one.
 */
@Composable
internal fun RecoveryCompletionDialog(state: AppState, onDismiss: () -> Unit) {
    val completion = state.recoveryCompletion ?: return
    val context = LocalContext.current
    val link = recoveryCompletionLink(completion)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "New recovery link (shown once)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    recoveryCompletionMessage(completion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                completion.rotatedCode?.takeIf { it.isNotBlank() }?.let { code ->
                    Text(
                        code,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                link?.let { value ->
                    Text(
                        value,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "Copy link",
                            modifier = Modifier
                                .clickable {
                                    val clipboard = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE,
                                    ) as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("DSH recovery link", value),
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
                            "Share link",
                            modifier = Modifier
                                .clickable {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, value)
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
                }
            }
        },
        confirmButton = {
            Text(
                "Done",
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
private fun WelcomeOption(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                if (accent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HadalIconTile(icon = icon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
