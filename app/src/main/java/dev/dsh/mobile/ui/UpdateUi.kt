package dev.dsh.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.BuildConfig
import dev.dsh.mobile.update.UpdateManager
import dev.dsh.mobile.update.UpdateUiState
import dev.dsh.mobile.ui.theme.Danger
import dev.dsh.mobile.ui.theme.Success
import dev.dsh.mobile.ui.theme.Warning

/** Sideload update dialog: notes, download progress, unknown-sources guidance, errors. */
@Composable
fun UpdateDialog(manager: UpdateManager) {
    // Play builds have no in-app installer/feed at all.
    if (BuildConfig.PLAY_BUILD) return
    if (!manager.showDialog) return
    val state = manager.state

    AlertDialog(
        onDismissRequest = manager::dismissDialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "❯",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    dialogTitle(state).uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                dialogMessage(manager, state)?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state) {
                    is UpdateUiState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { (state.percent ?: 0).coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            "${state.percent?.coerceIn(0, 100) ?: 0}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    is UpdateUiState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "verifying sha-256…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    else -> Unit
                }
                if (state is UpdateUiState.Available && state.update.notes.isNotBlank()) {
                    HadalCaps("release notes")
                    Text(
                        state.update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { dialogConfirmButton(manager, state) },
        dismissButton = { dialogDismissButton(manager, state) },
    )
}

/** Settings row: current version, live check/download status, tap to check (or reopen dialog). */
@Composable
fun UpdateSettingsRow(manager: UpdateManager) {
    // Play builds have no in-app installer/feed at all.
    if (BuildConfig.PLAY_BUILD) return
    val state = manager.state
    val busy = state is UpdateUiState.Checking ||
        state is UpdateUiState.Downloading ||
        state is UpdateUiState.Verifying

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HadalCaps("updates", modifier = Modifier.padding(start = 2.dp))
        HadalCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        when (state) {
                            is UpdateUiState.Available,
                            is UpdateUiState.NeedsInstallPermission,
                            is UpdateUiState.InstallPrompted,
                            is UpdateUiState.UpToDate,
                            is UpdateUiState.Failed -> manager.showUpdateDialog()

                            else -> manager.check(manual = true)
                        }
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Check for updates",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Current version ${manager.currentVersionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    updateStatusText(state)?.let { (text, color) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Check for updates",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun updateStatusText(state: UpdateUiState): Pair<String, Color>? = when (state) {
    UpdateUiState.Idle -> null
    UpdateUiState.Checking -> "Checking..." to MaterialTheme.colorScheme.tertiary
    is UpdateUiState.UpToDate -> "Up to date" to Success
    is UpdateUiState.Available ->
        "Update available (${state.update.versionName})" to MaterialTheme.colorScheme.primary
    is UpdateUiState.Downloading -> {
        val percent = state.percent?.let { " $it%" }.orEmpty()
        "Downloading$percent..." to MaterialTheme.colorScheme.primary
    }

    is UpdateUiState.Verifying -> "Verifying download..." to MaterialTheme.colorScheme.primary
    is UpdateUiState.NeedsInstallPermission ->
        "Ready to install - allow installs in Settings" to Warning
    is UpdateUiState.InstallPrompted -> "Installer opened" to MaterialTheme.colorScheme.primary
    is UpdateUiState.Failed -> state.message to Danger
}

private fun dialogTitle(state: UpdateUiState): String = when (state) {
    UpdateUiState.Idle -> "Updates"
    UpdateUiState.Checking -> "Checking for updates"
    is UpdateUiState.UpToDate -> "You're up to date"
    is UpdateUiState.Available -> "Update available"
    is UpdateUiState.Downloading -> "Downloading update"
    is UpdateUiState.Verifying -> "Verifying update"
    is UpdateUiState.NeedsInstallPermission -> "Allow installs"
    is UpdateUiState.InstallPrompted -> "Install the update"
    is UpdateUiState.Failed -> "Update failed"
}

private fun dialogMessage(manager: UpdateManager, state: UpdateUiState): String? = when (state) {
    UpdateUiState.Idle -> null
    UpdateUiState.Checking -> "Looking for a newer build..."
    is UpdateUiState.UpToDate ->
        "You're running the latest version (${state.currentVersionName})."
    is UpdateUiState.Available ->
        "Version ${state.update.versionName} is available. You're on ${manager.currentVersionName}."
    is UpdateUiState.Downloading ->
        "Downloading version ${state.update.versionName}."
    is UpdateUiState.Verifying ->
        "Checking the SHA-256 of the downloaded APK before installing."
    is UpdateUiState.NeedsInstallPermission ->
        "Android needs your permission to install apps from DSH Mobile.\n\n" +
            "1. Tap Continue to open the setting\n" +
            "2. Enable \"Allow from this source\"\n" +
            "3. Come back and tap Continue again to install."
    is UpdateUiState.InstallPrompted ->
        "The Android package installer is open. Follow its prompt to finish installing " +
            "${state.update.versionName}."
    is UpdateUiState.Failed -> state.message
}

@Composable
private fun dialogConfirmButton(manager: UpdateManager, state: UpdateUiState) {
    when (state) {
        is UpdateUiState.Available -> HadalPrimaryButton(
            text = "Update",
            onClick = manager::downloadAndInstall,
        )

        is UpdateUiState.NeedsInstallPermission -> HadalPrimaryButton(
            text = if (manager.canInstallPackages()) "Install" else "Continue",
            onClick = manager::installDownloaded,
        )

        else -> Unit
    }
}

@Composable
private fun dialogDismissButton(manager: UpdateManager, state: UpdateUiState) {
    val label = when (state) {
        is UpdateUiState.Available -> "Later"
        is UpdateUiState.NeedsInstallPermission -> "Later"
        is UpdateUiState.Downloading, is UpdateUiState.Verifying -> "Hide"
        else -> "Close"
    }
    HadalGhostButton(text = label, onClick = manager::dismissDialog)
}
