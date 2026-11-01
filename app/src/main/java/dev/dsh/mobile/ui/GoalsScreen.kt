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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.Session
import dev.dsh.mobile.data.Workspace
import dev.dsh.mobile.ui.theme.Success
import dev.dsh.mobile.ui.theme.Warning

/**
 * Goals dashboard: every session that currently has a goal, ordered paused/blocked first, then
 * active, then complete. Tapping a row closes the dashboard and opens that session's chat.
 */
@Composable
fun GoalsScreen(state: AppState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val goals = state.goalSessions()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
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
                "GOALS",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${goals.size} with goals",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(10.dp))
            ConnectionPill(state.connection)
        }

        if (goals.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "❯",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No goals on this host",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Goals you set from a session chat appear here with their phase and round " +
                        "progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 4.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(goals, key = { "goal:${it.id}" }) { session ->
                GoalSessionCard(
                    session = session,
                    title = state.displayTitle(session),
                    workspace = state.workspaceFor(session.workspaceId),
                    onClick = {
                        state.closeGoals()
                        state.openSession(session.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun GoalSessionCard(
    session: Session,
    title: String,
    workspace: Workspace,
    onClick: () -> Unit,
) {
    HadalCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    workspace.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Objective comes from the new optional summary field; fall back to the session
                // preview when an older host omits it.
                val objective = session.goalObjective?.trim().orEmpty()
                val summaryLine = objective.ifBlank { session.preview.trim() }
                if (summaryLine.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (objective.isNotBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GoalPhaseChip(session.goalPhase)
                Text(
                    if (session.goalMaxRounds > 0) {
                        "${session.goalRoundsStarted}/${session.goalMaxRounds} rounds"
                    } else {
                        "${session.goalRoundsStarted} rounds"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun GoalPhaseChip(phase: String?) {
    val normalized = phase?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) return
    val color = when (normalized) {
        "active" -> MaterialTheme.colorScheme.primary
        "paused", "blocked" -> Warning
        "complete" -> Success
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        normalized.uppercase(),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
    )
}
