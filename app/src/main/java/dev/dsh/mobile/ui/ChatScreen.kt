package dev.dsh.mobile.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.Approval
import dev.dsh.mobile.data.AssistantMessage
import dev.dsh.mobile.data.AttachmentLimits
import dev.dsh.mobile.data.ConnectionStatus
import dev.dsh.mobile.data.MAX_QUESTION_ANSWER_CHARS
import dev.dsh.mobile.data.MESSAGE_JUMP_HIGHLIGHT_MS
import dev.dsh.mobile.data.MESSAGE_JUMP_MAX_OLDER_LOADS
import dev.dsh.mobile.data.MESSAGE_JUMP_NOT_FOUND_COPY
import dev.dsh.mobile.data.messageJumpIndex
import dev.dsh.mobile.data.messageJumpScrollIndex
import dev.dsh.mobile.data.messageJumpTargetMatches
import dev.dsh.mobile.data.Message
import dev.dsh.mobile.data.MessageAttachment
import dev.dsh.mobile.data.imageAttachmentsForViewer
import dev.dsh.mobile.data.hostQueueCanSteer
import dev.dsh.mobile.data.hostQueueAtCap
import dev.dsh.mobile.data.hostQueueEditable
import dev.dsh.mobile.data.hostQueueItemPreview
import dev.dsh.mobile.data.hostQueueTargetLabel
import dev.dsh.mobile.data.HostQueueItem
import dev.dsh.mobile.data.modelChipLabel
import dev.dsh.mobile.data.modelDefaultEffortId
import dev.dsh.mobile.data.modelDisplayName
import dev.dsh.mobile.data.modelEffortsFor
import dev.dsh.mobile.data.modelFailureForGroup
import dev.dsh.mobile.data.modelSelectionChanged
import dev.dsh.mobile.data.modelSelectionValid
import dev.dsh.mobile.data.normalizeQuestionAnswer
import dev.dsh.mobile.data.queueItemAttachmentLabel
import dev.dsh.mobile.data.sanitizedModelEffort
import dev.dsh.mobile.data.ModelSelection
import dev.dsh.mobile.data.PendingAttachmentRecord
import dev.dsh.mobile.data.PendingAttachmentStore
import dev.dsh.mobile.data.Question
import dev.dsh.mobile.data.PromptSnippet
import dev.dsh.mobile.data.ScheduleTiming
import dev.dsh.mobile.data.ScheduleTimingInput
import dev.dsh.mobile.data.ScheduleTimingMode
import dev.dsh.mobile.data.scheduleTitleFromDraft
import dev.dsh.mobile.data.sessionModelSourceLabel
import dev.dsh.mobile.data.TRANSCRIPT_PDF_EMPTY_COPY
import dev.dsh.mobile.data.TRANSCRIPT_PDF_EXPORTING_COPY
import dev.dsh.mobile.data.TRANSCRIPT_PDF_FAILURE_COPY
import dev.dsh.mobile.data.TRANSCRIPT_PDF_MAX_PAGES
import dev.dsh.mobile.data.TRANSCRIPT_PDF_MARGIN
import dev.dsh.mobile.data.TRANSCRIPT_PDF_PAGE_HEIGHT
import dev.dsh.mobile.data.TRANSCRIPT_PDF_PAGE_WIDTH
import dev.dsh.mobile.data.TRANSCRIPT_PDF_SHARE_FAILURE_COPY
import dev.dsh.mobile.data.TRANSCRIPT_PDF_SUCCESS_COPY
import dev.dsh.mobile.data.transcriptPdfCappedNote
import dev.dsh.mobile.data.transcriptPdfExportableMessages
import dev.dsh.mobile.data.transcriptPdfFileName
import dev.dsh.mobile.data.transcriptPdfFooterLabel
import dev.dsh.mobile.data.transcriptPdfMessageText
import dev.dsh.mobile.data.transcriptPdfMessageTimeLabel
import dev.dsh.mobile.data.transcriptPdfMessagesCapped
import dev.dsh.mobile.data.transcriptPdfRoleLabel
import dev.dsh.mobile.data.sanitizeTranscriptPdfText
import dev.dsh.mobile.data.sanitizedAgentPreset
import dev.dsh.mobile.data.SESSION_OPEN_PARENT_LABEL
import dev.dsh.mobile.data.SESSION_PARENT_UNAVAILABLE
import dev.dsh.mobile.data.sessionForkedFromLabel
import dev.dsh.mobile.data.sessionOpenParentContentDescription
import dev.dsh.mobile.data.sessionParentUi
import dev.dsh.mobile.data.sessionPresetLabel
import dev.dsh.mobile.data.delegationBadgeLabel
import dev.dsh.mobile.data.delegationContentDescription
import dev.dsh.mobile.data.SCHEDULED_MESSAGE_CONTENT_DESCRIPTION
import dev.dsh.mobile.data.SCHEDULED_MESSAGE_LABEL
import dev.dsh.mobile.data.scheduledMessageCopyText
import dev.dsh.mobile.data.scheduledTranscriptHeading
import dev.dsh.mobile.data.SHARE_CARD_ACTION_LABEL
import dev.dsh.mobile.data.SHARE_CARD_ACTION_SUBTITLE
import dev.dsh.mobile.data.SHARE_CARD_BRAND
import dev.dsh.mobile.data.SHARE_CARD_CACHE_DIR
import dev.dsh.mobile.data.SHARE_CARD_CODE_CHARS_PER_LINE
import dev.dsh.mobile.data.SHARE_CARD_FAILURE_TOAST
import dev.dsh.mobile.data.SHARE_CARD_FOOTER_PX
import dev.dsh.mobile.data.SHARE_CARD_HEADER_PX
import dev.dsh.mobile.data.SHARE_CARD_MAX_LINES
import dev.dsh.mobile.data.SHARE_CARD_PADDING_PX
import dev.dsh.mobile.data.SHARE_CARD_PROSE_CHARS_PER_LINE
import dev.dsh.mobile.data.SHARE_CARD_TAGLINE
import dev.dsh.mobile.data.SHARE_CARD_TEXT_SIZE_PX
import dev.dsh.mobile.data.SHARE_CARD_TRUNCATED_NOTE
import dev.dsh.mobile.data.SHARE_CARD_WIDTH_PX
import dev.dsh.mobile.data.parseShareCardSegments
import dev.dsh.mobile.data.sanitizedShareCardInput
import dev.dsh.mobile.data.shareCardFileName
import dev.dsh.mobile.data.shareCardHeightPx
import dev.dsh.mobile.data.shareCardRoleFor
import dev.dsh.mobile.data.shareCardRoleLabel
import dev.dsh.mobile.data.shareCardTimeLabel
import dev.dsh.mobile.data.wrapShareCardText
import dev.dsh.mobile.data.Session
import dev.dsh.mobile.data.SessionGoal
import dev.dsh.mobile.data.SessionGoalState
import dev.dsh.mobile.data.SessionStatus
import dev.dsh.mobile.data.PLAN_ACTION_LABEL
import dev.dsh.mobile.data.PLAN_EMPTY_COPY
import dev.dsh.mobile.data.PLAN_LOAD_FAILED_COPY
import dev.dsh.mobile.data.PLAN_LOADING_COPY
import dev.dsh.mobile.data.PLAN_RETRY_LABEL
import dev.dsh.mobile.data.PLAN_SHEET_TITLE
import dev.dsh.mobile.data.PLAN_UNSUPPORTED_COPY
import dev.dsh.mobile.data.PlanTodoStatus
import dev.dsh.mobile.data.orderedPlanTodos
import dev.dsh.mobile.data.planActionContentDescription
import dev.dsh.mobile.data.planProgressHint
import dev.dsh.mobile.data.planProgressLabel
import dev.dsh.mobile.data.CONTEXT_AMBER_THRESHOLD
import dev.dsh.mobile.data.CONTEXT_BREAKDOWN_HEADING
import dev.dsh.mobile.data.CONTEXT_LOAD_FAILED_COPY
import dev.dsh.mobile.data.CONTEXT_LOADING_COPY
import dev.dsh.mobile.data.CONTEXT_REFRESH_LABEL
import dev.dsh.mobile.data.CONTEXT_RETRY_LABEL
import dev.dsh.mobile.data.CONTEXT_SECTION_TITLE
import dev.dsh.mobile.data.CONTEXT_UNKNOWN_COPY
import dev.dsh.mobile.data.CONTEXT_UNSUPPORTED_COPY
import dev.dsh.mobile.data.CONTEXT_WARNING_AMBER_COPY
import dev.dsh.mobile.data.CONTEXT_WARNING_RED_COPY
import dev.dsh.mobile.data.ContextWarningLevel
import dev.dsh.mobile.data.contextPercent
import dev.dsh.mobile.data.contextPercentA11yLabel
import dev.dsh.mobile.data.contextPercentRaw
import dev.dsh.mobile.data.contextPressureSummary
import dev.dsh.mobile.data.contextSampledLabel
import dev.dsh.mobile.data.contextTokensSummary
import dev.dsh.mobile.data.contextWarningCopy
import dev.dsh.mobile.data.contextWarningLevel
import dev.dsh.mobile.data.formatCompactTokenCount
import dev.dsh.mobile.data.orderedContextBreakdown
import dev.dsh.mobile.data.TRAJECTORY_ACTION_LABEL
import dev.dsh.mobile.data.TRAJECTORY_EMPTY_COPY
import dev.dsh.mobile.data.TRAJECTORY_LOAD_FAILED_COPY
import dev.dsh.mobile.data.TRAJECTORY_LOADING_COPY
import dev.dsh.mobile.data.TRAJECTORY_LOADING_OLDER_COPY
import dev.dsh.mobile.data.TRAJECTORY_LOAD_OLDER_LABEL
import dev.dsh.mobile.data.TRAJECTORY_READ_ONLY_NOTE
import dev.dsh.mobile.data.TRAJECTORY_REFRESH_LABEL
import dev.dsh.mobile.data.TRAJECTORY_RETRY_LABEL
import dev.dsh.mobile.data.TRAJECTORY_SHEET_TITLE
import dev.dsh.mobile.data.TRAJECTORY_UNSUPPORTED_COPY
import dev.dsh.mobile.data.isTrajectoryErrorStatus
import dev.dsh.mobile.data.FILES_ACTION_LABEL
import dev.dsh.mobile.data.FILES_EMPTY_COPY
import dev.dsh.mobile.data.FILES_EMPTY_FILE_COPY
import dev.dsh.mobile.data.FILES_LOAD_FAILED_COPY
import dev.dsh.mobile.data.FILES_LOADING_COPY
import dev.dsh.mobile.data.FILES_OPEN_EXTERNAL_LABEL
import dev.dsh.mobile.data.FILES_PREVIEW_FAILED_COPY
import dev.dsh.mobile.data.FILES_PREVIEW_LOADING_COPY
import dev.dsh.mobile.data.FILES_PREVIEW_NOT_TEXT_COPY
import dev.dsh.mobile.data.FILES_PREVIEW_TRUNCATED_NOTE
import dev.dsh.mobile.data.FILES_REFRESH_LABEL
import dev.dsh.mobile.data.FILES_RETRY_LABEL
import dev.dsh.mobile.data.FILES_SHEET_TITLE
import dev.dsh.mobile.data.FILES_UNSUPPORTED_COPY
import dev.dsh.mobile.data.FILES_UP_LABEL
import dev.dsh.mobile.data.JOBS_ACTION_LABEL
import dev.dsh.mobile.data.JOBS_EMPTY_COPY
import dev.dsh.mobile.data.JOBS_LOAD_FAILED_COPY
import dev.dsh.mobile.data.JOBS_LOADING_COPY
import dev.dsh.mobile.data.JOBS_READ_ONLY_NOTE
import dev.dsh.mobile.data.JOBS_REFRESH_LABEL
import dev.dsh.mobile.data.JOBS_RETRY_LABEL
import dev.dsh.mobile.data.JOBS_SHEET_TITLE
import dev.dsh.mobile.data.JOBS_UNSUPPORTED_COPY
import dev.dsh.mobile.data.formatJobDuration
import dev.dsh.mobile.data.JobStatus
import dev.dsh.mobile.data.jobCountsSummary
import dev.dsh.mobile.data.jobFailureDetail
import dev.dsh.mobile.data.jobRunningHint
import dev.dsh.mobile.data.jobStartedLabel
import dev.dsh.mobile.data.jobsActionContentDescription
import dev.dsh.mobile.data.orderedJobs
import dev.dsh.mobile.data.dirFirstEntries
import dev.dsh.mobile.data.formatFileSize
import dev.dsh.mobile.data.workspacePathLabel
import dev.dsh.mobile.data.WorkspaceEntryKind
import dev.dsh.mobile.data.TEAM_ACTION_LABEL
import dev.dsh.mobile.data.TEAM_AGENTS_HEADING
import dev.dsh.mobile.data.TEAM_EMPTY_COPY
import dev.dsh.mobile.data.TEAM_LOAD_FAILED_COPY
import dev.dsh.mobile.data.TEAM_LOADING_COPY
import dev.dsh.mobile.data.TEAM_RETRY_LABEL
import dev.dsh.mobile.data.TEAM_SHEET_TITLE
import dev.dsh.mobile.data.TEAM_TASKS_HEADING
import dev.dsh.mobile.data.TEAM_UNSUPPORTED_COPY
import dev.dsh.mobile.data.TeamTaskStatus
import dev.dsh.mobile.data.orderedTeamTasks
import dev.dsh.mobile.data.teamActionContentDescription
import dev.dsh.mobile.data.teamActiveHint
import dev.dsh.mobile.data.teamCountsSummary
import dev.dsh.mobile.data.teamMemberPhaseLabel
import dev.dsh.mobile.data.teamTaskBlockedByLabel
import dev.dsh.mobile.data.SKILLS_ACTION_LABEL
import dev.dsh.mobile.data.SKILLS_COPY_FALLBACK_TOAST
import dev.dsh.mobile.data.SKILLS_EMPTY_COPY
import dev.dsh.mobile.data.SKILLS_LOADING_COPY
import dev.dsh.mobile.data.SKILLS_REFRESH_FAILED_COPY
import dev.dsh.mobile.data.SKILLS_RETRY_LABEL
import dev.dsh.mobile.data.SKILLS_SESSION_UNAVAILABLE_COPY
import dev.dsh.mobile.data.SKILLS_SHEET_TITLE
import dev.dsh.mobile.data.SKILLS_UNSUPPORTED_COPY
import dev.dsh.mobile.data.skillPrefillDraft
import dev.dsh.mobile.data.skillPromptPrefill
import dev.dsh.mobile.data.SystemNote
import dev.dsh.mobile.data.TOOL_OUTPUT_COPY_FAILURE
import dev.dsh.mobile.data.TOOL_OUTPUT_COPY_SUCCESS
import dev.dsh.mobile.data.TOOL_OUTPUT_SHARE_FAILURE
import dev.dsh.mobile.data.TOOL_OUTPUT_TRUNCATED_NOTE
import dev.dsh.mobile.data.TOOL_OUTPUT_VIEW_LABEL
import dev.dsh.mobile.data.toolOutputAvailable
import dev.dsh.mobile.data.toolOutputCopyText
import dev.dsh.mobile.data.toolOutputIsTruncated
import dev.dsh.mobile.data.toolOutputShareSubject
import dev.dsh.mobile.data.toolOutputViewerTitle
import dev.dsh.mobile.data.ToolActivity
import dev.dsh.mobile.data.ToolState
import dev.dsh.mobile.data.UserMessage
import dev.dsh.mobile.net.DshApiException
import dev.dsh.mobile.ui.theme.Danger
import dev.dsh.mobile.ui.theme.Success
import dev.dsh.mobile.ui.theme.Warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Pending-attachment model (v0.2.2): files the user picked but has not sent yet.
// ---------------------------------------------------------------------------

/** Upload state of a file/image the user attached but has not sent yet. */
private sealed interface PendingAttachmentState {
    data class Uploading(val fraction: Float = 0f) : PendingAttachmentState
    data class Uploaded(val attachmentId: String) : PendingAttachmentState
    data class Failed(val message: String) : PendingAttachmentState
}

private data class PendingAttachment(
    val localId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val state: PendingAttachmentState,
    /** Retained bytes so a failed upload can be retried without picking the file again. */
    val bytes: ByteArray = ByteArray(0),
    /** Small decoded preview for image attachments; kept after upload for the chip. */
    val thumbnail: ImageBitmap? = decodeImageThumbnail(bytes, mimeType),
)

private data class PickedAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** Decode a small image preview without ever loading the full-resolution bitmap. */
private fun decodeImageThumbnail(bytes: ByteArray, mimeType: String): ImageBitmap? {
    if (bytes.isEmpty() || !mimeType.startsWith("image/")) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}

/** A prompt that failed to send, kept for one-tap retry. */
private data class FailedPrompt(
    val text: String,
    val attachmentIds: List<String>,
)

// ---------------------------------------------------------------------------
// Chat screen — Hadal Mint shell + terminal transcript + composer.
// ---------------------------------------------------------------------------

@Composable
fun ChatScreen(state: AppState, session: Session) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val composerFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var draft by remember(session.id) { mutableStateOf(state.loadDraft(session.id)) }
    var hasOlderMessages by remember(session.id) {
        mutableStateOf(state.hasOlderMessages(session.id))
    }
    var loadingOlderMessages by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.messages.size) {
        hasOlderMessages = state.hasOlderMessages(session.id)
    }
    LaunchedEffect(state.draftRevision) {
        draft = state.loadDraft(session.id)
    }
    LaunchedEffect(session.id) {
        state.consumePendingSharedText()?.let { shared ->
            val existing = state.loadDraft(session.id)
            val combined = if (existing.isBlank()) shared else existing.trimEnd() + "\n\n" + shared
            state.saveDraft(session.id, combined)
            draft = combined
        }
    }
    LaunchedEffect(session.id, state.isLive) {
        state.refreshGoal(session.id)
    }
    // Keep the banner in step with desktop-side pause/resume and automatic goal rounds.
    LaunchedEffect(session.id, state.isLive) {
        if (!state.isLive) return@LaunchedEffect
        while (true) {
            delay(20_000)
            state.refreshGoal(session.id)
        }
    }
    val attachmentStore = remember(session.id) { PendingAttachmentStore(context) }
    var pendingAttachments by remember(session.id) {
        mutableStateOf(
            attachmentStore.load(session.id).mapIndexed { index, record ->
                PendingAttachment(
                    localId = "restored-$index-${record.attachmentId}",
                    name = record.name,
                    mimeType = record.mimeType,
                    sizeBytes = record.sizeBytes,
                    state = PendingAttachmentState.Uploaded(record.attachmentId),
                )
            },
        )
    }
    LaunchedEffect(pendingAttachments) {
        attachmentStore.save(
            session.id,
            pendingAttachments.mapNotNull { attachment ->
                val uploaded = attachment.state as? PendingAttachmentState.Uploaded
                    ?: return@mapNotNull null
                PendingAttachmentRecord(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    attachmentId = uploaded.attachmentId,
                )
            },
        )
    }
    val uploadJobs = remember(session.id) { mutableMapOf<String, Job>() }
    var composerError by remember(session.id) { mutableStateOf<String?>(null) }
    var sendError by remember(session.id) { mutableStateOf<String?>(null) }
    var failedPrompt by remember(session.id) { mutableStateOf<FailedPrompt?>(null) }
    var sending by remember(session.id) { mutableStateOf(false) }
    var showSessionInfo by remember(session.id) { mutableStateOf(false) }
    var showToolActivity by remember(session.id) { mutableStateOf(false) }
    var exportingPdf by remember(session.id) { mutableStateOf(false) }
    var showTemplates by remember(session.id) { mutableStateOf(false) }
    var showSkills by remember(session.id) { mutableStateOf(false) }
    var showPlan by remember(session.id) { mutableStateOf(false) }
    var showTeam by remember(session.id) { mutableStateOf(false) }
    var showTrajectory by remember(session.id) { mutableStateOf(false) }
    var showFiles by remember(session.id) { mutableStateOf(false) }
    var showJobs by remember(session.id) { mutableStateOf(false) }
    var showSchedulePrompt by remember(session.id) { mutableStateOf(false) }
    var schedulingPrompt by remember(session.id) { mutableStateOf(false) }
    var schedulePromptNotice by remember(session.id) { mutableStateOf<String?>(null) }
    var viewerImages by remember(session.id) {
        mutableStateOf<List<MessageAttachment>>(emptyList())
    }
    var viewerInitialId by remember(session.id) { mutableStateOf("") }
    var actionMessage by remember(session.id) { mutableStateOf<Message?>(null) }
    var sharingImageMessageId by remember(session.id) { mutableStateOf<String?>(null) }
    var forkMessage by remember(session.id) { mutableStateOf<Message?>(null) }
    var stickToBottom by remember(session.id) { mutableStateOf(true) }
    var highlightedMessageId by remember(session.id) { mutableStateOf<String?>(null) }
    var jumpNotice by remember(session.id) { mutableStateOf<String?>(null) }
    var autoJumpOlderLoads by remember(session.id) { mutableIntStateOf(0) }
    val jumpTarget = state.messageJumpTargetFor(session.id)
    val transcriptLoading = state.isTranscriptLoading(session.id)
    var showGoalDialog by remember(session.id) { mutableStateOf(false) }
    var goalDraft by remember(session.id) { mutableStateOf("") }
    var goalRoundsDraft by remember(session.id) { mutableStateOf("120") }
    var goalDialogError by remember(session.id) { mutableStateOf<String?>(null) }
    var showEditGoalDialog by remember(session.id) { mutableStateOf(false) }
    var editGoalDraft by remember(session.id) { mutableStateOf("") }
    var editGoalRoundsDraft by remember(session.id) { mutableStateOf("120") }
    var editGoalDialogError by remember(session.id) { mutableStateOf<String?>(null) }
    var showClearGoalDialog by remember(session.id) { mutableStateOf(false) }
    var showCompleteGoalDialog by remember(session.id) { mutableStateOf(false) }
    var showModelPicker by remember(session.id) { mutableStateOf(false) }
    var showHostQueueDialog by remember(session.id) { mutableStateOf(false) }
    // Chat-top chip needs the session model; the dialog loads the catalog on open.
    LaunchedEffect(session.id) { state.loadModelPreview(session.id) }
    // Host queue loads on chat open (lightweight; the strip only shows when items wait).
    LaunchedEffect(session.id) { state.loadHostQueue(session.id) }
    // Skills are cached per session; failure leaves the chat fully usable.
    LaunchedEffect(session.id) { state.loadSessionSkills(session.id) }
    // Plan/todos: 30s cache; opening the chat warms it (failures are silent).
    LaunchedEffect(session.id) { state.loadSessionPlan(session.id) }
    // Team board: 30s cache; opening the chat warms it (failures are silent).
    LaunchedEffect(session.id) { state.loadSessionTeam(session.id) }
    // Background jobs: 30s cache; opening the chat warms it (failures are silent).
    LaunchedEffect(session.id) { state.loadSessionJobs(session.id) }
    // Context-window meter: 30s cache, silent on failure; Session Info refreshes it when opened.
    LaunchedEffect(session.id) { state.loadSessionContext(session.id) }
    LaunchedEffect(showSessionInfo) {
        if (showSessionInfo) state.loadSessionContext(session.id)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }
            .collect { (scrolling, atEnd) ->
                // A user drag releases follow mode; reaching the end re-arms it.
                if (scrolling) stickToBottom = false
                if (atEnd) stickToBottom = true
            }
    }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { tts?.shutdown() }
        }
    }
    val attachmentEnabled = state.isLive
    val maxAttachments = AttachmentLimits.MAX_PENDING_ATTACHMENTS
    val attachmentCapReached = pendingAttachments.size >= maxAttachments
    val attachEnabled = attachmentEnabled && !attachmentCapReached
    val attachHint = when {
        !attachmentEnabled -> "Attachments need a paired host"
        attachmentCapReached -> "Attachment limit reached ($maxAttachments)"
        else -> null
    }
    val approval = state.pendingApproval?.takeIf { it.sessionId == session.id }
    val question = state.pendingQuestion?.takeIf { it.sessionId == session.id }
    val workspace = state.workspaceFor(session.workspaceId)
    val tools = session.messages.mapNotNull { (it as? AssistantMessage)?.tool }
    val tailItemCount = session.messages.size +
        (if (question != null) 1 else 0) +
        (if (approval != null) 1 else 0) +
        (if (session.status == SessionStatus.Working) 1 else 0)
    var findOpen by remember(session.id) { mutableStateOf(false) }
    var findQuery by remember(session.id) { mutableStateOf("") }
    var findIndex by remember(session.id) { mutableStateOf(0) }
    val findFocus = remember { FocusRequester() }
    val findMatches = remember(session.messages, findQuery) {
        if (findQuery.isBlank()) {
            emptyList()
        } else {
            session.messages.mapIndexedNotNull { index, message ->
                if (messageText(message).contains(findQuery, ignoreCase = true)) index else null
            }
        }
    }
    LaunchedEffect(findOpen) {
        if (findOpen) findFocus.requestFocus()
    }
    LaunchedEffect(findMatches) {
        if (findMatches.isNotEmpty() && findOpen) {
            findIndex = 0
            runCatching { listState.scrollToItem(findMatches[0]) }
        }
    }

    val uploadedIds = pendingAttachments.mapNotNull {
        (it.state as? PendingAttachmentState.Uploaded)?.attachmentId
    }
    val uploading = pendingAttachments.any { it.state is PendingAttachmentState.Uploading }
    val canSend = !sending && !uploading && (draft.isNotBlank() || uploadedIds.isNotEmpty())

    /** Shared send path for the composer and "retry": keeps the failed prompt for one tap. */
    val submitPrompt: (String, List<String>) -> Unit = submit@{ text, ids ->
        if (sending || uploading) return@submit
        sending = true
        scope.launch {
            try {
                state.sendMessage(text, ids)
                if (draft == text) {
                    draft = ""
                    state.clearDraft(session.id)
                }
                pendingAttachments = emptyList()
                composerError = null
                sendError = null
                failedPrompt = null
            } catch (error: Exception) {
                sendError = attachmentErrorMessage(state, error)
                failedPrompt = FailedPrompt(text, ids)
            } finally {
                sending = false
            }
        }
    }

    /** Shared upload path: first pick and the Retry action on a failed chip both use it. */
    val uploadPicked: (String, PickedAttachment) -> Unit = { localId, picked ->
        pendingAttachments = pendingAttachments.map { attachment ->
            if (attachment.localId == localId) {
                attachment.copy(state = PendingAttachmentState.Uploading())
            } else {
                attachment
            }
        }
        composerError = null
        uploadJobs.remove(localId)?.cancel()
        val job = scope.launch {
            try {
                val uploaded = state.uploadAttachment(
                    name = picked.name,
                    mimeType = picked.mimeType,
                    bytes = picked.bytes,
                    onProgress = { fraction ->
                        scope.launch {
                            pendingAttachments = pendingAttachments.map { attachment ->
                                if (attachment.localId == localId &&
                                    attachment.state is PendingAttachmentState.Uploading
                                ) {
                                    attachment.copy(
                                        state = PendingAttachmentState.Uploading(fraction),
                                    )
                                } else {
                                    attachment
                                }
                            }
                        }
                    },
                )
                pendingAttachments = pendingAttachments.map { attachment ->
                    if (attachment.localId == localId) {
                        attachment.copy(
                            state = PendingAttachmentState.Uploaded(uploaded.id),
                            bytes = ByteArray(0),
                        )
                    } else {
                        attachment
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val message = attachmentErrorMessage(state, error)
                pendingAttachments = pendingAttachments.map { attachment ->
                    if (attachment.localId == localId) {
                        attachment.copy(state = PendingAttachmentState.Failed(message))
                    } else {
                        attachment
                    }
                }
                composerError = message
            } finally {
                uploadJobs.remove(localId)
            }
        }
        if (job.isActive) uploadJobs[localId] = job
    }

    LaunchedEffect(session.id, state.sharedAttachmentsRevision) {
        val incoming = state.consumeSharedAttachments()
        if (incoming.isEmpty()) return@LaunchedEffect
        if (!attachmentEnabled) {
            composerError = "Attachments need a paired host. Pair your desktop in Settings first."
            return@LaunchedEffect
        }
        var slots = maxAttachments - pendingAttachments.size
        if (slots <= 0) {
            composerError = "You can attach up to $maxAttachments files per prompt."
            return@LaunchedEffect
        }
        composerError = null
        incoming.forEach { item ->
            if (slots <= 0) return@forEach
            val localId = "att-${System.nanoTime()}"
            pendingAttachments = pendingAttachments + PendingAttachment(
                localId = localId,
                name = item.name,
                mimeType = item.mimeType,
                sizeBytes = item.bytes.size.toLong(),
                state = PendingAttachmentState.Uploading(),
                bytes = item.bytes,
            )
            uploadPicked(localId, PickedAttachment(item.name, item.mimeType, item.bytes))
            slots -= 1
        }
    }

    val retryAttachment: (String) -> Unit = { localId ->
        val failed = pendingAttachments.firstOrNull { it.localId == localId }
        if (failed != null && failed.bytes.isNotEmpty()) {
            uploadPicked(
                failed.localId,
                PickedAttachment(failed.name, failed.mimeType, failed.bytes),
            )
        }
    }

    val pickAttachment = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (!attachmentEnabled) {
            composerError = "Attachments need a paired host. Pair your desktop in Settings first."
            return@rememberLauncherForActivityResult
        }
        if (pendingAttachments.size >= maxAttachments) {
            composerError = "You can attach up to $maxAttachments files per prompt."
            return@rememberLauncherForActivityResult
        }
        composerError = null
        scope.launch {
            var index = 0
            for (uri in uris) {
                if (pendingAttachments.size >= maxAttachments) {
                    composerError = "You can attach up to $maxAttachments files per prompt."
                    break
                }
                val result = readPickedAttachment(context, uri)
                val picked = result.getOrNull()
                if (picked == null) {
                    composerError = result.exceptionOrNull()?.message ?: "Couldn't read that file."
                    continue
                }
                val localId = "att-${System.nanoTime()}-${index++}"
                pendingAttachments = pendingAttachments + PendingAttachment(
                    localId = localId,
                    name = picked.name,
                    mimeType = picked.mimeType,
                    sizeBytes = picked.bytes.size.toLong(),
                    state = PendingAttachmentState.Uploading(),
                    bytes = picked.bytes,
                )
                uploadPicked(localId, picked)
            }
        }
    }

    // Continuous dictation: one recognizer stays warm and restarts after every final utterance, so
    // talking through short pauses no longer ends the old one-shot RecognizeSpeech dialog.
    val voice = rememberVoiceDictation()

    fun startDictation() {
        voice.start(
            baseDraft = draft,
            preferOffline = state.voicePreferOffline,
            languageTag = state.voiceLanguageTag,
            onPreview = { preview -> draft = preview },
            onCommit = { committedText ->
                draft = committedText
                state.saveDraft(session.id, committedText)
            },
        )
    }

    val recordAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startDictation()
        } else {
            composerError = "Microphone permission needed for voice dictation. " +
                "Allow the microphone in system settings."
        }
    }

    // A recognizer error the controller cannot retry (permission, missing engine, …) is surfaced
    // through the composer's existing error row and then cleared from the controller.
    LaunchedEffect(voice.state.error) {
        val message = voice.state.error
        if (message != null) {
            composerError = message
            voice.clearError()
        }
    }
    LaunchedEffect(session.id) { voice.stop() }
    val voiceLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(voiceLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        voiceLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { voiceLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var cameraFile by remember(session.id) { mutableStateOf<File?>(null) }
    var cameraUri by remember(session.id) { mutableStateOf<Uri?>(null) }
    val capturePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success: Boolean ->
        val file = cameraFile
        val uri = cameraUri
        cameraFile = null
        cameraUri = null
        if (!success || file == null || uri == null) {
            file?.delete()
            return@rememberLauncherForActivityResult
        }
        if (!attachmentEnabled) {
            file.delete()
            composerError = "Attachments need a paired host. Pair your desktop in Settings first."
            return@rememberLauncherForActivityResult
        }
        if (pendingAttachments.size >= maxAttachments) {
            file.delete()
            composerError = "You can attach up to $maxAttachments files per prompt."
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        file.delete()
        if (bytes == null || bytes.isEmpty()) {
            composerError = "Couldn't read that photo."
            return@rememberLauncherForActivityResult
        }
        if (bytes.size.toLong() > AttachmentLimits.MAX_DECODED_BYTES) {
            composerError = "That photo is over " + formatAttachmentSize(AttachmentLimits.MAX_DECODED_BYTES) + ". Lower the camera resolution and try again."
            return@rememberLauncherForActivityResult
        }
        val localId = "att-${System.nanoTime()}"
        val picked = PickedAttachment(
            name = "photo-${System.currentTimeMillis()}.jpg",
            mimeType = "image/jpeg",
            bytes = bytes,
        )
        composerError = null
        pendingAttachments = pendingAttachments + PendingAttachment(
            localId = localId,
            name = picked.name,
            mimeType = picked.mimeType,
            sizeBytes = picked.bytes.size.toLong(),
            state = PendingAttachmentState.Uploading(),
            bytes = picked.bytes,
        )
        uploadPicked(localId, picked)
    }

    fun launchCamera() {
        if (!attachmentEnabled) {
            composerError = "Attachments need a paired host. Pair your desktop in Settings first."
            return
        }
        if (pendingAttachments.size >= maxAttachments) {
            composerError = "You can attach up to $maxAttachments files per prompt."
            return
        }
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.update_provider", file)
        }.getOrNull()
        if (uri == null) {
            composerError = "Couldn't prepare the camera file."
            return
        }
        cameraFile = file
        cameraUri = uri
        runCatching { capturePhoto.launch(uri) }.onFailure {
            file.delete()
            cameraFile = null
            cameraUri = null
            composerError = "No camera app is available."
        }
    }

    LaunchedEffect(session.status) {
        if (session.status == SessionStatus.Idle) {
            state.flushQueued(session.id)
        }
    }

    LaunchedEffect(session.messages.size, session.status, approval?.id, question?.id) {
        var itemCount = session.messages.size
        if (approval != null) itemCount += 1
        if (question != null) itemCount += 1
        if (session.status == SessionStatus.Working) itemCount += 1
        // A pending jump suppresses the usual auto-scroll-to-bottom exactly once.
        if (itemCount > 0 && stickToBottom && jumpTarget == null) {
            runCatching { listState.animateScrollToItem(itemCount - 1) }
        }
    }

    LaunchedEffect(jumpTarget?.sessionId, jumpTarget?.seq, jumpTarget?.messageId) {
        if (jumpTarget != null) stickToBottom = false
    }

    // Bounded jump-to-search-hit: scroll when the target row is loaded, otherwise pull older
    // pages (max 20) until found/exhausted/failed. The target is consumed so rotations don't loop.
    LaunchedEffect(
        jumpTarget?.sessionId,
        jumpTarget?.seq,
        jumpTarget?.messageId,
        session.messages.size,
        hasOlderMessages,
        loadingOlderMessages,
        transcriptLoading,
    ) {
        val target = jumpTarget ?: return@LaunchedEffect
        if (!messageJumpTargetMatches(target, session.id)) return@LaunchedEffect
        val index = messageJumpIndex(session.messages, target)
        if (index != null) {
            stickToBottom = false
            state.consumeMessageJumpTarget()
            val scrollIndex = messageJumpScrollIndex(
                index,
                hasOlderHeader = hasOlderMessages || loadingOlderMessages,
            )
            runCatching { listState.animateScrollToItem(scrollIndex.coerceAtLeast(0)) }
            highlightedMessageId = session.messages.getOrNull(index)?.id
            return@LaunchedEffect
        }
        if (transcriptLoading || loadingOlderMessages) return@LaunchedEffect
        if (hasOlderMessages && autoJumpOlderLoads < MESSAGE_JUMP_MAX_OLDER_LOADS) {
            // Increment before awaiting so this effect cannot launch a duplicate page load.
            autoJumpOlderLoads += 1
            loadingOlderMessages = true
            val loaded = try {
                state.loadOlderMessages(session.id)
            } finally {
                // Always release the in-flight gate, including effect cancellation.
                hasOlderMessages = state.hasOlderMessages(session.id)
                loadingOlderMessages = false
            }
            if (!loaded && !hasOlderMessages) {
                // Network/older-page failure: fall back to the normal bottom view once.
                state.consumeMessageJumpTarget()
                jumpNotice = MESSAGE_JUMP_NOT_FOUND_COPY
                stickToBottom = true
                val bottom = (tailItemCount - 1).coerceAtLeast(0)
                if (tailItemCount > 0) {
                    runCatching { listState.animateScrollToItem(bottom) }
                }
            }
            return@LaunchedEffect
        }
        // Target older than every page the host can give us / never loaded: normal bottom view.
        state.consumeMessageJumpTarget()
        jumpNotice = MESSAGE_JUMP_NOT_FOUND_COPY
        stickToBottom = true
        if (tailItemCount > 0) {
            runCatching { listState.animateScrollToItem((tailItemCount - 1).coerceAtLeast(0)) }
        }
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId == null) return@LaunchedEffect
        delay(MESSAGE_JUMP_HIGHLIGHT_MS)
        highlightedMessageId = null
    }

    LaunchedEffect(jumpNotice) {
        if (jumpNotice == null) return@LaunchedEffect
        delay(4_000)
        jumpNotice = null
    }

    val shellStatus = ShellStatus(
        left = "❯ " + workspace.name,
        items = listOf(
            chatStatusItem(session.status, state.connection),
            "${session.messages.size} msgs",
            "${tools.size} tools",
        ),
        right = listOf("CTX —", "CACHE —"),
    )
    val deckCards = chatDeckCards(tools = tools, pendingApproval = approval)
    val onCommand: (ShellCommand) -> Unit = { command ->
        when (command) {
            ShellCommand.NewSession -> state.openNewSession()
            ShellCommand.Search, ShellCommand.Sessions -> state.closeSession()
            ShellCommand.Settings -> state.openSettings()
            ShellCommand.Refresh -> state.refreshNow()
            ShellCommand.Connection -> if (state.hostCount > 0) {
                state.openHosts()
            } else {
                state.openPairing()
            }
            ShellCommand.SessionInfo -> showSessionInfo = true
            ShellCommand.ToolActivity -> showToolActivity = true
            ShellCommand.Approvals -> {
                if (approval != null) {
                    val target = session.messages.size + if (question != null) 1 else 0
                    if (target > 0) {
                        scope.launch { runCatching { listState.animateScrollToItem(target) } }
                    }
                }
            }

            else -> Unit
        }
    }

    ShellFrame(
        title = state.displayTitle(session),
        subtitle = listOfNotNull(workspace.name, sessionPresetLabel(session)).joinToString(" · "),
        status = shellStatus,
        deckCards = deckCards,
        onCommand = onCommand,
        onBack = state::closeSession,
        drawerSessions = state.visibleSessions().map { item -> item.title },
        onOpenSessionTitle = { title ->
            state.visibleSessions().firstOrNull { item -> item.title == title }?.let { target ->
                state.openSession(target.id)
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modelChipLabel(state.modelCatalog, state.modelPreview?.selection),
                    modifier = Modifier
                        .widthIn(max = 120.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.openModelPicker(session.id)
                            showModelPicker = true
                        }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    SKILLS_ACTION_LABEL,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) {
                            showSkills = true
                            state.openSkills(session.id)
                        }
                        .semantics { contentDescription = "Open skills" }
                        .padding(horizontal = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val planHint = state.plan?.takeIf { it.supported }?.let { plan ->
                    planProgressHint(plan.counts)
                }
                Column(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) {
                            showPlan = true
                            state.openPlan(session.id)
                        }
                        .semantics {
                            contentDescription = planActionContentDescription(
                                state.plan?.takeIf { it.supported }?.counts,
                            )
                        }
                        .padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        PLAN_ACTION_LABEL,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    planHint?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = { showTemplates = true }) {
                    Icon(
                        Icons.Filled.TextSnippet,
                        contentDescription = "Prompt templates",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    findOpen = !findOpen
                    if (!findOpen) findQuery = ""
                }) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = if (findOpen) "Close find" else "Find in conversation",
                        tint = if (findOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            if (state.isLive && state.connection == ConnectionStatus.Offline) {
                CompactReconnectBar(
                    onReconnect = state::reconnect,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            // Host-side queue strip: only when the live host reports waiting items. This is
            // distinct from the local offline queue in the composer.
            val hostQueueItems = state.hostQueue
                ?.takeIf { state.hostQueueSessionId == session.id && it.supported && it.live }
                ?.items.orEmpty()
            if (state.isLive && hostQueueItems.isNotEmpty()) {
                HostQueueStrip(
                    count = hostQueueItems.size,
                    onClick = {
                        state.openHostQueue(session.id)
                        showHostQueueDialog = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            val goalState = state.goalFor(session.id)
            if (state.isLive && goalState?.supported != false &&
                (goalState != null || state.goalErrorFor(session.id) != null)
            ) {
                GoalBanner(
                    goalState = goalState ?: SessionGoalState(supported = true, live = true, goal = null),
                    loading = state.goalLoadingFor(session.id),
                    error = state.goalErrorFor(session.id),
                    onResume = { state.resumeGoal(session.id) },
                    onPause = { state.pauseGoal(session.id) },
                    onSetGoal = {
                        goalDraft = ""
                        goalRoundsDraft = "120"
                        goalDialogError = null
                        showGoalDialog = true
                    },
                    onEdit = {
                        val goal = state.goalFor(session.id)?.goal
                        editGoalDraft = goal?.objective.orEmpty()
                        editGoalRoundsDraft = goal?.maxGoalRounds
                            ?.takeIf { it > 0 }
                            ?.toString()
                            ?: "120"
                        editGoalDialogError = null
                        showEditGoalDialog = true
                    },
                    onClear = { showClearGoalDialog = true },
                    onComplete = { showCompleteGoalDialog = true },
                    onRetry = { state.refreshGoal(session.id) },
                )
            }
            if (findOpen) {
                FindBar(
                    query = findQuery,
                    onQueryChange = { findQuery = it },
                    current = findIndex,
                    total = findMatches.size,
                    focusRequester = findFocus,
                    onPrevious = {
                        if (findMatches.isNotEmpty()) {
                            findIndex = (findIndex - 1 + findMatches.size) % findMatches.size
                            val target = findMatches.getOrNull(findIndex)
                            if (target != null) scope.launch { runCatching { listState.scrollToItem(target) } }
                        }
                    },
                    onNext = {
                        if (findMatches.isNotEmpty()) {
                            findIndex = (findIndex + 1) % findMatches.size
                            val target = findMatches.getOrNull(findIndex)
                            if (target != null) scope.launch { runCatching { listState.scrollToItem(target) } }
                        }
                    },
                    onClose = {
                        findOpen = false
                        findQuery = ""
                    },
                )
            }
            jumpNotice?.let { notice ->
                Text(
                    notice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 12.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (hasOlderMessages || loadingOlderMessages) {
                        item(key = "older-messages") {
                            if (loadingOlderMessages) {
                                Text(
                                    "Loading earlier messages…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                HadalGhostButton(
                                    text = "Load earlier messages",
                                    onClick = {
                                        loadingOlderMessages = true
                                        scope.launch {
                                            state.loadOlderMessages(session.id)
                                            hasOlderMessages = state.hasOlderMessages(session.id)
                                            loadingOlderMessages = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    itemsIndexed(
                        items = session.messages,
                        key = { index, message -> "${message.id}#$index" },
                    ) { _, message ->
                        MessageRow(
                            message,
                            state = state,
                            sessionId = session.id,
                            fontScale = state.chatFontScale,
                            wrapCode = state.wrapCodeBlocks,
                            highlighted = message.id == highlightedMessageId,
                            onLongPress = { actionMessage = message },
                            isAttachmentOpening = state::isAttachmentOpening,
                            onOpenAttachment = { attachment ->
                                state.openAttachment(session.id, attachment)
                            },
                            onOpenImageAttachments = { messageAttachments, attachment ->
                                viewerImages = imageAttachmentsForViewer(messageAttachments)
                                viewerInitialId = attachment.id
                            },
                        )
                    }
                if (question != null) {
                    item(key = "question-${question.id}") {
                        QuestionCard(
                            question = question,
                            readOnly = state.questionsReadOnly,
                            answering = state.isQuestionAnswering(question.id),
                            onAnswer = { answer, optionIndex ->
                                state.answerQuestion(answer, optionIndex)
                            },
                        )
                    }
                }
                if (approval != null) {
                    item(key = "approval-${approval.id}") {
                        ApprovalCard(
                            approval = approval,
                            readOnly = state.approvalsReadOnly,
                            onAllow = { state.answerApproval(true) },
                            onDeny = { state.answerApproval(false) },
                        )
                    }
                }
                if (session.status == SessionStatus.Working) {
                    item(key = "working-row") {
                        WorkingRow(onStop = state::stopWorking)
                    }
                }
                }
                if (!stickToBottom && tailItemCount > 0) {
                    Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                stickToBottom = true
                                scope.launch {
                                    runCatching {
                                        listState.animateScrollToItem((tailItemCount - 1).coerceAtLeast(0))
                                    }
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "↓ Latest",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            val queued = state.queuedFor(session.id)
            if (queued.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "QUEUED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    queued.forEach { prompt ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                prompt.text.trim().replace("\n", " ").take(48).ifBlank { "attachment" },
                                modifier = Modifier.clickable(enabled = prompt.attachmentIds.isEmpty()) {
                                    val edit = state.takeQueuedForEdit(session.id, prompt.id)
                                    if (edit != null) {
                                        draft = edit.text
                                        state.saveDraft(session.id, edit.text)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (queued.size > 1) {
                                IconButton(
                                    onClick = { state.moveQueued(session.id, prompt.id, -1) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Move queued prompt earlier",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { state.moveQueued(session.id, prompt.id, 1) },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Move queued prompt later",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { state.cancelQueued(session.id, prompt.id) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Cancel queued prompt",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    if (queued.size > 1) {
                        Text(
                            "clear",
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { state.cancelAllQueued(session.id) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            SchedulePromptActionRow(
                draftBlank = draft.isBlank(),
                enabled = state.isLive && !sending,
                notice = schedulePromptNotice,
                onClick = { showSchedulePrompt = true },
            )
            ChatInputBar(
                state = state,
                draft = draft,
                onDraftChange = {
                    // Typing takes over from dictation: finalize what was heard, then apply the edit.
                    if (voice.state.listening) voice.stop()
                    draft = it
                    state.saveDraft(session.id, it)
                },
                onSend = {
                    if (canSend) {
                        voice.stop()
                        submitPrompt(draft, uploadedIds)
                    }
                },
                working = session.status == SessionStatus.Working,
                attachments = pendingAttachments,
                onRemoveAttachment = { localId ->
                    uploadJobs.remove(localId)?.cancel()
                    pendingAttachments = pendingAttachments.filterNot { it.localId == localId }
                },
                onRetryAttachment = retryAttachment,
                onAttach = { if (attachEnabled) pickAttachment.launch(arrayOf("*/*")) },
                onCamera = { if (attachEnabled) launchCamera() },
                onVoice = {
                    when {
                        voice.state.listening -> voice.stop()
                        voice.state.starting -> Unit
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED -> startDictation()
                        else -> runCatching {
                            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }.onFailure {
                            composerError = "Microphone permission needed for voice dictation."
                        }
                    }
                },
                voice = voice.state,
                attachEnabled = attachEnabled,
                cameraEnabled = attachEnabled,
                attachHint = attachHint,
                composerError = composerError,
                onDismissComposerError = { composerError = null },
                sendError = sendError,
                onDismissSendError = {
                    sendError = null
                    failedPrompt = null
                },
                onRetrySend = {
                    failedPrompt?.let { prompt -> submitPrompt(prompt.text, prompt.attachmentIds) }
                },
                canSend = canSend,
                composerFocusRequester = composerFocusRequester,
            )
        }
    }

    if (showGoalDialog) {
        AlertDialog(
            onDismissRequest = { showGoalDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Set a goal",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The agent keeps working toward this objective across continuation rounds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = goalDraft,
                        onValueChange = {
                            goalDraft = it
                            goalDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Objective") },
                        minLines = 3,
                        maxLines = 6,
                    )
                    OutlinedTextField(
                        value = goalRoundsDraft,
                        onValueChange = { value ->
                            goalRoundsDraft = value.filter { it.isDigit() }.take(5)
                            goalDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Max rounds") },
                        singleLine = true,
                    )
                    goalDialogError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                        )
                    }
                }
            },
            confirmButton = {
                Text(
                    "Create",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val objective = goalDraft.trim()
                            val rounds = goalRoundsDraft.toIntOrNull()
                            when {
                                objective.isEmpty() -> goalDialogError = "Objective must not be empty"
                                rounds == null || rounds < 1 ->
                                    goalDialogError = "Max rounds must be a positive number"
                                else -> {
                                    state.createGoal(session.id, objective, rounds)
                                    showGoalDialog = false
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showGoalDialog = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }

    if (showEditGoalDialog) {
        AlertDialog(
            onDismissRequest = { showEditGoalDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Edit goal",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Update the objective or continuation-round limit for the current goal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = editGoalDraft,
                        onValueChange = {
                            editGoalDraft = it
                            editGoalDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Objective") },
                        minLines = 3,
                        maxLines = 6,
                    )
                    OutlinedTextField(
                        value = editGoalRoundsDraft,
                        onValueChange = { value ->
                            editGoalRoundsDraft = value.filter { it.isDigit() }.take(5)
                            editGoalDialogError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Max rounds") },
                        singleLine = true,
                    )
                    editGoalDialogError?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                        )
                    }
                }
            },
            confirmButton = {
                Text(
                    "Save",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val objective = editGoalDraft.trim()
                            val rounds = editGoalRoundsDraft.toIntOrNull()
                            when {
                                objective.isEmpty() ->
                                    editGoalDialogError = "Objective must not be empty"

                                rounds == null || rounds < 1 ->
                                    editGoalDialogError = "Max rounds must be a positive number"

                                else -> {
                                    state.editGoal(session.id, objective, rounds)
                                    showEditGoalDialog = false
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showEditGoalDialog = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }

    if (showClearGoalDialog) {
        AlertDialog(
            onDismissRequest = { showClearGoalDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Clear goal?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "This removes the current goal from this session. The session and its history " +
                        "are kept, and you can set a new goal afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Clear",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            state.clearGoal(session.id)
                            showClearGoalDialog = false
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
                        .clickable { showClearGoalDialog = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }

    if (showCompleteGoalDialog) {
        AlertDialog(
            onDismissRequest = { showCompleteGoalDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Mark this goal complete?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "This stops automatic continuation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Mark complete",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val goal = state.goalFor(session.id)?.goal
                            state.completeGoal(session.id, goal?.id, goal?.revision)
                            showCompleteGoalDialog = false
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = Warning,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showCompleteGoalDialog = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }

    if (showSessionInfo) {
        SessionInfoSheet(
            state = state,
            session = session,
            tools = tools,
            onExport = {
                showSessionInfo = false
                shareText(context, transcriptMarkdown(session))
            },
            onExportFile = {
                showSessionInfo = false
                scope.launch { shareTranscriptFile(context, session) }
            },
            exportingPdf = exportingPdf,
            onExportPdf = {
                showSessionInfo = false
                if (!exportingPdf) {
                    exportingPdf = true
                    scope.launch {
                        try {
                            shareTranscriptPdf(context, session)
                        } finally {
                            exportingPdf = false
                        }
                    }
                }
            },
            onOpenTeam = {
                showSessionInfo = false
                showTeam = true
                state.openTeam(session.id)
            },
            onOpenTrajectory = {
                showSessionInfo = false
                showTrajectory = true
                state.openTrajectory(session.id)
            },
            onOpenFiles = {
                showSessionInfo = false
                showFiles = true
                state.openFiles(session.id)
            },
            onOpenJobs = {
                showSessionInfo = false
                showJobs = true
                state.openJobs(session.id)
            },
            onHide = {
                showSessionInfo = false
                state.hideSession(session.id)
            },
            onDismiss = { showSessionInfo = false },
        )
    }
    if (showToolActivity) {
        ToolActivitySheet(
            tools = tools,
            onDismiss = { showToolActivity = false },
        )
    }
    LaunchedEffect(schedulePromptNotice) {
        if (schedulePromptNotice != null) {
            delay(4_000)
            schedulePromptNotice = null
        }
    }

    if (showSchedulePrompt) {
        SchedulePromptDialog(
            defaultTitle = scheduleTitleFromDraft(draft),
            instruction = draft,
            submitting = schedulingPrompt,
            onDismiss = { if (!schedulingPrompt) showSchedulePrompt = false },
            onSubmit = { title, timing ->
                if (!schedulingPrompt) {
                    schedulingPrompt = true
                    scope.launch {
                        val created = state.schedulePrompt(session.id, title, draft, timing)
                        schedulingPrompt = false
                        if (created) {
                            showSchedulePrompt = false
                            schedulePromptNotice = "Scheduled for this session"
                        }
                    }
                }
            },
        )
    }

    if (viewerImages.isNotEmpty()) {
        AttachmentImageViewer(
            state = state,
            sessionId = session.id,
            images = viewerImages,
            initialAttachmentId = viewerInitialId,
            onClose = { viewerImages = emptyList() },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            state = state,
            onDismiss = {
                showModelPicker = false
                state.closeModelPicker()
            },
        )
    }

    if (showHostQueueDialog) {
        HostQueueDialog(
            state = state,
            sessionWorking = session.status == SessionStatus.Working,
            onDismiss = {
                showHostQueueDialog = false
                state.closeHostQueue()
            },
        )
    }

    if (showTemplates) {
        PromptTemplatesSheet(
            recentPrompts = state.recentPrompts(),
            snippets = state.savedSnippets(),
            canSaveDraft = draft.isNotBlank(),
            onSaveDraft = {
                state.saveSnippet(draft)
                showTemplates = false
            },
            onDeleteSnippet = { id ->
                state.deleteSnippet(id)
                showTemplates = false
            },
            onPick = { prompt ->
                draft = if (draft.isBlank()) prompt else draft.trimEnd() + "\n\n" + prompt
                state.saveDraft(session.id, draft)
                showTemplates = false
            },
            onDismiss = { showTemplates = false },
        )
    }
    if (showSkills) {
        SkillsSheet(
            state = state,
            onPick = { skillName ->
                val prefill = skillPromptPrefill(skillName)
                if (prefill == null) {
                    // Defensive fallback if the composer cannot take the draft.
                    copyToClipboard(context, "Use the $skillName skill: ")
                    Toast.makeText(
                        context,
                        SKILLS_COPY_FALLBACK_TOAST,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    draft = skillPrefillDraft(draft, prefill)
                    state.saveDraft(session.id, draft)
                    scope.launch { runCatching { composerFocusRequester.requestFocus() } }
                }
                showSkills = false
                state.closeSkills()
            },
            onDismiss = {
                showSkills = false
                state.closeSkills()
            },
        )
    }
    if (showPlan) {
        PlanSheet(
            state = state,
            onDismiss = {
                showPlan = false
                state.closePlan()
            },
        )
    }
    if (showTeam) {
        TeamSheet(
            state = state,
            onDismiss = {
                showTeam = false
                state.closeTeam()
            },
        )
    }
    if (showJobs) {
        JobsSheet(
            state = state,
            onDismiss = {
                showJobs = false
                state.closeJobs()
            },
        )
    }
    if (showFiles) {
        FilesSheet(
            state = state,
            onDismiss = {
                showFiles = false
                state.closeFiles()
            },
            onMentionFile = { path ->
                if (state.mentionWorkspaceFileInDraft(session.id, path)) {
                    showFiles = false
                    state.closeFiles()
                    scope.launch { runCatching { composerFocusRequester.requestFocus() } }
                }
            },
        )
    }
    if (showTrajectory) {
        TrajectorySheet(
            state = state,
            onDismiss = {
                showTrajectory = false
                state.closeTrajectory()
            },
        )
    }
    actionMessage?.let { message ->
        val text = messageText(message)
        // Copy/share/read-aloud keep the Scheduled marker; quote inserts the raw prompt text.
        val copyText = if (message is UserMessage) {
            scheduledMessageCopyText(text, message.scheduled)
        } else {
            text
        }
        MessageActionsSheet(
            text = text,
            canSpeak = message is AssistantMessage && text.isNotBlank(),
            canShareAsImage = message is UserMessage || message is AssistantMessage,
            forkSeq = state.messageSeqFor(message.id),
            onForkFromHere = {
                state.clearForkStatus()
                forkMessage = message
                actionMessage = null
            },
            onDismiss = { actionMessage = null },
            onCopy = {
                copyToClipboard(context, copyText)
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                actionMessage = null
            },
            onShare = {
                shareText(context, copyText)
                actionMessage = null
            },
            onReadAloud = {
                runCatching {
                    val current = tts
                    if (current != null) {
                        current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dsh-message")
                    } else {
                        // Create the engine only when the user asks to speak. Some OEM TTS
                        // engines fail asynchronously; doing this on every session open made
                        // those failures indistinguishable from chat crashes.
                        val holder = arrayOfNulls<TextToSpeech>(1)
                        val listener = TextToSpeech.OnInitListener { status ->
                            if (status == TextToSpeech.SUCCESS) {
                                runCatching {
                                    holder[0]?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dsh-message")
                                }
                            }
                        }
                        val engine = TextToSpeech(context, listener)
                        holder[0] = engine
                        tts = engine
                    }
                }.onFailure {
                    Toast.makeText(context, "Text-to-speech isn't available", Toast.LENGTH_SHORT).show()
                }
                actionMessage = null
            },
            onQuote = {
                draft = if (draft.isBlank()) {
                    "> " + text.replace("\n", "\n> ")
                } else {
                    draft.trimEnd() + "\n\n> " + text.replace("\n", "\n> ")
                }
                state.saveDraft(session.id, draft)
                actionMessage = null
            },
            onShareAsImage = {
                actionMessage = null
                if (sharingImageMessageId == null) {
                    sharingImageMessageId = message.id
                    scope.launch {
                        try {
                            shareMessageAsImage(context, session, message)
                        } finally {
                            sharingImageMessageId = null
                        }
                    }
                }
            },
        )
    }
    forkMessage?.let { message ->
        val busy = state.forkInProgressSessionId == session.id
        val forkSeq = state.messageSeqFor(message.id)
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    forkMessage = null
                    state.clearForkStatus()
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Fork from here?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A new session is created from the messages up to and including this " +
                            "one. Earlier messages are copied; later messages are not.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (forkSeq == null) {
                        Text(
                            "This message has no host fork boundary.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (busy) {
                        Text(
                            "Forking...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    state.forkStatus?.let { status ->
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.startsWith("Forked")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            },
            confirmButton = {
                Text(
                    if (busy) "Forking..." else "Fork from here",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy && forkSeq != null) {
                            forkSeq?.let { seq -> state.forkConversation(session.id, seq) }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (busy || forkSeq == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            if (!busy) {
                                forkMessage = null
                                state.clearForkStatus()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Shell status / deck mapping
// ---------------------------------------------------------------------------

/** Middle status item: the live turn state wins, otherwise show the connection. */
private fun chatStatusItem(status: SessionStatus, connection: ConnectionStatus): String =
    when (status) {
        SessionStatus.Working -> "● Working"
        SessionStatus.NeedsInput -> "● Needs input"
        SessionStatus.Idle -> when (connection) {
            ConnectionStatus.Live -> "● Live"
            ConnectionStatus.Connecting -> "● Connecting"
            ConnectionStatus.Offline -> "● Offline"
            ConnectionStatus.Demo -> "● Demo"
        }
    }

private fun sessionStatusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Working -> "Working"
    SessionStatus.NeedsInput -> "Needs input"
    SessionStatus.Idle -> "Idle"
}

private fun connectionLabel(connection: ConnectionStatus): String = when (connection) {
    ConnectionStatus.Live -> "live"
    ConnectionStatus.Connecting -> "connecting"
    ConnectionStatus.Offline -> "offline"
    ConnectionStatus.Demo -> "demo"
}

private fun chatDeckCards(
    tools: List<ToolActivity>,
    pendingApproval: Approval?,
): List<DeckCard> = buildList {
    add(
        DeckCard(
            icon = Icons.Filled.Info,
            title = "Session Info",
            description = "Workspace, status and activity counts",
            command = ShellCommand.SessionInfo,
        ),
    )
    add(
        DeckCard(
            icon = Icons.Filled.Terminal,
            title = "Tool Activity",
            description = if (tools.isEmpty()) {
                "No tool calls recorded yet"
            } else {
                "${tools.size} tool calls recorded"
            },
            command = ShellCommand.ToolActivity,
        ),
    )
    if (pendingApproval != null) {
        add(
            DeckCard(
                icon = Icons.Filled.Lock,
                title = "Approvals",
                description = "A command is waiting for your decision",
                command = ShellCommand.Approvals,
            ),
        )
    }
    add(
        DeckCard(
            icon = Icons.Filled.Refresh,
            title = "Refresh",
            description = "Sync this transcript with the host",
            command = ShellCommand.Refresh,
        ),
    )
    add(
        DeckCard(
            icon = Icons.Filled.Settings,
            title = "Settings",
            description = "Pairing, appearance and updates",
            command = ShellCommand.Settings,
        ),
    )
}

// ---------------------------------------------------------------------------
// Transcript
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    state: AppState,
    sessionId: String,
    fontScale: Float,
    wrapCode: Boolean,
    highlighted: Boolean = false,
    onLongPress: () -> Unit,
    isAttachmentOpening: (String) -> Boolean,
    onOpenAttachment: (MessageAttachment) -> Unit,
    onOpenImageAttachments: (List<MessageAttachment>, MessageAttachment) -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
        LocalWrapCodeBlocks provides wrapCode,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    if (highlighted) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        Color.Transparent
                    },
                    RoundedCornerShape(10.dp),
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            when (message) {
                is UserMessage -> UserBubble(
                    message = message,
                    state = state,
                    sessionId = sessionId,
                    isAttachmentOpening = isAttachmentOpening,
                    onOpenAttachment = onOpenAttachment,
                    onOpenImageAttachments = onOpenImageAttachments,
                )

                is AssistantMessage -> AssistantBlock(
                    message = message,
                    state = state,
                    sessionId = sessionId,
                    isAttachmentOpening = isAttachmentOpening,
                    onOpenAttachment = onOpenAttachment,
                    onOpenImageAttachments = onOpenImageAttachments,
                )

                is SystemNote -> SystemNoteRow(message)
            }
        }
    }
}

private fun messageText(message: Message): String = when (message) {
    is UserMessage -> message.text
    is AssistantMessage -> message.text
    is SystemNote -> message.text
}

/** Markdown transcript for sharing from the session info sheet. */
private fun transcriptMarkdown(session: Session): String = buildString {
    appendLine("# ${session.title}")
    appendLine()
    session.messages.forEach { message ->
        val text = messageText(message).trim()
        if (text.isEmpty()) return@forEach
        val role = when (message) {
            is UserMessage -> "User"
            is AssistantMessage -> "Assistant"
            is SystemNote -> "System"
        }
        val heading = scheduledTranscriptHeading(
            role,
            scheduled = message is UserMessage && message.scheduled,
        )
        appendLine("## $heading")
        appendLine()
        appendLine(text)
        appendLine()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DSH message", text))
}

private fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share message"))
}

/**
 * Writes the same Markdown transcript as "Export transcript" to cacheDir/exports and shares the
 * resulting .md file through the app's FileProvider. Runs off the main thread and never throws:
 * an IO or FileProvider failure turns into a Toast instead of a crash.
 */
private suspend fun shareTranscriptFile(context: Context, session: Session) {
    val uri = runCatching {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, TRANSCRIPT_EXPORTS_DIR).apply { mkdirs() }
            val file = File(dir, transcriptFileName(session))
            file.writeText(transcriptMarkdown(session))
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.update_provider",
                file,
            )
        }
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(context, "Couldn't create the transcript file.", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share transcript")) }
        .onFailure {
            Toast.makeText(context, "No app can share the transcript file.", Toast.LENGTH_LONG).show()
        }
}

/** Share-safe file name: dsh-<sanitized-title>-<timestamp>.md. */
private fun transcriptFileName(session: Session): String {
    val slug = session.title
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(40)
        .trim('-')
        .ifBlank { "session" }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "dsh-$slug-$stamp.md"
}

private const val TRANSCRIPT_EXPORTS_DIR = "exports"

/** One measured card body block (prose or fenced code) before drawing. */
private data class ShareCardBlock(val lines: List<String>, val monospace: Boolean)

/**
 * Renders the message as a 1080px-wide PNG share card off the main thread, then opens the chooser
 * with `image/png`. Any rendering/IO/FileProvider failure becomes a Toast; there is one attempt.
 */
private suspend fun shareMessageAsImage(context: Context, session: Session, message: Message) {
    Toast.makeText(context, "Creating image…", Toast.LENGTH_SHORT).show()
    // Cancellation means the user left/rotated: never toast a failure in that case.
    val uri = try {
        withContext(Dispatchers.Default) { buildShareCardUri(context, session, message) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }
    if (uri == null) {
        Toast.makeText(context, SHARE_CARD_FAILURE_TOAST, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share image")) }
        .onFailure {
            Toast.makeText(context, SHARE_CARD_FAILURE_TOAST, Toast.LENGTH_LONG).show()
        }
}

private fun buildShareCardUri(context: Context, session: Session, message: Message): Uri {
    val (plain, charTruncated) = sanitizedShareCardInput(messageText(message))
    val segments = parseShareCardSegments(plain)
    val blocks = mutableListOf<ShareCardBlock>()
    var remainingLines = SHARE_CARD_MAX_LINES
    var truncated = charTruncated
    segments.forEach { segment ->
        if (remainingLines <= 0) {
            truncated = true
            return@forEach
        }
        val perLine = if (segment.monospace) {
            SHARE_CARD_CODE_CHARS_PER_LINE
        } else {
            SHARE_CARD_PROSE_CHARS_PER_LINE
        }
        val (lines: List<String>, segmentTruncated: Boolean) = wrapShareCardText(
            segment.text,
            maxCharsPerLine = perLine,
            maxLines = remainingLines,
        )
        if (lines.isNotEmpty()) {
            blocks += ShareCardBlock(lines, segment.monospace)
            remainingLines -= lines.size
        }
        truncated = truncated || segmentTruncated
    }
    if (blocks.isEmpty()) {
        blocks += ShareCardBlock(listOf("(no text)"), false)
    }
    val lineCount = blocks.sumOf { it.lines.size }.coerceAtLeast(1)
    val bitmapHeight = shareCardHeightPx(lineCount, truncated)
    val bitmap = Bitmap.createBitmap(SHARE_CARD_WIDTH_PX, bitmapHeight, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#0B1220"))
        val accent = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#34D399")
        }
        canvas.drawRect(0f, 0f, SHARE_CARD_WIDTH_PX.toFloat(), 10f, accent)
        val padding = SHARE_CARD_PADDING_PX.toFloat()
        val bodyWidth = SHARE_CARD_WIDTH_PX - 2 * SHARE_CARD_PADDING_PX
        val brandPaint = Paint().apply {
            isAntiAlias = true
            color = accent.color
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(SHARE_CARD_BRAND, padding, 116f, brandPaint)
        val rawTitle = session.title.trim()
        val titleText = rawTitle.take(90).ifBlank { "Session" } + if (rawTitle.length > 90) "…" else ""
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#E2E8F0")
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(titleText, padding, 196f, titlePaint)
        val metaPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 30f
        }
        val role = shareCardRoleLabel(shareCardRoleFor(message))
        val time = shareCardTimeLabel(message.time)
        canvas.drawText(if (time.isBlank()) role else "$role · $time", padding, 254f, metaPaint)
        canvas.drawRect(
            padding,
            288f,
            SHARE_CARD_WIDTH_PX - padding,
            290f,
            Paint().apply { color = android.graphics.Color.parseColor("#1E293B") },
        )
        var y = SHARE_CARD_HEADER_PX.toFloat()
        blocks.forEach { block ->
            val text = block.lines.joinToString("\n")
            val textPaint = TextPaint().apply {
                isAntiAlias = true
                textSize = SHARE_CARD_TEXT_SIZE_PX
                typeface = if (block.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
                color = android.graphics.Color.parseColor(
                    if (block.monospace) "#A7F3D0" else "#E2E8F0",
                )
            }
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, bodyWidth)
                .setLineSpacing(8f, 1f)
                .setIncludePad(false)
                // S1: pin FIRSTSTRONG_LTR so RTL runs inside an LTR card never flip the layout.
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build()
            if (block.monospace) {
                val bg = Paint().apply {
                    color = android.graphics.Color.parseColor("#111827")
                }
                canvas.drawRoundRect(
                    padding - 16f,
                    y - 10f,
                    SHARE_CARD_WIDTH_PX - padding + 16f,
                    y + layout.height + 10f,
                    12f,
                    12f,
                    bg,
                )
            }
            canvas.save()
            canvas.translate(padding, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + 16f
        }
        if (truncated) {
            val notePaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.parseColor("#FBBF24")
                textSize = 28f
            }
            canvas.drawText(SHARE_CARD_TRUNCATED_NOTE, padding, y + 6f, notePaint)
        }
        val footerPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#64748B")
            textSize = 28f
        }
        canvas.drawText(
            SHARE_CARD_TAGLINE,
            padding,
            bitmapHeight - SHARE_CARD_FOOTER_PX / 2f,
            footerPaint,
        )
        val dir = File(context.cacheDir, SHARE_CARD_CACHE_DIR).apply { mkdirs() }
        // S4: a fresh name per render; never delete/overwrite a card whose URI may be in flight.
        val file = File(
            dir,
            shareCardFileName(
                sessionTitle = session.title,
                messageId = message.id,
                nonce = System.nanoTime(),
            ),
        )
        val temp = File(dir, file.name + ".part")
        FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        if (!temp.renameTo(file)) {
            val written = file.outputStream().use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            }
            temp.delete()
            check(written > 0) { "Share card write failed" }
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.update_provider",
            file,
        )
    } finally {
        bitmap.recycle()
    }
}

/** Which caps the PDF renderer hit (drives the status Toast). */
private data class TranscriptPdfBuildResult(
    val messagesCapped: Boolean,
    val pagesCapped: Boolean,
)

/**
 * Export PDF: renders title/export time and the same messages as the Markdown transcript with
 * PdfDocument + StaticLayout, paginates A4 portrait with page-number footers, writes
 * `dsh-mobile-<session>-<timestamp>.pdf` into cacheDir/exports and shares it through the existing
 * FileProvider. Runs off the main thread; IO/build/share failures become Toasts, never crashes.
 */
private suspend fun shareTranscriptPdf(context: Context, session: Session) {
    val exportable = transcriptPdfExportableMessages(session.messages)
    if (exportable.isEmpty()) {
        Toast.makeText(context, TRANSCRIPT_PDF_EMPTY_COPY, Toast.LENGTH_LONG).show()
        return
    }
    // P1: the 200-message selection cap is reported, not silently dropped.
    val messageCountCapped = transcriptPdfMessagesCapped(session.messages)
    Toast.makeText(context, TRANSCRIPT_PDF_EXPORTING_COPY, Toast.LENGTH_SHORT).show()
    var build: TranscriptPdfBuildResult? = null
    var partialFile: File? = null
    val uri = try {
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, TRANSCRIPT_EXPORTS_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, transcriptPdfFileName(session.title, stamp))
            partialFile = file
            build = buildTranscriptPdf(session, exportable, file, messageCountCapped)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.update_provider",
                file,
            )
        }
    } catch (cancellation: CancellationException) {
        // P6: leaving the screen cancels the export: delete the partial file and do not toast.
        partialFile?.let { file -> runCatching { file.delete() } }
        throw cancellation
    } catch (error: Exception) {
        null
    }
    if (uri == null) {
        Toast.makeText(context, TRANSCRIPT_PDF_FAILURE_COPY, Toast.LENGTH_LONG).show()
        return
    }
    build?.let { result ->
        transcriptPdfCappedNote(result.messagesCapped, result.pagesCapped)?.let { note ->
            Toast.makeText(context, note, Toast.LENGTH_LONG).show()
        }
    }
    Toast.makeText(context, TRANSCRIPT_PDF_SUCCESS_COPY, Toast.LENGTH_SHORT).show()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, session.title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share PDF")) }
        .onFailure {
            Toast.makeText(context, TRANSCRIPT_PDF_SHARE_FAILURE_COPY, Toast.LENGTH_LONG).show()
        }
}

/**
 * Builds the actual PDF file. A single styled StaticLayout gives us line-level pagination across
 * pages; page count is capped so a pathological transcript can never run away.
 */
private fun buildTranscriptPdf(
    session: Session,
    messages: List<Message>,
    file: File,
    messageCountCapped: Boolean,
): TranscriptPdfBuildResult {
    val content = SpannableStringBuilder()
    val title = sanitizeTranscriptPdfText(session.title, 200).ifBlank { "Session" }
    val titleStart = content.length
    content.append(title).append('\n')
    content.setSpan(
        RelativeSizeSpan(1.5f),
        titleStart,
        titleStart + title.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    content.setSpan(
        StyleSpan(Typeface.BOLD),
        titleStart,
        titleStart + title.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    val exported = "Exported " +
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    content.append(exported).append("\n\n")

    var totalChars = exported.length
    var messagesCapped = messageCountCapped
    for (message in messages) {
        val body = sanitizeTranscriptPdfText(transcriptPdfMessageText(message))
        if (body.isEmpty()) continue
        if (totalChars + body.length > dev.dsh.mobile.data.TRANSCRIPT_PDF_MAX_TOTAL_CHARS) {
            messagesCapped = true
            break
        }
        val label = transcriptPdfRoleLabel(message) + " · " +
            transcriptPdfMessageTimeLabel(message)
        val labelStart = content.length
        content.append(label).append('\n')
        content.setSpan(
            StyleSpan(Typeface.BOLD),
            labelStart,
            labelStart + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        content.setSpan(
            ForegroundColorSpan(0xFF2E6B54.toInt()),
            labelStart,
            labelStart + label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        content.append(body).append("\n\n")
        totalChars += label.length + body.length
    }
    if (messagesCapped) content.append("… transcript capped …\n")

    val paint = TextPaint().apply {
        isAntiAlias = true
        textSize = 11f
        color = android.graphics.Color.BLACK
    }
    val contentWidth = TRANSCRIPT_PDF_PAGE_WIDTH - 2 * TRANSCRIPT_PDF_MARGIN
    val layout = StaticLayout.Builder
        .obtain(content, 0, content.length, paint, contentWidth)
        .setLineSpacing(0f, 1.15f)
        .setIncludePad(false)
        .build()

    val document = PdfDocument()
    var pagesCapped = false
    try {
        val contentHeight = TRANSCRIPT_PDF_PAGE_HEIGHT - 2 * TRANSCRIPT_PDF_MARGIN - 18
        val footerPaint = Paint().apply {
            isAntiAlias = true
            textSize = 9f
            color = 0xFF666666.toInt()
            textAlign = Paint.Align.CENTER
        }
        var line = 0
        var pageNumber = 1
        while (line < layout.lineCount && pageNumber <= TRANSCRIPT_PDF_MAX_PAGES) {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(
                    TRANSCRIPT_PDF_PAGE_WIDTH,
                    TRANSCRIPT_PDF_PAGE_HEIGHT,
                    pageNumber,
                ).create(),
            )
            val canvas = page.canvas
            val pageTop = layout.getLineTop(line)
            var lastLine = line
            while (lastLine + 1 < layout.lineCount &&
                layout.getLineBottom(lastLine + 1) - pageTop <= contentHeight
            ) {
                lastLine += 1
            }
            canvas.save()
            canvas.clipRect(
                TRANSCRIPT_PDF_MARGIN.toFloat(),
                TRANSCRIPT_PDF_MARGIN.toFloat(),
                (TRANSCRIPT_PDF_PAGE_WIDTH - TRANSCRIPT_PDF_MARGIN).toFloat(),
                (TRANSCRIPT_PDF_MARGIN + contentHeight).toFloat(),
            )
            canvas.translate(
                TRANSCRIPT_PDF_MARGIN.toFloat(),
                TRANSCRIPT_PDF_MARGIN.toFloat() - pageTop,
            )
            layout.draw(canvas)
            canvas.restore()
            canvas.drawText(
                transcriptPdfFooterLabel(pageNumber),
                TRANSCRIPT_PDF_PAGE_WIDTH / 2f,
                TRANSCRIPT_PDF_PAGE_HEIGHT - TRANSCRIPT_PDF_MARGIN / 2f,
                footerPaint,
            )
            document.finishPage(page)
            line = lastLine + 1
            pageNumber += 1
        }
        if (line < layout.lineCount) pagesCapped = true
        FileOutputStream(file).use { document.writeTo(it) }
    } finally {
        document.close()
    }
    return TranscriptPdfBuildResult(messagesCapped = messagesCapped, pagesCapped = pagesCapped)
}

/** Assistant messages longer than this collapse behind Show more. */
private const val LONG_MESSAGE_COLLAPSE_CHARS = 1_400

/** Compact goal status above the transcript; Resume is one tap when the goal is paused/blocked. */
@Composable
private fun GoalBanner(
    goalState: SessionGoalState,
    loading: Boolean,
    error: String?,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onSetGoal: () -> Unit,
    onEdit: () -> Unit,
    onClear: () -> Unit,
    onComplete: () -> Unit,
    onRetry: () -> Unit,
) {
    val goal = goalState.goal
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "GOAL · ${goal?.phase?.uppercase() ?: "NONE"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (goal != null) {
                Text(
                    goal.objective,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(goal.roundsLabel)
                        if (goal.activation == "armed") append(" · armed")
                        if (goal.phase == "complete") append(" · complete")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                goal.blockedReason?.let { reason ->
                    Text(
                        "Blocked: $reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            error?.let { message ->
                Text(
                    message,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Danger,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(start = 8.dp, top = 2.dp)
                    .size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            goal != null -> Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GoalAction(
                        text = "Edit",
                        color = MaterialTheme.colorScheme.primary,
                        onClick = onEdit,
                    )
                    GoalAction(
                        text = "Clear",
                        color = MaterialTheme.colorScheme.tertiary,
                        onClick = onClear,
                    )
                }
                val canComplete = goal.phase == "active" ||
                    goal.phase == "paused" ||
                    goal.phase == "blocked"
                when {
                    goal.pausedOrStopped && goal.phase != "complete" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        GoalAction(
                            text = "Resume",
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onResume,
                        )
                        if (canComplete) {
                            GoalAction(
                                text = "Complete",
                                color = Warning,
                                onClick = onComplete,
                            )
                        }
                    }

                    goal.phase == "active" -> Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        GoalAction(
                            text = "Pause",
                            color = MaterialTheme.colorScheme.tertiary,
                            onClick = onPause,
                        )
                        if (canComplete) {
                            GoalAction(
                                text = "Complete",
                                color = Warning,
                                onClick = onComplete,
                            )
                        }
                    }

                    else -> Unit
                }
            }

            error != null -> Text(
                "Retry",
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            else -> Text(
                "Set goal",
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onSetGoal)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Compact text action used by the GOAL banner (Edit / Clear / Resume / Pause). */
@Composable
private fun GoalAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    current: Int,
    total: Int,
    focusRequester: FocusRequester,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .focusRequester(focusRequester),
        )
        Text(
            if (query.isBlank()) "FIND" else "${current + 1}/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        IconButton(onClick = onPrevious, enabled = total > 0) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "Previous match",
                tint = if (total > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
        }
        IconButton(onClick = onNext, enabled = total > 0) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Next match",
                tint = if (total > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close find",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageActionsSheet(
    text: String,
    canSpeak: Boolean,
    canShareAsImage: Boolean,
    forkSeq: Int?,
    onForkFromHere: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onReadAloud: () -> Unit,
    onQuote: () -> Unit,
    onShareAsImage: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        Text(
            "MESSAGE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
        )
        Text(
            text.trim().replace("\n", " ").take(140).ifBlank { "(no text)" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(4.dp))
        MessageActionRow("Copy", "Copy the message text", onCopy)
        MessageActionRow("Share", "Send to another app", onShare)
        if (canShareAsImage) {
            MessageActionRow(
                SHARE_CARD_ACTION_LABEL,
                SHARE_CARD_ACTION_SUBTITLE,
                onShareAsImage,
            )
        }
        MessageActionRow("Quote in reply", "Insert this text into the composer", onQuote)
        if (canSpeak) MessageActionRow("Read aloud", "Speak this message", onReadAloud)
        if (forkSeq != null) {
            MessageActionRow(
                "Fork from here",
                "Create a new session up to and including this message",
                onForkFromHere,
            )
        }
    }
}

@Composable
private fun MessageActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Terminal quote: Layer3 fill, 2dp mint left rule, `❯` prefix, radius 14. */
@Composable
private fun UserBubble(
    message: UserMessage,
    state: AppState,
    sessionId: String,
    isAttachmentOpening: (String) -> Boolean,
    onOpenAttachment: (MessageAttachment) -> Unit,
    onOpenImageAttachments: (List<MessageAttachment>, MessageAttachment) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(14.dp),
                ),
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "❯",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    if (message.attachments.isNotEmpty()) {
                        AttachmentNamesRow(names = message.attachments)
                        if (message.messageAttachments.isNotEmpty() || message.text.isNotBlank()) {
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                    if (message.messageAttachments.isNotEmpty()) {
                        MessageAttachmentsColumn(
                            state = state,
                            sessionId = sessionId,
                            attachments = message.messageAttachments,
                            isOpening = isAttachmentOpening,
                            onOpen = onOpenAttachment,
                            onOpenImage = onOpenImageAttachments,
                        )
                        if (message.text.isNotBlank()) {
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                    if (message.text.isNotBlank()) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (message.time.isNotBlank() || message.scheduled) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (message.scheduled) {
                                Text(
                                    SCHEDULED_MESSAGE_LABEL,
                                    modifier = Modifier.semantics {
                                        contentDescription = SCHEDULED_MESSAGE_CONTENT_DESCRIPTION
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                                if (message.time.isNotBlank()) {
                                    Text(
                                        " · ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                            if (message.time.isNotBlank()) {
                                Text(
                                    message.time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentNamesRow(names: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.AttachFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            names.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Assistant body via RichText; a streaming turn ends with a mint block cursor. */
@Composable
private fun AssistantBlock(
    message: AssistantMessage,
    state: AppState,
    sessionId: String,
    isAttachmentOpening: (String) -> Boolean,
    onOpenAttachment: (MessageAttachment) -> Unit,
    onOpenImageAttachments: (List<MessageAttachment>, MessageAttachment) -> Unit,
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val collapsible = !message.streaming && message.text.length > LONG_MESSAGE_COLLAPSE_CHARS
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.text.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .then(
                            if (collapsible && !expanded) {
                                Modifier
                                    .heightIn(max = 440.dp)
                                    .clipToBounds()
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    RichText(
                        text = message.text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (message.streaming) {
                    Text(
                        "▍",
                        modifier = Modifier.padding(start = 2.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (collapsible) {
                Text(
                    if (expanded) "Show less" else "Show more",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (message.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkingDots(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Thinking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message.tool?.let { tool -> ToolRowCard(tool) }
        if (message.messageAttachments.isNotEmpty()) {
            MessageAttachmentsColumn(
                state = state,
                sessionId = sessionId,
                attachments = message.messageAttachments,
                isOpening = isAttachmentOpening,
                onOpen = onOpenAttachment,
                onOpenImage = onOpenImageAttachments,
            )
        }
        if (!message.streaming && message.time.isNotBlank()) {
            Text(
                message.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Dim centered system note between two `──` hairline rules. */
@Composable
private fun SystemNoteRow(message: SystemNote) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Text(
            message.text,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** Tool call as a quiet ledger row: icon tile, mono name/detail, mint check / red error. */
@Composable
private fun ToolRowCard(tool: ToolActivity) {
    var showOutput by remember(tool) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HadalIconTile(icon = Icons.Filled.Terminal, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tool.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tool.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (toolOutputAvailable(tool)) {
                Text(
                    TOOL_OUTPUT_VIEW_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showOutput = true }
                        .padding(top = 3.dp, end = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (tool.state) {
            ToolState.Running -> CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            ToolState.Done -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Done",
                tint = Success,
                modifier = Modifier.size(15.dp),
            )

            ToolState.Failed -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Failed",
                tint = Danger,
                modifier = Modifier.size(15.dp),
            )
        }
    }
    if (showOutput) {
        ToolOutputDialog(tool = tool, onDismiss = { showOutput = false })
    }
}

/**
 * Modal tool-output viewer: monospace, vertically scrollable and selectable (SelectionContainer),
 * with the host truncation note and Copy/Share limited to the output text itself.
 */
@Composable
private fun ToolOutputDialog(tool: ToolActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val truncated = toolOutputIsTruncated(tool.output)
    DshBottomSheet(onDismiss = onDismiss) {
        Text(
            toolOutputViewerTitle(tool),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (truncated) {
            Spacer(Modifier.height(4.dp))
            Text(
                TOOL_OUTPUT_TRUNCATED_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = Warning,
            )
        }
        Spacer(Modifier.height(10.dp))
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    tool.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HadalGhostButton(
                text = "Copy",
                onClick = {
                    runCatching { copyToClipboard(context, toolOutputCopyText(tool.output)) }
                        .onSuccess {
                            Toast.makeText(
                                context,
                                TOOL_OUTPUT_COPY_SUCCESS,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .onFailure {
                            Toast.makeText(
                                context,
                                TOOL_OUTPUT_COPY_FAILURE,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                },
                modifier = Modifier.weight(1f),
            )
            HadalGhostButton(
                text = "Share",
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, toolOutputCopyText(tool.output))
                        putExtra(Intent.EXTRA_SUBJECT, toolOutputShareSubject(tool))
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(intent, "Share tool output")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(
                            context,
                            TOOL_OUTPUT_SHARE_FAILURE,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Mint working dots + `Working…` + a quiet Stop ghost action. */
@Composable
private fun WorkingRow(onStop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkingDots(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(
            "Working…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        HadalGhostButton(text = "Stop", onClick = onStop)
    }
}

// ---------------------------------------------------------------------------
// Decisions: approval + question, amber-ruled ledger cards
// ---------------------------------------------------------------------------

/** HadalCard silhouette with an amber warning rule instead of the kelp hairline. */
@Composable
private fun WarningRuledCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, Warning.copy(alpha = 0.45f), shape)
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun WarningCaps(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        ),
        color = Warning,
        maxLines = 1,
    )
}

@Composable
private fun ApprovalCard(
    approval: Approval,
    readOnly: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    WarningRuledCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            WarningCaps("APPROVAL NEEDED")
        }
        Spacer(Modifier.height(9.dp))
        Text(
            approval.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            approval.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        CommandLedgerLine(command = approval.command)
        Spacer(Modifier.height(12.dp))
        if (readOnly) {
            Text(
                "Resolve on desktop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "This host exposes approvals read-only, so it can't be answered from the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalPrimaryButton(
                    text = "Allow once",
                    onClick = onAllow,
                    modifier = Modifier.weight(1f),
                )
                DangerGhostButton(
                    text = "Deny",
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    readOnly: Boolean,
    answering: Boolean,
    onAnswer: (String, Int?) -> Unit,
) {
    // Input state is keyed by the question id so a different question starts blank.
    var otherOpen by remember(question.id) { mutableStateOf(false) }
    var draft by remember(question.id) { mutableStateOf("") }
    val canSend = !answering && normalizeQuestionAnswer(draft) != null
    WarningRuledCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.HelpOutline,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            WarningCaps("INPUT NEEDED")
        }
        Spacer(Modifier.height(9.dp))
        Text(
            question.prompt,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (readOnly) {
            question.options.forEachIndexed { index, option ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${index + 1}. $option",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Answer on desktop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "This host keeps questions on the desktop, so it can't be answered from the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (question.options.isNotEmpty()) {
                question.options.forEachIndexed { index, option ->
                    Spacer(Modifier.height(8.dp))
                    MintOptionButton(
                        text = option,
                        onClick = { onAnswer(option, index) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (otherOpen) "Hide custom answer" else "Other…",
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { otherOpen = !otherOpen }
                        .padding(vertical = 3.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (question.options.isEmpty() || otherOpen) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_QUESTION_ANSWER_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !answering,
                    placeholder = {
                        Text(
                            "Type an answer…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.height(6.dp))
                HadalPrimaryButton(
                    text = if (answering) "Sending…" else "Send",
                    onClick = {
                        val answer = normalizeQuestionAnswer(draft)
                        if (answer != null && !answering) {
                            onAnswer(answer, null)
                            draft = ""
                            otherOpen = false
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (question.options.isEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Free-text question · answers map to a custom response.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Ink code line with a 2dp mint left rule; used for the pending command. */
@Composable
private fun CommandLedgerLine(command: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(6.dp),
            ),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            command,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Mint-outlined option row (question choices read as terminal actions). */
@Composable
private fun MintOptionButton(
    text: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            "❯ $text",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Red-ruled quiet action used for a denial. */
@Composable
private fun DangerGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, Danger.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .heightIn(min = 42.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = Danger,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

@Composable
private fun ChatInputBar(
    state: AppState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    working: Boolean,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
    voice: VoiceDictationState,
    attachEnabled: Boolean,
    cameraEnabled: Boolean,
    attachHint: String?,
    composerError: String?,
    onDismissComposerError: () -> Unit,
    sendError: String?,
    onDismissSendError: () -> Unit,
    onRetrySend: () -> Unit,
    canSend: Boolean,
    composerFocusRequester: FocusRequester? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        sendError?.let { message ->
            ComposerErrorRow(
                message = message,
                onDismiss = onDismissSendError,
                onRetry = onRetrySend,
            )
        }
        composerError?.let { message ->
            ComposerErrorRow(message = message, onDismiss = onDismissComposerError)
        }
        state.syncError?.let { message ->
            ComposerErrorRow(message = message, onDismiss = state::clearSyncError)
        }
        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        onRemove = { onRemoveAttachment(attachment.localId) },
                        onRetry = { onRetryAttachment(attachment.localId) },
                    )
                }
            }
        }
        attachHint?.let { hint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        VoiceStatusRow(voice = voice, onStop = onVoice)
        ChatComposer(
            value = draft,
            onValueChange = onDraftChange,
            onSend = onSend,
            working = working,
            onStop = state::stopWorking,
            attachEnabled = attachEnabled,
            onAttach = onAttach,
            cameraEnabled = cameraEnabled,
            onCamera = onCamera,
            onVoice = onVoice,
            voiceListening = voice.listening,
            voiceStarting = voice.starting,
            sendEnabled = canSend,
            focusRequester = composerFocusRequester,
        )
    }
}

/**
 * Composer quick action: schedule the current draft as a prompt for this session. Blank drafts
 * disable the action with an explanation; a successful create shows a brief confirmation.
 */
@Composable
private fun SchedulePromptActionRow(
    draftBlank: Boolean,
    enabled: Boolean,
    notice: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Schedule this prompt",
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = enabled && !draftBlank, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled && !draftBlank) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        if (draftBlank) {
            Spacer(Modifier.width(6.dp))
            Text(
                "write a prompt first",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (notice != null) {
            Spacer(Modifier.weight(1f))
            Text(
                notice,
                style = MaterialTheme.typography.labelSmall,
                color = Success,
            )
        }
    }
}

/** Small create dialog for "Schedule this prompt": title + after-minutes/daily timing. */
@Composable
private fun SchedulePromptDialog(
    defaultTitle: String,
    instruction: String,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, ScheduleTimingInput) -> Unit,
) {
    var title by remember { mutableStateOf(defaultTitle) }
    var mode by remember { mutableStateOf(ScheduleTimingMode.AfterMinutes) }
    var afterMinutes by remember { mutableStateOf("30") }
    var dailyTime by remember { mutableStateOf("09:00") }
    val afterValue = ScheduleTiming.parseAfterMinutes(afterMinutes)
    val dailyValue = ScheduleTiming.parseDailyTime(dailyTime)
    val timingValid = when (mode) {
        ScheduleTimingMode.AfterMinutes -> afterValue != null
        ScheduleTimingMode.Daily -> dailyValue != null
    }
    val canSubmit = !submitting && title.isNotBlank() && timingValid
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Schedule this prompt",
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
                    onValueChange = { title = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Title", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
                Text(
                    "INSTRUCTION · from the composer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    instruction.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "RUN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
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
                            onClick = { mode = ScheduleTimingMode.AfterMinutes },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (mode == ScheduleTimingMode.Daily) {
                        HadalPrimaryButton(
                            text = "Daily time",
                            onClick = { mode = ScheduleTimingMode.Daily },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        HadalGhostButton(
                            text = "Daily time",
                            onClick = { mode = ScheduleTimingMode.Daily },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                when (mode) {
                    ScheduleTimingMode.AfterMinutes -> OutlinedTextField(
                        value = afterMinutes,
                        onValueChange = {
                            afterMinutes = it.filter { ch -> ch.isDigit() }.take(7)
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
                        onValueChange = { dailyTime = it.take(5) },
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
            }
        },
        confirmButton = {
            Text(
                if (submitting) "Scheduling…" else "Create",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canSubmit) {
                        val timing = when (mode) {
                            ScheduleTimingMode.AfterMinutes ->
                                afterValue?.let { ScheduleTimingInput.AfterMinutes(it) }

                            ScheduleTimingMode.Daily ->
                                dailyValue?.let { ScheduleTimingInput.Daily(it) }
                        }
                        if (timing != null) onSubmit(title.trim(), timing)
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
                    .clickable(enabled = !submitting, onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

@Composable
private fun ComposerErrorRow(
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = Danger,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        onRetry?.let { retry ->
            Text(
                "retry",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = retry)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            "dismiss",
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceStatusRow(voice: VoiceDictationState, onStop: () -> Unit) {
    val status = voice.status ?: return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = if (voice.listening) {
                Danger
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            status,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = if (voice.listening) {
                Danger
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (voice.listening) {
            Spacer(Modifier.width(6.dp))
            Text(
                "stop",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onStop)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(start = 9.dp, top = 5.dp, bottom = 5.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnail = attachment.thumbnail
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Image attachment preview",
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.AttachFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.widthIn(max = 160.dp)) {
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                attachmentStatusLabel(attachment),
                style = MaterialTheme.typography.labelSmall,
                color = attachmentStatusColor(attachment.state),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        when (attachment.state) {
            is PendingAttachmentState.Uploading -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            is PendingAttachmentState.Uploaded -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Uploaded",
                tint = Success,
                modifier = Modifier.size(13.dp),
            )

            is PendingAttachmentState.Failed -> {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "Upload failed",
                    tint = Danger,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "retry",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove attachment",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

private fun attachmentStatusLabel(attachment: PendingAttachment): String =
    when (val attachmentState = attachment.state) {
        is PendingAttachmentState.Uploading -> {
            val percent = (attachmentState.fraction * 100f).toInt().coerceIn(0, 100)
            "${formatAttachmentSize(attachment.sizeBytes)} - uploading $percent%"
        }

        is PendingAttachmentState.Uploaded -> formatAttachmentSize(attachment.sizeBytes)
        is PendingAttachmentState.Failed -> attachmentState.message
    }

@Composable
private fun attachmentStatusColor(attachmentState: PendingAttachmentState): Color =
    when (attachmentState) {
        is PendingAttachmentState.Uploading,
        is PendingAttachmentState.Uploaded -> MaterialTheme.colorScheme.onSurfaceVariant

        is PendingAttachmentState.Failed -> Danger
    }

/** `❯` prompt gutter, mono placeholder with a resting block cursor, mint circular send. */
@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    working: Boolean,
    onStop: () -> Unit,
    attachEnabled: Boolean,
    onAttach: () -> Unit,
    cameraEnabled: Boolean,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
    voiceListening: Boolean,
    voiceStarting: Boolean,
    sendEnabled: Boolean,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (focused) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val focusGlow = if (focused) {
        Modifier.shadow(
            elevation = 14.dp,
            shape = shape,
            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
    } else {
        Modifier
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusGlow),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            Modifier.padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = onVoice,
                enabled = !voiceStarting,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = if (voiceListening) "Stop dictation" else "Dictate",
                    tint = if (voiceListening) {
                        Danger
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (voiceStarting) 0.35f else 1f,
                        )
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onCamera,
                enabled = cameraEnabled,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = "Take photo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (cameraEnabled) 1f else 0.35f,
                    ),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onAttach,
                enabled = attachEnabled,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (attachEnabled) 1f else 0.35f,
                    ),
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                Text(
                    "❯",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 3.dp, top = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 21.dp, end = 2.dp, top = 12.dp, bottom = 12.dp)
                        .onFocusChanged { focusState -> focused = focusState.isFocused }
                        .then(
                            if (focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Message DSH…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.7f),
                                    )
                                    Text(
                                        "▍",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary
                                            .copy(alpha = 0.85f),
                                    )
                                }
                            }
                            inner()
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (working) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop",
                            tint = Danger,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                FilledIconButton(
                    onClick = if (sendEnabled) onSend else ({ }),
                    enabled = sendEnabled,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (sendEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (sendEnabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    ),
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Prompt templates
// ---------------------------------------------------------------------------

private data class PromptTemplate(
    val title: String,
    val hint: String,
    val prompt: String,
)

private val PROMPT_TEMPLATES = listOf(
    PromptTemplate(
        title = "Continue",
        hint = "Pick up where the last turn stopped",
        prompt = "Continue where you left off. Re-check the current workspace state first, then finish the remaining work.",
    ),
    PromptTemplate(
        title = "Plan first",
        hint = "No edits yet — inspect and propose a plan",
        prompt = "Do not edit any files yet. Inspect the relevant code and explain a short implementation plan with risks and open questions.",
    ),
    PromptTemplate(
        title = "Review the diff",
        hint = "Look for bugs and risky assumptions",
        prompt = "Review the current uncommitted changes for bugs, risky assumptions, missing error handling, and regressions. Report findings first; fix only clear issues.",
    ),
    PromptTemplate(
        title = "Fix the build",
        hint = "Run tests, repair failures, verify",
        prompt = "Run the build and full test suite, find the root cause of any failure, fix it, and rerun until green. Report exactly what failed and what changed.",
    ),
    PromptTemplate(
        title = "Write tests",
        hint = "Cover the new behavior",
        prompt = "Add focused tests for the behavior we just changed, including edge cases. Run them and report the results.",
    ),
    PromptTemplate(
        title = "Commit and push",
        hint = "Ship the completed work",
        prompt = "Commit the completed work with a clear message and push it to the current branch. Verify the build/tests pass first.",
    ),
    PromptTemplate(
        title = "Explain simply",
        hint = "Short answer for a newcomer",
        prompt = "Explain what this code does and why, in plain language for someone new to the project. Keep it concise and concrete.",
    ),
    PromptTemplate(
        title = "Summarize session",
        hint = "Decisions, open questions, next steps",
        prompt = "Summarize this session so far: what was accomplished, key decisions, open questions, and the next concrete steps.",
    ),
)

@Composable
private fun PromptTemplatesSheet(
    recentPrompts: List<String>,
    snippets: List<PromptSnippet>,
    canSaveDraft: Boolean,
    onSaveDraft: () -> Unit,
    onDeleteSnippet: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps("Prompt templates")
        Spacer(Modifier.height(8.dp))
        Text(
            "Insert a starting prompt, then edit it before sending.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (snippets.isNotEmpty()) {
            HadalCaps("Saved")
            Spacer(Modifier.height(2.dp))
            snippets.take(6).forEach { snippet ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(snippet.prompt) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        snippet.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "×",
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onDeleteSnippet(snippet.id) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (canSaveDraft) {
            HadalGhostButton(
                text = "Save current draft as snippet",
                onClick = onSaveDraft,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
        if (recentPrompts.isNotEmpty()) {
            HadalCaps("Recent")
            Spacer(Modifier.height(2.dp))
            recentPrompts.take(5).forEach { recent ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(recent) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        recent.replace("\n", " "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HadalCaps("Templates")
            Spacer(Modifier.height(2.dp))
        }
        PROMPT_TEMPLATES.forEachIndexed { index, template ->
            if (index > 0) Spacer(Modifier.height(2.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(template.prompt) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    template.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    template.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// In-screen sheets — real session data only
// ---------------------------------------------------------------------------

/** Read-only, newest-first trajectory journal with beforeSeq pagination. */
@Composable
private fun TrajectorySheet(
    state: AppState,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps(TRAJECTORY_SHEET_TITLE)
        Spacer(Modifier.height(8.dp))
        Text(
            TRAJECTORY_READ_ONLY_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val page = state.trajectory
        when {
            page == null && state.trajectoryLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    TRAJECTORY_LOADING_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            page == null && state.trajectoryError != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    TRAJECTORY_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    TRAJECTORY_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retryTrajectory() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            page == null -> Text(
                TRAJECTORY_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            !page.supported -> Text(
                TRAJECTORY_UNSUPPORTED_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            page.events.isEmpty() -> Text(
                TRAJECTORY_EMPTY_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(page.events, key = { event -> event.seq }) { event ->
                    TrajectoryEventRow(event)
                }
                if (page.hasMore) {
                    item(key = "load-older") {
                        if (state.trajectoryOlderLoading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    TRAJECTORY_LOADING_OLDER_COPY,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        } else {
                            HadalGhostButton(
                                text = TRAJECTORY_LOAD_OLDER_LABEL,
                                onClick = { state.loadOlderTrajectory() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (state.trajectoryError != null && page != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TRAJECTORY_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    TRAJECTORY_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retryTrajectory() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isLive) {
                HadalGhostButton(
                    text = TRAJECTORY_REFRESH_LABEL,
                    onClick = { state.refreshTrajectory() },
                    modifier = Modifier.weight(1f),
                )
            }
            HadalGhostButton(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrajectoryEventRow(event: dev.dsh.mobile.data.TrajectoryEvent) {
    val error = isTrajectoryErrorStatus(event.status)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(event.typeLabel)
                    if (event.summary.isNotBlank()) {
                        append(", ")
                        append(event.summary)
                    }
                    event.status?.let { status ->
                        append(", ")
                        append(status)
                    }
                }
            }
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("#")
                    append(event.seq)
                    if (event.time.isNotBlank()) {
                        append("  ")
                        append(event.time)
                    }
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                event.typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
            )
        }
        Text(
            event.summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        event.status?.let { status ->
            if (error) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Read-only background jobs: counts, running-first list, durations and failure detail. */
@Composable
private fun JobsSheet(
    state: AppState,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps(JOBS_SHEET_TITLE)
        Spacer(Modifier.height(8.dp))
        Text(
            JOBS_READ_ONLY_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val jobs = state.jobs
        when {
            jobs == null && state.jobsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    JOBS_LOADING_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            jobs == null && state.jobsError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    JOBS_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    JOBS_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retryJobs() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            jobs == null -> Text(
                JOBS_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            !jobs.supported -> Text(
                JOBS_UNSUPPORTED_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            jobs.jobs.isEmpty() -> Text(
                JOBS_EMPTY_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            else -> {
                Text(
                    jobCountsSummary(jobs.counts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(orderedJobs(jobs.jobs), key = { job -> job.id }) { job ->
                        JobRow(job)
                    }
                }
            }
        }
        if (state.jobsLoading && jobs != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    JOBS_LOADING_COPY,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (state.jobsError != null && jobs != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    JOBS_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    JOBS_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retryJobs() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isLive) {
                HadalGhostButton(
                    text = JOBS_REFRESH_LABEL,
                    onClick = { state.refreshJobs() },
                    modifier = Modifier.weight(1f),
                )
            }
            HadalGhostButton(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun JobRow(job: dev.dsh.mobile.data.JobEntry) {
    val statusColor = when (job.status) {
        JobStatus.Running -> MaterialTheme.colorScheme.primary
        JobStatus.Pending -> MaterialTheme.colorScheme.tertiary
        JobStatus.Completed -> Success
        JobStatus.Failed -> Danger
        JobStatus.Stopped -> Warning
    }
    val duration = formatJobDuration(job.durationMs)
    val started = jobStartedLabel(job.startedAt)
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${job.kindLabel}, ${job.status.label}, ${job.label}"
            }
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                job.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
            )
        }
        Text(
            job.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (duration.isNotEmpty() || started.isNotEmpty()) {
            Text(
                listOf(duration, started).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        jobFailureDetail(job)?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Read-only workspace browser: dirs/files, breadcrumb/Up, text preview, external raw open. */
@Composable
private fun FilesSheet(
    state: AppState,
    onDismiss: () -> Unit,
    onMentionFile: (String) -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        if (state.filePreviewPath != null) {
            FilePreviewPanel(state = state, onDismiss = onDismiss, onMentionFile = onMentionFile)
            return@DshBottomSheet
        }
        HadalCaps(FILES_SHEET_TITLE)
        Spacer(Modifier.height(8.dp))
        Text(
            workspacePathLabel(state.filesPath),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.filesPath.isNotEmpty()) {
                HadalGhostButton(
                    text = FILES_UP_LABEL,
                    onClick = { state.filesUp() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                )
            }
            HadalGhostButton(
                text = FILES_REFRESH_LABEL,
                onClick = { state.refreshFiles() },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        state.filesNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }
        val page = state.filesPage
        when {
            page == null && state.filesLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    FILES_LOADING_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            page == null && state.filesError != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    FILES_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    FILES_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retryFiles() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            page == null -> Text(
                FILES_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            !page.supported -> Text(
                FILES_UNSUPPORTED_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            page.entries.isEmpty() -> Text(
                FILES_EMPTY_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(
                    dirFirstEntries(page.entries),
                    key = { entry -> entry.path + "/" + entry.name },
                ) { entry ->
                    WorkspaceEntryRow(
                        entry = entry,
                        opening = state.filesOpeningRawPath == entry.path,
                        onClick = { state.openWorkspaceEntry(entry) },
                    )
                }
            }
        }
        if (state.filesError != null && page != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    FILES_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    FILES_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retryFiles() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        )
    }
}

@Composable
private fun WorkspaceEntryRow(
    entry: dev.dsh.mobile.data.WorkspaceEntry,
    opening: Boolean,
    onClick: () -> Unit,
) {
    val sizeLabel = if (entry.isDirectory) "" else formatFileSize(entry.bytes)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(if (entry.isDirectory) "Folder" else "File")
                    append(", ")
                    append(entry.name)
                    if (sizeLabel.isNotEmpty()) {
                        append(", ")
                        append(sizeLabel)
                    }
                }
                role = Role.Button
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.kind == WorkspaceEntryKind.Dir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sizeLabel.isNotEmpty()) {
                Text(
                    sizeLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (opening) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun FilePreviewPanel(
    state: AppState,
    onDismiss: () -> Unit,
    onMentionFile: (String) -> Unit,
) {
    HadalCaps("Preview")
    Spacer(Modifier.height(6.dp))
    Text(
        state.filePreviewName.ifBlank { "File" },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
    val content = state.filePreview
    when {
        state.filePreviewLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                FILES_PREVIEW_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        content == null && state.filePreviewError != null -> Column {
            Text(
                state.filePreviewError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalGhostButton(
                    text = FILES_RETRY_LABEL,
                    onClick = { state.retryFilePreview() },
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = FILES_OPEN_EXTERNAL_LABEL,
                    onClick = {
                        state.filePreviewPath?.let { path ->
                            state.openWorkspaceFileExternally(path, state.filePreviewName)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        content != null -> {
            Text(
                buildString {
                    append(formatFileSize(content.bytes))
                    if (content.mimeType.isNotBlank()) {
                        append(" · ")
                        append(content.mimeType)
                    }
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (content.truncated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    FILES_PREVIEW_TRUNCATED_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (content.text == FILES_EMPTY_FILE_COPY || content.text.isEmpty()) {
                Text(
                    FILES_EMPTY_FILE_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            content.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HadalGhostButton(
                        text = "Mention in prompt",
                        onClick = { onMentionFile(content.path) },
                        modifier = Modifier.weight(1f),
                    )
                    HadalGhostButton(
                        text = FILES_OPEN_EXTERNAL_LABEL,
                        onClick = {
                            state.openWorkspaceFileExternally(
                                content.path,
                                content.name.ifBlank { state.filePreviewName },
                                content.mimeType,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                HadalGhostButton(
                    text = "Back",
                    onClick = { state.clearFilePreview() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        else -> {
            Text(
                state.filePreviewError ?: FILES_PREVIEW_FAILED_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (content != null) {
            HadalGhostButton(
                text = "Back",
                onClick = { state.clearFilePreview() },
                modifier = Modifier.weight(1f),
            )
        }
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Read-only Agent Teams board: counts, members/phases and active tasks with dependencies. */
@Composable
private fun TeamSheet(
    state: AppState,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps(TEAM_SHEET_TITLE)
        Spacer(Modifier.height(8.dp))
        Text(
            "Read-only view of the agent team and its active tasks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val team = state.team
        when {
            team == null && state.teamLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    TEAM_LOADING_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            team == null && state.teamError != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    TEAM_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    TEAM_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retrySessionTeam() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            team == null -> Text(
                TEAM_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            !team.supported -> Text(
                TEAM_UNSUPPORTED_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            team.members.isEmpty() && team.tasks.isEmpty() -> Text(
                TEAM_EMPTY_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            else -> {
                Text(
                    teamCountsSummary(team.counts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (team.members.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HadalCaps(TEAM_AGENTS_HEADING)
                    team.members.forEach { member ->
                        TeamMemberRow(member)
                    }
                }
                Spacer(Modifier.height(10.dp))
                HadalCaps(TEAM_TASKS_HEADING)
                if (team.tasks.isEmpty()) {
                    Text(
                        TEAM_EMPTY_COPY,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(orderedTeamTasks(team.tasks)) { task ->
                            TeamTaskRow(task)
                        }
                    }
                }
            }
        }
        if (state.teamLoading && team != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    TEAM_LOADING_COPY,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isLive) {
                HadalGhostButton(
                    text = "Refresh",
                    onClick = { state.retrySessionTeam() },
                    modifier = Modifier.weight(1f),
                )
            }
            HadalGhostButton(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TeamMemberRow(member: dev.dsh.mobile.data.TeamMember) {
    val phase = teamMemberPhaseLabel(member.phase)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${member.name}, $phase"
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            member.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            phase,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TeamTaskRow(task: dev.dsh.mobile.data.TeamTask) {
    val statusColor = when (task.status) {
        TeamTaskStatus.Completed -> Success
        TeamTaskStatus.InProgress -> MaterialTheme.colorScheme.primary
        TeamTaskStatus.Blocked -> Warning
        TeamTaskStatus.Pending -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(task.subject)
                    append(", ")
                    append(task.status.label)
                    teamTaskBlockedByLabel(task)?.let { blocked ->
                        append(", ")
                        append(blocked)
                    }
                }
            }
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.subject,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                task.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                maxLines = 1,
            )
        }
        teamTaskBlockedByLabel(task)?.let { blocked ->
            Text(
                blocked,
                style = MaterialTheme.typography.labelSmall,
                color = Warning,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Read-only plan/todo sheet: progress, checklist states, refresh and honest empty/error states. */
@Composable
private fun PlanSheet(
    state: AppState,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps(PLAN_SHEET_TITLE)
        Spacer(Modifier.height(8.dp))
        Text(
            "The agent's plan for this session. Nothing here changes it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val plan = state.plan
        when {
            plan == null && state.planLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    PLAN_LOADING_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            plan == null && state.planError != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    PLAN_LOAD_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    PLAN_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retrySessionPlan() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            plan == null -> Text(
                PLAN_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            !plan.supported -> Text(
                PLAN_UNSUPPORTED_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            plan.todos.isEmpty() -> Text(
                PLAN_EMPTY_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            else -> {
                Text(
                    planProgressLabel(plan.counts),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(orderedPlanTodos(plan.todos)) { todo ->
                        PlanTodoRow(todo = todo)
                    }
                }
            }
        }
        if (state.planLoading && plan != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    PLAN_LOADING_COPY,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isLive) {
                HadalGhostButton(
                    text = "Refresh",
                    onClick = { state.retrySessionPlan() },
                    modifier = Modifier.weight(1f),
                )
            }
            HadalGhostButton(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlanTodoRow(todo: dev.dsh.mobile.data.PlanTodo) {
    val marker = when (todo.status) {
        PlanTodoStatus.Completed -> "✓"
        PlanTodoStatus.InProgress -> "◐"
        PlanTodoStatus.Pending -> "○"
    }
    val markerColor = when (todo.status) {
        PlanTodoStatus.Completed -> Success
        PlanTodoStatus.InProgress -> MaterialTheme.colorScheme.primary
        PlanTodoStatus.Pending -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${todo.status.label}: ${todo.content}"
            }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            marker,
            modifier = Modifier.width(20.dp),
            style = MaterialTheme.typography.bodySmall,
            color = markerColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                todo.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                todo.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = markerColor,
                maxLines = 1,
            )
        }
    }
}

/** Chat skills browser: cached fetch, empty/unsupported/error states, tap to prefill composer. */
@Composable
private fun SkillsSheet(
    state: AppState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps(SKILLS_SHEET_TITLE)
        Spacer(Modifier.height(8.dp))
        Text(
            "Insert a skill prompt, then edit it before sending.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val result = state.skills
        when {
            result == null && state.skillsLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    SKILLS_LOADING_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            result == null && state.skillsError != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.skillsError.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    SKILLS_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retrySessionSkills() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            result == null -> Text(
                SKILLS_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            !result.supported -> Text(
                if (result.sessionUnavailable) {
                    SKILLS_SESSION_UNAVAILABLE_COPY
                } else {
                    SKILLS_UNSUPPORTED_COPY
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            result.skills.isEmpty() -> Text(
                SKILLS_EMPTY_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(result.skills, key = { skill -> skill.name }) { skill ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onPick(skill.name) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = if (skill.description.isBlank()) {
                                    skill.name
                                } else {
                                    "${skill.name}. ${skill.description}"
                                }
                                role = Role.Button
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            skill.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (skill.description.isNotBlank()) {
                            Text(
                                skill.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (state.skillsLoading && result != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    SKILLS_LOADING_COPY,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        if (state.skillsError != null && result != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SKILLS_REFRESH_FAILED_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    SKILLS_RETRY_LABEL,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { state.retrySessionSkills() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Shared shell-style bottom sheet: 20dp top radius, drawer-toned surface, drag handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DshBottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            content = content,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Read-only context-window meter inside Session Info: percent bar, tokens, warnings, breakdown. */
@Composable
private fun ContextSection(state: AppState, sessionId: String) {
    val context = state.contextFor(sessionId)
    val usage = context?.usage
    val percent = contextPercent(usage)
    val rawPercent = contextPercentRaw(usage)
    val meterA11yLabel = contextPercentA11yLabel(usage)
    val level = contextWarningLevel(percent)
    val warningColor = when (level) {
        ContextWarningLevel.Normal -> MaterialTheme.colorScheme.primary
        ContextWarningLevel.Amber -> Warning
        ContextWarningLevel.Red -> Danger
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HadalCaps(CONTEXT_SECTION_TITLE)
        Spacer(Modifier.weight(1f))
        if (state.isLive && context?.supported != false) {
            Text(
                CONTEXT_REFRESH_LABEL,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { state.refreshContext() }
                    .semantics {
                        contentDescription = "Refresh context usage"
                        role = Role.Button
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    when {
        context == null && state.contextLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                CONTEXT_LOADING_COPY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        context == null && state.contextError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                CONTEXT_LOAD_FAILED_COPY,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                CONTEXT_RETRY_LABEL,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { state.retryContext() }
                    .semantics {
                        contentDescription = "Retry loading context usage"
                        role = Role.Button
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        context == null -> Text(
            CONTEXT_LOADING_COPY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        !context.supported -> Text(
            CONTEXT_UNSUPPORTED_COPY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        usage == null -> Text(
            CONTEXT_UNKNOWN_COPY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        else -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (percent != null) {
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.labelLarge,
                        color = warningColor,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    contextTokensSummary(usage) ?: CONTEXT_UNKNOWN_COPY,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (percent != null) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .semantics {
                            // C4: the visual bar is also an accessible progress control.
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = (rawPercent ?: percent.toDouble()).toFloat()
                                    .coerceIn(0f, 100f),
                                range = 0f..100f,
                            )
                            meterA11yLabel?.let { contentDescription = it }
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(
                                ((rawPercent ?: percent.toDouble()) / 100.0)
                                    .toFloat()
                                    .coerceIn(0f, 1f),
                            )
                            .fillMaxHeight()
                            .background(warningColor),
                    )
                }
            }
            contextWarningCopy(level)?.let { copy ->
                Spacer(Modifier.height(4.dp))
                Text(
                    copy,
                    style = MaterialTheme.typography.labelSmall,
                    color = warningColor,
                )
            }
            contextPressureSummary(usage)?.let { pressure ->
                Spacer(Modifier.height(2.dp))
                Text(
                    pressure,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            val breakdown = orderedContextBreakdown(usage.breakdown)
            if (breakdown.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    CONTEXT_BREAKDOWN_HEADING,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                breakdown.forEach { (label, tokens) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${formatCompactTokenCount(tokens)} tokens",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            contextSampledLabel(usage.sampledAt)?.let { sampled ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "sampled $sampled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SessionInfoSheet(
    state: AppState,
    session: Session,
    tools: List<ToolActivity>,
    onExport: () -> Unit,
    onExportFile: () -> Unit,
    exportingPdf: Boolean,
    onExportPdf: () -> Unit,
    onOpenTeam: () -> Unit,
    onOpenTrajectory: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenJobs: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val workspace = state.workspaceFor(session.workspaceId)
    val attachmentCount = session.messages
        .filterIsInstance<UserMessage>()
        .sumOf { message -> message.attachments.size }
    val host = state.hostLabel
    val lineage = sessionParentUi(session, state.sessions)
    val preset = sanitizedAgentPreset(session.agentPreset)

    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps("Session")
        Spacer(Modifier.height(8.dp))
        Text(
            session.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        preset?.let { value ->
            Spacer(Modifier.height(4.dp))
            Text(
                "Preset: $value",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        HadalCard {
            InfoRow("session", session.id)
            InfoRow("workspace", workspace.name)
            if (workspace.path.isNotBlank()) {
                InfoRow("path", workspace.path)
            }
            InfoRow("status", sessionStatusLabel(session.status))
            InfoRow("updated", session.updated)
            InfoRow("messages", session.messages.size.toString())
            InfoRow("tool calls", tools.size.toString())
            delegationBadgeLabel(
                session.delegationTotal,
                session.delegationWorking,
                "subagent",
            )?.let { label ->
                InfoRow("delegation", label)
            }
            if (attachmentCount > 0) {
                InfoRow("attachments", attachmentCount.toString())
            }
        }
        ContextSection(state = state, sessionId = session.id)
        lineage?.let { parent ->
            Spacer(Modifier.height(10.dp))
            HadalCard {
                Text(
                    sessionForkedFromLabel(parent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (parent.available) parent.id else parent.shortId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (parent.available) {
                    Spacer(Modifier.height(4.dp))
                    HadalGhostButton(
                        text = SESSION_OPEN_PARENT_LABEL,
                        onClick = {
                            onDismiss()
                            state.openSession(parent.id)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = sessionOpenParentContentDescription(parent)
                                role = Role.Button
                            },
                    )
                } else {
                    // L6: never show a dead/disabled control; say the parent is unavailable.
                    Spacer(Modifier.height(2.dp))
                    Text(
                        SESSION_PARENT_UNAVAILABLE,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HadalCard {
            InfoRow("connection", connectionLabel(state.connection))
            if (host != null) {
                InfoRow("host", host)
            }
        }
        Spacer(Modifier.height(14.dp))
        val teamHint = state.team
            ?.takeIf { it.supported }
            ?.counts
            ?.let { teamActiveHint(it) }
        HadalGhostButton(
            text = if (teamHint != null) "$TEAM_ACTION_LABEL · $teamHint" else TEAM_ACTION_LABEL,
            onClick = onOpenTeam,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = teamActionContentDescription(
                        state.team?.takeIf { it.supported }?.counts,
                    )
                    role = Role.Button
                },
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = TRAJECTORY_ACTION_LABEL,
            onClick = onOpenTrajectory,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = "Open trajectory"
                    role = Role.Button
                },
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = FILES_ACTION_LABEL,
            onClick = onOpenFiles,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = "Browse workspace files"
                    role = Role.Button
                },
        )
        Spacer(Modifier.height(8.dp))
        val jobsHint = state.jobs
            ?.takeIf { it.supported }
            ?.counts
            ?.let { jobRunningHint(it) }
        HadalGhostButton(
            text = if (jobsHint != null) "$JOBS_ACTION_LABEL · $jobsHint" else JOBS_ACTION_LABEL,
            onClick = onOpenJobs,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = jobsActionContentDescription(
                        state.jobs?.takeIf { it.supported }?.counts,
                    )
                    role = Role.Button
                },
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = "Export transcript",
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = "Share transcript file",
            onClick = onExportFile,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = if (exportingPdf) "Exporting PDF…" else "Export PDF",
            onClick = { if (!exportingPdf) onExportPdf() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = "Hide from session list",
            onClick = onHide,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToolActivitySheet(
    tools: List<ToolActivity>,
    onDismiss: () -> Unit,
) {
    DshBottomSheet(onDismiss = onDismiss) {
        HadalCaps("Tool Activity")
        Spacer(Modifier.height(8.dp))
        Text(
            if (tools.isEmpty()) {
                "No tool calls in this session yet."
            } else {
                "${tools.size} tool calls in this session"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tools.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tools) { tool ->
                    ToolRowCard(tool)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        HadalGhostButton(
            text = "Close",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Attachment helpers (business logic unchanged from the v0.2.2 flow)
// ---------------------------------------------------------------------------

/** Hosts older than v0.2.2 answer attachments with 501 `unsupported`; say that clearly inline. */
private fun attachmentErrorMessage(state: AppState, error: Throwable): String =
    if (error is DshApiException.Unsupported) {
        "Attachments aren't supported by this host yet. Update the desktop host plugin and retry."
    } else {
        state.errorMessage(error)
    }

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

/**
 * Reads the picked document/image into memory with the decoded limit enforced while
 * streaming, so an arbitrarily large file can never exhaust the app's heap.
 */
private suspend fun readPickedAttachment(
    context: Context,
    uri: Uri,
): Result<PickedAttachment> = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        var name = "attachment"
        var declaredSize = -1L
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: name
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex)
                }
            }
        }
        if (declaredSize > AttachmentLimits.MAX_DECODED_BYTES) {
            throw IllegalArgumentException(
                "${name.ifBlank { "That file" }} is ${formatAttachmentSize(declaredSize)} - " +
                    "the attachment limit is " + formatAttachmentSize(AttachmentLimits.MAX_DECODED_BYTES) + ".",
            )
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > AttachmentLimits.MAX_DECODED_BYTES) {
                    throw IllegalArgumentException(
                        "${name.ifBlank { "That file" }} is larger than the " + formatAttachmentSize(AttachmentLimits.MAX_DECODED_BYTES) + " attachment limit.",
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalStateException("Couldn't open that file.")
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("That file is empty.")
        }
        PickedAttachment(name = name, mimeType = mimeType, bytes = bytes)
    }
}

// region model + thinking picker dialog

/**
 * Session model and thinking-effort picker: shows the effective selection + source, the host
 * catalog's provider groups, the model's reasoning efforts (with a "Default" choice when the model
 * advertises any), and saves through PUT /sessions/{id}/model. Cold/unsupported hosts keep the
 * dialog explanatory and disable Save.
 */
@Composable
private fun ModelPickerDialog(state: AppState, onDismiss: () -> Unit) {
    val catalog = state.modelCatalog
    val current = state.sessionModel
    val fallback = current?.selection ?: catalog?.default
    var provider by remember { mutableStateOf(fallback?.provider.orEmpty()) }
    var modelId by remember { mutableStateOf(fallback?.model.orEmpty()) }
    var effort by remember { mutableStateOf(fallback?.reasoningEffort) }
    LaunchedEffect(current, catalog) {
        val selection = current?.selection ?: catalog?.default
        if (selection != null) {
            provider = selection.provider
            modelId = selection.model
            // M6: a saved effort is only shown when the selected model advertises it.
            effort = sanitizedModelEffort(catalog, selection)
        }
    }
    val selected = if (provider.isNotBlank() && modelId.isNotBlank()) {
        ModelSelection(provider, modelId, effort)
    } else {
        null
    }
    val efforts = modelEffortsFor(catalog, selected)
    val sessionLive = current?.live == true
    val supported = catalog?.supported != false && current?.supported != false
    val loading = (catalog == null && state.modelCatalogLoading) ||
        (current == null && state.sessionModelLoading)
    val changed = modelSelectionChanged(selected, current?.selection)
    val canSave = supported && sessionLive && modelSelectionValid(selected) && changed &&
        !state.modelSaving
    AlertDialog(
        onDismissRequest = { if (!state.modelSaving) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Model & thinking",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Loading model catalog…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    !supported -> Text(
                        "This host doesn't support model selection yet. Update the desktop " +
                            "host plugin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    !sessionLive -> Text(
                        "Changes require a live session. Reconnect to the host to pick a model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> {
                        Text(
                            "Current: ${modelDisplayName(catalog, current?.selection)} · " +
                                sessionModelSourceLabel(current?.source.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        // M1: this is a *default* change too — keep it prominent, not fine print.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                "Also changes your default model",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Saving updates this session's next request AND becomes the " +
                                    "default model for new sessions on this host (DSH saves it in " +
                                    "the background).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        catalog?.groups.orEmpty().forEach { group ->
                            val groupFailure = modelFailureForGroup(catalog, group)
                            Text(
                                buildString {
                                    append(group.name.ifBlank { group.id })
                                    if (groupFailure != null) append(" · unavailable")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (groupFailure != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                            groupFailure?.let { failure ->
                                Text(
                                    "Unavailable: ${failure.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            group.models.forEach { modelItem ->
                                val isSelected =
                                    provider == group.id && modelId == modelItem.id
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                                    .copy(alpha = 0.12f)
                                            } else {
                                                androidx.compose.ui.graphics.Color.Transparent
                                            },
                                        )
                                        .clickable(enabled = groupFailure == null) {
                                            provider = group.id
                                            modelId = modelItem.id
                                            // M6: reset to the newly selected model's advertised default.
                                            effort = modelDefaultEffortId(
                                                catalog,
                                                group.id,
                                                modelItem.id,
                                            )
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        if (isSelected) "●" else "○",
                                        modifier = Modifier.padding(horizontal = 5.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                    )
                                    Column {
                                        Text(
                                            modelItem.name.ifBlank { modelItem.id },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (groupFailure != null) {
                                                MaterialTheme.colorScheme.tertiary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        modelItem.description?.let { description ->
                                            Text(
                                                description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (efforts.isNotEmpty()) {
                            Text(
                                "Thinking effort",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            val defaultEffortId = modelDefaultEffortId(catalog, provider, modelId)
                            val defaultEffortName = efforts
                                .firstOrNull { it.id == defaultEffortId }
                                ?.name
                                ?.ifBlank { defaultEffortId }
                            ChoiceRow(
                                label = if (defaultEffortName.isNullOrBlank()) {
                                    "Default"
                                } else {
                                    "Default ($defaultEffortName)"
                                },
                                selected = effort == null,
                                onClick = { effort = null },
                            )
                            efforts.forEach { choice ->
                                ChoiceRow(
                                    label = choice.name.ifBlank { choice.id },
                                    selected = effort == choice.id,
                                    onClick = { effort = choice.id },
                                )
                            }
                        }
                        catalog?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
                            Text(
                                "Some models couldn't load: " + failures.joinToString("; ") {
                                    "${it.name.ifBlank { it.id }}: ${it.message}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (catalog?.truncated == true) {
                            Text(
                                "Some models were omitted by the host.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        state.modelCatalogError?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        state.sessionModelError?.let { error ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                // M11: a failed save never replaces the current selection.
                                Text(
                                    "Your previous selection is still active. Retry or pick " +
                                        "another model.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        state.modelStatus?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                if (state.modelSaving) "Saving…" else "Save",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = canSave) {
                        state.saveSessionModel(provider, modelId, effort)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (canSave) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        },
        dismissButton = {
            Text(
                "Close",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { if (!state.modelSaving) onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            modifier = Modifier.padding(horizontal = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// endregion

// region host queue panel

/** Compact chat strip: Queue - N | Waiting on the host (local outbox is separate). */
@Composable
private fun HostQueueStrip(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Queue - $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Waiting on the host - tap to review",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Host queue panel: items waiting on the desktop host (not the local offline outbox). Supports
 * inline edit, remove (confirmed) and steer for next-turn items, with a manual refresh.
 */
@Composable
private fun HostQueueDialog(
    state: AppState,
    sessionWorking: Boolean,
    onDismiss: () -> Unit,
) {
    val queue = state.hostQueue
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var removeItemId by remember { mutableStateOf<String?>(null) }
    var steerItemId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (state.hostQueueSavingItemId == null) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Host queue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Waiting on the host (desktop). These are separate from this phone's " +
                        "local offline queue, which stays in the composer until it can send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                when {
                    state.hostQueueLoading && queue == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Loading host queue...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    queue?.supported == false -> Text(
                        "This host doesn't support the queue panel yet. Update the desktop " +
                            "host plugin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    queue == null && state.hostQueueError != null -> Text(
                        state.hostQueueError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    queue != null && !queue.live -> Text(
                        "The host queue is only available for a live session. Reconnect to " +
                            "the host to see it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    queue != null && queue.items.isEmpty() -> Text(
                        "Nothing is waiting on the host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )

                    queue != null -> {
                        queue.items.forEach { item ->
                            HostQueueItemRow(
                                item = item,
                                sessionWorking = sessionWorking,
                                saving = state.hostQueueSavingItemId == item.itemId,
                                editing = editingItemId == item.itemId,
                                editText = editText,
                                onEditTextChange = { editText = it },
                                onStartEdit = {
                                    editingItemId = item.itemId
                                    editText = item.text
                                },
                                onCancelEdit = { editingItemId = null },
                                onSaveEdit = {
                                    state.editHostQueueItem(item.itemId, editText)
                                    editingItemId = null
                                },
                                onRemove = { removeItemId = item.itemId },
                                onSteer = { steerItemId = item.itemId },
                            )
                        }
                        if (hostQueueAtCap(queue)) {
                            Text(
                                "The host keeps at most 100 waiting prompts; this list is at " +
                                    "the cap.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        state.hostQueueError?.let { error ->
                            Text(
                                error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        state.hostQueueStatus?.let { status ->
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                "Refresh",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { state.refreshHostQueue() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        dismissButton = {
            Text(
                "Close",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { if (state.hostQueueSavingItemId == null) onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        },
    )
    removeItemId?.let { itemId ->
        AlertDialog(
            onDismissRequest = { removeItemId = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Remove queued prompt?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "This removes it from the host queue. It won't be sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Remove",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            removeItemId = null
                            state.removeHostQueueItem(itemId)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { removeItemId = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
    steerItemId?.let { itemId ->
        AlertDialog(
            onDismissRequest = { steerItemId = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Steer this prompt?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "Sends this prompt into the running turn now, ahead of its current work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Text(
                    "Steer into running turn",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            steerItemId = null
                            state.steerHostQueueItem(itemId)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { steerItemId = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    }
}

@Composable
private fun HostQueueItemRow(
    item: HostQueueItem,
    sessionWorking: Boolean,
    saving: Boolean,
    editing: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onRemove: () -> Unit,
    onSteer: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            hostQueueItemPreview(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hostQueueTargetLabel(item.target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            queueItemAttachmentLabel(item.attachmentCount)?.let { attachmentLabel ->
                Spacer(Modifier.width(6.dp))
                Text(
                    "- $attachmentLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (saving) {
                Spacer(Modifier.weight(1f))
                Text(
                    "Saving...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (editing) {
            OutlinedTextField(
                value = editText,
                onValueChange = onEditTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Edit queued prompt") },
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Save",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = !saving) { onSaveEdit() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Cancel",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onCancelEdit() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else if (!saving) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hostQueueEditable(item)) {
                    Text(
                        "Edit",
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onStartEdit() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        "Edit unavailable",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "Remove",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onRemove() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (hostQueueCanSteer(item, sessionWorking)) {
                    Text(
                        "Steer into running turn",
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSteer() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!hostQueueEditable(item)) {
                Text(
                    "Text editing is unavailable for attachment prompts (editing would drop " +
                        "their attachments).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (hostQueueCanSteer(item, sessionWorking)) {
                Text(
                    "Sends this prompt into the running turn now.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (item.target == "next-turn" && !sessionWorking) {
                Text(
                    "Steer is available while the agent is running.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

// endregion