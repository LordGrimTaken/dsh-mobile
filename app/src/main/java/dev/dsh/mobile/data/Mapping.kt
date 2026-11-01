package dev.dsh.mobile.data

import dev.dsh.mobile.net.ApprovalDto
import dev.dsh.mobile.net.DeepSeekBalanceDto
import dev.dsh.mobile.net.MessageAttachmentDto
import dev.dsh.mobile.net.MessageDto
import dev.dsh.mobile.net.QuestionDto
import dev.dsh.mobile.net.ScheduleDto
import dev.dsh.mobile.net.SessionSummaryDto
import dev.dsh.mobile.net.ToolDto
import dev.dsh.mobile.net.UsageDto
import dev.dsh.mobile.net.UsageTotalsDto
import dev.dsh.mobile.net.WorkspaceDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// region wire -> UI model

internal fun WorkspaceDto.toUi(): Workspace = Workspace(
    id = id,
    name = name.ifBlank { id },
    path = path,
)

internal fun PairingWorkspace.toUi(): Workspace = Workspace(
    id = id,
    name = name.ifBlank { id },
    path = path,
)

internal fun SessionSummaryDto.toUi(
    existing: Session? = null,
    preserveGoalWhenMissing: Boolean = false,
    /**
     * L1: incremental/partial payloads (SSE `session.updated`) preserve known lineage when the
     * optional fields are omitted. Full snapshots/detail refreshes leave this false so a hidden or
     * changed parent/preset is cleared instead of showing stale lineage.
     */
    preserveLineageWhenMissing: Boolean = false,
    /** Partial updates preserve known delegation counts; full refreshes clear them when omitted. */
    preserveDelegationWhenMissing: Boolean = false,
): Session {
    // `session.updated` and session-detail payloads do not carry SessionSummary.goal;
    // a null there means "omitted", not "goal cleared" (that arrives via goal.changed or
    // the next GET /sessions snapshot). Preserve the badge instead of wiping it.
    val keepExistingGoal = preserveGoalWhenMissing && goal == null
    val lineageParent = parentSessionId?.trim()?.takeIf { it.isNotEmpty() }
    val lineagePreset = agentPreset?.trim()?.takeIf { it.isNotEmpty() }
    val delegationCounts = delegation?.let { clampedDelegationCounts(it.total, it.working) }
    return Session(
        id = id,
        title = title.ifBlank { existing?.title ?: "Untitled session" },
        workspaceId = workspaceId.orEmpty(),
        preview = preview.ifBlank { existing?.preview.orEmpty() },
        updated = relativeLabel(updatedAt).ifBlank { existing?.updated ?: "" },
        day = dayLabel(updatedAt),
        status = when (status) {
            "working" -> SessionStatus.Working
            "needs_input" -> SessionStatus.NeedsInput
            else -> SessionStatus.Idle
        },
        unread = unread,
        messages = existing?.messages.orEmpty(),
        goalPhase = if (keepExistingGoal) {
            existing?.goalPhase
        } else {
            goal?.phase?.trim()?.takeIf { it.isNotEmpty() }
        },
        goalRoundsStarted = if (keepExistingGoal) {
            existing?.goalRoundsStarted ?: 0
        } else {
            goal?.roundsStarted ?: 0
        },
        goalMaxRounds = if (keepExistingGoal) {
            existing?.goalMaxRounds ?: 0
        } else {
            goal?.maxGoalRounds ?: 0
        },
        goalObjective = if (keepExistingGoal) {
            existing?.goalObjective
        } else {
            goal?.objective?.trim()?.takeIf { it.isNotEmpty() }
        },
        // Full refresh (default): omitted = cleared. Partial updates opt into preservation.
        parentSessionId = lineageParent
            ?: if (preserveLineageWhenMissing) existing?.parentSessionId else null,
        agentPreset = lineagePreset
            ?: if (preserveLineageWhenMissing) existing?.agentPreset else null,
        delegationTotal = delegationCounts?.first
            ?: if (preserveDelegationWhenMissing) existing?.delegationTotal ?: 0 else 0,
        delegationWorking = delegationCounts?.second
            ?: if (preserveDelegationWhenMissing) existing?.delegationWorking ?: 0 else 0,
    )
}

internal fun ToolDto.toUi(): ToolActivity = ToolActivity(
    name = name.ifBlank { "Tool" },
    detail = detail,
    state = when (state) {
        "running" -> ToolState.Running
        "failed" -> ToolState.Failed
        else -> ToolState.Done
    },
    output = output,
)

internal fun MessageAttachmentDto.toUi(): MessageAttachment = MessageAttachment(
    id = id.ifBlank { name },
    kind = kind.ifBlank { "file" },
    name = name,
    mimeType = mimeType,
    bytes = bytes,
    width = width,
    height = height,
)

internal fun MessageDto.toUi(): Message {
    val clock = clockLabel(createdAt)
    val toolActivity = tool?.toUi()
    val messageAttachments = attachments.map { it.toUi() }
    val eventSeq = seq?.takeIf { it > 0 }
    return when (role) {
        "user" -> UserMessage(
            id = id,
            time = clock,
            text = text,
            messageAttachments = messageAttachments,
            eventSeq = eventSeq,
            scheduled = isScheduledMessageSource(source),
        )
        "system" -> SystemNote(
            id = id,
            time = clock,
            text = text.ifBlank { toolActivity?.detail.orEmpty() },
            eventSeq = eventSeq,
        )
        "tool" -> AssistantMessage(
            id = id,
            time = clock,
            text = text,
            tool = toolActivity,
            messageAttachments = messageAttachments,
            eventSeq = eventSeq,
        )
        else -> AssistantMessage(
            id = id,
            time = clock,
            text = text,
            streaming = streaming,
            tool = toolActivity,
            messageAttachments = messageAttachments,
            eventSeq = eventSeq,
        )
    }
}

internal fun ScheduleDto.toUi(): SessionSchedule = SessionSchedule(
    id = id,
    sessionId = sessionId,
    sessionTitle = sessionTitle?.trim()?.takeIf { it.isNotEmpty() },
    title = title.ifBlank { "Scheduled run" },
    instruction = instruction,
    status = status.ifBlank { "active" },
    kind = schedule?.kind.orEmpty(),
    summary = schedule?.summary.orEmpty(),
    nextRunAt = schedule?.nextRunAt?.takeIf { it.isNotBlank() },
    lastRunAt = schedule?.lastRunAt?.takeIf { it.isNotBlank() },
)

internal fun ApprovalDto.toUi(): Approval = Approval(
    id = id,
    sessionId = sessionId,
    title = title.ifBlank { "Permission needed" },
    description = description,
    command = command,
)

internal fun QuestionDto.toUi(): Question = Question(
    id = id,
    sessionId = sessionId,
    prompt = prompt,
    options = options,
    createdAt = createdAt,
)

internal fun UsageTotalsDto.toUi(): TokenUsage = TokenUsage(
    uncachedInputTokens = uncachedInputTokens,
    outputTokens = outputTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWriteTokens = cacheWriteTokens,
    totalTokens = if (totalTokens > 0) totalTokens else
        uncachedInputTokens + outputTokens + cacheReadTokens + cacheWriteTokens,
)

internal fun DeepSeekBalanceDto.toUi(): DeepSeekBalance = DeepSeekBalance(
    available = available,
    balances = balances.map { BalanceInfo(it.currency, it.totalBalance, it.grantedBalance, it.toppedUpBalance) },
)

internal fun UsageDto.toUi(): HostUsage = HostUsage(
    generatedAt = generatedAt,
    totals = totals.toUi(),
    sessions = sessions.map { session ->
        val sessionTotals = TokenUsage(
            uncachedInputTokens = session.uncachedInputTokens,
            outputTokens = session.outputTokens,
            cacheReadTokens = session.cacheReadTokens,
            cacheWriteTokens = session.cacheWriteTokens,
            totalTokens = session.uncachedInputTokens + session.outputTokens +
                session.cacheReadTokens + session.cacheWriteTokens,
        )
        SessionUsage(
            sessionId = session.sessionId,
            title = session.title.ifBlank { "Untitled session" },
            updatedAt = session.updatedAt,
            usage = sessionTotals,
        )
    },
    balance = balance?.toUi(),
    balanceError = balanceError,
)

// endregion

// region time formatting

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun nowClock(): String = LocalTime.now().format(CLOCK)

internal fun instantOf(iso: String): Instant? {
    if (iso.isBlank()) return null
    return runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
}

internal fun clockLabel(iso: String): String {
    val instant = instantOf(iso) ?: return ""
    return CLOCK.withZone(ZoneId.systemDefault()).format(instant)
}

/** "now", "4m", "2h", "Yesterday", "3d" - display-only, matching the demo row labels. */
internal fun relativeLabel(iso: String): String {
    val instant = instantOf(iso) ?: return ""
    val minutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        minutes < 48 * 60 -> "Yesterday"
        else -> "${minutes / (24 * 60)}d"
    }
}

/** Bucket label used by the session list groups: Today / Yesterday / Earlier. */
internal fun dayLabel(iso: String): String {
    val date = instantOf(iso)?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: return "Earlier"
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        else -> "Earlier"
    }
}

// endregion
