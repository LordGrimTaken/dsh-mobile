package dev.dsh.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.ScheduleEditableTiming
import dev.dsh.mobile.data.ScheduleTiming
import dev.dsh.mobile.data.ScheduleTimingInput
import dev.dsh.mobile.data.ScheduleTimingMode
import dev.dsh.mobile.data.Session
import dev.dsh.mobile.data.SessionSchedule
import dev.dsh.mobile.data.editableScheduleTiming
import dev.dsh.mobile.ui.theme.Danger
import dev.dsh.mobile.ui.theme.Success
import dev.dsh.mobile.ui.theme.Warning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Schedules manager: list every host schedule, create one (after N minutes or daily at HH:MM) for a
 * visible session, and delete with confirmation. Unsupported hosts show an explanatory line.
 */
@Composable
fun SchedulesScreen(state: AppState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val listState = state.scheduleListState
    val schedules = listState?.schedules.orEmpty()
    val unsupported = listState?.supported == false
    val sessions = state.visibleSessions()

    // Create-form state.
    var sessionId by remember(sessions) {
        mutableStateOf(sessions.firstOrNull()?.id.orEmpty())
    }
    var title by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ScheduleTimingMode.AfterMinutes) }
    var afterMinutes by remember { mutableStateOf("30") }
    var dailyTime by remember { mutableStateOf("09:00") }
    var formError by remember { mutableStateOf<String?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var sessionPickerOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SessionSchedule?>(null) }
    var editing by remember { mutableStateOf<SessionSchedule?>(null) }

    val selectedSession = sessions.firstOrNull { it.id == sessionId }
    val afterMinutesValue = ScheduleTiming.parseAfterMinutes(afterMinutes)
    val dailyTimeValue = ScheduleTiming.parseDailyTime(dailyTime)
    val timingValid = when (mode) {
        ScheduleTimingMode.AfterMinutes -> afterMinutesValue != null
        ScheduleTimingMode.Daily -> dailyTimeValue != null
    }
    val canSubmit = !state.schedulesActionLoading &&
        sessionId.isNotBlank() &&
        title.isNotBlank() &&
        instruction.isNotBlank() &&
        timingValid

    // A successful create refreshes the list; clear the draft then.
    LaunchedEffect(state.schedulesActionLoading, state.schedulesError, schedules.size) {
        if (submitted && !state.schedulesActionLoading && state.schedulesError == null) {
            title = ""
            instruction = ""
            submitted = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
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
                "SCHEDULES",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (state.scheduleCount == null) "—" else "${schedules.size} scheduled",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(6.dp))
            if (state.schedulesLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 10.dp).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                IconButton(onClick = state::refreshSchedules) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh schedules",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ConnectionPill(state.connection)
        }

        state.schedulesError?.let { message ->
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
                        .clickable(onClick = state::clearSchedulesError)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 2.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!unsupported) {
                item(key = "create") {
                    CreateScheduleCard(
                        sessions = sessions,
                        selectedSession = selectedSession,
                        sessionPickerOpenRequest = { sessionPickerOpen = true },
                        title = title,
                        onTitleChange = { title = it.take(200) },
                        instruction = instruction,
                        onInstructionChange = { instruction = it.take(4_000) },
                        mode = mode,
                        onModeChange = { mode = it },
                        afterMinutes = afterMinutes,
                        onAfterMinutesChange = {
                            afterMinutes = it.filter { ch -> ch.isDigit() }.take(7)
                            formError = null
                        },
                        dailyTime = dailyTime,
                        onDailyTimeChange = {
                            dailyTime = it.take(5)
                            formError = null
                        },
                        timingValid = timingValid,
                        formError = formError,
                        actionLoading = state.schedulesActionLoading,
                        canSubmit = canSubmit,
                        onSubmit = {
                            val timing = when (mode) {
                                ScheduleTimingMode.AfterMinutes ->
                                    afterMinutesValue?.let { ScheduleTimingInput.AfterMinutes(it) }

                                ScheduleTimingMode.Daily ->
                                    dailyTimeValue?.let { ScheduleTimingInput.Daily(it) }
                            }
                            if (timing == null) {
                                formError = "Enter a valid time for the schedule."
                            } else {
                                formError = null
                                submitted = true
                                state.createSchedule(sessionId, title, instruction, timing)
                            }
                        },
                    )
                }
            }

            if (state.scheduleListState == null && state.schedulesLoading) {
                item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (unsupported) {
                item(key = "unsupported") {
                    ScheduleEmptyState(
                        title = "Schedules aren't available on this host yet",
                        body = "Update the desktop host plugin to schedule prompts from the phone.",
                    )
                }
            } else {
                item(key = "list-header") {
                    HadalCaps(
                        "SCHEDULES",
                        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                    )
                }
                if (schedules.isEmpty() && state.scheduleListState != null) {
                    item(key = "empty") {
                        ScheduleEmptyState(
                            title = "No schedules on this host",
                            body = "Create one above; scheduled runs happen on the desktop and " +
                                "show up here.",
                        )
                    }
                } else {
                    items(schedules, key = { "schedule:${it.id}" }) { schedule ->
                        ScheduleCard(
                            schedule = schedule,
                            actionLoading = state.schedulesActionLoading,
                            onEdit = { editing = schedule },
                            onDelete = { deleting = schedule },
                        )
                    }
                }
            }
        }
    }

    if (sessionPickerOpen) {
        SessionPickerDialog(
            sessions = sessions,
            selectedId = sessionId,
            titleFor = { session -> state.displayTitle(session) },
            onPick = { id ->
                sessionId = id
                formError = null
                sessionPickerOpen = false
            },
            onDismiss = { sessionPickerOpen = false },
        )
    }

    editing?.let { schedule ->
        EditScheduleDialog(
            schedule = schedule,
            actionLoading = state.schedulesActionLoading,
            onDismiss = { editing = null },
            onSubmit = { title, instruction, timing ->
                state.updateSchedule(schedule.id, title, instruction, timing)
                editing = null
            },
        )
    }

    deleting?.let { schedule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Delete schedule?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "\"${schedule.title}\" will be removed from this host. The session and its " +
                        "history are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Delete",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.deleteSchedule(schedule.id)
                            deleting = null
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
                        .clickable { deleting = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

@Composable
private fun CreateScheduleCard(
    sessions: List<Session>,
    selectedSession: Session?,
    sessionPickerOpenRequest: () -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    instruction: String,
    onInstructionChange: (String) -> Unit,
    mode: ScheduleTimingMode,
    onModeChange: (ScheduleTimingMode) -> Unit,
    afterMinutes: String,
    onAfterMinutesChange: (String) -> Unit,
    dailyTime: String,
    onDailyTimeChange: (String) -> Unit,
    timingValid: Boolean,
    formError: String?,
    actionLoading: Boolean,
    canSubmit: Boolean,
    onSubmit: () -> Unit,
) {
    HadalCard {
        HadalCaps("NEW SCHEDULE")
        Spacer(Modifier.height(8.dp))
        FieldLabel("SESSION")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = sessionPickerOpenRequest)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedSession?.title?.takeIf { it.isNotBlank() } ?: "Choose a session…",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (selectedSession == null) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (sessions.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "No visible sessions to schedule yet.",
                style = MaterialTheme.typography.labelSmall,
                color = Warning,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Title", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = instruction,
            onValueChange = onInstructionChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            label = { Text("Instruction", style = MaterialTheme.typography.bodySmall) },
            placeholder = {
                Text(
                    "What should the agent do?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(10.dp))
        FieldLabel("RUN")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == ScheduleTimingMode.AfterMinutes) {
                HadalPrimaryButton(
                    text = "After minutes",
                    onClick = { onModeChange(ScheduleTimingMode.AfterMinutes) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                HadalGhostButton(
                    text = "After minutes",
                    onClick = { onModeChange(ScheduleTimingMode.AfterMinutes) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (mode == ScheduleTimingMode.Daily) {
                HadalPrimaryButton(
                    text = "Daily time",
                    onClick = { onModeChange(ScheduleTimingMode.Daily) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                HadalGhostButton(
                    text = "Daily time",
                    onClick = { onModeChange(ScheduleTimingMode.Daily) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when (mode) {
            ScheduleTimingMode.AfterMinutes -> OutlinedTextField(
                value = afterMinutes,
                onValueChange = onAfterMinutesChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text("Minutes from now", style = MaterialTheme.typography.bodySmall)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(8.dp),
                isError = afterMinutes.isNotBlank() && !timingValid,
            )

            ScheduleTimingMode.Daily -> OutlinedTextField(
                value = dailyTime,
                onValueChange = onDailyTimeChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = {
                    Text("Daily at (HH:MM, 24h)", style = MaterialTheme.typography.bodySmall)
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(8.dp),
                isError = dailyTime.isNotBlank() && !timingValid,
            )
        }
        if (!timingValid) {
            Spacer(Modifier.height(3.dp))
            Text(
                if (mode == ScheduleTimingMode.AfterMinutes) {
                    "Enter 1–${ScheduleTiming.MAX_AFTER_MINUTES} minutes."
                } else {
                    "Use 24h HH:MM, for example 08:30."
                },
                style = MaterialTheme.typography.labelSmall,
                color = Warning,
            )
        }
        formError?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = Danger,
            )
        }
        Spacer(Modifier.height(10.dp))
        HadalPrimaryButton(
            text = if (actionLoading) "Creating…" else "Create schedule",
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ScheduleCard(
    schedule: SessionSchedule,
    actionLoading: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    HadalCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    schedule.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                schedule.sessionTitle?.let { sessionTitle ->
                    Text(
                        sessionTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (schedule.instruction.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        schedule.instruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScheduleStatusChip(schedule.status)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Edit",
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable(enabled = !actionLoading, onClick = onEdit)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actionLoading) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        "Delete",
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable(enabled = !actionLoading, onClick = onDelete)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actionLoading) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            Danger
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Host summaries are rendered verbatim, so kinds this app doesn't create (every/weekly/
        // cron/at) still show a useful line: "<KIND> · <host summary>".
        val timingLabel = buildString {
            if (schedule.kind.isNotBlank()) append(schedule.kind.trim().uppercase())
            if (schedule.summary.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(schedule.summary.trim())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (timingLabel.isNotEmpty()) {
                Text(
                    timingLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
            }
            scheduleWhenLabel(schedule.nextRunAt)?.let { next ->
                Text(
                    "Next · $next",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ScheduleStatusChip(status: String) {
    val normalized = status.trim().lowercase()
    val color = when {
        normalized in setOf("active", "running", "armed", "scheduled") ->
            MaterialTheme.colorScheme.primary

        normalized in setOf("paused", "disabled") -> Warning
        normalized in setOf("error", "failed") -> Danger
        normalized == "complete" || normalized == "done" -> Success
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        normalized.ifBlank { "unknown" }.uppercase(),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun ScheduleEmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "◷",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun EditScheduleDialog(
    schedule: SessionSchedule,
    actionLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String, ScheduleTimingInput?) -> Unit,
) {
    val editable = editableScheduleTiming(schedule.kind)
    var title by remember(schedule.id) { mutableStateOf(schedule.title) }
    var instruction by remember(schedule.id) { mutableStateOf(schedule.instruction) }
    var mode by remember(schedule.id) {
        mutableStateOf(
            if (editable == ScheduleEditableTiming.Daily) {
                ScheduleTimingMode.Daily
            } else {
                ScheduleTimingMode.AfterMinutes
            },
        )
    }
    var afterMinutes by remember(schedule.id) { mutableStateOf("30") }
    var dailyTime by remember(schedule.id) { mutableStateOf("09:00") }
    // Only send a timing change when the user actually edits timing. Otherwise a
    // title/instruction edit would reschedule the reminder (and re-home a daily
    // rule into the phone's timezone).
    var timingDirty by remember(schedule.id) { mutableStateOf(false) }
    val afterValue = ScheduleTiming.parseAfterMinutes(afterMinutes)
    val dailyValue = ScheduleTiming.parseDailyTime(dailyTime)
    val timingValid = when (editable) {
        ScheduleEditableTiming.Unsupported -> true
        else -> when (mode) {
            ScheduleTimingMode.AfterMinutes -> afterValue != null
            ScheduleTimingMode.Daily -> dailyValue != null
        }
    }
    val canSubmit = !actionLoading &&
        title.isNotBlank() &&
        instruction.isNotBlank() &&
        timingValid
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Edit schedule",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it.take(4_000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("Instruction", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
                FieldLabel("TIMING")
                if (editable == ScheduleEditableTiming.Unsupported) {
                    Text(
                        schedule.summary.ifBlank {
                            schedule.kind.ifBlank { "Host-managed" }.uppercase()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Timing for this ${schedule.kind.ifBlank { "host" }} schedule is managed " +
                            "on the host. You can still edit the title and instruction.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mode == ScheduleTimingMode.AfterMinutes) {
                            HadalPrimaryButton(
                                text = "After minutes",
                                onClick = { mode = ScheduleTimingMode.AfterMinutes },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            HadalGhostButton(
                                text = "After minutes",
                                onClick = {
                                    mode = ScheduleTimingMode.AfterMinutes
                                    timingDirty = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (mode == ScheduleTimingMode.Daily) {
                            HadalPrimaryButton(
                                text = "Daily time",
                                onClick = {
                                    mode = ScheduleTimingMode.Daily
                                    timingDirty = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            HadalGhostButton(
                                text = "Daily time",
                                onClick = {
                                    mode = ScheduleTimingMode.Daily
                                    timingDirty = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    when (mode) {
                        ScheduleTimingMode.AfterMinutes -> OutlinedTextField(
                            value = afterMinutes,
                            onValueChange = {
                                afterMinutes = it.filter { ch -> ch.isDigit() }.take(7)
                                timingDirty = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = {
                                Text("Minutes from now", style = MaterialTheme.typography.bodySmall)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            isError = afterMinutes.isNotBlank() && !timingValid,
                        )

                        ScheduleTimingMode.Daily -> OutlinedTextField(
                            value = dailyTime,
                            onValueChange = {
                                dailyTime = it.take(5)
                                timingDirty = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = {
                                Text(
                                    "Daily at (HH:MM, 24h)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            isError = dailyTime.isNotBlank() && !timingValid,
                        )
                    }
                    if (!timingValid) {
                        Text(
                            if (mode == ScheduleTimingMode.AfterMinutes) {
                                "Enter 1–${ScheduleTiming.MAX_AFTER_MINUTES} minutes."
                            } else {
                                "Use 24h HH:MM, for example 08:30."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Warning,
                        )
                    }
                    if (schedule.summary.isNotBlank()) {
                        Text(
                            "Current: ${schedule.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Text(
                if (actionLoading) "Saving…" else "Save",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canSubmit) {
                        val timing = if (!timingDirty || editable == ScheduleEditableTiming.Unsupported) {
                            null
                        } else {
                            when (editable) {
                                ScheduleEditableTiming.AfterMinutes ->
                                    afterValue?.let { ScheduleTimingInput.AfterMinutes(it) }

                                ScheduleEditableTiming.Daily ->
                                    dailyValue?.let { ScheduleTimingInput.Daily(it) }

                                ScheduleEditableTiming.Unsupported -> null
                            }
                        }
                        if (timingValid) onSubmit(title, instruction, timing)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

@Composable
private fun SessionPickerDialog(
    sessions: List<Session>,
    selectedId: String,
    titleFor: (Session) -> String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Choose a session",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(sessions, key = { "pick:${it.id}" }) { session ->
                    val title = titleFor(session)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onPick(session.id) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session.id == selectedId) {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "Cancel",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

private val SCHEDULE_WHEN_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d · HH:mm", Locale.US).withZone(ZoneId.systemDefault())

private fun scheduleWhenLabel(timestamp: String?): String? {
    val raw = timestamp?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { SCHEDULE_WHEN_FORMAT.format(Instant.parse(raw)) }.getOrElse { raw }
}
