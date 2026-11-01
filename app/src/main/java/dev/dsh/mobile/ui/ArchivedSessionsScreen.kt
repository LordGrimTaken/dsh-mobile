package dev.dsh.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.Session
import dev.dsh.mobile.ui.theme.Danger

/**
 * Archived sessions: sessions removed from the active list on the host. Each card can restore the
 * session (Unarchive), which refreshes both the active and archived lists. Unsupported hosts show
 * an explanatory empty state.
 */
@Composable
fun ArchivedSessionsScreen(state: AppState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { state.refreshArchivedSessions() }

    val archivedState = state.archivedSessionsState
    val sessions = archivedState?.sessions.orEmpty()
    val unsupported = archivedState?.supported == false
    val loading = state.archivedLoading

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
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
                "❯",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "ARCHIVED",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (archivedState == null) "—" else "${sessions.size} archived",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(6.dp))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 10.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                IconButton(onClick = state::refreshArchivedSessions) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh archived sessions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ConnectionPill(state.connection)
        }

        state.archivedError?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                )
                Text(
                    "Dismiss",
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { state.clearArchivedError() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        when {
            unsupported -> ArchivedEmptyState(
                title = "Archived sessions aren't available on this host yet",
                body = "Update the desktop host plugin to archive and restore sessions from the phone.",
            )

            archivedState == null && loading -> Row(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            sessions.isEmpty() -> ArchivedEmptyState(
                title = "No archived sessions",
                body = "Sessions archived from the session list or the desktop appear here.",
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 4.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { "archived:${it.id}" }) { session ->
                    ArchivedSessionCard(
                        session = session,
                        workspaceName = state.workspaceFor(session.workspaceId).name,
                        title = state.displayTitle(session),
                        actionLoading = state.sessionActionLoading,
                        onUnarchive = { state.unarchiveSessionOnHost(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedSessionCard(
    session: Session,
    workspaceName: String,
    title: String,
    actionLoading: Boolean,
    onUnarchive: () -> Unit,
) {
    HadalCard {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            workspaceName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (session.preview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                session.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = "Unarchive",
            onClick = { if (!actionLoading) onUnarchive() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ArchivedEmptyState(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "▣",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
