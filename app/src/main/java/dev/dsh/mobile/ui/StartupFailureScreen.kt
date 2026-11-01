package dev.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown instead of the crash-looping Compose tree when [dev.dsh.mobile.data.AppState] cannot be
 * constructed. It deliberately has no AppState dependency, so a broken snapshot, preference or
 * connection bootstrap can still be retried or recovered through Safe mode.
 */
@Composable
internal fun StartupFailureScreen(
    summary: String,
    onRetry: () -> Unit,
    onOpenSafeMode: () -> Unit,
    onShareReport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "DSH Mobile couldn't start",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Your pairing, drafts and settings were not cleared. Retry, or open Safe mode " +
                    "to clear caches and reset presentation settings if the app keeps failing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Retry startup" },
            ) { Text("Retry") }
            TextButton(
                onClick = onOpenSafeMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Open Safe mode" },
            ) { Text("Open Safe mode") }
            TextButton(
                onClick = onShareReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Share the startup error report" },
            ) { Text("Share error report") }
        }
    }
}
