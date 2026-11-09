package dev.dsh.mobile.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Shared JSON codec for the DSH Mobile API v1.
 *
 * The host is allowed to grow fields inside a v1 payload, so unknown keys are ignored
 * and nulls are coerced to defaults wherever possible.
 */
val DshJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

// region pairing / health

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val version: String = "",
    val apiVersion: Int = 1,
    val paired: Boolean = false,
)

@Serializable
data class PairCompleteRequest(
    val code: String,
    val deviceName: String,
    val platform: String = "android",
    val publicKey: String? = null,
)

@Serializable
data class HostInfoDto(
    val name: String = "",
    val apiVersion: Int = 1,
    val version: String = "",
)

@Serializable
data class PairCompleteResponse(
    val token: String = "",
    val deviceId: String = "",
    val host: HostInfoDto? = null,
    val workspaces: List<WorkspaceDto> = emptyList(),
)

@Serializable
data class WorkspaceDto(
    val id: String = "",
    val name: String = "",
    val path: String = "",
)

@Serializable
data class HostDto(
    val name: String = "",
    val version: String = "",
    val workspaces: List<WorkspaceDto> = emptyList(),
)

// endregion

// region sessions

/** Optional goal summary on a session summary (frozen contract: `goal` may be absent/null). */
@Serializable
data class GoalSummaryDto(
    val phase: String = "",
    val roundsStarted: Int = 0,
    val maxGoalRounds: Int = 0,
    /** Optional goal objective (trimmed, may be ''/absent on older summaries). */
    val objective: String = "",
)

/** Optional delegation counts on a session summary (host 0.3.4+); absent when none. */
@Serializable
data class DelegationSummaryDto(
    val total: Int = 0,
    val working: Int = 0,
)

@Serializable
data class SessionSummaryDto(
    val id: String = "",
    val title: String = "",
    /** Nullable per contract: a session may outlive / not resolve its workspace. */
    val workspaceId: String? = null,
    val status: String = "idle",
    val preview: String = "",
    val updatedAt: String = "",
    val unread: Boolean = false,
    val live: Boolean = false,
    /** Current goal badge data; null/absent when the session has no goal. */
    val goal: GoalSummaryDto? = null,
    /** Optional lineage/preset context (host 0.3.0+); omitted when unknown/not visible. */
    val parentSessionId: String? = null,
    val agentPreset: String? = null,
    /** Optional child-session delegation counts; null/absent for old hosts and no children. */
    val delegation: DelegationSummaryDto? = null,
)

@Serializable
data class SessionsResponse(
    val sessions: List<SessionSummaryDto> = emptyList(),
)

// region usage

@Serializable
data class UsageTotalsDto(
    val uncachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val totalTokens: Long = 0,
)

@Serializable
data class SessionUsageDto(
    val sessionId: String = "",
    val title: String = "",
    val updatedAt: String = "",
    val uncachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)

@Serializable
data class BalanceInfoDto(
    val currency: String = "",
    val totalBalance: String = "0",
    val grantedBalance: String = "0",
    val toppedUpBalance: String = "0",
)

@Serializable
data class DeepSeekBalanceDto(
    val available: Boolean = false,
    val balances: List<BalanceInfoDto> = emptyList(),
)

@Serializable
data class UsageDto(
    val generatedAt: String = "",
    val totals: UsageTotalsDto = UsageTotalsDto(),
    val sessions: List<SessionUsageDto> = emptyList(),
    val balance: DeepSeekBalanceDto? = null,
    val balanceError: String? = null,
)

@Serializable
data class HiddenSessionsDto(
    val ids: List<String> = emptyList(),
    val updatedAt: String = "",
)

@Serializable
data class HiddenSessionsRequest(
    val hiddenIds: List<String>,
)

// endregion

// region session management (rename / archive)

@Serializable
data class RenameSessionRequest(
    val title: String,
)

@Serializable
data class RenameSessionResponse(
    val ok: Boolean = false,
    val title: String = "",
    /** Optional host sequence number for the renamed session. */
    val seq: Int? = null,
)

@Serializable
data class SessionArchiveResponse(
    val ok: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
data class ArchivedSessionsResponse(
    val sessions: List<SessionSummaryDto> = emptyList(),
    /** False when the host plugin doesn't expose the archive bridge (501/none). */
    val supported: Boolean = true,
)

// endregion

@Serializable
data class ToolDto(
    val name: String = "",
    val detail: String = "",
    val state: String = "done",
    /** Optional text output (host 0.2.7+); capped by the host and marked when truncated. */
    val output: String = "",
)

@Serializable
data class MessageDto(
    val id: String = "",
    val role: String = "assistant",
    val text: String = "",
    val createdAt: String = "",
    val streaming: Boolean = false,
    val tool: ToolDto? = null,
    /** Historical attachments read back with the message; absent/empty for plain messages. */
    val attachments: List<MessageAttachmentDto> = emptyList(),
    /** Fork boundary carried by host messages; null for local/optimistic rows. */
    val seq: Int? = null,
    /** Optional additive source (host 0.3.6+): "schedule" marks a scheduled prompt. */
    val source: String? = null,
)

/** One attachment on a historical message (frozen read-back contract). */
@Serializable
data class MessageAttachmentDto(
    val id: String = "",
    /** "image" or "file". */
    val kind: String = "file",
    val name: String = "",
    val mimeType: String = "",
    val bytes: Long = 0L,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SessionDetailResponse(
    val session: SessionSummaryDto? = null,
    val messages: List<MessageDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class CreateSessionRequest(
    val workspaceId: String,
    val preset: String? = null,
)

@Serializable
data class CreateSessionResponse(
    val session: SessionSummaryDto? = null,
)

/** One agent preset from GET /presets. */
@Serializable
data class PresetDto(
    val id: String = "",
    /** Human label; falls back to [id] when absent. */
    val name: String? = null,
    val description: String? = null,
    val isDefault: Boolean = false,
    /** True when the host knows this preset but it cannot be used. */
    val broken: Boolean = false,
)

/** One installed plugin package from GET /plugins. */
@Serializable
data class PluginPackageDto(
    val name: String = "",
    val version: String? = null,
    val specifier: String? = null,
    val local: Boolean = false,
)

@Serializable
data class PluginsResponse(
    val supported: Boolean = true,
    val profile: String? = null,
    val packages: List<PluginPackageDto> = emptyList(),
    val bundles: List<String> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class PresetsResponse(
    val presets: List<PresetDto> = emptyList(),
    /** False when the host plugin has no presets service (old host). */
    val supported: Boolean = true,
    val defaultId: String? = null,
)

// region host-wide message search

@Serializable
data class SearchHitDto(
    val sessionId: String = "",
    val sessionTitle: String? = null,
    val messageId: String = "",
    val role: String = "",
    val snippet: String = "",
    val createdAt: String? = null,
    /** Optional event sequence for jump-to-message (host 0.3.5+); absent when unknown. */
    val seq: Int? = null,
)

@Serializable
data class SearchResponse(
    val hits: List<SearchHitDto> = emptyList(),
    val supported: Boolean = true,
    val truncated: Boolean = false,
    /** Echo of the filters the host applied, when it reports them. */
    val filters: SearchFiltersDto? = null,
    /** Pagination echo; old hosts default to offset 0 with no further pages. */
    val offset: Int = 0,
    val nextOffset: Int? = null,
    val hasMore: Boolean = false,
    /** Host 0.2.7 scan coverage; old hosts omit these and default to "complete/unknown". */
    val scanIncomplete: Boolean = false,
    val scannedSessions: Int = 0,
    val totalSessions: Int = 0,
)

@Serializable
data class SearchFiltersDto(
    val roles: List<String> = emptyList(),
    val since: String? = null,
    val until: String? = null,
)

/**
 * Query params for GET /search. Roles are comma-separated (max 3); since/until are optional ISO
 * instants. `offset` is only sent for later pages so old hosts see exactly today's first request.
 * Kept pure for tests.
 */
internal fun hostSearchQueryParams(
    query: String,
    limit: Int = 50,
    role: String? = null,
    since: String? = null,
    until: String? = null,
    offset: Int = 0,
): Map<String, String> = buildMap {
    put("q", query)
    put("limit", limit.toString())
    role?.takeIf { it.isNotBlank() }?.let { put("role", it) }
    since?.takeIf { it.isNotBlank() }?.let { put("since", it) }
    until?.takeIf { it.isNotBlank() }?.let { put("until", it) }
    if (offset > 0) put("offset", offset.toString())
}

// endregion

// region push (ntfy)

@Serializable
data class PushConfigDto(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val server: String = "",
    val topic: String = "",
    /** Whether a token is stored on the host; the token itself is never returned. */
    val hasToken: Boolean = false,
    /** Host 0.3.3+ push-suppression summary; absent on old hosts. */
    val suppression: PushSuppressionSummaryDto? = null,
)

/**
 * Unified public suppression summary (GET /push and PUT /push/suppress response): no session ids,
 * just the count plus whether the host accepted the timezone.
 */
@Serializable
data class PushSuppressionSummaryDto(
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    val timeZone: String = "",
    val timeZoneValid: Boolean = false,
    val mutedCount: Int = 0,
)

/** PUT /push/suppress request body (full state; the response is the summary above). */
@Serializable
data class PushSuppressionDto(
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    /** Device IANA zone id, e.g. "America/New_York". */
    val timeZone: String = "",
    val mutedSessionIds: List<String> = emptyList(),
)

/** PUT /push; omitted server/topic/token keep their current host values. */
@Serializable
data class PushUpdateRequest(
    val enabled: Boolean,
    val server: String? = null,
    val topic: String? = null,
    /** Blank/omitted means "keep the stored token". */
    val token: String? = null,
)

@Serializable
data class PushTestResponse(
    val ok: Boolean = false,
)

// endregion

// region approval policy

@Serializable
data class ApprovalPolicyDto(
    val supported: Boolean = true,
    val live: Boolean = false,
    /** "ask" or "never". */
    val policy: String = "ask",
)

@Serializable
data class ApprovalPolicyUpdateRequest(
    val policy: String,
)

// endregion

// region paired devices

@Serializable
data class DeviceDto(
    val deviceId: String = "",
    val deviceName: String = "",
    val platform: String = "",
    val pairedAt: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
data class DevicesResponse(
    val supported: Boolean = true,
    val devices: List<DeviceDto> = emptyList(),
    /** The device making this request ("This device"); never a token or hash. */
    val currentDeviceId: String = "",
)

@Serializable
data class RevokeDeviceResponse(
    val ok: Boolean = false,
    val revoked: Boolean = false,
)

// endregion

// region model + thinking picker

@Serializable
data class ModelEffortDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
)

@Serializable
data class ModelReasoningDto(
    val efforts: List<ModelEffortDto> = emptyList(),
    val defaultEffort: String? = null,
)

@Serializable
data class ModelDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val reasoning: ModelReasoningDto? = null,
)

@Serializable
data class ModelGroupDto(
    val id: String = "",
    val name: String = "",
    val models: List<ModelDto> = emptyList(),
)

@Serializable
data class ModelSelectionDto(
    val provider: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
)

@Serializable
data class ModelFailureDto(
    val id: String = "",
    val name: String = "",
    val message: String = "",
)

@Serializable
data class ModelsResponse(
    val supported: Boolean = true,
    val default: ModelSelectionDto? = null,
    val groups: List<ModelGroupDto> = emptyList(),
    val failures: List<ModelFailureDto> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class SessionModelResponse(
    val supported: Boolean = true,
    val live: Boolean = false,
    val selection: ModelSelectionDto? = null,
    /** "session" or "default". */
    val source: String = "default",
)

@Serializable
data class ModelSelectionRequest(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

// endregion

// region host queue

@Serializable
data class HostQueueItemDto(
    val itemId: String = "",
    val text: String = "",
    /** "next-turn" or "next-step". */
    val target: String = "next-turn",
    val attachmentCount: Int = 0,
    val createdAt: String? = null,
)

@Serializable
data class HostQueueResponse(
    val supported: Boolean = true,
    val live: Boolean = false,
    val items: List<HostQueueItemDto> = emptyList(),
)

@Serializable
data class HostQueueActionRequest(
    /** "edit", "remove" or "steer". */
    val action: String,
    /** Required for edit. */
    val text: String? = null,
)

@Serializable
data class HostQueueActionResponse(
    val accepted: Boolean = false,
)

// endregion

// region session skills

/** One skill registered on the host for a session. */
@Serializable
data class SkillDto(
    val name: String = "",
    val description: String = "",
)

/** GET /sessions/{id}/skills; old hosts / missing `supported` default to unsupported. */
@Serializable
data class SkillsResponse(
    val supported: Boolean = false,
    val skills: List<SkillDto> = emptyList(),
)

// endregion

// region session todos (plan)

/** One plan item; statuses are `pending|in_progress|completed`. */
@Serializable
data class TodoDto(
    val content: String = "",
    val status: String = "pending",
)

/** Host-provided counts; may be absent (derive from [todos] in that case). */
@Serializable
data class TodoCountsDto(
    val pending: Int = 0,
    val inProgress: Int = 0,
    val completed: Int = 0,
)

/** GET /sessions/{id}/todos; old hosts 404/unsupported and missing `supported` = unsupported. */
@Serializable
data class TodosResponse(
    val supported: Boolean = false,
    val updatedAt: String? = null,
    val counts: TodoCountsDto? = null,
    val todos: List<TodoDto> = emptyList(),
)

// endregion

// region session team

/** One agent-team member and its current phase. */
@Serializable
data class TeamMemberDto(
    val name: String = "",
    val phase: String = "",
)

/** One active team task; completed tasks appear only in counts. */
@Serializable
data class TeamTaskDto(
    val id: String = "",
    val subject: String = "",
    val status: String = "pending",
    val blockedBy: List<String> = emptyList(),
)

@Serializable
data class TeamCountsDto(
    val pending: Int = 0,
    val inProgress: Int = 0,
    val blocked: Int = 0,
    val completed: Int = 0,
)

/** GET /sessions/{id}/team; old hosts 404/unsupported and missing `supported` = unsupported. */
@Serializable
data class TeamResponse(
    val supported: Boolean = false,
    val members: List<TeamMemberDto> = emptyList(),
    val tasks: List<TeamTaskDto> = emptyList(),
    val counts: TeamCountsDto? = null,
)

// endregion

// region session trajectory

/** One read-only journal event; newest-first sequence order from the host. */
@Serializable
data class TrajectoryEventDto(
    val seq: Int = 0,
    val time: String = "",
    val type: String = "",
    val summary: String = "",
    /** Optional secondary cue, e.g. "error"/"ok"; never the only signal in the UI. */
    val status: String? = null,
)

/** GET /sessions/{id}/trajectory?limit=&beforeSeq=; old hosts 404/unsupported. */
@Serializable
data class TrajectoryResponse(
    val supported: Boolean = false,
    val events: List<TrajectoryEventDto> = emptyList(),
    val nextBeforeSeq: Int? = null,
    val hasMore: Boolean = false,
)

/**
 * Query params for one trajectory page. `beforeSeq` is only sent for older pages (null/<=0 for the
 * newest page); limit is clamped to a sane 1..200. Kept pure for tests.
 */
internal fun trajectoryQueryParams(
    limit: Int = 100,
    beforeSeq: Int? = null,
): Map<String, String> = buildMap {
    put("limit", limit.coerceIn(1, 200).toString())
    beforeSeq?.takeIf { it > 0 }?.let { put("beforeSeq", it.toString()) }
}

// endregion

// region workspace files (read-only)

/** One entry in a workspace directory listing; [kind] is "dir" or "file". */
@Serializable
data class FileEntryDto(
    val name: String = "",
    val kind: String = "file",
    /** Host-relative path (never absolute); sanitized for display by the repository. */
    val path: String = "",
    val bytes: Long? = null,
    val mimeType: String? = null,
    val modifiedAt: String? = null,
)

/** GET /sessions/{id}/files?path= — dirs first, host-capped at 500 entries. */
@Serializable
data class FilesResponse(
    val supported: Boolean = false,
    val path: String = "",
    val parent: String? = null,
    val entries: List<FileEntryDto> = emptyList(),
)

/** GET /sessions/{id}/files/content?path= — text preview; binary/oversize returns 415. */
@Serializable
data class FileContentDto(
    val name: String = "",
    val path: String = "",
    val bytes: Long = 0,
    val mimeType: String = "",
    val text: String = "",
    val truncated: Boolean = false,
)

/** Query map for the workspace file endpoints; an empty path means the workspace root. */
internal fun filesQueryParams(path: String?): Map<String, String> =
    buildMap { path?.takeIf { it.isNotBlank() }?.let { put("path", it) } }

// endregion

// region background jobs

/** One read-only background tool job. */
@Serializable
data class JobDto(
    val id: String = "",
    val kind: String = "",
    val label: String = "",
    val status: String = "pending",
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val durationMs: Long? = null,
    val detail: String? = null,
)

@Serializable
data class JobCountsDto(
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
)

/** GET /sessions/{id}/jobs; old hosts 404/unsupported and missing `supported` = unsupported. */
@Serializable
data class JobsResponse(
    val supported: Boolean = false,
    val counts: JobCountsDto? = null,
    val jobs: List<JobDto> = emptyList(),
)

// endregion

// region context window

/** Optional context breakdown; any field may be absent on older hosts. */
@Serializable
data class ContextBreakdownDto(
    val systemTokens: Long? = null,
    val toolsTokens: Long? = null,
    val messageTokens: Long? = null,
)

/** GET /sessions/{id}/context; old hosts 404/unsupported and missing `supported` = unsupported. */
@Serializable
data class ContextResponse(
    val supported: Boolean = false,
    val surfaceTokens: Long? = null,
    val contextWindow: Long? = null,
    val pressureTokens: Long? = null,
    val percent: Double? = null,
    val breakdown: ContextBreakdownDto? = null,
    val sampledAt: String? = null,
)

// endregion

// region fork conversation

@Serializable
data class ForkRequest(
    /** Fork boundary; null forks at the host's latest completed turn. */
    val atSeq: Int? = null,
)

@Serializable
data class ForkResponse(
    val sessionId: String = "",
    val forkedFrom: String = "",
    val atSeq: Int? = null,
)

// endregion

// region recovery access

@Serializable
data class RecoveryStatusDto(
    val supported: Boolean = true,
    val configured: Boolean = false,
    val createdAt: String? = null,
)

@Serializable
data class RecoveryCodeDto(
    val code: String = "",
    val createdAt: String = "",
)

/** POST /pair/recovery (unauthenticated): restores a token with a recovery code. */
@Serializable
data class RecoveryPairRequest(
    val code: String,
    val deviceName: String? = null,
    val platform: String? = null,
)

@Serializable
data class RecoveryPairResponse(
    val token: String = "",
    val device: DeviceDto? = null,
    /** A fresh rotated code, if the host issues one on use. */
    val recoveryCode: String? = null,
)

// endregion

// region tailscale one-tap pairing

@Serializable
data class PairMethodsDto(
    val desktopCode: Boolean = true,
    val recovery: Boolean = false,
    val tailscaleAutoPair: Boolean = false,
    val tailscaleLoginRequired: Boolean = false,
)

/** POST /pair/tailscale (unauthenticated): the tailnet identity replaces the code. */
@Serializable
data class TailscalePairRequest(
    val deviceName: String? = null,
    val platform: String? = null,
)

/** Same shape as /pair/complete, plus the pairing method and verified tailnet identity. */
@Serializable
data class TailscalePairResponse(
    val token: String = "",
    val deviceId: String = "",
    val host: HostInfoDto? = null,
    val workspaces: List<WorkspaceDto> = emptyList(),
    val pairingMethod: String = "tailscale",
    val identity: String? = null,
)

// endregion

@Serializable
data class PromptRequest(
    val text: String,
    /**
     * Attachment ids uploaded via POST /sessions/{id}/attachments. Omitted when empty; prompt text
     * may be empty when at least one id is present (contract v0.2.2).
     */
    val attachmentIds: List<String>? = null,
)

/** POST /sessions/{id}/attachments body. One attachment per request (contract v0.2.2). */
@Serializable
data class AttachmentUploadRequest(
    val name: String,
    val mimeType: String,
    /** Standard base64 (no line wrapping) of the raw file bytes. */
    val dataBase64: String,
)

@Serializable
data class AttachmentDto(
    val attachmentId: String = "",
    val name: String = "",
    val mimeType: String = "",
    val bytes: Long = 0L,
    /** "file" or "image". */
    val kind: String = "file",
)

@Serializable
data class AttachmentUploadResponse(
    val attachment: AttachmentDto? = null,
)

@Serializable
data class PromptAcceptedDto(
    val accepted: Boolean = false,
    val runId: String = "",)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
)

// endregion

// region approvals / questions

@Serializable
data class ApprovalDto(
    val id: String = "",
    val sessionId: String = "",
    val title: String = "",
    val description: String = "",
    val command: String = "",
    val createdAt: String = "",
)

@Serializable
data class ApprovalsResponse(
    val approvals: List<ApprovalDto> = emptyList(),
    /** Host reports whether its approval bridge exists (default true for older hosts). */
    val supported: Boolean = true,
    /** Host keeps approvals on the desktop; the phone may only inspect them. */
    val readOnly: Boolean = false,
)

@Serializable
data class DecisionRequest(
    val decision: String,
    val note: String? = null,
)

@Serializable
data class QuestionDto(
    val id: String = "",
    val sessionId: String = "",
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val createdAt: String = "",
)

@Serializable
data class QuestionsResponse(
    val questions: List<QuestionDto> = emptyList(),
    /** Host reports whether its question bridge exists (default true for older hosts). */
    val supported: Boolean = true,
    /** Host keeps questions on the desktop; the phone may only inspect them. */
    val readOnly: Boolean = false,
)

@Serializable
data class AnswerRequest(
    val answer: String,
    val optionIndex: Int? = null,
)

// endregion

// region SSE events

/**
 * The `data:` line of every SSE event carries this envelope. Event-specific payloads live in
 * [data] and are decoded by event type in the repository.
 */
@Serializable
data class EventEnvelopeDto(
    val id: String? = null,
    val type: String? = null,
    val at: String? = null,
    val sessionId: String? = null,
    val data: JsonObject? = null,
)

@Serializable
data class SessionRemovedEvent(
    val sessionId: String = "",
)

@Serializable
data class MessageAddedEvent(
    val sessionId: String = "",
    val message: MessageDto? = null,
)

@Serializable
data class MessageDeltaEvent(
    val sessionId: String = "",
    val messageId: String = "",
    val text: String = "",
)

@Serializable
data class MessageCompletedEvent(
    val sessionId: String = "",
    val messageId: String = "",
)

@Serializable
data class ApprovalResolvedEvent(
    val approvalId: String = "",
)

@Serializable
data class QuestionResolvedEvent(
    val questionId: String = "",
)

@Serializable
data class HostStatusEvent(
    val connected: Boolean = true,
)

// endregion

// region goals

@Serializable
data class GoalBlockedReasonDto(
    val code: String = "",
    val message: String = "",
)

@Serializable
data class GoalDto(
    val id: String = "",
    val objective: String = "",
    val phase: String = "",
    val maxGoalRounds: Int = 0,
    val roundsStarted: Int = 0,
    val revision: Int = 1,
    val activation: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val blockedReason: GoalBlockedReasonDto? = null,
)

@Serializable
data class SessionGoalResponse(
    val supported: Boolean = true,
    val live: Boolean = false,
    val goal: GoalDto? = null,
)

@Serializable
data class GoalCreateRequest(
    val objective: String,
    val maxGoalRounds: Int? = null,
)

/** POST /sessions/{id}/goal/edit; omitted fields keep their current value. */
@Serializable
data class GoalEditRequest(
    val objective: String? = null,
    val maxGoalRounds: Int? = null,
)

/** Optional stale-state guard for goal actions: POST /sessions/{id}/goal/complete. */
@Serializable
data class GoalRefRequest(
    val id: String? = null,
    val revision: Int? = null,
)

@Serializable
data class GoalChangedEventDto(
    val sessionId: String = "",
    val operation: String = "",
    val goal: GoalDto? = null,
)

// endregion

// region schedules

@Serializable
data class ScheduleTimingDto(
    val kind: String = "",
    val summary: String = "",
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
)

@Serializable
data class ScheduleDto(
    val id: String = "",
    val sessionId: String = "",
    val sessionTitle: String? = null,
    val title: String = "",
    val instruction: String = "",
    val status: String = "",
    val schedule: ScheduleTimingDto? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class SchedulesResponse(
    val schedules: List<ScheduleDto> = emptyList(),
    /** False when the host plugin doesn't expose the schedules bridge (501/none). */
    val supported: Boolean = true,
)

/** POST /sessions/{id}/schedules; exactly one timing field is sent. */
@Serializable
data class ScheduleCreateRequest(
    val title: String,
    val instruction: String,
    val afterSeconds: Long? = null,
    val everySeconds: Long? = null,
    val dailyTime: String? = null,
    /** IANA zone for dailyTime; the host defaults to UTC when omitted. */
    val timeZone: String? = null,
    val at: String? = null,
)

@Serializable
data class ScheduleDeletedResponse(
    val ok: Boolean = false,
    val deleted: Boolean = false,
)

/** PATCH /schedules/{id}; omitted fields keep their current value. */
@Serializable
data class ScheduleUpdateRequest(
    val title: String? = null,
    val instruction: String? = null,
    val afterSeconds: Long? = null,
    val everySeconds: Long? = null,
    val dailyTime: String? = null,
    /** IANA zone for dailyTime; the host defaults to UTC when omitted. */
    val timeZone: String? = null,
    val at: String? = null,
)

// endregion

// region errors

@Serializable
data class ErrorEnvelopeDto(
    val error: ApiErrorDto? = null,
)

@Serializable
data class ApiErrorDto(
    val code: String = "",
    val message: String = "",
)

// endregion
