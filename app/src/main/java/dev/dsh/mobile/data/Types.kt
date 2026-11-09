package dev.dsh.mobile.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/**
 * A host question waiting for an answer (contract `Question`). The Android UI renders the options
 * as buttons; free-text answers are accepted by the API but not needed for v0.2.
 */
data class Question(
    val id: String,
    val sessionId: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val createdAt: String = "",
)

/** Connection state surfaced as the Live / Demo / Offline pill and in Settings. */
enum class ConnectionStatus { Demo, Connecting, Live, Offline }

/** Compact "goal <phase>" session-list badge; null when the session has no goal phase. */
object GoalPhaseBadge {
    fun label(phase: String?): String? {
        val normalized = phase?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return "goal $normalized"
    }
}

/** A prompt the user sent while the agent was still working; sent when the turn goes idle. */
data class QueuedPrompt(
    val id: Long,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
)

/** An attachment uploaded to the host, ready to be referenced by a prompt. */
data class UploadedAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: Long,
    /** "file" or "image" (contract v0.2.2). */
    val kind: String,
)

/** A historical message attachment returned with a transcript message (read-back contract). */
data class MessageAttachment(
    val id: String,
    /** "image" or "file"; the host may omit it for older rows. */
    val kind: String = "file",
    val name: String = "",
    val mimeType: String = "",
    val bytes: Long = 0L,
    val width: Int? = null,
    val height: Int? = null,
) {
    /** True when the host marks this as an image (or it carries an image mime type). */
    val isImage: Boolean
        get() = kind.equals("image", ignoreCase = true) ||
            mimeType.startsWith("image/", ignoreCase = true)
}

/** Safe cache file name for a downloaded attachment: "<id>-<sanitized name>". */
internal fun attachmentCacheFileName(attachmentId: String, name: String): String {
    val safeId = attachmentId.trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(64)
        .ifBlank { "attachment" }
    val safeName = name.trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(80)
        .ifBlank { "attachment" }
    return "$safeId-$safeName"
}

// region agent presets

/** One host agent preset shown in the new-session sheet. */
/** One installed package in the host's active DSH profile (from GET /plugins). */
data class ProfilePlugin(
    val name: String,
    val version: String = "",
    val specifier: String = "",
    val local: Boolean = false,
) {
    /** "0.4.2" / "local link" / "installed". */
    val summary: String get() = when {
        local -> "local link"
        version.isNotBlank() -> "v$version"
        else -> "installed"
    }
}

/** Installed-plugin inventory for the active profile; unsupported hosts return [Unsupported]. */
data class PluginCatalog(
    val supported: Boolean,
    val profile: String = "",
    val packages: List<ProfilePlugin> = emptyList(),
    val bundles: List<String> = emptyList(),
    val truncated: Boolean = false,
) {
    companion object {
        val Unsupported = PluginCatalog(supported = false)
    }
}

data class AgentPreset(
    /** Machine id sent to POST /sessions. */
    val id: String,
    val name: String = "",
    val description: String? = null,
    val isDefault: Boolean = false,
    /** The host knows this preset but cannot run it; the chip is disabled. */
    val broken: Boolean = false,
) {
    /** Display label: name when present, otherwise the id. */
    val label: String get() = name.ifBlank { id }
}

/** Host preset roster; old/unsupported hosts fall back to [Fallback]. */
data class PresetRoster(
    val supported: Boolean,
    val presets: List<AgentPreset> = emptyList(),
    val defaultId: String? = null,
) {
    /** True when the fallback chips are in use because the host has no presets service. */
    val isFallback: Boolean get() = !supported

    /** Preset selected when the sheet opens: defaultId, then isDefault, then the first usable. */
    fun initialSelection(): AgentPreset? {
        val usable = presets.filterNot { it.broken }
        return usable.firstOrNull { it.id == defaultId }
            ?: usable.firstOrNull { it.isDefault }
            ?: usable.firstOrNull()
    }

    companion object {
        /** Old-host fallback: the three chips the app shipped before the presets service. */
        val Fallback = PresetRoster(
            supported = false,
            presets = listOf(
                AgentPreset(id = "standard", name = "Standard", isDefault = true),
                AgentPreset(id = "minimal", name = "Minimal"),
                AgentPreset(id = "ptc", name = "PTC"),
            ),
            defaultId = "standard",
        )
    }
}

// endregion

// region read-back thumbnail + attachment cache budget

/** Image attachments larger than this are never thumbnailed (chip stays an icon). */
internal const val ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES: Long = 15L * 1024L * 1024L

/** Longest decoded edge of a historical image thumbnail. */
internal const val ATTACHMENT_THUMBNAIL_MAX_EDGE_PX: Int = 512

/** Target upper bound for one decoded thumbnail (ARGB_8888 bytes). */
internal const val ATTACHMENT_THUMBNAIL_MAX_BYTES: Long = 256L * 1024L

/** Bounded in-memory thumbnail cache: at most 24 entries / 16 MB. */
internal const val ATTACHMENT_THUMBNAIL_CACHE_ENTRIES: Int = 24
internal const val ATTACHMENT_THUMBNAIL_CACHE_BYTES: Int = 16 * 1024 * 1024

/** Read-back attachment cache trimming budget: 100 MB or entries older than 7 days. */
internal const val ATTACHMENT_CACHE_MAX_BYTES: Long = 100L * 1024L * 1024L
internal const val ATTACHMENT_CACHE_MAX_AGE_MS: Long = 7L * 24L * 60L * 60L * 1000L

/** 2^n sample size that keeps the longest edge <= [maxEdgePx] (1 when unknown/already small). */
internal fun attachmentThumbnailSampleSize(
    width: Int,
    height: Int,
    maxEdgePx: Int = ATTACHMENT_THUMBNAIL_MAX_EDGE_PX,
): Int {
    if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
    var sample = 1
    while (maxOf(width / sample, height / sample) > maxEdgePx && sample < 32) sample *= 2
    return sample
}

/** One file currently in the read-back attachment cache (pure input for the prune plan). */
internal data class AttachmentCacheEntry(
    val fileName: String,
    val bytes: Long,
    val lastModifiedMs: Long,
)

/**
 * Files to delete so the read-back cache stays within [maxTotalBytes] and [maxAgeMs].
 * Expired files go first, then the oldest files until the size budget holds; files named in
 * [protectedFileNames] (the attachment currently being opened) are never returned.
 */
internal fun attachmentCachePrunePlan(
    entries: List<AttachmentCacheEntry>,
    nowMs: Long,
    maxTotalBytes: Long = ATTACHMENT_CACHE_MAX_BYTES,
    maxAgeMs: Long = ATTACHMENT_CACHE_MAX_AGE_MS,
    protectedFileNames: Set<String> = emptySet(),
): List<String> {
    val deletable = entries.filter { it.fileName !in protectedFileNames }
    val expired = deletable
        .filter { nowMs - it.lastModifiedMs > maxAgeMs }
        .mapTo(mutableSetOf()) { it.fileName }
    var remainingBytes = entries.filter { it.fileName !in expired }.sumOf { it.bytes }
    val toDelete = expired.toMutableSet()
    if (remainingBytes > maxTotalBytes) {
        val oldestFirst = deletable
            .filter { it.fileName !in toDelete }
            .sortedBy { it.lastModifiedMs }
        for (entry in oldestFirst) {
            if (remainingBytes <= maxTotalBytes) break
            if (toDelete.add(entry.fileName)) remainingBytes -= entry.bytes
        }
    }
    return toDelete.toList()
}

// endregion

// region historical image viewer math

/** Maximum pinch zoom of the full-screen image viewer. */
internal const val ATTACHMENT_VIEWER_MAX_SCALE: Float = 6f

/** Double-tap zoom target; a second double-tap returns to 1x. */
internal const val ATTACHMENT_VIEWER_DOUBLE_TAP_SCALE: Float = 2.5f

/** Longest edge of the full-screen decoded bitmap (bounds/downsample, never full-res). */
internal const val ATTACHMENT_VIEWER_MAX_EDGE_PX: Int = 2_048

/** Hard cap for one decoded viewer bitmap (ARGB_8888 bytes). */
internal const val ATTACHMENT_VIEWER_MAX_BYTES: Long = 24L * 1024L * 1024L

/** Clamps a pinch zoom into the viewer's 1x..6x range. */
internal fun clampAttachmentViewerScale(scale: Float): Float =
    scale.coerceIn(1f, ATTACHMENT_VIEWER_MAX_SCALE)

/** Double-tap target: zoomed-in images return to 1x, otherwise zoom to the 2.5x target. */
internal fun attachmentViewerDoubleTapScale(current: Float): Float =
    if (clampAttachmentViewerScale(current) > 1.05f) 1f else ATTACHMENT_VIEWER_DOUBLE_TAP_SCALE

/**
 * Size of a fitted (letterboxed) image inside a viewport, preserving aspect ratio.
 * The result is the actual displayed content box at scale 1x, which pan limits must use so a
 * portrait/landscape image cannot be dragged off its own pixels.
 */
internal fun attachmentViewerFittedContent(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Pair<Float, Float> {
    if (viewportWidth <= 0f || viewportHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
        return viewportWidth.coerceAtLeast(0f) to viewportHeight.coerceAtLeast(0f)
    }
    val fit = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
    return (imageWidth * fit) to (imageHeight * fit)
}

/** Maximum pan along one axis for a scaled image inside a viewport. */
internal fun attachmentViewerMaxPan(viewportPx: Float, contentPx: Float, scale: Float): Float {
    if (viewportPx <= 0f) return 0f
    val scaled = contentPx * clampAttachmentViewerScale(scale)
    return if (scaled <= viewportPx) 0f else (scaled - viewportPx) / 2f
}

/** Clamps a pan offset to [-maxPan, maxPan]. */
internal fun clampAttachmentViewerPan(value: Float, maxPan: Float): Float =
    value.coerceIn(-maxPan, maxPan)

/** Images eligible for the viewer (kind=="image" only); videos/files stay external. */
internal fun imageAttachmentsForViewer(
    attachments: List<MessageAttachment>,
): List<MessageAttachment> = attachments.filter {
    it.id.isNotBlank() && it.kind.equals("image", ignoreCase = true)
}

/** Index of the tapped attachment in the viewer image list, or -1 when it isn't an image. */
internal fun attachmentViewerIndexOf(
    images: List<MessageAttachment>,
    attachmentId: String,
): Int = images.indexOfFirst { it.id == attachmentId }

/** Clamped next/previous index for the viewer pager (never wraps). */
internal fun attachmentViewerNeighbourIndex(current: Int, delta: Int, size: Int): Int {
    if (size <= 0) return 0
    return (current + delta).coerceIn(0, size - 1)
}

// endregion

// region attachment image save/copy

/** MediaStore relative path for images saved from the full-screen viewer. */
internal const val ATTACHMENT_IMAGE_RELATIVE_PATH_SAVED: String = "Pictures/DSH Mobile"

/** Toast/status copy for the viewer image actions. */
internal const val ATTACHMENT_SAVE_ACTION_LABEL: String = "Save"
internal const val ATTACHMENT_COPY_ACTION_LABEL: String = "Copy"
internal const val ATTACHMENT_SAVE_SUCCESS_COPY: String = "Saved to Pictures/DSH Mobile"
internal const val ATTACHMENT_SAF_SAVE_SUCCESS_COPY: String = "Image saved"
internal const val ATTACHMENT_SAVE_FAILURE_COPY: String = "Couldn't save this image."
internal const val ATTACHMENT_SAVE_CANCELLED_COPY: String = "Save cancelled"
internal const val ATTACHMENT_COPY_SUCCESS_COPY: String = "Image copied"
internal const val ATTACHMENT_COPY_FAILURE_COPY: String = "Couldn't copy this image."
internal const val ATTACHMENT_MISSING_FILE_COPY: String =
    "The cached file is missing. Retry to download it again."

/** Supported output MIME -> extension; the extension is ALWAYS derived from the resolved MIME. */
private val ATTACHMENT_IMAGE_EXTENSION_BY_MIME: Map<String, String> = linkedMapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/webp" to "webp",
    "image/gif" to "gif",
    "image/heic" to "heic",
    "image/avif" to "avif",
)

/** Declared MIME normalized to a supported output type; null when unsupported/unknown. */
internal fun attachmentImageMimeFromDeclared(mimeType: String?): String? {
    val normalized = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    val canonical = when (normalized) {
        "image/jpg" -> "image/jpeg"
        "image/heif", "image/heif-sequence", "image/heics" -> "image/heic"
        else -> normalized
    }
    return canonical.takeIf { it in ATTACHMENT_IMAGE_EXTENSION_BY_MIME }
}

private fun ByteArray.attachmentAsciiMatches(offset: Int, text: String): Boolean {
    if (size < offset + text.length) return false
    for (index in text.indices) {
        if (this[offset + index] != text[index].code.toByte()) return false
    }
    return true
}

private fun ByteArray.attachmentAsciiAt(offset: Int, length: Int): String? {
    if (size < offset + length) return null
    return buildString(length) {
        for (index in 0 until length) {
            append(this@attachmentAsciiAt[offset + index].toInt().toChar())
        }
    }
}

/**
 * Magic-byte sniff of the first bytes of the cached attachment; the real source of truth when a
 * host filename/MIME lies. Covers PNG, JPEG, WebP, GIF, HEIF/HEIC and AVIF.
 */
internal fun attachmentImageMimeFromBytes(header: ByteArray?): String? {
    if (header == null || header.isEmpty()) return null
    if (header.size >= 8 &&
        header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
        header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() &&
        header[4] == 0x0D.toByte() && header[5] == 0x0A.toByte() &&
        header[6] == 0x1A.toByte() && header[7] == 0x0A.toByte()
    ) {
        return "image/png"
    }
    if (header.size >= 3 &&
        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    ) {
        return "image/jpeg"
    }
    if (header.attachmentAsciiMatches(0, "GIF87a") ||
        header.attachmentAsciiMatches(0, "GIF89a")
    ) {
        return "image/gif"
    }
    if (header.size >= 12 &&
        header.attachmentAsciiMatches(0, "RIFF") &&
        header.attachmentAsciiMatches(8, "WEBP")
    ) {
        return "image/webp"
    }
    if (header.size >= 12 && header.attachmentAsciiMatches(4, "ftyp")) {
        return when (header.attachmentAsciiAt(8, 4)) {
            "heic", "heix", "hevc", "hevx", "heif", "mif1", "msf1" -> "image/heic"
            "avif", "avis" -> "image/avif"
            else -> null
        }
    }
    return null
}

/** Extension for a resolved MIME (jpeg -> jpg); unknown types fall back to png. */
internal fun attachmentImageExtensionForMime(mimeType: String): String =
    ATTACHMENT_IMAGE_EXTENSION_BY_MIME[mimeType] ?: "png"

/** MIME + matching extension written to MediaStore/SAF. */
internal data class AttachmentImageOutput(
    val mimeType: String,
    val extension: String,
)

/**
 * Resolution order (filename is intentionally NEVER consulted):
 * 1. magic bytes of the cached file (PNG/JPEG/WebP/GIF/HEIF/AVIF) - source of truth;
 * 2. the attachment's declared MIME when it is a supported image type;
 * 3. `image/png` fallback for unknown bytes/MIME.
 */
internal fun resolveAttachmentImageOutput(
    declaredMimeType: String?,
    header: ByteArray?,
): AttachmentImageOutput {
    val resolvedMime = attachmentImageMimeFromBytes(header)
        ?: attachmentImageMimeFromDeclared(declaredMimeType)
        ?: "image/png"
    return AttachmentImageOutput(
        mimeType = resolvedMime,
        extension = attachmentImageExtensionForMime(resolvedMime),
    )
}

/** Timestamped base name; collision handling appends `-<attempt>` in [attachmentSavedImageDisplayName]. */
internal fun attachmentSavedImageBaseName(timestampMs: Long): String = "dsh-mobile-$timestampMs"

/** Unique display name: `dsh-mobile-<ts>.<ext>`, then `dsh-mobile-<ts>-1.<ext>`, … */
internal fun attachmentSavedImageDisplayName(
    timestampMs: Long,
    extension: String,
    attempt: Int = 0,
): String {
    val safeExtension = extension
        .trim()
        .lowercase()
        .filter { ch -> ch.isLetterOrDigit() }
        .take(5)
        .ifBlank { "png" }
        .let { if (it == "jpeg") "jpg" else it }
    val base = attachmentSavedImageBaseName(timestampMs)
    return if (attempt <= 0) "$base.$safeExtension" else "$base-$attempt.$safeExtension"
}

/** Android 10+ writes through MediaStore without a runtime permission; older uses SAF. */
internal fun attachmentSaveUsesMediaStore(apiLevel: Int): Boolean = apiLevel >= 29

/** Top-bar/Toast label for the current API branch. */
internal fun attachmentSaveActionLabel(apiLevel: Int): String =
    if (attachmentSaveUsesMediaStore(apiLevel)) "Save to Photos" else "Save image"

/** Save/copy actions are disabled while a save (or the same action) is in flight. */
internal fun attachmentViewerActionEnabled(busy: Boolean): Boolean = !busy

// endregion

// region attachment labels

/**
 * Human-readable size for attachment chips; null for 0/negative/unknown bytes so synthetic
 * workspace images never show a misleading "0 B". Known sizes keep the existing formatting.
 */
internal fun attachmentSizeLabel(bytes: Long): String? = when {
    bytes <= 0L -> null
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

/** Chip subtitle: IMAGE/FILE plus the size only when it is known. */
internal fun attachmentMetaLabel(isImage: Boolean, bytes: Long): String = buildString {
    append(if (isImage) "IMAGE" else "FILE")
    attachmentSizeLabel(bytes)?.let { size ->
        append(" · ")
        append(size)
    }
}

/** Display name with the original (possibly non-ASCII) name preserved when nonblank. */
internal fun attachmentDisplayName(name: String, isImage: Boolean): String =
    name.ifBlank { if (isImage) "Image" else "Attachment" }

/**
 * Full-screen viewer subtitle: IMAGE/FILE, size only when known (so a synthetic 0-byte workspace
 * image shows “IMAGE”, never “0 B”), and the pager counter when there are multiple images.
 */
internal fun attachmentViewerMetaLabel(
    isImage: Boolean,
    bytes: Long,
    index: Int,
    count: Int,
): String = buildString {
    append(if (isImage) "IMAGE" else "FILE")
    attachmentSizeLabel(bytes)?.let { size ->
        append(" · ")
        append(size)
    }
    if (count > 1) {
        append(" · ")
        append(index + 1)
        append('/')
        append(count)
    }
}

// endregion

// region session skills

/** UI copy for the chat Skills sheet. */
internal const val SKILLS_ACTION_LABEL: String = "Skills"
internal const val SKILLS_SHEET_TITLE: String = "Skills"
internal const val SKILLS_EMPTY_COPY: String = "No skills available on this host."
internal const val SKILLS_UNSUPPORTED_COPY: String = "This host does not expose skills."
internal const val SKILLS_SESSION_UNAVAILABLE_COPY: String = "This session is no longer available."
internal const val SKILLS_LOADING_COPY: String = "Loading skills…"
internal const val SKILLS_REFRESH_FAILED_COPY: String = "Couldn't refresh skills."
internal const val SKILLS_RETRY_LABEL: String = "Retry"
internal const val SKILLS_COPY_FALLBACK_TOAST: String = "Skill prompt copied"

/** Cached skills go stale after this; opening the sheet refreshes them in the background. */
internal const val SKILLS_CACHE_TTL_MS: Long = 5 * 60 * 1000L

/** Tapping a skill prefills the composer with this prompt; it is never auto-sent. */
internal const val SKILLS_PROMPT_TEMPLATE_PREFIX: String = "Use the "
internal const val SKILLS_PROMPT_TEMPLATE_SUFFIX: String = " skill: "
internal const val SKILLS_MAX_NAME_CHARS: Int = 80
internal const val SKILLS_MAX_DESCRIPTION_CHARS: Int = 240
internal const val SKILLS_MAX_PROMPT_CHARS: Int = 400

/** One skill as shown in the sheet; both fields are already sanitized by the repository. */
data class HostSkill(
    val name: String,
    val description: String = "",
)

/** Skills snapshot; unsupported hosts return [supported] = false and no skills. */
data class HostSkills(
    val supported: Boolean,
    val skills: List<HostSkill> = emptyList(),
    /** A2: 404/invisible session — distinct from an old host that has no registry. */
    val sessionUnavailable: Boolean = false,
) {
    companion object {
        val Unsupported = HostSkills(supported = false)
        val SessionUnavailable = HostSkills(supported = false, sessionUnavailable = true)
    }
}

/** A4: control characters become spaces so words never join after sanitizing. */
private fun skillTextWithSpaces(raw: String): String = buildString(raw.length) {
    raw.forEach { ch -> append(if (ch.isISOControl()) ' ' else ch) }
}

/** Sanitized single-line skill name; null when blank. */
internal fun sanitizedSkillName(raw: String?): String? = raw
    ?.let { skillTextWithSpaces(it) }
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(SKILLS_MAX_NAME_CHARS)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/** Sanitized single-line, bounded skill description; null when blank. */
internal fun sanitizedSkillDescription(raw: String?): String? = raw
    ?.let { skillTextWithSpaces(it) }
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(SKILLS_MAX_DESCRIPTION_CHARS)
    ?.takeIf { it.isNotEmpty() }

/** Composer prefill for a skill; null when the (sanitized) name is blank. */
internal fun skillPromptPrefill(name: String?): String? = sanitizedSkillName(name)
    ?.let { "$SKILLS_PROMPT_TEMPLATE_PREFIX$it$SKILLS_PROMPT_TEMPLATE_SUFFIX" }
    ?.take(SKILLS_MAX_PROMPT_CHARS)

/**
 * A3: inserted draft keeps any existing prompt, separates the template with a blank line, and
 * always ends with the template so the composer's external-text sync puts the cursor at the end.
 */
internal fun skillPrefillDraft(currentDraft: String, prefill: String): String =
    if (currentDraft.isBlank()) prefill else currentDraft.trimEnd() + "\n\n" + prefill

/** A1: cached snapshot age; true once the TTL elapsed. */
internal fun isSessionSkillsCacheStale(fetchedAtMs: Long, nowMs: Long): Boolean =
    nowMs - fetchedAtMs >= SKILLS_CACHE_TTL_MS

/**
 * Cache/fetch decision: refresh when forced, when nothing is cached, when the cache has no
 * timestamp, or when the cached snapshot is older than [SKILLS_CACHE_TTL_MS]. Never while loading.
 */
internal fun shouldLoadSessionSkills(
    cached: HostSkills?,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    if (force || cached == null) return true
    if (fetchedAtMs == null) return true
    return isSessionSkillsCacheStale(fetchedAtMs, nowMs)
}

// endregion

// region delegation badges

/** Display cap: larger counts render as "99+" instead of stretching narrow rows. */
internal const val DELEGATION_DISPLAY_MAX: Int = 99

/** Clamped counts: non-negative total, working never exceeds total. */
internal fun clampedDelegationCounts(total: Int, working: Int): Pair<Int, Int> {
    val safeTotal = total.coerceAtLeast(0)
    return safeTotal to working.coerceIn(0, safeTotal)
}

/** "7" or "99+" for a display count. */
internal fun delegationDisplayCount(count: Int): String =
    if (count > DELEGATION_DISPLAY_MAX) "$DELEGATION_DISPLAY_MAX+" else count.toString()

/**
 * Compact badge copy, e.g. "2 agents", "1 agent · 1 working", "7 agents · 3 working".
 * Null when there are no child sessions (badges hidden for old/normal sessions).
 */
internal fun delegationBadgeLabel(total: Int, working: Int, noun: String): String? {
    val (safeTotal, safeWorking) = clampedDelegationCounts(total, working)
    if (safeTotal <= 0) return null
    val base = buildString {
        append(delegationDisplayCount(safeTotal))
        append(' ')
        append(noun)
        if (safeTotal != 1) append('s')
    }
    return if (safeWorking > 0) {
        "$base · ${delegationDisplayCount(safeWorking)} working"
    } else {
        base
    }
}

/** TalkBack label describing both counts (raw clamped numbers, not the visual 99+ cap). */
internal fun delegationContentDescription(total: Int, working: Int, noun: String): String? {
    val (safeTotal, safeWorking) = clampedDelegationCounts(total, working)
    if (safeTotal <= 0) return null
    val agents = buildString {
        append(safeTotal)
        append(' ')
        append(noun)
        if (safeTotal != 1) append('s')
    }
    return if (safeWorking > 0) {
        "$agents, $safeWorking working"
    } else {
        agents
    }
}

// endregion

// region session plan (todos)

/** Plan cache TTL: a reopened sheet refreshes after 30 s; chat open can reuse it. */
internal const val PLAN_CACHE_TTL_MS: Long = 30_000L
internal const val PLAN_ACTION_LABEL: String = "Plan"
internal const val PLAN_SHEET_TITLE: String = "Plan"
internal const val PLAN_EMPTY_COPY: String = "No plan yet."
internal const val PLAN_UNSUPPORTED_COPY: String = "This host does not expose plans."
internal const val PLAN_LOAD_FAILED_COPY: String = "Couldn't load the plan."
internal const val PLAN_LOADING_COPY: String = "Loading plan…"
internal const val PLAN_RETRY_LABEL: String = "Retry"
internal const val PLAN_MAX_CONTENT_CHARS: Int = 500

enum class PlanTodoStatus(val label: String) {
    Pending("Pending"),
    InProgress("In progress"),
    Completed("Done"),
}

/** One normalized plan row. */
data class PlanTodo(
    val content: String,
    val status: PlanTodoStatus,
)

/** Clamped plan counters; [total] always matches the visible todo list. */
data class PlanCounts(
    val pending: Int,
    val inProgress: Int,
    val completed: Int,
) {
    val total: Int get() = pending + inProgress + completed
}

/** Host plan snapshot; old/unsupported hosts return [supported] = false. */
data class HostPlan(
    val supported: Boolean,
    val counts: PlanCounts = PlanCounts(0, 0, 0),
    val todos: List<PlanTodo> = emptyList(),
    val updatedAt: String? = null,
) {
    companion object {
        val Unsupported = HostPlan(supported = false)
        val Empty = HostPlan(supported = true)
    }
}

/** Normalizes host statuses; unknown/missing statuses are pending. */
internal fun normalizedPlanStatus(raw: String?): PlanTodoStatus = when (
    raw?.trim()?.lowercase()?.replace('-', '_')
) {
    "completed", "complete", "done", "finished" -> PlanTodoStatus.Completed
    "in_progress", "inprogress", "working", "active", "started" -> PlanTodoStatus.InProgress
    else -> PlanTodoStatus.Pending
}

/** Control-safe, bounded plan content; null for blank rows (skipped by the repository). */
internal fun sanitizedPlanTodoContent(raw: String?): String? {
    val cleaned = raw
        ?.let { text -> buildString(text.length) { text.forEach { append(if (it.isISOControl()) ' ' else it) } } }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(PLAN_MAX_CONTENT_CHARS)
        ?.takeIf { it.isNotEmpty() }
    return cleaned
}

/** Counts derived from the todo rows (source of truth when host counts are absent). */
internal fun planCountsFromTodos(todos: List<PlanTodo>): PlanCounts = PlanCounts(
    pending = todos.count { it.status == PlanTodoStatus.Pending },
    inProgress = todos.count { it.status == PlanTodoStatus.InProgress },
    completed = todos.count { it.status == PlanTodoStatus.Completed },
)

/**
 * Clamps host counts to the actual todo list: each counter is non-negative and the sum can never
 * exceed the visible rows; an absent counts object is derived from the rows.
 */
internal fun clampedPlanCounts(
    hostCounts: PlanCounts?,
    todos: List<PlanTodo>,
): PlanCounts {
    if (hostCounts == null) return planCountsFromTodos(todos)
    val total = todos.size
    val completed = hostCounts.completed.coerceIn(0, total)
    val inProgress = hostCounts.inProgress.coerceIn(0, total - completed)
    val pending = (total - completed - inProgress).coerceAtLeast(0)
    return PlanCounts(pending = pending, inProgress = inProgress, completed = completed)
}

/** Compact top-bar hint, e.g. "2/5"; null when there is no plan to hint at. */
internal fun planProgressHint(counts: PlanCounts): String? =
    if (counts.total <= 0) null else "${counts.completed}/${counts.total}"

/** Sheet progress line, e.g. "2 of 5 done". */
internal fun planProgressLabel(counts: PlanCounts): String =
    "${counts.completed} of ${counts.total} done"

/** Action contentDescription including progress when known. */
internal fun planActionContentDescription(counts: PlanCounts?): String {
    val hint = counts?.let { planProgressHint(it) }
    return if (hint == null) "Open plan" else "Open plan, $hint done"
}

/** Actionable items first: in progress, pending, then completed (host order preserved per group). */
internal fun orderedPlanTodos(todos: List<PlanTodo>): List<PlanTodo> =
    todos.filter { it.status == PlanTodoStatus.InProgress } +
        todos.filter { it.status == PlanTodoStatus.Pending } +
        todos.filter { it.status == PlanTodoStatus.Completed }

/** TTL/cache decision: refresh when forced, uncached, timestamp-less or older than 30 s. */
internal fun shouldLoadSessionPlan(
    cached: HostPlan?,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    if (force || cached == null) return true
    if (fetchedAtMs == null) return true
    return nowMs - fetchedAtMs >= PLAN_CACHE_TTL_MS
}

// endregion

// region session team

internal const val TEAM_CACHE_TTL_MS: Long = 30_000L
internal const val TEAM_ACTION_LABEL: String = "Team"
internal const val TEAM_SHEET_TITLE: String = "Team"
internal const val TEAM_AGENTS_HEADING: String = "Agents"
internal const val TEAM_TASKS_HEADING: String = "Active tasks"
internal const val TEAM_EMPTY_COPY: String = "No active team tasks."
internal const val TEAM_UNSUPPORTED_COPY: String = "This host does not expose team activity."
internal const val TEAM_LOAD_FAILED_COPY: String = "Couldn't load team activity."
internal const val TEAM_LOADING_COPY: String = "Loading team activity…"
internal const val TEAM_RETRY_LABEL: String = "Retry"
internal const val TEAM_MAX_SUBJECT_CHARS: Int = 500
internal const val TEAM_MAX_NAME_CHARS: Int = 80
internal const val TEAM_MAX_PHASE_CHARS: Int = 60

enum class TeamTaskStatus(val label: String) {
    Pending("Pending"),
    InProgress("In progress"),
    Blocked("Blocked"),
    Completed("Done"),
}

data class TeamMember(
    val name: String,
    val phase: String?,
)

data class TeamTask(
    val id: String,
    val subject: String,
    val status: TeamTaskStatus,
    val blockedBy: List<String> = emptyList(),
)

/** Counts summary; completed tasks are counts-only by contract. */
data class TeamCounts(
    val pending: Int,
    val inProgress: Int,
    val blocked: Int,
    val completed: Int,
) {
    val active: Int get() = pending + inProgress + blocked
}

data class HostTeam(
    val supported: Boolean,
    val members: List<TeamMember> = emptyList(),
    val tasks: List<TeamTask> = emptyList(),
    val counts: TeamCounts = TeamCounts(0, 0, 0, 0),
) {
    companion object {
        val Unsupported = HostTeam(supported = false)
        val Empty = HostTeam(supported = true)
    }
}

/** DTO statuses normalized; completed remains representable for robustness but is counts-only. */
internal fun normalizedTeamTaskStatus(raw: String?): TeamTaskStatus = when (
    raw?.trim()?.lowercase()?.replace('-', '_')
) {
    "completed", "complete", "done", "finished" -> TeamTaskStatus.Completed
    "in_progress", "inprogress", "working", "active", "started" -> TeamTaskStatus.InProgress
    "blocked", "waiting", "stalled" -> TeamTaskStatus.Blocked
    else -> TeamTaskStatus.Pending
}

private fun sanitizedTeamText(raw: String?, maxChars: Int): String? = raw
    ?.let { text -> buildString(text.length) { text.forEach { append(if (it.isISOControl()) ' ' else it) } } }
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(maxChars)
    ?.takeIf { it.isNotEmpty() }

internal fun sanitizedTeamName(raw: String?): String = sanitizedTeamText(raw, TEAM_MAX_NAME_CHARS) ?: "Agent"

internal fun sanitizedTeamPhase(raw: String?): String? = sanitizedTeamText(raw, TEAM_MAX_PHASE_CHARS)

internal fun sanitizedTeamSubject(raw: String?): String =
    sanitizedTeamText(raw, TEAM_MAX_SUBJECT_CHARS) ?: "(untitled task)"

internal fun teamMemberPhaseLabel(phase: String?): String = sanitizedTeamPhase(phase) ?: "phase unknown"

/** Active tasks in status order: in progress → blocked → pending → completed (stable per group). */
internal fun orderedTeamTasks(tasks: List<TeamTask>): List<TeamTask> =
    tasks.filter { it.status == TeamTaskStatus.InProgress } +
        tasks.filter { it.status == TeamTaskStatus.Blocked } +
        tasks.filter { it.status == TeamTaskStatus.Pending } +
        tasks.filter { it.status == TeamTaskStatus.Completed }

/** Counters are non-negative; missing host counts derive the active rows (completed stays 0). */
internal fun clampedTeamCounts(hostCounts: TeamCounts?, tasks: List<TeamTask>): TeamCounts {
    val derived = TeamCounts(
        pending = tasks.count { it.status == TeamTaskStatus.Pending },
        inProgress = tasks.count { it.status == TeamTaskStatus.InProgress },
        blocked = tasks.count { it.status == TeamTaskStatus.Blocked },
        completed = tasks.count { it.status == TeamTaskStatus.Completed },
    )
    if (hostCounts == null) return derived
    return TeamCounts(
        pending = hostCounts.pending.coerceAtLeast(0),
        inProgress = hostCounts.inProgress.coerceAtLeast(0),
        blocked = hostCounts.blocked.coerceAtLeast(0),
        completed = hostCounts.completed.coerceAtLeast(0),
    )
}

internal fun teamActiveHint(counts: TeamCounts): String? =
    if (counts.active <= 0) null else "${counts.active} active"

internal fun teamActionContentDescription(counts: TeamCounts?): String {
    val hint = counts?.let { teamActiveHint(it) }
    return if (hint == null) "Open team" else "Open team, $hint"
}

internal fun teamCountsSummary(counts: TeamCounts): String = buildString {
    append("Pending ${counts.pending}")
    append(" · In progress ${counts.inProgress}")
    append(" · Blocked ${counts.blocked}")
    append(" · Completed ${counts.completed}")
}

/** "blocked by a, b" for active blocked tasks; null when there are no dependency ids. */
internal fun teamTaskBlockedByLabel(task: TeamTask): String? {
    val ids = task.blockedBy
        .mapNotNull { sanitizedTeamText(it, 80) }
        .distinct()
    if (ids.isEmpty()) return null
    return "blocked by ${ids.joinToString(", ")}"
}

/** 30 s TTL/cache decision for the read-only team board. */
internal fun shouldLoadSessionTeam(
    cached: HostTeam?,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    if (force || cached == null) return true
    if (fetchedAtMs == null) return true
    return nowMs - fetchedAtMs >= TEAM_CACHE_TTL_MS
}

// endregion

// region session trajectory

internal const val TRAJECTORY_CACHE_TTL_MS: Long = 30_000L
internal const val TRAJECTORY_PAGE_LIMIT: Int = 100
internal const val TRAJECTORY_MAX_SUMMARY_CHARS: Int = 240
internal const val TRAJECTORY_ACTION_LABEL: String = "Trajectory"
internal const val TRAJECTORY_SHEET_TITLE: String = "Trajectory"
internal const val TRAJECTORY_READ_ONLY_NOTE: String = "Read-only journal of this session."
internal const val TRAJECTORY_EMPTY_COPY: String = "No trajectory events yet."
internal const val TRAJECTORY_UNSUPPORTED_COPY: String = "This host does not expose a trajectory."
internal const val TRAJECTORY_LOAD_FAILED_COPY: String = "Couldn't load the trajectory."
internal const val TRAJECTORY_LOADING_COPY: String = "Loading trajectory…"
internal const val TRAJECTORY_LOADING_OLDER_COPY: String = "Loading older events…"
internal const val TRAJECTORY_LOAD_OLDER_LABEL: String = "Load older"
internal const val TRAJECTORY_RETRY_LABEL: String = "Retry"
internal const val TRAJECTORY_REFRESH_LABEL: String = "Refresh"

/** One normalized journal row. */
data class TrajectoryEvent(
    val seq: Int,
    val time: String,
    val typeLabel: String,
    val summary: String,
    val status: String?,
)

/** Newest-first page snapshot; old/unsupported hosts return [supported] = false. */
data class HostTrajectoryPage(
    val supported: Boolean,
    val events: List<TrajectoryEvent> = emptyList(),
    val nextBeforeSeq: Int? = null,
    val hasMore: Boolean = false,
) {
    companion object {
        val Unsupported = HostTrajectoryPage(supported = false)
        val Empty = HostTrajectoryPage(supported = true)
    }
}

private val TRAJECTORY_TYPE_LABELS: Map<String, String> = mapOf(
    "user_message" to "User message",
    "user" to "User message",
    "assistant_message" to "Message",
    "assistant" to "Message",
    "message" to "Message",
    "tool_call" to "Tool call",
    "tool_result" to "Tool result",
    "tool" to "Tool",
    "approval" to "Approval",
    "question" to "Question",
    "goal" to "Goal",
    "plan" to "Plan",
    "todo" to "Plan",
    "team" to "Team",
    "delegation" to "Delegation",
    "subagent" to "Delegation",
    "error" to "Error",
    "system" to "System",
    "session" to "Session",
)

/** Friendly event type label; unknown types are prettified and bounded. */
internal fun trajectoryTypeLabel(raw: String?): String {
    val cleaned = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return "Event"
    TRAJECTORY_TYPE_LABELS[cleaned]?.let { return it }
    return cleaned
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(40)
        .replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
        .ifBlank { "Event" }
}

/** Control-safe, bounded summary; blank rows stay visible with honest placeholder text. */
internal fun sanitizedTrajectorySummary(raw: String?): String {
    val cleaned = raw
        ?.let { text -> buildString(text.length) { text.forEach { append(if (it.isISOControl()) ' ' else it) } } }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(TRAJECTORY_MAX_SUMMARY_CHARS)
        ?.takeIf { it.isNotEmpty() }
    return cleaned ?: "(no summary)"
}

/** Host time string for the monospace meta; blank stays blank (UI hides it). */
internal fun sanitizedTrajectoryTime(raw: String?): String = raw
    ?.filter { ch -> !ch.isISOControl() }
    ?.trim()
    ?.take(40)
    .orEmpty()

/** Secondary status cue (e.g. "error"); null when absent. Never the only visual signal. */
internal fun normalizedTrajectoryStatus(raw: String?): String? = raw
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotEmpty() }

internal fun isTrajectoryErrorStatus(status: String?): Boolean =
    normalizedTrajectoryStatus(status) in setOf("error", "failed", "failure", "blocked", "timeout")

/** Older pages only while supported, hasMore, a positive cursor and nothing else in flight. */
internal fun canLoadOlderTrajectory(
    page: HostTrajectoryPage?,
    loadingFirstPage: Boolean,
    loadingOlder: Boolean,
): Boolean {
    if (page == null || !page.supported || !page.hasMore) return false
    if (loadingFirstPage || loadingOlder) return false
    return page.nextBeforeSeq?.let { it > 0 } == true
}

/**
 * Appends one older page (oldest at the bottom) with a stale cursor guard: the page is only
 * accepted while the current nextBeforeSeq still equals the requested cursor. Duplicate seqs are
 * skipped, and an empty page stops pagination instead of looping.
 */
internal fun appendTrajectoryPage(
    current: HostTrajectoryPage,
    page: HostTrajectoryPage,
    requestedBeforeSeq: Int?,
): HostTrajectoryPage {
    if (!current.supported || !page.supported) return current
    if (requestedBeforeSeq != null && current.nextBeforeSeq != requestedBeforeSeq) return current
    if (page.events.isEmpty()) {
        return current.copy(hasMore = false, nextBeforeSeq = page.nextBeforeSeq)
    }
    val seen = current.events.mapTo(HashSet()) { it.seq }
    val merged = current.events.toMutableList()
    page.events.forEach { event ->
        if (event.seq > 0 && seen.add(event.seq)) merged += event
    }
    val next = page.nextBeforeSeq?.takeIf { it > 0 }
    return current.copy(
        events = merged,
        nextBeforeSeq = next,
        hasMore = page.hasMore && next != null,
    )
}

/** 30 s TTL/cache decision for the read-only trajectory. */
internal fun shouldLoadSessionTrajectory(
    cached: HostTrajectoryPage?,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    if (force || cached == null) return true
    if (fetchedAtMs == null) return true
    return nowMs - fetchedAtMs >= TRAJECTORY_CACHE_TTL_MS
}

// endregion

// region workspace files (read-only)

internal const val FILES_CACHE_TTL_MS: Long = 30_000L
internal const val FILES_RAW_MAX_BYTES: Long = 25L * 1024L * 1024L
internal const val FILES_MAX_PATH_CHARS: Int = 512
internal const val FILES_MAX_PREVIEW_CHARS: Int = 200_000
internal const val FILES_ACTION_LABEL: String = "Files"
internal const val FILES_SHEET_TITLE: String = "Files"
internal const val FILES_ROOT_LABEL: String = "Workspace"
internal const val FILES_EMPTY_COPY: String = "This folder is empty."
internal const val FILES_UNSUPPORTED_COPY: String = "This host does not expose workspace files."
internal const val FILES_LOAD_FAILED_COPY: String = "Couldn't load files."
internal const val FILES_LOADING_COPY: String = "Loading files…"
internal const val FILES_RETRY_LABEL: String = "Retry"
internal const val FILES_REFRESH_LABEL: String = "Refresh"
internal const val FILES_UP_LABEL: String = "Up"
internal const val FILES_PREVIEW_LOADING_COPY: String = "Loading preview…"
internal const val FILES_PREVIEW_FAILED_COPY: String = "Couldn't open this file."
internal const val FILES_PREVIEW_NOT_TEXT_COPY: String = "This file can't be previewed as text."
internal const val FILES_PREVIEW_TRUNCATED_NOTE: String =
    "Preview truncated — showing the first part of the file."
internal const val FILES_EMPTY_FILE_COPY: String = "(empty file)"
internal const val FILES_OPEN_EXTERNAL_LABEL: String = "Open externally"
internal const val FILES_RAW_TOO_LARGE_COPY: String = "This file is too large to open on the phone."

enum class WorkspaceEntryKind { Dir, File }

/** One normalized read-only workspace entry; [path] is always host-relative. */
data class WorkspaceEntry(
    val name: String,
    val kind: WorkspaceEntryKind,
    val path: String,
    val bytes: Long? = null,
    val mimeType: String? = null,
    val modifiedAt: String? = null,
) {
    val isDirectory: Boolean get() = kind == WorkspaceEntryKind.Dir
}

data class HostFilesPage(
    val supported: Boolean,
    val path: String = "",
    val parent: String? = null,
    val entries: List<WorkspaceEntry> = emptyList(),
) {
    companion object {
        val Unsupported = HostFilesPage(supported = false)
        val Empty = HostFilesPage(supported = true)
    }
}

data class HostFileContent(
    val name: String,
    val path: String,
    val bytes: Long,
    val mimeType: String,
    val text: String,
    val truncated: Boolean,
)

/** Host-relative path sanitizer: resolves `.`/`..`, strips backslashes and leading `/`. */
internal fun sanitizedRelativePath(raw: String?): String {
    val segments = mutableListOf<String>()
    raw.orEmpty()
        .replace('\\', '/')
        .split('/')
        .forEach { rawSegment ->
            val segment = rawSegment.trim()
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
    return segments.joinToString("/").take(FILES_MAX_PATH_CHARS)
}

/**
 * Prompt mention for a workspace file. Paths with whitespace/quotes are quoted so the composer
 * and agent keep them as one reference; empty/root paths produce an empty string.
 */
internal fun workspaceMentionText(rawPath: String?): String {
    val path = sanitizedRelativePath(rawPath)
    if (path.isEmpty()) return ""
    val needsQuotes = path.any { it.isWhitespace() || it == '"' }
    if (!needsQuotes) return "@$path"
    val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
    return "@\"$escaped\""
}


internal fun joinWorkspacePath(parent: String, name: String): String {
    val safeParent = sanitizedRelativePath(parent)
    val safeName = sanitizedRelativePath(name)
    return if (safeParent.isEmpty()) safeName else listOf(safeParent, safeName)
        .filter { it.isNotEmpty() }
        .joinToString("/")
}

/** Parent path inside the workspace; "" is the root (Up is unavailable there). */
internal fun workspaceParentPath(path: String): String {
    val safe = sanitizedRelativePath(path)
    val index = safe.lastIndexOf('/')
    return if (index < 0) "" else safe.substring(0, index)
}

/** Breadcrumb segments, root first (empty for the workspace root). */
internal fun workspaceBreadcrumbSegments(path: String): List<String> =
    sanitizedRelativePath(path)
        .split('/')
        .filter { it.isNotEmpty() }

/** Display label: "Workspace / src / main"; always relative, never absolute. */
internal fun workspacePathLabel(path: String): String = buildString {
    append(FILES_ROOT_LABEL)
    workspaceBreadcrumbSegments(path).forEach { segment ->
        append(" / ")
        append(segment)
    }
}

/** Human size for listings; null/unknown bytes are hidden by the caller. */
internal fun formatFileSize(bytes: Long?): String {
    val safe = bytes?.takeIf { it >= 0 } ?: return ""
    if (safe == 0L) return "0 B"
    return when {
        safe < 1_024L -> "$safe B"
        safe < 1_048_576L -> String.format(java.util.Locale.US, "%.1f KB", safe / 1_024.0)
        safe < 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f MB", safe / 1_048_576.0)
        else -> String.format(java.util.Locale.US, "%.1f GB", safe / 1_073_741_824.0)
    }
}

private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "toml", "ini", "cfg", "conf",
    "xml", "csv", "tsv", "log", "html", "htm", "css", "scss", "js", "jsx", "ts", "tsx",
    "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh",
    "bash", "zsh", "fish", "sql", "gradle", "properties", "env", "gitignore", "dockerfile",
)

/** Text-ish files get the in-app preview; everything else opens raw/external. */
internal fun isTextPreviewable(mimeType: String?, name: String): Boolean {
    val mime = mimeType.orEmpty().trim().lowercase()
    if (mime.startsWith("text/")) return true
    if (mime.contains("json") || mime.contains("xml") || mime.contains("yaml") ||
        mime.contains("javascript") || mime.contains("sql") || mime.contains("x-sh")
    ) {
        return true
    }
    if (mime.isNotEmpty() && !mime.contains("octet-stream") && !mime.contains("binary")) {
        return false
    }
    val extension = name.substringAfterLast('.', "").trim().lowercase()
    if (extension.isEmpty()) return name.equals("dockerfile", ignoreCase = true)
    return extension in TEXT_FILE_EXTENSIONS
}

/** Directories first, then case-insensitive by name (host order otherwise preserved). */
internal fun dirFirstEntries(entries: List<WorkspaceEntry>): List<WorkspaceEntry> =
    entries.sortedWith(
        compareByDescending<WorkspaceEntry> { it.isDirectory }
            .thenBy { it.name.lowercase() },
    )

/** Preview text is bounded client-side too; empty files show an explicit placeholder. */
internal fun previewTextFor(
    raw: String?,
    hostTruncated: Boolean,
    maxChars: Int = FILES_MAX_PREVIEW_CHARS,
): Pair<String, Boolean> {
    val text = raw.orEmpty()
    if (text.isEmpty()) return FILES_EMPTY_FILE_COPY to hostTruncated
    val bounded = if (text.length > maxChars) text.take(maxChars) else text
    return bounded to (hostTruncated || bounded.length < text.length)
}

/** Cache TTL decision per session+path; a path mismatch always refetches. */
internal fun shouldFetchWorkspacePath(
    cachedPage: HostFilesPage?,
    cachedPath: String?,
    requestedPath: String,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    val requested = sanitizedRelativePath(requestedPath)
    if (force || cachedPage == null) return true
    if (sanitizedRelativePath(cachedPath) != requested) return true
    if (fetchedAtMs == null) return true
    return nowMs - fetchedAtMs >= FILES_CACHE_TTL_MS
}

/** A response may only be applied while the user is still on the path it was requested for. */
internal fun isWorkspaceNavigationStale(requestedPath: String, currentPath: String): Boolean =
    sanitizedRelativePath(requestedPath) != sanitizedRelativePath(currentPath)

/** External-open MIME: prefer the host value, else a conservative extension guess. */
internal fun workspaceMimeType(name: String, mimeType: String?): String {
    val declared = mimeType.orEmpty().trim()
    if (declared.isNotEmpty()) return declared
    return when (name.substringAfterLast('.', "").trim().lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "txt", "md", "log", "csv", "yaml", "yml", "xml", "kt", "java", "js", "ts",
        "py", "sh", "sql", "html", "css", "toml", "ini" -> "text/plain"
        "zip" -> "application/zip"
        else -> "*/*"
    }
}

// endregion

// region share message as image card

internal const val SHARE_CARD_ACTION_LABEL: String = "Share as image"
internal const val SHARE_CARD_ACTION_SUBTITLE: String = "Render this message as a PNG card"
internal const val SHARE_CARD_BRAND: String = "DSH Mobile"
internal const val SHARE_CARD_TAGLINE: String = "Shared from DSH Mobile"
internal const val SHARE_CARD_FAILURE_TOAST: String = "Couldn't create the image card."
internal const val SHARE_CARD_CACHE_DIR: String = "share-cards"
internal const val SHARE_CARD_WIDTH_PX: Int = 1080
internal const val SHARE_CARD_PADDING_PX: Int = 64
internal const val SHARE_CARD_MAX_CHARS: Int = 6_000
internal const val SHARE_CARD_MAX_LINES: Int = 80
internal const val SHARE_CARD_PROSE_CHARS_PER_LINE: Int = 24
internal const val SHARE_CARD_CODE_CHARS_PER_LINE: Int = 32
internal const val SHARE_CARD_TEXT_SIZE_PX: Float = 38f
internal const val SHARE_CARD_LINE_HEIGHT_PX: Int = 54
internal const val SHARE_CARD_HEADER_PX: Int = 330
internal const val SHARE_CARD_FOOTER_PX: Int = 190
internal const val SHARE_CARD_MAX_HEIGHT_PX: Int = 6_000
internal const val SHARE_CARD_TRUNCATED_NOTE: String = "Message truncated for this card."
internal const val SHARE_CARD_REDACTED: String = "<redacted>"
internal const val SHARE_CARD_PRIVACY_NOTE: String =
    "Credential-like strings are redacted from this card."

/** A stretch of card body text; code blocks render in a monospace face. */
data class ShareCardSegment(
    val text: String,
    val monospace: Boolean,
)

/** Friendly author label for the card header. */
internal fun shareCardRoleLabel(role: String?): String = when (role?.trim()?.lowercase()) {
    "user" -> "You"
    "assistant", "tool" -> "Assistant"
    "system" -> "System"
    else -> "Message"
}

internal fun shareCardRoleFor(message: Message): String = when (message) {
    is UserMessage -> "user"
    is AssistantMessage -> "assistant"
    is SystemNote -> "system"
}

/** The card only ever shows a single-line, bounded clock string. */
internal fun shareCardTimeLabel(raw: String?): String = raw
    ?.filter { ch -> !ch.isISOControl() }
    ?.trim()
    ?.take(40)
    .orEmpty()

/**
 * Privacy filter for the shared card: credential-like strings never leave the device. Covers
 * `sk-...` keys, Bearer/Authorization headers, token/api_key/password/secret assignments (quoted or
 * not) and long base64-ish runs. The surrounding text and the key name stay readable.
 */
internal fun redactedShareCardText(raw: String): String {
    var text = raw
    text = text.replace(
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
        "Bearer $SHARE_CARD_REDACTED",
    )
    text = text.replace(
        Regex("""(?i)\bsk-[A-Za-z0-9_-]{12,}"""),
        SHARE_CARD_REDACTED,
    )
    text = text.replace(
        Regex("""(?i)\b(?:gh[pousr]|glpat|xox[baprs])[-_][A-Za-z0-9_-]{12,}"""),
        SHARE_CARD_REDACTED,
    )
    text = text.replace(
        Regex(
            """(?i)\b(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)\b(\s*[:=]\s*)(["']?)([A-Za-z0-9._~+/=-]{6,})\3""",
        ),
    ) { match ->
        val key = match.groupValues[1]
        val separator = match.groupValues[2]
        "$key$separator$SHARE_CARD_REDACTED"
    }
    // Long base64-ish runs (e.g. raw tokens) are redacted; require a digit and a letter so ordinary
    // long words/prose runs are not destroyed.
    text = text.replace(
        Regex(
            """\b(?=[A-Za-z0-9+/]*[0-9])(?=[A-Za-z0-9+/]*[A-Za-z])[A-Za-z0-9+/]{40,}={0,2}\b""",
        ),
        SHARE_CARD_REDACTED,
    )
    return text
}

/** Control-safe body text with a hard code-point cap; returns (text, truncatedByCap). */
internal fun sanitizedShareCardInput(raw: String?): Pair<String, Boolean> {
    val normalized = raw.orEmpty()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\t", "  ")
        .filter { ch -> ch == '\n' || !ch.isISOControl() }
    val bounded = redactedShareCardText(normalized)
    // Count code points, never UTF-16 units: `take(n)` could split an emoji surrogate pair.
    val codePoints = bounded.codePointCount(0, bounded.length)
    val truncated = codePoints > SHARE_CARD_MAX_CHARS
    val capped = if (truncated) {
        bounded.substring(0, bounded.offsetByCodePoints(0, SHARE_CARD_MAX_CHARS))
    } else {
        bounded
    }
    return capped.trimEnd('\n') to truncated
}

/** Removes common inline Markdown markers while preserving the visible words. */
internal fun stripShareCardMarkdownInline(line: String): String {
    var text = line
    text = text.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
    text = text.replace(Regex("^\\s{0,3}>\\s?"), "")
    text = text.replace(Regex("^\\s{0,3}[-*+]\\s+"), "• ")
    text = text.replace(Regex("^\\s{0,3}\\d+[.)]\\s+")) { match -> match.value.trim() + " " }
    text = text.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    text = text.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    text = text.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
    return text.trimEnd()
}

/**
 * Splits sanitized text into prose and fenced-code segments: fence lines are removed, prose gets
 * light Markdown stripping, and code keeps its whitespace and renders monospace. Blank lines are
 * preserved. Returns an empty list for blank input.
 */
internal fun parseShareCardSegments(plainText: String): List<ShareCardSegment> {
    if (plainText.isBlank()) return emptyList()
    val segments = mutableListOf<ShareCardSegment>()
    var inCode = false
    val current = StringBuilder()
    fun flush(monospace: Boolean) {
        if (current.isNotEmpty()) {
            val text = current.toString().trim('\n')
            if (text.isNotBlank()) segments += ShareCardSegment(text, monospace)
            current.setLength(0)
        }
    }
    plainText.replace("\r\n", "\n").split('\n').forEach { line ->
        if (line.trimStart().startsWith("```")) {
            flush(inCode)
            inCode = !inCode
        } else {
            current.append(if (inCode) line else stripShareCardMarkdownInline(line)).append('\n')
        }
    }
    flush(inCode)
    return segments
}

/** Surrogate-pair-safe hard split point so emoji are never cut in half. */
private fun safeLineEnd(text: String, start: Int, limit: Int): Int {
    var end = (start + limit).coerceAtMost(text.length)
    if (end < text.length && end > start &&
        text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
    ) {
        end -= 1
    }
    return if (end <= start) (start + 1).coerceAtMost(text.length) else end
}

/**
 * Wraps text into at most [maxLines] lines of ~[maxCharsPerLine] chars, preserving explicit line
 * breaks and hard-breaking unbroken words (CJK/emoji safe). The last line is ellipsized when the
 * text overflows; returns (lines, truncated).
 */
internal fun wrapShareCardText(
    text: String,
    maxCharsPerLine: Int = SHARE_CARD_PROSE_CHARS_PER_LINE,
    maxLines: Int = SHARE_CARD_MAX_LINES,
): Pair<List<String>, Boolean> {
    val perLine = maxCharsPerLine.coerceAtLeast(1)
    val lineLimit = maxLines.coerceAtLeast(1)
    val lines = mutableListOf<String>()
    var truncated = false

    Paragraph@ for (paragraph in text.replace("\r\n", "\n").split('\n')) {
        if (paragraph.isEmpty()) {
            if (lines.size < lineLimit) {
                lines += ""
            } else {
                truncated = true
                break@Paragraph
            }
            continue
        }
        val words = paragraph.split(' ')
        var current = StringBuilder()
        var pending = words.toMutableList()
        while (pending.isNotEmpty() || current.isNotEmpty()) {
            if (lines.size >= lineLimit) {
                truncated = true
                break@Paragraph
            }
            if (pending.isEmpty()) {
                lines += current.toString()
                current = StringBuilder()
                break
            }
            val word = pending.removeAt(0)
            if (word.isEmpty()) continue
            if (word.length > perLine) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                    if (lines.size >= lineLimit) {
                        truncated = true
                        break@Paragraph
                    }
                }
                var index = 0
                while (index < word.length) {
                    val end = safeLineEnd(word, index, perLine)
                    val chunk = word.substring(index, end)
                    index = end
                    if (index < word.length) {
                        lines += chunk
                        if (lines.size >= lineLimit) {
                            truncated = true
                            break@Paragraph
                        }
                    } else {
                        current.append(chunk)
                    }
                }
            } else if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= perLine) {
                current.append(' ').append(word)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (pending.isNotEmpty() || current.isNotEmpty()) {
            // Explicit paragraph break: commit the pending line and continue.
            if (current.isNotEmpty()) lines += current.toString()
            if (lines.size >= lineLimit) {
                truncated = pending.isNotEmpty()
                break
            }
        }
    }
    if (lines.isEmpty()) lines += ""
    if (truncated) {
        val last = lines.lastOrNull().orEmpty()
        lines[lines.lastIndex] = if (last.length + 1 > perLine) {
            last.take((perLine - 1).coerceAtLeast(0)) + "…"
        } else {
            last + "…"
        }
    }
    return lines to truncated
}

/** Stable ASCII slug used in the PNG filename. */
internal fun shareCardFileSlug(raw: String?): String = raw.orEmpty()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .take(32)
    .ifBlank { "message" }

/**
 * PNG name: readable slug + stable session/message hash + a per-render [nonce]. Every render gets a
 * fresh file so an in-flight share URI is never overwritten or deleted.
 */
internal fun shareCardFileName(sessionTitle: String?, messageId: String, nonce: Long): String {
    val key = (sessionTitle.orEmpty() + "\u0000" + messageId)
    val hash = key.hashCode().toUInt().toString(16).padStart(8, '0')
    val unique = nonce.toUInt().toString(16).padStart(8, '0')
    return "share-card-${shareCardFileSlug(sessionTitle)}-$hash-$unique.png"
}

/** Deterministic bitmap height within memory bounds; [truncated] reserves the note band. */
internal fun shareCardHeightPx(lineCount: Int, truncated: Boolean): Int {
    val textHeight = lineCount.coerceAtLeast(1) * SHARE_CARD_LINE_HEIGHT_PX
    val note = if (truncated) SHARE_CARD_LINE_HEIGHT_PX else 0
    return (SHARE_CARD_HEADER_PX + textHeight + note + SHARE_CARD_FOOTER_PX)
        .coerceIn(900, SHARE_CARD_MAX_HEIGHT_PX)
}

// endregion

// region background jobs

internal const val JOBS_CACHE_TTL_MS: Long = 30_000L
internal const val JOBS_ACTION_LABEL: String = "Jobs"
internal const val JOBS_SHEET_TITLE: String = "Jobs"
internal const val JOBS_READ_ONLY_NOTE: String = "Read-only view of background jobs."
internal const val JOBS_EMPTY_COPY: String = "No background jobs yet."
internal const val JOBS_UNSUPPORTED_COPY: String = "This host does not expose jobs."
internal const val JOBS_LOAD_FAILED_COPY: String = "Couldn't load jobs."
internal const val JOBS_LOADING_COPY: String = "Loading jobs…"
internal const val JOBS_RETRY_LABEL: String = "Retry"
internal const val JOBS_REFRESH_LABEL: String = "Refresh"
internal const val JOBS_MAX_LABEL_CHARS: Int = 160
internal const val JOBS_MAX_DETAIL_CHARS: Int = 400

/**
 * Normalized job status. J1: only [Running] is active; every settled/unknown status is terminal.
 * The host folds stopped/cancelled/killed into "stopped" and counts settled/unknown terminals as
 * completed, so [Stopped] keeps a distinct label while counting as completed for derived counts.
 */
enum class JobStatus(val label: String, val active: Boolean) {
    Running("Running", true),
    Pending("Pending", false),
    Completed("Completed", false),
    Failed("Failed", false),
    Stopped("Stopped", false),
}

data class JobEntry(
    val id: String,
    val kindLabel: String,
    val label: String,
    val status: JobStatus,
    val startedAt: String?,
    val finishedAt: String?,
    val durationMs: Long?,
    val detail: String?,
)

data class JobCounts(
    val running: Int,
    val completed: Int,
    val failed: Int,
) {
    val total: Int get() = running + completed + failed
}

data class HostJobs(
    val supported: Boolean,
    val counts: JobCounts = JobCounts(0, 0, 0),
    val jobs: List<JobEntry> = emptyList(),
) {
    companion object {
        val Unsupported = HostJobs(supported = false)
        val Empty = HostJobs(supported = true)
    }
}

/** Host statuses normalized; queued/unknown statuses count as pending (active). */
/**
 * J1 status matrix aligned with the host: only `running` is active. `stopped`/`cancelled`/`killed`
 * stay a distinct [JobStatus.Stopped] label; unknown/pending values are terminal and never active
 * (unknown terminal values count as completed, matching the host).
 */
internal fun normalizedJobStatus(raw: String?): JobStatus = when (
    raw?.trim()?.lowercase()?.replace('-', '_')
) {
    "running", "active", "started", "in_progress", "working" -> JobStatus.Running
    "pending", "queued", "created", "scheduled", "waiting" -> JobStatus.Pending
    "completed", "complete", "done", "succeeded", "success" -> JobStatus.Completed
    "failed", "failure", "error", "errored", "timeout" -> JobStatus.Failed
    "stopped", "cancelled", "canceled", "killed", "terminated", "aborted" -> JobStatus.Stopped
    // Unknown terminal status: not active; the host counts it as completed.
    else -> JobStatus.Completed
}

private fun sanitizedJobText(raw: String?, maxChars: Int): String? = raw
    ?.let { text -> buildString(text.length) { text.forEach { append(if (it.isISOControl()) ' ' else it) } } }
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(maxChars)
    ?.takeIf { it.isNotEmpty() }

/** Friendly kind label; unknown kinds are prettified, blank is "Job". */
internal fun jobKindLabel(raw: String?): String {
    val cleaned = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return "Job"
    val known = when (cleaned) {
        "shell", "bash", "sh", "exec", "command", "cmd" -> "Shell"
        "python", "py" -> "Python"
        "node", "nodejs", "javascript", "js", "ts", "typescript" -> "Node"
        "build", "gradle", "make", "cmake" -> "Build"
        "test", "tests", "pytest", "jest" -> "Test"
        "browser", "playwright", "chromium" -> "Browser"
        "http", "request", "fetch", "curl" -> "HTTP"
        "file", "fs", "filesystem" -> "File"
        "script", "task", "job" -> "Script"
        else -> null
    }
    if (known != null) return known
    return cleaned
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(40)
        .replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
        .ifBlank { "Job" }
}

/** Control-safe label, bounded for the row and never blank. */
internal fun sanitizedJobLabel(raw: String?): String =
    sanitizedJobText(raw, JOBS_MAX_LABEL_CHARS) ?: "(untitled job)"

/** Control-safe, credential-redacted failure detail, bounded for the sheet. */
internal fun sanitizedJobDetail(raw: String?): String? =
    sanitizedJobText(raw, JOBS_MAX_DETAIL_CHARS)?.let { redactedShareCardText(it) }

/** Duration label: "" when unknown, ms/s/m/h with one decimal for seconds. */
internal fun formatJobDuration(durationMs: Long?): String {
    val safe = durationMs?.takeIf { it >= 0 } ?: return ""
    return when {
        safe < 1_000L -> "$safe ms"
        safe < 60_000L -> String.format(java.util.Locale.US, "%.1f s", safe / 1_000.0)
        safe < 3_600_000L -> {
            val totalSeconds = safe / 1_000L
            String.format(java.util.Locale.US, "%dm %02ds", totalSeconds / 60, totalSeconds % 60)
        }

        else -> {
            val totalMinutes = safe / 60_000L
            String.format(java.util.Locale.US, "%dh %02dm", totalMinutes / 60, totalMinutes % 60)
        }
    }
}

/** Started-at meta for the row; blank for old hosts. */
internal fun jobStartedLabel(raw: String?): String = raw
    ?.filter { !it.isISOControl() }
    ?.trim()
    ?.take(32)
    .orEmpty()

internal fun jobFailureDetail(job: JobEntry): String? =
    if (job.status == JobStatus.Failed) job.detail?.takeIf { it.isNotBlank() } else null

/** Active jobs first (running, then pending), then terminals newest finishedAt (no re-sort by kind). */
internal fun orderedJobs(jobs: List<JobEntry>): List<JobEntry> {
    fun rank(job: JobEntry): Int = when (job.status) {
        JobStatus.Running -> 0
        JobStatus.Pending -> 1
        // J5/J9: completed/failed/stopped keep the host's finished ordering, not a status re-sort.
        JobStatus.Completed, JobStatus.Failed, JobStatus.Stopped -> 2
    }
    fun timestamp(job: JobEntry): String = job.finishedAt ?: job.startedAt ?: ""
    return jobs.sortedWith(
        compareBy<JobEntry> { rank(it) }.thenByDescending { timestamp(it) },
    )
}

/**
 * Counters are non-negative; host counts are authoritative when supplied (J1), and are only derived
 * when absent. Derived completed includes stopped/unknown terminals, matching host accounting.
 */
internal fun clampedJobCounts(hostCounts: JobCounts?, jobs: List<JobEntry>): JobCounts {
    val derived = JobCounts(
        running = jobs.count { it.status == JobStatus.Running },
        completed = jobs.count {
            it.status == JobStatus.Completed || it.status == JobStatus.Stopped
        },
        failed = jobs.count { it.status == JobStatus.Failed },
    )
    if (hostCounts == null) return derived
    return JobCounts(
        running = hostCounts.running.coerceAtLeast(0),
        completed = hostCounts.completed.coerceAtLeast(0),
        failed = hostCounts.failed.coerceAtLeast(0),
    )
}

internal fun jobRunningHint(counts: JobCounts): String? =
    if (counts.running <= 0) null else "${counts.running} running"

internal fun jobsActionContentDescription(counts: JobCounts?): String {
    val hint = counts?.let { jobRunningHint(it) }
    return if (hint == null) "Open jobs" else "Open jobs, $hint"
}

internal fun jobCountsSummary(counts: JobCounts): String =
    "Running ${counts.running} · Completed ${counts.completed} · Failed ${counts.failed}"

/** 30 s TTL/cache decision for the read-only jobs sheet. */
internal fun shouldLoadSessionJobs(
    cached: HostJobs?,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    if (force || cached == null) return true
    if (fetchedAtMs == null) return true
    return nowMs - fetchedAtMs >= JOBS_CACHE_TTL_MS
}

// endregion

// region accent theme packs

internal const val ACCENT_PREF_KEY: String = "theme_accent"

/** Persisted accent choice; Hadal is the default/current look. */
enum class ThemeAccent(val key: String, val label: String) {
    Hadal("hadal", "Hadal"),
    Ocean("ocean", "Ocean"),
    Amethyst("amethyst", "Amethyst"),
    Ember("ember", "Ember"),
    Sakura("sakura", "Sakura"),
    Slate("slate", "Slate"),
    ;

    companion object {
        val Default: ThemeAccent = Hadal

        /** Unknown/missing/blank values fall back to the default accent, never crash. */
        fun fromStorage(raw: String?): ThemeAccent = entries.firstOrNull { accent ->
            accent.key == raw?.trim()?.lowercase()
        } ?: Default
    }
}

internal fun themeAccentFromStorage(raw: String?): ThemeAccent = ThemeAccent.fromStorage(raw)

internal fun themeAccentStorageValue(accent: ThemeAccent): String = accent.key

/** TalkBack label for an accent chip; selection is announced, not color-only. */
internal fun themeAccentChipContentDescription(accent: ThemeAccent, selected: Boolean): String =
    if (selected) "${accent.label} accent, selected" else "${accent.label} accent"

/** ARGB palette for the accent override roles only; surfaces/background stay theme-owned. */
data class AccentArgbPalette(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val inversePrimary: Long,
)

private val OCEAN_DARK = AccentArgbPalette(
    primary = 0xFF6CC6FF,
    onPrimary = 0xFF04121C,
    primaryContainer = 0xFF0E3A55,
    onPrimaryContainer = 0xFFBFE7FF,
    secondary = 0xFFA9DEF5,
    onSecondary = 0xFF04121C,
    secondaryContainer = 0xFF163A4A,
    onSecondaryContainer = 0xFFCDEBF7,
    inversePrimary = 0xFF1F7FB0,
)
private val OCEAN_LIGHT = AccentArgbPalette(
    primary = 0xFF0B5E8C,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFCDE9F7,
    onPrimaryContainer = 0xFF063349,
    secondary = 0xFF3F6675,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFDCEAF0,
    onSecondaryContainer = 0xFF0A2633,
    inversePrimary = 0xFF7CC9F0,
)
private val AMETHYST_DARK = AccentArgbPalette(
    primary = 0xFFC7A8FF,
    onPrimary = 0xFF160A26,
    primaryContainer = 0xFF3A2760,
    onPrimaryContainer = 0xFFE7D8FF,
    secondary = 0xFFD9C2FF,
    onSecondary = 0xFF160A26,
    secondaryContainer = 0xFF2E2344,
    onSecondaryContainer = 0xFFE3D8F5,
    inversePrimary = 0xFF7E57C2,
)
private val AMETHYST_LIGHT = AccentArgbPalette(
    primary = 0xFF5B2E9E,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFE9DDFF,
    onPrimaryContainer = 0xFF2C0D56,
    secondary = 0xFF63546F,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFE9DDF2,
    onSecondaryContainer = 0xFF1F1428,
    inversePrimary = 0xFFC7A8FF,
)
private val EMBER_DARK = AccentArgbPalette(
    primary = 0xFFFFA36B,
    onPrimary = 0xFF1F0B00,
    primaryContainer = 0xFF55260C,
    onPrimaryContainer = 0xFFFFD9C2,
    secondary = 0xFFFFC09A,
    onSecondary = 0xFF1F0B00,
    secondaryContainer = 0xFF3E2414,
    onSecondaryContainer = 0xFFF7DCCB,
    inversePrimary = 0xFFC96F2E,
)
private val EMBER_LIGHT = AccentArgbPalette(
    primary = 0xFFA6430F,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFFFDBC8,
    onPrimaryContainer = 0xFF351000,
    secondary = 0xFF75513E,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFF7DCCB,
    onSecondaryContainer = 0xFF2B1709,
    inversePrimary = 0xFFFFA36B,
)
private val SAKURA_DARK = AccentArgbPalette(
    primary = 0xFFFFA8C8,
    onPrimary = 0xFF260713,
    primaryContainer = 0xFF5A1F35,
    onPrimaryContainer = 0xFFFFD6E4,
    secondary = 0xFFFFC2D7,
    onSecondary = 0xFF260713,
    secondaryContainer = 0xFF3F2030,
    onSecondaryContainer = 0xFFF5D7E1,
    inversePrimary = 0xFFC2557E,
)
private val SAKURA_LIGHT = AccentArgbPalette(
    primary = 0xFFA3315B,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFFFD9E5,
    onPrimaryContainer = 0xFF3D0A20,
    secondary = 0xFF74515E,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFF7DCE5,
    onSecondaryContainer = 0xFF2B111A,
    inversePrimary = 0xFFFFA8C8,
)
private val SLATE_DARK = AccentArgbPalette(
    primary = 0xFFB8CBD9,
    onPrimary = 0xFF0A151C,
    primaryContainer = 0xFF2C3E4A,
    onPrimaryContainer = 0xFFD6E4EE,
    secondary = 0xFFC4D6E2,
    onSecondary = 0xFF0A151C,
    secondaryContainer = 0xFF293A45,
    onSecondaryContainer = 0xFFDCE8F0,
    inversePrimary = 0xFF6E8797,
)
private val SLATE_LIGHT = AccentArgbPalette(
    primary = 0xFF3F5866,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFD6E4EC,
    onPrimaryContainer = 0xFF152A35,
    secondary = 0xFF586770,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFDDE5EA,
    onSecondaryContainer = 0xFF1C262B,
    inversePrimary = 0xFFB8CBD9,
)

/**
 * Accent override palette for [accent] and theme luminance. Null means "keep the current Hadal
 * scheme exactly" (default behavior); surfaces, error, typography and shapes are never touched.
 */
internal fun accentPalette(accent: ThemeAccent, dark: Boolean): AccentArgbPalette? = when (accent) {
    ThemeAccent.Hadal -> null
    ThemeAccent.Ocean -> if (dark) OCEAN_DARK else OCEAN_LIGHT
    ThemeAccent.Amethyst -> if (dark) AMETHYST_DARK else AMETHYST_LIGHT
    ThemeAccent.Ember -> if (dark) EMBER_DARK else EMBER_LIGHT
    ThemeAccent.Sakura -> if (dark) SAKURA_DARK else SAKURA_LIGHT
    ThemeAccent.Slate -> if (dark) SLATE_DARK else SLATE_LIGHT
}

/** Swatch color for the Settings chip; Hadal keeps the exact current mint tokens. */
internal fun accentSwatchArgb(accent: ThemeAccent, dark: Boolean): Long =
    accentPalette(accent, dark)?.primary ?: if (dark) 0xFF4EE59B else 0xFF1F8A5B

/** Responsive chip grid: 2 per row on narrow sheets so labels are never clipped. */
internal fun accentChipColumns(availableWidthDp: Float): Int = when {
    availableWidthDp < 380f -> 2
    else -> 3
}

/** WCAG relative contrast ratio for two 0xAARRGGBB colors (used to keep accent text readable). */
internal fun contrastRatioArgb(first: Long, second: Long): Double {
    fun luminance(argb: Long): Double {
        fun channel(value: Long): Double {
            val srgb = value / 255.0
            return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    val firstLum = luminance(first)
    val secondLum = luminance(second)
    val lighter = maxOf(firstLum, secondLum)
    val darker = minOf(firstLum, secondLum)
    return (lighter + 0.05) / (darker + 0.05)
}

// endregion

// region adaptive two-pane layout

/**
 * Wide-window breakpoint: **tablets and foldables at/above 960dp** get the rail + embedded chat.
 * R12: landscape phones below 960dp stay single-pane (at 840dp a 308dp rail would leave only ~532dp
 * for the chat pane, which is cramped).
 */
internal const val TWO_PANE_MIN_WIDTH_DP: Float = 960f

/** Rail width in the wide layout (persistent session list, ~300–308dp). */
internal const val SESSION_RAIL_WIDTH_DP: Float = 308f

/**
 * Two-pane decision: only wide windows, with an open session, and no full-screen overlay
 * (pairing/hosts/welcome/settings/usage/diagnostics/goals/schedules/inbox/hidden/archived/search)
 * are showing. Every other case keeps today's single-pane behavior.
 */
internal fun usesTwoPaneLayout(
    widthDp: Float,
    hasSession: Boolean,
    hasOverlay: Boolean,
): Boolean = widthDp >= TWO_PANE_MIN_WIDTH_DP && hasSession && !hasOverlay

/** Compact connection summary for the rail header (pure, testable). */
internal fun sessionRailConnectionLabel(
    connection: ConnectionStatus,
    hostLabel: String?,
): String = when (connection) {
    ConnectionStatus.Live -> "live · ${hostLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "desktop"}"
    ConnectionStatus.Offline -> "offline"
    ConnectionStatus.Connecting -> "connecting…"
    ConnectionStatus.Demo -> "demo · not paired"
}

internal fun sessionRailStatusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Working -> "working"
    SessionStatus.NeedsInput -> "needs input"
    SessionStatus.Idle -> "idle"
}

/** TalkBack label for one rail row: title, workspace, status, unread and selection state. */
internal fun sessionRailItemContentDescription(
    title: String,
    workspaceName: String?,
    status: SessionStatus,
    unread: Boolean,
    selected: Boolean,
): String = buildString {
    append(title.trim().ifBlank { "Untitled session" })
    workspaceName?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append(", ")
        append(it)
    }
    append(", ")
    append(sessionRailStatusLabel(status))
    if (unread) append(", unread")
    if (selected) append(", current")
}

// endregion

// region context window meter

internal const val CONTEXT_CACHE_TTL_MS: Long = 30_000L
internal const val CONTEXT_SECTION_TITLE: String = "Context"
internal const val CONTEXT_LOADING_COPY: String = "Loading context…"
internal const val CONTEXT_UNSUPPORTED_COPY: String = "This host does not expose context usage."
internal const val CONTEXT_LOAD_FAILED_COPY: String = "Couldn't load context usage."
internal const val CONTEXT_UNKNOWN_COPY: String = "Context usage unknown."
internal const val CONTEXT_RETRY_LABEL: String = "Retry"
internal const val CONTEXT_REFRESH_LABEL: String = "Refresh"
internal const val CONTEXT_BREAKDOWN_HEADING: String = "Breakdown (approx.)"
internal const val CONTEXT_WARNING_AMBER_COPY: String = "Context nearly full"
internal const val CONTEXT_WARNING_RED_COPY: String = "Context almost full — compact soon"
internal const val CONTEXT_AMBER_THRESHOLD: Int = 80
internal const val CONTEXT_RED_THRESHOLD: Int = 95

data class ContextBreakdown(
    val systemTokens: Long?,
    val toolsTokens: Long?,
    val messageTokens: Long?,
)

data class ContextUsage(
    val surfaceTokens: Long?,
    val contextWindow: Long?,
    val pressureTokens: Long?,
    val percent: Double?,
    val breakdown: ContextBreakdown?,
    val sampledAt: String?,
)

data class HostContext(
    val supported: Boolean,
    val usage: ContextUsage? = null,
) {
    companion object {
        val Unsupported = HostContext(supported = false)
    }
}

enum class ContextWarningLevel { Normal, Amber, Red }

/**
 * Percent clamped to 0..100 without rounding (a11y/progress-bar precision). When the host omits
 * `percent`, the fallback mirrors the host formula: **pressureTokens/window first**, then
 * surfaceTokens/window (C1); partial data without a window yields null, not a misleading 0%.
 */
internal fun contextPercentRaw(usage: ContextUsage?): Double? {
    usage ?: return null
    val raw = usage.percent ?: run {
        val window = usage.contextWindow ?: return null
        if (window <= 0L) return null
        val used = usage.pressureTokens ?: usage.surfaceTokens ?: return null
        used * 100.0 / window
    }
    if (!raw.isFinite() || raw < 0.0) return null
    return raw.coerceIn(0.0, 100.0)
}

private fun formatPercentOneDecimal(value: Double): String =
    String.format(java.util.Locale.US, "%.1f", value).trimEnd('0').trimEnd('.')

/** TalkBack label for the meter bar, e.g. "Context 64.8 percent full". */
internal fun contextPercentA11yLabel(usage: ContextUsage?): String? {
    val raw = contextPercentRaw(usage) ?: return null
    return "Context ${formatPercentOneDecimal(raw)} percent full"
}

/**
 * Percent rounded/clamped for display and warnings. C5: warnings are evaluated from this single
 * rounded value so 79.9 → 80 (amber) and 94.9 → 95 (red) without double-rounding surprises.
 */
internal fun contextPercent(usage: ContextUsage?): Int? =
    contextPercentRaw(usage)?.let { Math.round(it).toInt().coerceIn(0, 100) }

/** Compact token formatting: 999 → "999", 1500 → "1.5k", 567000 → "567k", 1M → "1.0M". */
internal fun formatCompactTokenCount(tokens: Long?): String {
    val safe = tokens?.takeIf { it >= 0L } ?: return ""
    if (safe < 1_000L) return safe.toString()
    if (safe < 1_000_000L) {
        val value = String.format(java.util.Locale.US, "%.1f", safe / 1_000.0)
            .trimEnd('0')
            .trimEnd('.')
        return "${value}k"
    }
    if (safe < 1_000_000_000L) {
        return String.format(java.util.Locale.US, "%.1fM", safe / 1_000_000.0)
    }
    return String.format(java.util.Locale.US, "%.1fG", safe / 1_000_000_000.0)
}

/** "567k / 1.0M tokens" with graceful degradation when only one side is known. */
internal fun contextTokensSummary(usage: ContextUsage?): String? {
    usage ?: return null
    val used = formatCompactTokenCount(usage.surfaceTokens)
    val window = formatCompactTokenCount(usage.contextWindow)
    return when {
        used.isNotEmpty() && window.isNotEmpty() -> "$used / $window tokens"
        used.isNotEmpty() -> "$used tokens"
        window.isNotEmpty() -> "of $window tokens"
        else -> null
    }
}

internal fun contextPressureSummary(usage: ContextUsage?): String? {
    val pressure = usage?.pressureTokens?.takeIf { it > 0L } ?: return null
    return "Pressure ~${formatCompactTokenCount(pressure)} tokens"
}

internal fun contextWarningLevel(percent: Int?): ContextWarningLevel = when {
    percent == null -> ContextWarningLevel.Normal
    percent >= CONTEXT_RED_THRESHOLD -> ContextWarningLevel.Red
    percent >= CONTEXT_AMBER_THRESHOLD -> ContextWarningLevel.Amber
    else -> ContextWarningLevel.Normal
}

internal fun contextWarningCopy(level: ContextWarningLevel): String? = when (level) {
    ContextWarningLevel.Normal -> null
    ContextWarningLevel.Amber -> CONTEXT_WARNING_AMBER_COPY
    ContextWarningLevel.Red -> CONTEXT_WARNING_RED_COPY
}

/** Breakdown rows in stable System → Tools → Messages order; missing values are skipped. */
internal fun orderedContextBreakdown(
    breakdown: ContextBreakdown?,
): List<Pair<String, Long>> {
    breakdown ?: return emptyList()
    return buildList {
        breakdown.systemTokens?.takeIf { it >= 0L }?.let { add("System" to it) }
        breakdown.toolsTokens?.takeIf { it >= 0L }?.let { add("Tools" to it) }
        breakdown.messageTokens?.takeIf { it >= 0L }?.let { add("Messages" to it) }
    }
}

internal fun contextSampledLabel(raw: String?): String? = raw
    ?.filter { !it.isISOControl() }
    ?.trim()
    ?.take(40)
    ?.takeIf { it.isNotEmpty() }

/** 30 s TTL/cache decision for the read-only context meter. */
internal fun shouldLoadSessionContext(
    cached: HostContext?,
    loading: Boolean,
    force: Boolean,
    fetchedAtMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (loading) return false
    if (force || cached == null) return true
    if (fetchedAtMs == null) return true
    return nowMs - fetchedAtMs >= CONTEXT_CACHE_TTL_MS
}

// endregion

// region session lineage + preset

/** UI copy for lineage/preset context. */
internal const val SESSION_PRESET_LABEL_PREFIX: String = "Preset: "
internal const val SESSION_FORKED_FROM_PREFIX: String = "Forked from "
internal const val SESSION_PARENT_UNAVAILABLE: String = "Parent session not available"
internal const val SESSION_OPEN_PARENT_LABEL: String = "Open parent"

/** Sanitized agent preset label; null for blank/absent and bounded for absurd names. */
internal fun sanitizedAgentPreset(raw: String?): String? = raw
    ?.filter { ch -> !ch.isISOControl() }
    ?.trim()
    ?.take(80)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/** Compact header/info label, e.g. "Preset: cordis"; null for old hosts. */
internal fun sessionPresetLabel(session: Session): String? =
    sanitizedAgentPreset(session.agentPreset)?.let { "$SESSION_PRESET_LABEL_PREFIX$it" }

/** Stable short id fallback for a parent we cannot resolve from the loaded session list. */
internal fun shortSessionId(id: String, maxChars: Int = 8): String {
    val cleaned = id.trim()
    if (cleaned.length <= maxChars) return cleaned
    return cleaned.take(maxChars) + "…"
}

/** Resolved parent lineage; null when there is no visible parent (blank/self/old host). */
internal data class SessionParentUi(
    val id: String,
    val title: String?,
    val shortId: String,
    val available: Boolean,
)

/** Resolves the parent title from the already-loaded session list; short id when unavailable. */
internal fun sessionParentUi(session: Session, sessions: List<Session>): SessionParentUi? {
    val parentId = session.parentSessionId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != session.id }
        ?: return null
    val parent = sessions.firstOrNull { it.id == parentId }
    val parentTitle = parent?.title
        ?.filter { ch -> !ch.isISOControl() }
        ?.trim()
        ?.take(120)
        ?.takeIf { it.isNotEmpty() }
    return SessionParentUi(
        id = parentId,
        title = parentTitle,
        shortId = shortSessionId(parentId),
        available = parent != null,
    )
}

/** "Forked from <parent title>" (or the short id when the title is unknown). */
internal fun sessionForkedFromLabel(parent: SessionParentUi): String =
    SESSION_FORKED_FROM_PREFIX + (parent.title ?: parent.shortId)

/** L6: accessibility label for the Open parent button (title, or short id when unresolved). */
internal fun sessionOpenParentContentDescription(parent: SessionParentUi): String =
    "$SESSION_OPEN_PARENT_LABEL session: ${parent.title ?: parent.shortId}"

// endregion

// region tool output viewer

/** Compact affordance label on a tool row with nonblank output. */
internal const val TOOL_OUTPUT_VIEW_LABEL: String = "View output"
internal const val TOOL_OUTPUT_VIEWER_TITLE: String = "Tool output"
internal const val TOOL_OUTPUT_TRUNCATED_NOTE: String =
    "The host capped this output, so the end may be missing."
internal const val TOOL_OUTPUT_COPY_SUCCESS: String = "Output copied"
internal const val TOOL_OUTPUT_COPY_FAILURE: String = "Couldn't copy the output."
internal const val TOOL_OUTPUT_SHARE_FAILURE: String = "No app can share the output."

/** Host marker appended when the 8k text-block cap cut the output. */
internal const val TOOL_OUTPUT_TRUNCATED_MARKER: String = "(output truncated)"

/** Absent/blank output keeps today's tool row: no affordance, no dialog. */
internal fun toolOutputAvailable(tool: ToolActivity): Boolean = tool.output.isNotBlank()

/** Viewer heading; falls back to the generic title for nameless tools. */
internal fun toolOutputViewerTitle(tool: ToolActivity): String {
    val name = tool.name.trim()
    return if (name.isBlank()) TOOL_OUTPUT_VIEWER_TITLE else "$TOOL_OUTPUT_VIEWER_TITLE · $name"
}

/** True when the host marked the text as capped. */
internal fun toolOutputIsTruncated(output: String): Boolean =
    output.contains(TOOL_OUTPUT_TRUNCATED_MARKER, ignoreCase = true)

/** Copy/share payload: the output exactly as received (never mixed with message text). */
internal fun toolOutputCopyText(output: String): String = output

internal fun toolOutputShareSubject(tool: ToolActivity): String =
    "${tool.name.trim().ifBlank { "Tool" }} output"

// endregion

// region transcript PDF export

/** Cap for one exported PDF: messages, per-message char points, total chars and pages. Lower caps
 * than a whole-transcript layout keep peak memory bounded on low-RAM devices. */
internal const val TRANSCRIPT_PDF_MAX_MESSAGES: Int = 200
internal const val TRANSCRIPT_PDF_MAX_MESSAGE_CHARS: Int = 20_000
internal const val TRANSCRIPT_PDF_MAX_TOTAL_CHARS: Int = 120_000
internal const val TRANSCRIPT_PDF_MAX_PAGES: Int = 80

/** A4 portrait at 72 dpi (points). */
internal const val TRANSCRIPT_PDF_PAGE_WIDTH: Int = 595
internal const val TRANSCRIPT_PDF_PAGE_HEIGHT: Int = 842
internal const val TRANSCRIPT_PDF_MARGIN: Int = 48

/** Status copy for the Export PDF action. */
internal const val TRANSCRIPT_PDF_EXPORTING_COPY: String = "Exporting PDF…"
internal const val TRANSCRIPT_PDF_SUCCESS_COPY: String = "PDF ready"
internal const val TRANSCRIPT_PDF_FAILURE_COPY: String = "Couldn't export the PDF."
internal const val TRANSCRIPT_PDF_SHARE_FAILURE_COPY: String = "No app can share the PDF."
internal const val TRANSCRIPT_PDF_EMPTY_COPY: String = "There are no messages to export."
internal const val TRANSCRIPT_PDF_CAPPED_MESSAGES_COPY: String =
    "PDF was shortened (first $TRANSCRIPT_PDF_MAX_MESSAGES messages)."
internal const val TRANSCRIPT_PDF_CAPPED_PAGES_COPY: String =
    "PDF was shortened (first $TRANSCRIPT_PDF_MAX_PAGES pages)."

/**
 * Code-point-safe prefix: never splits a surrogate pair (emoji) the way String.take() can.
 * [maxCodePoints] counts code points, not UTF-16 units.
 */
internal fun takeTranscriptPdfCodePoints(text: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0) return ""
    if (text.length <= maxCodePoints) return text
    var count = 0
    var index = 0
    while (index < text.length && count < maxCodePoints) {
        val codePoint = text.codePointAt(index)
        index += Character.charCount(codePoint)
        count += 1
    }
    return text.substring(0, index)
}

/** Share-safe PDF file name: dsh-mobile-<sanitized-title>-<timestamp>.pdf. */
internal fun transcriptPdfFileName(title: String, timestampLabel: String): String {
    val rawSlug = title
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
    val slug = takeTranscriptPdfCodePoints(rawSlug, 40)
        .trim('-')
        .ifBlank { "session" }
    val stamp = timestampLabel.filter { ch -> ch.isLetterOrDigit() || ch == '-' }
        .ifBlank { "0" }
    return "dsh-mobile-$slug-$stamp.pdf"
}

/**
 * PDF-safe text: normalizes CRLF, drops control characters except newline/tab, trims and caps the
 * length (by code points) with an ellipsis so one huge message cannot blow up the document.
 */
internal fun sanitizeTranscriptPdfText(
    text: String,
    maxChars: Int = TRANSCRIPT_PDF_MAX_MESSAGE_CHARS,
): String {
    val normalized = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { ch -> ch == '\n' || ch == '\t' || !ch.isISOControl() }
        .trim()
    if (maxChars <= 0) return ""
    if (normalized.length <= maxChars) return normalized
    return takeTranscriptPdfCodePoints(normalized, maxChars).trimEnd() + "…"
}

/** Role label rendered above each transcript message. */
internal fun transcriptPdfRoleLabel(message: Message): String = when (message) {
    is UserMessage -> "User"
    is AssistantMessage -> "Assistant"
    is SystemNote -> "System"
}

/** The exact text the Markdown transcript uses; empty messages are skipped by the PDF exporter. */
internal fun transcriptPdfMessageText(message: Message): String = when (message) {
    is UserMessage -> message.text
    is AssistantMessage -> message.text
    is SystemNote -> message.text
}

/** P3: the host-provided time is user-facing text; strip controls/CR and bound it before drawing. */
internal fun transcriptPdfMessageTimeLabel(message: Message): String =
    sanitizeTranscriptPdfText(message.time, 80).ifBlank { "unknown time" }

/** Messages with real content, capped to the first [TRANSCRIPT_PDF_MAX_MESSAGES]. */
internal fun transcriptPdfExportableMessages(messages: List<Message>): List<Message> =
    messages
        .asSequence()
        .filter { message -> transcriptPdfMessageText(message).isNotBlank() }
        .take(TRANSCRIPT_PDF_MAX_MESSAGES)
        .toList()

/** P1: the message-count cap must be visible; it silently drops the rest otherwise. */
internal fun transcriptPdfMessagesCapped(messages: List<Message>): Boolean =
    messages.count { message -> transcriptPdfMessageText(message).isNotBlank() } >
        TRANSCRIPT_PDF_MAX_MESSAGES

internal fun transcriptPdfWasCapped(totalMessages: Int): Boolean =
    totalMessages > TRANSCRIPT_PDF_MAX_MESSAGES

/** Footer label; total page count is not known until layout finishes. */
internal fun transcriptPdfFooterLabel(pageNumber: Int): String = "Page $pageNumber"

/** Pages needed for a measured content height, used by tests/guards. */
internal fun transcriptPdfPagesNeeded(
    contentHeightPx: Int,
    pageContentHeightPx: Int,
): Int {
    if (contentHeightPx <= 0) return 1
    if (pageContentHeightPx <= 0) return 1
    return (contentHeightPx + pageContentHeightPx - 1) / pageContentHeightPx
}

/** Optional status line when caps kicked in; null when the export is complete. */
internal fun transcriptPdfCappedNote(
    messagesCapped: Boolean,
    pagesCapped: Boolean,
): String? = when {
    messagesCapped && pagesCapped ->
        "PDF was shortened ($TRANSCRIPT_PDF_MAX_MESSAGES messages / " +
            "$TRANSCRIPT_PDF_MAX_PAGES pages)."

    messagesCapped -> TRANSCRIPT_PDF_CAPPED_MESSAGES_COPY
    pagesCapped -> TRANSCRIPT_PDF_CAPPED_PAGES_COPY
    else -> null
}

// endregion

// region host-wide message search

/** Shortest query the host search screen will send. */
internal const val HOST_SEARCH_MIN_QUERY_CHARS = 2

/** Cap for a host search query (mirrors the server-side sanity bound). */
internal const val HOST_SEARCH_MAX_QUERY_CHARS = 200

/** Result page size requested from GET /search. */
internal const val HOST_SEARCH_LIMIT = 50

/** Contract bound for the GET /search `offset` param (frozen host contract). */
internal const val HOST_SEARCH_MAX_OFFSET = 500

/** Trimmed, capped query; null when shorter than the minimum (no request for < 2 chars). */
internal fun hostSearchQuery(raw: String): String? {
    val query = raw.trim()
    if (query.length < HOST_SEARCH_MIN_QUERY_CHARS) return null
    return query.take(HOST_SEARCH_MAX_QUERY_CHARS)
}

/**
 * Case-insensitive, non-overlapping match ranges of [query] in [text]; used to highlight the query
 * inside a host snippet cheaply (no regex).
 */
internal fun hostSearchHighlightRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return emptyList()
    val haystack = text.lowercase()
    val target = needle.lowercase()
    val ranges = mutableListOf<IntRange>()
    var start = 0
    while (true) {
        val index = haystack.indexOf(target, start)
        if (index < 0) break
        ranges += index until (index + target.length)
        start = index + target.length
    }
    return ranges
}

/** One host-wide search hit. */
data class HostSearchHit(
    val sessionId: String,
    val sessionTitle: String?,
    val messageId: String,
    val role: String,
    val snippet: String,
    val createdAt: String?,
    /** Optional event sequence for jump-to-message (host 0.3.5+); null when unknown. */
    val seq: Int? = null,
)

// region scheduled prompts

internal const val SCHEDULED_MESSAGE_LABEL: String = "Scheduled"
internal const val SCHEDULED_MESSAGE_CONTENT_DESCRIPTION: String = "Scheduled prompt"

/** Exact match only: future/other internal sources must never be surfaced as scheduled. */
internal fun isScheduledMessageSource(raw: String?): Boolean = raw?.trim() == "schedule"

/** Bubble label; null for ordinary user messages. */
internal fun scheduledMessageLabel(scheduled: Boolean): String? =
    if (scheduled) SCHEDULED_MESSAGE_LABEL else null

/** Copy/share text keeps the label visible instead of silently dropping it. */
internal fun scheduledMessageCopyText(text: String, scheduled: Boolean): String =
    if (scheduled) "$SCHEDULED_MESSAGE_LABEL: $text" else text

/** Transcript heading keeps the marker ("User (Scheduled)") rather than losing it on export. */
internal fun scheduledTranscriptHeading(role: String, scheduled: Boolean): String =
    if (scheduled) "$role ($SCHEDULED_MESSAGE_LABEL)" else role

// endregion

// region message jump

/** Bounded automatic older-page loads while looking for a search-hit message. */
internal const val MESSAGE_JUMP_MAX_OLDER_LOADS: Int = 20
internal const val MESSAGE_JUMP_HIGHLIGHT_MS: Long = 1_600L
internal const val MESSAGE_JUMP_NOT_FOUND_COPY: String =
    "Message not found (it may be older than the loaded history)."

/** Where the chat should jump after opening a session from host search. */
data class MessageJumpTarget(
    val sessionId: String,
    val seq: Int?,
    val messageId: String,
)

/** Builds a target from a search hit; null when there is nothing to jump to. */
internal fun messageJumpTargetForSearchHit(
    sessionId: String,
    seq: Int?,
    messageId: String?,
): MessageJumpTarget? {
    val safeSession = sessionId.trim()
    if (safeSession.isEmpty()) return null
    val safeSeq = seq?.takeIf { it > 0 }
    val safeMessageId = messageId?.trim().orEmpty()
    if (safeSeq == null && safeMessageId.isEmpty()) return null
    return MessageJumpTarget(
        sessionId = safeSession,
        seq = safeSeq,
        messageId = safeMessageId,
    )
}

/** True when [target] belongs to [sessionId] (prevents leaking jumps across sessions). */
internal fun messageJumpTargetMatches(target: MessageJumpTarget?, sessionId: String): Boolean =
    target != null && target.sessionId == sessionId

/**
 * Index of the loaded message to jump to. J2: when a seq is known it wins (tool-call hits carry
 * `m_<callId>` chat ids that are not the seq target), using exact seq → closest at/after (so a
 * tool-call hit lands on the following row) → nearest at/before. The message id is the fallback
 * when there is no seq or no seq match. Duplicate seqs resolve to the first occurrence.
 */
internal fun messageJumpIndex(messages: List<Message>, target: MessageJumpTarget): Int? {
    if (messages.isEmpty()) return null
    val targetSeq = target.seq
    if (targetSeq != null) {
        var afterIndex: Int? = null
        var afterSeq: Int? = null
        var beforeIndex: Int? = null
        var beforeSeq: Int? = null
        messages.forEachIndexed { index, message ->
            val eventSeq = message.eventSeq ?: return@forEachIndexed
            if (eventSeq == targetSeq) return index
            if (eventSeq > targetSeq && (afterSeq == null || eventSeq < afterSeq!!)) {
                afterSeq = eventSeq
                afterIndex = index
            }
            if (eventSeq < targetSeq && (beforeSeq == null || eventSeq > beforeSeq!!)) {
                beforeSeq = eventSeq
                beforeIndex = index
            }
        }
        (afterIndex ?: beforeIndex)?.let { return it }
    }
    if (target.messageId.isNotBlank()) {
        val byId = messages.indexOfFirst { message -> message.id == target.messageId }
        if (byId >= 0) return byId
    }
    return null
}

/** LazyColumn index for a message index, accounting for the optional "older messages" header. */
internal fun messageJumpScrollIndex(messageIndex: Int, hasOlderHeader: Boolean): Int =
    messageIndex + if (hasOlderHeader) 1 else 0

// endregion

/** Host search snapshot; unsupported hosts return [supported] = false and no hits. */
data class HostSearchResult(
    val supported: Boolean,
    val hits: List<HostSearchHit> = emptyList(),
    val truncated: Boolean = false,
    /**
     * S14: whether the host echoed the applied filters. Old hosts ignore role/since/until and return
     * no `filters` object; the UI must not present their results as filtered.
     */
    val filtersEchoed: Boolean = true,
    /** Pagination echo; old hosts default to offset 0 with no further pages. */
    val offset: Int = 0,
    val nextOffset: Int? = null,
    val hasMore: Boolean = false,
    /**
     * Host 0.2.7 search coverage: when true the host could not scan all sessions, so results may be
     * incomplete. Old hosts omit these fields; defaults mean "complete/unknown" (no notice).
     */
    val scanIncomplete: Boolean = false,
    val scannedSessions: Int = 0,
    val totalSessions: Int = 0,
) {
    companion object {
        val Unsupported = HostSearchResult(supported = false)
    }
}

// endregion

// region host search pagination

/**
 * Frozen-contract check for one page: an empty page stops pagination; `hasMore` is only trusted
 * with a nextOffset that advances past this page's offset and stays inside the contract bound.
 */
internal fun sanitizeHostSearchPage(page: HostSearchResult): HostSearchResult {
    if (page.hits.isEmpty()) return page.copy(hasMore = false, nextOffset = null)
    val next = page.nextOffset
    val valid = page.hasMore &&
        next != null &&
        next > page.offset &&
        next in 0..HOST_SEARCH_MAX_OFFSET
    return if (valid) page.copy(hasMore = true, nextOffset = next) else {
        page.copy(hasMore = false, nextOffset = null)
    }
}

/**
 * Appends a load-more page: stale pages (offset mismatch) are ignored, an empty page keeps the
 * current hits but stops pagination, and valid pages dedupe by (sessionId, messageId) while
 * preserving order. Truncated merges with OR.
 */
internal fun appendHostSearchPage(
    current: HostSearchResult,
    page: HostSearchResult,
    requestedOffset: Int = page.offset,
): HostSearchResult {
    // A page for a different cursor must never append to the current result set.
    if (page.offset != requestedOffset) return current
    val next = sanitizeHostSearchPage(page)
    if (next.hits.isEmpty()) return current.copy(hasMore = false, nextOffset = null)
    val seen = HashSet<String>(current.hits.size + next.hits.size)
    val merged = ArrayList<HostSearchHit>(current.hits.size + next.hits.size)
    (current.hits + next.hits).forEach { hit ->
        if (seen.add(hit.sessionId + "\u0000" + hit.messageId)) merged += hit
    }
    return current.copy(
        hits = merged,
        supported = current.supported && next.supported,
        truncated = current.truncated || next.truncated,
        filtersEchoed = current.filtersEchoed && next.filtersEchoed,
        offset = next.offset,
        nextOffset = next.nextOffset,
        hasMore = next.hasMore,
        // Keep the coverage warning visible after loading further pages.
        scanIncomplete = current.scanIncomplete || next.scanIncomplete,
        scannedSessions = next.scannedSessions.takeIf { it > 0 } ?: current.scannedSessions,
        totalSessions = next.totalSessions.takeIf { it > 0 } ?: current.totalSessions,
    )
}

/**
 * Whether a "Load more" request may start: supported result with at least one hit, `hasMore`,
 * a nextOffset that advances past the current offset and is within the contract bound, and neither
 * a fresh search nor a page already in flight. Query-length gating stays in AppState/UI.
 */
internal fun canLoadMoreHostSearch(
    result: HostSearchResult?,
    freshLoading: Boolean,
    loadingMore: Boolean,
): Boolean {
    if (result == null || !result.supported || !result.hasMore) return false
    if (freshLoading || loadingMore) return false
    if (result.hits.isEmpty()) return false
    val next = result.nextOffset ?: return false
    return next > result.offset && next in 0..HOST_SEARCH_MAX_OFFSET
}

/** "Showing N matches" note; the truncated marker is owned by the existing UI copy. */
internal fun searchShowingNote(hitCount: Int): String =
    if (hitCount == 1) "Showing 1 match" else "Showing $hitCount matches"

// endregion

// region host search filters

/** Valid roles accepted by GET /search (comma-separated, max 3). */
internal val SEARCH_FILTER_ROLES: List<String> = listOf("user", "assistant", "tool")

/** Date presets exposed as chips in the host search screen. */
enum class SearchDatePreset(val label: String) {
    ANY("Any"),
    TODAY("Today"),
    LAST_7_DAYS("7 days"),
    LAST_30_DAYS("30 days"),
}

/** Filters kept in UI order (user, assistant, tool); unknown roles are dropped. */
internal fun normalizedSearchRoles(roles: Set<String>): Set<String> =
    SEARCH_FILTER_ROLES.filterTo(linkedSetOf()) { it in roles }

/** Comma-separated `role` param; null = all roles (empty selection is the "All" chip). */
internal fun searchRoleQueryParam(roles: Set<String>): String? {
    val normalized = normalizedSearchRoles(roles)
    return normalized.takeIf { it.isNotEmpty() }?.joinToString(",")
}

/** Multi-select toggle: selecting every role collapses back to "All" (empty set). */
internal fun toggleSearchRole(current: Set<String>, role: String): Set<String> {
    if (role !in SEARCH_FILTER_ROLES) return normalizedSearchRoles(current)
    val next = linkedSetOf<String>().apply {
        addAll(normalizedSearchRoles(current))
        if (role in this) remove(role) else add(role)
    }
    return if (next.size == SEARCH_FILTER_ROLES.size) emptySet() else next
}

/**
 * ISO boundaries for a preset. The host does NOT default `until`, so every window sends both
 * bounds explicitly, captured against one [now] when the query runs:
 * - Today: local midnight (in [zone]) → now
 * - 7/30 days: rolling exact windows → now
 * Instants are rendered as UTC `Z` ISO strings (no `+` offset to URL-encode).
 */
internal fun searchDateRange(
    preset: SearchDatePreset,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<String?, String?> {
    val until = if (preset == SearchDatePreset.ANY) null else now
    val since = when (preset) {
        SearchDatePreset.ANY -> null
        SearchDatePreset.TODAY ->
            now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

        SearchDatePreset.LAST_7_DAYS -> now.minus(7, ChronoUnit.DAYS)
        SearchDatePreset.LAST_30_DAYS -> now.minus(30, ChronoUnit.DAYS)
    }
    return since?.toString() to until?.toString()
}

internal fun searchHasFilters(roles: Set<String>, datePreset: SearchDatePreset): Boolean =
    normalizedSearchRoles(roles).isNotEmpty() || datePreset != SearchDatePreset.ANY

/** S14: true when filters were requested but the host returned no `filters` echo. */
internal fun searchFiltersIgnored(
    filtersRequested: Boolean,
    result: HostSearchResult?,
): Boolean = filtersRequested &&
    result != null &&
    result.supported &&
    !result.filtersEchoed

/** S2: date filters use message timestamps, so undated rows disappear. */
internal const val SEARCH_UNDATED_NOTE: String = "Undated messages are hidden by date filters."

/** Host 0.2.7: honest scan-coverage notice when the host could not scan every session. */
internal const val SEARCH_SCAN_INCOMPLETE_NOTE: String =
    "The host could not scan every session — results may be incomplete."

/** Coverage note for the results state; null when the host reports a complete scan or is old. */
internal fun searchCoverageNote(result: HostSearchResult?): String? {
    if (result == null || !result.supported || !result.scanIncomplete) return null
    if (result.totalSessions <= 0) return SEARCH_SCAN_INCOMPLETE_NOTE
    val scanned = result.scannedSessions.coerceIn(0, result.totalSessions)
    return "Searched $scanned of ${result.totalSessions} sessions — results may be incomplete."
}

/** Short policy explanation for the active date chip (null for Any). */
internal fun searchDatePolicyHint(preset: SearchDatePreset): String? = when (preset) {
    SearchDatePreset.ANY -> null
    SearchDatePreset.TODAY -> "Today: local midnight to now."
    SearchDatePreset.LAST_7_DAYS -> "7 days: rolling window ending now."
    SearchDatePreset.LAST_30_DAYS -> "30 days: rolling window ending now."
}

/** S6: in-flight/duplicate guard identity = query + normalized roles + date preset. */
internal fun hostSearchKey(
    query: String,
    roles: Set<String>,
    datePreset: SearchDatePreset,
): String = buildString {
    append(query)
    append('|')
    append(normalizedSearchRoles(roles).joinToString(","))
    append('|')
    append(datePreset.name)
}

/** "Filters: user, assistant · 7 days" for empty/truncated states; null when no filters. */
internal fun searchActiveFilterSummary(
    roles: Set<String>,
    datePreset: SearchDatePreset,
): String? {
    val parts = buildList {
        val normalized = normalizedSearchRoles(roles)
        if (normalized.isNotEmpty()) add(normalized.joinToString(", "))
        if (datePreset != SearchDatePreset.ANY) add(datePreset.label)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { "Filters: $it" }
}

// endregion

// region recovery access

/** Recovery-code status for the active host; unsupported hosts return [supported] = false. */
data class RecoveryAccess(
    val supported: Boolean,
    val configured: Boolean = false,
    val createdAt: String? = null,
) {
    companion object {
        val Unsupported = RecoveryAccess(supported = false)
    }
}

/** One-time recovery code returned by POST /recovery (shown once, never persisted). */
data class RecoveryCode(
    val code: String,
    val createdAt: String = "",
)

/** Parsed `dshmobile://recover?base=...&code=...` link. */
data class RecoveryLink(
    val baseUrl: String,
    val code: String,
)

/** Rotated code returned by a successful restore; surfaced once in a completion step. */
data class RecoveryCompletion(
    val baseUrl: String,
    val rotatedCode: String?,
)

internal const val RECOVERY_LINK_PREFIX: String = "dshmobile://recover"

internal const val RECOVERY_SETUP_COPY: String =
    "Save this link in a password manager. It is single-use and automatically rotates after use; " +
        "it lets you re-add this host after clearing app data or reinstalling, without a desktop " +
        "pairing code."

/** RFC4648 base32 recovery codes: 52 characters of A-Z2-7 (after stripping spaces/dashes). */
internal const val RECOVERY_CODE_LENGTH: Int = 52
internal val RECOVERY_CODE_PATTERN: Regex = Regex("^[A-Z2-7]{52}$")
internal const val RECOVERY_CODE_FORMAT_MESSAGE: String =
    "That doesn't look like a recovery code."

internal const val RECOVERY_ROTATED_MISSING_COPY: String =
    "The host didn't return a new recovery code. Generate one later in Settings → Security."

/** Codes may be copied with spaces/grouping; strip whitespace and dashes before sending. */
internal fun normalizeRecoveryCode(raw: String): String =
    raw.trim().replace(Regex("[\\s-]+"), "")

/** Client-side format check: normalized code must be 52 RFC4648 base32 characters. */
internal fun recoveryCodeLooksValid(raw: String): Boolean =
    RECOVERY_CODE_PATTERN.matches(normalizeRecoveryCode(raw))

/** Link for the rotated code shown after a successful restore; null when the host returned none. */
internal fun recoveryCompletionLink(completion: RecoveryCompletion): String? =
    completion.rotatedCode
        ?.takeIf { it.isNotBlank() }
        ?.let { recoveryLink(completion.baseUrl, it) }

/** Completion-step copy: explains the old code is dead, or that nothing new was returned. */
internal fun recoveryCompletionMessage(completion: RecoveryCompletion): String =
    if (completion.rotatedCode.isNullOrBlank()) {
        RECOVERY_ROTATED_MISSING_COPY
    } else {
        "The previous code no longer works. Save this new recovery link now - it is shown only once."
    }

private fun encodeRecoveryParam(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")

private fun decodeRecoveryParam(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

/** Builds `dshmobile://recover?base=<encoded>&code=<encoded>`. */
internal fun recoveryLink(baseUrl: String, code: String): String {
    val base = encodeRecoveryParam(baseUrl.trim())
    val normalized = encodeRecoveryParam(normalizeRecoveryCode(code))
    return "$RECOVERY_LINK_PREFIX?base=$base&code=$normalized"
}

/**
 * Parses a pasted recovery link. Accepts only the `dshmobile://recover` host with a valid
 * http(s) base URL and a non-blank code; returns null otherwise.
 */
internal fun parseRecoveryLink(raw: String): RecoveryLink? {
    val text = raw.trim()
    if (!text.startsWith(RECOVERY_LINK_PREFIX)) return null
    val query = text.substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return null
    val params = query.split('&').mapNotNull { part ->
        val key = part.substringBefore('=', "").trim()
        if (key.isBlank()) null else key to decodeRecoveryParam(part.substringAfter('=', ""))
    }.toMap()
    val base = params["base"]?.trim().orEmpty()
    if (base.isEmpty() || !(base.startsWith("http://") || base.startsWith("https://"))) return null
    if (!runCatching { java.net.URI(base).host?.isNotBlank() == true }.getOrDefault(false)) {
        return null
    }
    val code = normalizeRecoveryCode(params["code"].orEmpty())
    if (code.isEmpty()) return null
    return RecoveryLink(baseUrl = base, code = code)
}

/** Settings summary line for the recovery status. */
internal fun recoveryAccessSummary(access: RecoveryAccess?): String = when {
    access == null -> "Loading recovery status…"
    !access.supported -> "Not supported by this host"
    access.configured -> "Recovery access is configured"
    else -> "Not set up yet"
}

/** Failure buckets for restore pairing, kept pure so the copy has stable tests. */
enum class RecoveryFailureKind { INVALID_CODE, RATE_LIMITED, UNSUPPORTED, NETWORK, NOT_FOUND, OTHER }

internal fun recoveryFailureMessage(kind: RecoveryFailureKind): String = when (kind) {
    RecoveryFailureKind.INVALID_CODE ->
        "That recovery code is invalid or has expired. Generate a new one on the host."

    RecoveryFailureKind.RATE_LIMITED ->
        "Too many recovery attempts. Wait a minute, then try again."

    RecoveryFailureKind.UNSUPPORTED ->
        "This host doesn't support recovery codes yet. Update the desktop host plugin."

    RecoveryFailureKind.NETWORK ->
        "Can't reach the host at that URL. Check the address and that the host is running."

    RecoveryFailureKind.NOT_FOUND ->
        "That URL doesn't look like a DSH host. Check the address and port."

    RecoveryFailureKind.OTHER -> "Restore failed. Try again."
}

// endregion

// region tailscale one-tap pairing

/** Pairing methods advertised by GET /pair/methods. */
data class PairMethods(
    val desktopCode: Boolean = true,
    val recovery: Boolean = false,
    val tailscaleAutoPair: Boolean = false,
    val tailscaleLoginRequired: Boolean = false,
)

/** Successful Tailscale auto-pairing result (token + verifiable tailnet identity). */
data class TailscalePairing(
    val token: String,
    val deviceId: String,
    val hostName: String? = null,
    val hostVersion: String? = null,
    val identity: String? = null,
)

internal const val TAILSCALE_IDENTITY_MISSING_CODE: String = "tailscale_identity_missing"

internal const val TAILSCALE_SIGNIN_COPY: String =
    "No pairing code needed on a Tailscale Serve URL; the tailnet verifies this device."

internal enum class TailscaleFailureKind {
    IDENTITY_MISSING,
    FORBIDDEN,
    UNSUPPORTED,
    RATE_LIMITED,
    NETWORK,
    NOT_FOUND,
    OTHER,
}

internal fun tailscaleFailureMessage(kind: TailscaleFailureKind): String = when (kind) {
    TailscaleFailureKind.IDENTITY_MISSING ->
        "Open this host through Tailscale Serve, or use a pairing code/recovery link."

    TailscaleFailureKind.FORBIDDEN ->
        "This host doesn't allow Tailscale sign-in. Use a pairing code or recovery link."

    TailscaleFailureKind.UNSUPPORTED ->
        "Tailscale sign-in isn't available on this host yet. Update the desktop host plugin."

    TailscaleFailureKind.RATE_LIMITED ->
        "Too many sign-in attempts. Wait a minute, then try again."

    TailscaleFailureKind.NETWORK ->
        "Can't reach the host at that URL. Check the address and that Tailscale is on."

    TailscaleFailureKind.NOT_FOUND ->
        "That URL doesn't look like a DSH host. Check the address and port."

    TailscaleFailureKind.OTHER -> "Tailscale sign-in failed. Try again."
}

/** Methods-aware hint under the URL field; null when nothing needs saying. */
internal fun tailscaleMethodsHint(methods: PairMethods?): String? {
    methods ?: return null
    return when {
        !methods.tailscaleAutoPair ->
            "This host isn't set up for Tailscale sign-in. Open it through Tailscale Serve, " +
                "or use a pairing code or recovery link."

        methods.tailscaleLoginRequired ->
            "Sign in to the Tailscale app on this phone first; the host verifies the tailnet " +
                "identity when you continue."

        else -> null
    }
}

/**
 * Button gate for Tailscale sign-in. Unknown methods (the preflight fetch failed) still allow an
 * attempt, because /pair/tailscale reports its own errors; a host that explicitly says auto-pair
 * is off is disabled instead of sending a request that cannot succeed.
 */
internal fun tailscaleSignInAvailable(methods: PairMethods?): Boolean =
    methods?.tailscaleAutoPair != false

// endregion

/** Host ntfy push configuration; the access token itself is never returned (only [hasToken]). */
data class PushConfig(
    val supported: Boolean,
    val enabled: Boolean = false,
    val server: String = "",
    val topic: String = "",
    val hasToken: Boolean = false,
    /** Host 0.3.3+ suppression summary echoed by GET /push; null on old hosts. */
    val suppression: PushSuppressionState? = null,
) {
    companion object {
        val Unsupported = PushConfig(supported = false)
    }
}

/** ntfy topic rules: 1..64 of [A-Za-z0-9_-]. */
internal const val PUSH_TOPIC_MAX_CHARS: Int = 64
internal val PUSH_TOPIC_PATTERN: Regex = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val PUSH_TOPIC_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
internal const val PUSH_TOPIC_RANDOM_LENGTH: Int = 24

internal fun pushTopicValid(topic: String): Boolean =
    PUSH_TOPIC_PATTERN.matches(topic.trim())

/** Suggestion for users who don't have a topic yet: `dsh-` + 24 random [a-z0-9] chars. */
internal fun generatePushTopic(random: Random = Random.Default): String {
    val suffix = buildString(PUSH_TOPIC_RANDOM_LENGTH) {
        repeat(PUSH_TOPIC_RANDOM_LENGTH) {
            append(PUSH_TOPIC_ALPHABET[random.nextInt(PUSH_TOPIC_ALPHABET.length)])
        }
    }
    return "dsh-$suffix"
}

/**
 * Blank means the host default. Otherwise the URL must be https; plain http is allowed only for
 * loopback (localhost/127.0.0.1/::1) because the desktop host has to reach it. Private-LAN and
 * loopback hosts are flagged so the UI can explain why pushes wouldn't arrive.
 */
internal fun pushServerError(server: String): String? {
    val trimmed = server.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
        ?: return "Enter a full URL such as https://ntfy.sh."
    val scheme = uri.scheme?.lowercase(Locale.US)
        ?: return "Enter a full URL such as https://ntfy.sh."
    val host = uri.host?.trim('[', ']')?.lowercase(Locale.US)
    if (host.isNullOrBlank()) return "Enter a full URL such as https://ntfy.sh."
    val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
    when (scheme) {
        "https" -> Unit
        "http" -> if (!loopback) {
            return "Use https:// — plain http is only allowed for localhost."
        }

        else -> return "The server must be an https:// URL."
    }
    if (!loopback && isPrivatePushHost(host)) {
        return "Private/loopback ntfy servers can't be reached by the desktop host; use a public " +
            "https URL."
    }
    return null
}

internal fun pushServerValid(server: String): Boolean = pushServerError(server) == null

private fun isPrivatePushHost(host: String): Boolean {
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size == 4) {
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            first == 127 ||
            (first == 169 && second == 254) ||
            first == 0
    }
    return host == "0.0.0.0" || host == "::" ||
        host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd")
}

/** Save is allowed when disabled; enabling requires a valid topic and a safe server URL. */
internal fun pushConfigValid(enabled: Boolean, server: String, topic: String): Boolean =
    !enabled || (pushTopicValid(topic) && pushServerValid(server))

// region push suppression sync

/** Coalesce bursts of quiet-hours/mute changes before one best-effort PUT. */
internal const val PUSH_SUPPRESS_DEBOUNCE_MS: Long = 500L
internal const val PUSH_SUPPRESS_MAX_MUTED_SESSIONS: Int = 500

/** Exact body for PUT /push/suppress; the time zone is always a valid IANA id. */
data class PushSuppressionPayload(
    val quietHoursEnabled: Boolean,
    val quietStartHour: Int,
    val quietEndHour: Int,
    val timeZone: String,
    val mutedSessionIds: List<String> = emptyList(),
)

/** Host suppression summary; old/unsupported hosts return [supported] = false. */
data class PushSuppressionState(
    val supported: Boolean,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    val timeZone: String = "",
    val timeZoneValid: Boolean = false,
    val mutedCount: Int = 0,
) {
    companion object {
        val Unsupported = PushSuppressionState(supported = false)
    }
}

/** Valid IANA zone id, falling back to UTC for blank/invalid input (never throws). */
internal fun sanitizedTimeZoneId(raw: String?): String {
    val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "UTC"
    return runCatching { ZoneId.of(candidate).id }.getOrElse { "UTC" }
}

/** Device IANA time zone (`java.time.ZoneId.systemDefault().id`), sanitized. */
internal fun deviceTimeZoneId(): String =
    runCatching { sanitizedTimeZoneId(ZoneId.systemDefault().id) }.getOrDefault("UTC")

/**
 * Builds the frozen suppression payload: hours coerced to 0..23, timezone validated, blank/
 * duplicate muted ids removed and bounded so one sync can never be enormous.
 */
internal fun buildPushSuppressionPayload(
    quietHoursEnabled: Boolean,
    quietStartHour: Int,
    quietEndHour: Int,
    timeZone: String?,
    mutedSessionIds: Collection<String>,
): PushSuppressionPayload = PushSuppressionPayload(
    quietHoursEnabled = quietHoursEnabled,
    quietStartHour = quietStartHour.coerceIn(0, 23),
    quietEndHour = quietEndHour.coerceIn(0, 23),
    timeZone = sanitizedTimeZoneId(timeZone ?: deviceTimeZoneId()),
    mutedSessionIds = mutedSessionIds
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .take(PUSH_SUPPRESS_MAX_MUTED_SESSIONS),
)

// endregion

// endregion

// region approval policy

internal const val APPROVAL_POLICY_ASK = "ask"
internal const val APPROVAL_POLICY_NEVER = "never"

/** Per-session approval policy; unsupported hosts return [supported] = false. */
data class ApprovalPolicy(
    val supported: Boolean,
    val live: Boolean = false,
    val policy: String = APPROVAL_POLICY_ASK,
) {
    companion object {
        val Unsupported = ApprovalPolicy(supported = false)
    }
}

internal fun approvalPolicyValid(policy: String): Boolean =
    policy == APPROVAL_POLICY_ASK || policy == APPROVAL_POLICY_NEVER

internal fun approvalPolicyLabel(policy: String): String = when (policy) {
    APPROVAL_POLICY_NEVER -> "Never"
    else -> "Ask"
}

// endregion

// region paired devices

/** One phone/desktop paired with the host; tokens/hashes are never part of this model. */
data class PairedDevice(
    val deviceId: String,
    val deviceName: String = "",
    val platform: String = "",
    val pairedAt: String? = null,
    val lastSeenAt: String? = null,
)

/** Device list for the host; unsupported hosts return [supported] = false. */
data class PairedDevices(
    val supported: Boolean,
    val devices: List<PairedDevice> = emptyList(),
    val currentDeviceId: String = "",
) {
    companion object {
        val Unsupported = PairedDevices(supported = false)
    }
}

/** Display name fallback: name, then platform, then a generic label. Never a token/hash. */
internal fun pairedDeviceName(device: PairedDevice): String =
    device.deviceName.trim().ifBlank {
        device.platform.trim().ifBlank { "Paired device" }
    }

internal fun pairedDeviceIsCurrent(devices: PairedDevices, device: PairedDevice): Boolean =
    devices.currentDeviceId.isNotBlank() && device.deviceId == devices.currentDeviceId

/** Revoke requests require a real device id (the current-device case is rejected by the host). */
internal fun revokeDeviceIdValid(deviceId: String): Boolean = deviceId.isNotBlank()

/**
 * Relative label for an ISO-8601 timestamp: "just now", "5m ago", "3h ago", "2d ago", then a date.
 * Null when the timestamp is blank or unparseable.
 */
internal fun relativeDeviceTime(iso: String?, now: Instant = Instant.now()): String? {
    val raw = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val then = runCatching { Instant.parse(raw) }.getOrNull() ?: return null
    val seconds = Duration.between(then, now).seconds.coerceAtLeast(0L)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 30L * 86_400 -> "${seconds / 86_400}d ago"
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(then)
    }
}

/** "paired 2d ago · last seen 5m ago" (missing pieces omitted; null when neither is usable). */
internal fun pairedDeviceMetadataLabel(
    device: PairedDevice,
    now: Instant = Instant.now(),
): String? {
    val parts = listOfNotNull(
        relativeDeviceTime(device.pairedAt, now)?.let { "paired $it" },
        relativeDeviceTime(device.lastSeenAt, now)?.let { "last seen $it" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** "2 paired devices" / "1 paired device" for the Settings summary. */
internal fun pairedDevicesSummary(devices: PairedDevices): String = when {
    !devices.supported -> "Not supported by this host"
    devices.devices.isEmpty() -> "No paired devices"
    devices.devices.size == 1 -> "1 paired device"
    else -> "${devices.devices.size} paired devices"
}

// endregion

// region model + thinking picker

internal const val MODEL_SOURCE_SESSION = "session"
internal const val MODEL_SOURCE_DEFAULT = "default"

data class ModelEffort(
    val id: String,
    val name: String = "",
    val description: String? = null,
)

data class ModelReasoning(
    val efforts: List<ModelEffort> = emptyList(),
    val defaultEffort: String? = null,
)

data class ModelInfo(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val reasoning: ModelReasoning? = null,
)

data class ModelGroup(
    val id: String,
    val name: String = "",
    val models: List<ModelInfo> = emptyList(),
)

data class ModelFailure(
    val id: String = "",
    val name: String = "",
    val message: String = "",
)

data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

/** Host model catalog; unsupported hosts return [supported] = false. */
data class ModelCatalog(
    val supported: Boolean,
    val default: ModelSelection? = null,
    val groups: List<ModelGroup> = emptyList(),
    val failures: List<ModelFailure> = emptyList(),
    val truncated: Boolean = false,
) {
    companion object {
        val Unsupported = ModelCatalog(supported = false)
    }
}

/** The session's effective model selection and where it came from. */
data class SessionModel(
    val supported: Boolean,
    val live: Boolean = false,
    val selection: ModelSelection? = null,
    val source: String = MODEL_SOURCE_DEFAULT,
) {
    companion object {
        val Unsupported = SessionModel(supported = false)
    }
}

internal fun modelSelectionValid(selection: ModelSelection?): Boolean =
    selection != null && selection.provider.isNotBlank() && selection.model.isNotBlank()

internal fun modelSelectionChanged(a: ModelSelection?, b: ModelSelection?): Boolean {
    if (a == null || b == null) return a != b
    return a.provider != b.provider ||
        a.model != b.model ||
        a.reasoningEffort.orEmpty() != b.reasoningEffort.orEmpty()
}

private fun findModel(catalog: ModelCatalog?, selection: ModelSelection?): ModelInfo? {
    val current = selection ?: return null
    return catalog?.groups
        ?.firstOrNull { it.id == current.provider }
        ?.models
        ?.firstOrNull { it.id == current.model }
}

/** Human model name (falls back to the raw model id). */
internal fun modelDisplayName(catalog: ModelCatalog?, selection: ModelSelection?): String {
    val model = findModel(catalog, selection)
    val name = model?.name?.trim().orEmpty()
    return name.ifBlank { selection?.model?.trim().orEmpty() }
}

/** Human reasoning-effort name (falls back to the raw effort id); null when not set. */
internal fun modelEffortDisplayName(catalog: ModelCatalog?, selection: ModelSelection?): String? {
    val effort = selection?.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val model = findModel(catalog, selection)
    val name = model?.reasoning?.efforts?.firstOrNull { it.id == effort }?.name?.trim().orEmpty()
    return name.ifBlank { effort }
}

/** Compact chat chip: "DeepSeek V4 · High" (model, then current thinking effort when set). */
internal fun modelChipLabel(catalog: ModelCatalog?, selection: ModelSelection?): String {
    if (selection == null) return "Model"
    val model = modelDisplayName(catalog, selection).ifBlank { "Model" }
    val effort = modelEffortDisplayName(catalog, selection)
    return if (effort == null) model else "$model · $effort"
}

internal fun sessionModelSourceLabel(source: String): String = when (source) {
    MODEL_SOURCE_SESSION -> "Session override"
    else -> "Host default"
}

/** Efforts advertised by the selected model (empty = thinking selection not applicable). */
internal fun modelEffortsFor(catalog: ModelCatalog?, selection: ModelSelection?): List<ModelEffort> =
    findModel(catalog, selection)?.reasoning?.efforts.orEmpty()

/** Advertised default effort for a model id, or null when the model has no thinking controls. */
internal fun modelDefaultEffortId(
    catalog: ModelCatalog?,
    provider: String,
    modelId: String,
): String? {
    val model = catalog?.groups
        ?.firstOrNull { it.id == provider }
        ?.models
        ?.firstOrNull { it.id == modelId } ?: return null
    val efforts = model.reasoning?.efforts.orEmpty()
    if (efforts.isEmpty()) return null
    val advertised = model.reasoning?.defaultEffort?.trim()?.takeIf { it.isNotEmpty() }
    return advertised?.takeIf { default -> efforts.any { it.id == default } }
        ?: efforts.first().id
}

/**
 * Never send an effort the selected model doesn't advertise (M6): an unknown/stale effort falls
 * back to the model's advertised default, and models without thinking controls send no effort.
 */
internal fun sanitizedModelEffort(catalog: ModelCatalog?, selection: ModelSelection?): String? {
    val effort = selection?.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val model = findModel(catalog, selection) ?: return null
    val efforts = model.reasoning?.efforts.orEmpty()
    if (efforts.isEmpty()) return null
    if (efforts.any { it.id == effort }) return effort
    return modelDefaultEffortId(catalog, selection.provider, selection.model)
}

/** Failure entry for a provider group, so the UI can annotate/disable it (M4). */
internal fun modelFailureForGroup(catalog: ModelCatalog?, group: ModelGroup): ModelFailure? =
    catalog?.failures?.firstOrNull { failure ->
        failure.id == group.id ||
            (failure.name.isNotBlank() && failure.name.equals(group.name, ignoreCase = true))
    }

// endregion

// region host queue

internal const val QUEUE_TARGET_NEXT_TURN = "next-turn"
internal const val QUEUE_TARGET_NEXT_STEP = "next-step"
internal const val QUEUE_ACTION_EDIT = "edit"
internal const val QUEUE_ACTION_REMOVE = "remove"
internal const val QUEUE_ACTION_STEER = "steer"
internal const val HOST_QUEUE_PREVIEW_CHARS = 120

/** The host queue keeps at most this many waiting prompts. */
internal const val HOST_QUEUE_MAX_ITEMS = 100

/** Whether the dialog should show the "queue is capped" note. */
internal fun hostQueueAtCap(queue: HostQueue): Boolean = queue.items.size >= HOST_QUEUE_MAX_ITEMS

/** One prompt waiting on the host (distinct from the local offline outbox). */
data class HostQueueItem(
    val itemId: String,
    val text: String = "",
    val target: String = QUEUE_TARGET_NEXT_TURN,
    val attachmentCount: Int = 0,
    val createdAt: String? = null,
)

/** Host queue snapshot; unsupported hosts return [supported] = false. */
data class HostQueue(
    val supported: Boolean,
    val live: Boolean = false,
    val items: List<HostQueueItem> = emptyList(),
) {
    companion object {
        val Unsupported = HostQueue(supported = false)
    }
}

internal fun hostQueueActionValid(action: String): Boolean =
    action == QUEUE_ACTION_EDIT || action == QUEUE_ACTION_REMOVE || action == QUEUE_ACTION_STEER

/** Edit needs non-blank text. */
internal fun hostQueueEditTextValid(text: String): Boolean = text.trim().isNotEmpty()

/**
 * Q4: the host edits replace the whole queued prompt with text (attachments would be lost), so
 * attachment prompts are not editable from the phone.
 */
internal fun hostQueueEditable(item: HostQueueItem): Boolean = item.attachmentCount <= 0

/**
 * Q8: steering only makes sense while the session is actually working (not merely because the
 * item targets the next turn).
 */
internal fun hostQueueCanSteer(item: HostQueueItem, sessionWorking: Boolean): Boolean =
    sessionWorking && item.target == QUEUE_TARGET_NEXT_TURN

internal fun hostQueueTargetLabel(target: String): String = when (target) {
    QUEUE_TARGET_NEXT_TURN -> "Next turn"
    QUEUE_TARGET_NEXT_STEP -> "Next step"
    else -> target.ifBlank { "Queued" }
}

/** Single-line preview with a hard character cap (chat strip/dialog readability). */
internal fun queueItemPreview(
    text: String,
    limit: Int = HOST_QUEUE_PREVIEW_CHARS,
): String {
    val collapsed = text.trim().replace(Regex("\\s+"), " ")
    if (collapsed.length <= limit) return collapsed
    return collapsed.take(limit).trimEnd() + "…"
}

internal fun queueItemAttachmentLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 attachment"
    else -> "$count attachments"
}

/**
 * Q10/Q14: dialog preview; attachment-only prompts show their attachment label instead of an
 * empty line.
 */
internal fun hostQueueItemPreview(
    item: HostQueueItem,
    limit: Int = HOST_QUEUE_PREVIEW_CHARS,
): String {
    val preview = queueItemPreview(item.text, limit)
    if (preview.isNotBlank()) return preview
    return queueItemAttachmentLabel(item.attachmentCount) ?: "(empty prompt)"
}

// endregion

// region fork conversation

/** Successful fork: the new session id, its source, and the message boundary used. */
data class ForkResult(
    val sessionId: String,
    val forkedFrom: String,
    val atSeq: Int? = null,
)

/** A message can only be a fork boundary when the host attached a positive seq. */
internal fun forkSeqValid(seq: Int?): Boolean = seq != null && seq > 0

/** Normalizes an optional fork boundary for the request (invalid/absent -> latest boundary). */
internal fun forkAtSeqOrNull(seq: Int?): Int? = seq?.takeIf { it > 0 }

/** Copy displayed in confirmations/status after a successful fork. */
internal fun forkResultStatus(result: ForkResult, listed: Boolean): String = if (listed) {
    "Forked into a new session."
} else {
    "Forked. New session ${result.sessionId} is not in the list yet; refresh to see it."
}

// endregion

/** Token counts as reported by DSH's tokenUsage projection and the Mobile API usage endpoint. */
data class TokenUsage(
    val uncachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val totalTokens: Long = 0,
) {
    val promptTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens
}

data class SessionUsage(
    val sessionId: String,
    val title: String,
    val updatedAt: String,
    val usage: TokenUsage,
)

/** One currency's remaining DeepSeek API balance (returned by /user/balance). */
data class BalanceInfo(
    val currency: String,
    val totalBalance: String,
    val grantedBalance: String,
    val toppedUpBalance: String,
)

data class DeepSeekBalance(
    val available: Boolean,
    val balances: List<BalanceInfo> = emptyList(),
)

/** Everything the Usage screen renders for the active host. */
data class HostUsage(
    val generatedAt: String = "",
    val totals: TokenUsage = TokenUsage(),
    val sessions: List<SessionUsage> = emptyList(),
    val balance: DeepSeekBalance? = null,
    val balanceError: String? = null,
)

/**
 * Attachment limits from docs/mobile-api-v1.md "Attachments (v0.2.2)":
 * base64 body <= 14 MB (~10 MB decoded), one attachment per request.
 */
object AttachmentLimits {
    /** Decoded payload budget enforced before upload: 10 MB. */
    const val MAX_DECODED_BYTES: Long = 64L * 1024L * 1024L

    /** Host's base64 request-body limit: 14 MB. */
    const val MAX_BASE64_BYTES: Long = 280L * 1024L * 1024L

    /** Host accepts at most 8 attachments in one prompt; keep the composer in step. */
    const val MAX_PENDING_ATTACHMENTS: Int = 8

    /** Read-back download budget: refuse to open historical attachments larger than 25 MB. */
    const val MAX_READ_BACK_BYTES: Long = 25L * 1024L * 1024L

    fun isWithinLimit(decodedBytes: Long): Boolean = decodedBytes in 1..MAX_DECODED_BYTES

    /** Upper bound on the standard (non-wrapped) base64 length for [decodedBytes]. */
    fun estimatedBase64Length(decodedBytes: Long): Long {
        if (decodedBytes <= 0L) return 0L
        return 4L * ((decodedBytes + 2L) / 3L)
    }

    fun isBase64WithinLimit(decodedBytes: Long): Boolean =
        estimatedBase64Length(decodedBytes) <= MAX_BASE64_BYTES

    /** False once the pending list reaches the host's 8-attachment cap. */
    fun canAddPendingAttachment(pendingCount: Int): Boolean =
        pendingCount in 0 until MAX_PENDING_ATTACHMENTS
}

/** Normalises attachment ids before they are sent in a prompt. */
object AttachmentIds {
    fun normalize(ids: List<String>): List<String> =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

// region goals

/** Current DSH goal for one session, as exposed by the mobile host bridge. */
data class SessionGoal(
    val id: String,
    val objective: String,
    val phase: String,
    val maxGoalRounds: Int,
    val roundsStarted: Int,
    val revision: Int,
    val activation: String,
    val blockedReason: String? = null,
) {
    val pausedOrStopped: Boolean
        get() = phase == "paused" || phase == "blocked" || activation == "disarmed"

    val roundsLabel: String
        get() = if (maxGoalRounds > 0) "$roundsStarted/$maxGoalRounds rounds" else "$roundsStarted rounds"
}

/** Goal support and the current goal for a session. */
data class SessionGoalState(
    val supported: Boolean,
    val live: Boolean,
    val goal: SessionGoal?,
) {
    companion object {
        val Unsupported = SessionGoalState(supported = false, live = false, goal = null)
        val Empty = SessionGoalState(supported = true, live = true, goal = null)
    }
}

/** One `goal.changed` SSE update from the host. */
data class GoalChanged(
    val sessionId: String,
    val operation: String,
    val goal: SessionGoal?,
)

// region schedules

/** Archived sessions snapshot; unsupported hosts return [supported] = false and no rows. */
data class ArchivedSessionsState(
    val supported: Boolean,
    val sessions: List<Session> = emptyList(),
) {
    companion object {
        val Unsupported = ArchivedSessionsState(supported = false)
    }
}

/** One host schedule for a session (read-back contract). */
data class SessionSchedule(
    val id: String,
    val sessionId: String,
    val sessionTitle: String?,
    val title: String,
    val instruction: String,
    val status: String,
    val kind: String,
    val summary: String,
    val nextRunAt: String?,
    val lastRunAt: String?,
)

/** Schedules snapshot; unsupported hosts return [supported] = false and no rows. */
data class ScheduleListState(
    val supported: Boolean,
    val schedules: List<SessionSchedule> = emptyList(),
) {
    companion object {
        val Unsupported = ScheduleListState(supported = false)
    }
}

/** Timing mode used by the schedule create form. */
enum class ScheduleTimingMode { AfterMinutes, Daily }

/** Timing input for POST /sessions/{id}/schedules. */
sealed interface ScheduleTimingInput {
    /** One-off run, N minutes from now. */
    data class AfterMinutes(val minutes: Long) : ScheduleTimingInput

    /** Daily run at a normalized 24h "HH:MM". */
    data class Daily(val time: String) : ScheduleTimingInput
}

/** Pure validation/formatting for the schedule create form (unit-tested on the JVM). */
object ScheduleTiming {
    /** One-off schedules may run up to a year after creation. */
    const val MAX_AFTER_MINUTES: Long = 525_600L

    /** Parses "N" minutes into a positive duration; null when invalid/out of range. */
    fun parseAfterMinutes(raw: String): Long? {
        val minutes = raw.trim().toLongOrNull() ?: return null
        return minutes.takeIf { it in 1..MAX_AFTER_MINUTES }
    }

    /** "HH:MM" 24h form; returns the normalized zero-padded time, or null when invalid. */
    fun parseDailyTime(raw: String): String? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").find(raw.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    /**
     * IANA zone sent with daily schedules. The host interprets `dailyTime` in this zone (it
     * defaults to UTC when the field is omitted), so "Daily at 09:00" stays local to the phone.
     */
    fun dailyTimeZoneId(): String = java.time.ZoneId.systemDefault().id

    /** Converted one-off duration for the request body; null when out of range. */
    fun afterSeconds(minutes: Long): Long? =
        if (minutes in 1..MAX_AFTER_MINUTES) minutes * 60L else null
}

/** Which timing controls the edit dialog can offer for a schedule kind. */
enum class ScheduleEditableTiming { AfterMinutes, Daily, Unsupported }

/** Mapping from host schedule kind to editable timing; unknown kinds are title/instruction only. */
fun editableScheduleTiming(kind: String): ScheduleEditableTiming =
    when (kind.trim().lowercase()) {
        "after" -> ScheduleEditableTiming.AfterMinutes
        "daily" -> ScheduleEditableTiming.Daily
        else -> ScheduleEditableTiming.Unsupported
    }

/** First non-blank draft line, capped, as the default title for "Schedule this prompt". */
fun scheduleTitleFromDraft(draft: String): String =
    draft.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.take(80)
        ?.ifBlank { "Scheduled prompt" }
        ?: "Scheduled prompt"

/**
 * Short, non-stacktrace description shown on the startup-failure screen. Keeps the exception type
 * and one sanitized message line (no multiline stack details) so it is safe to show and share;
 * the full report is written separately by CrashReporter.
 */
fun startupFailureSummary(throwable: Throwable?): String {
    val type = throwable?.javaClass?.simpleName?.takeIf { it.isNotBlank() } ?: "Unknown error"
    val message = throwable?.message
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(180)
        ?.takeIf { it.isNotBlank() }
    return if (message == null) type else "$type: $message"
}

// endregion

