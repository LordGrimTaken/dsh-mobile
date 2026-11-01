package dev.dsh.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.ATTACHMENT_THUMBNAIL_CACHE_BYTES
import dev.dsh.mobile.data.ATTACHMENT_THUMBNAIL_CACHE_ENTRIES
import dev.dsh.mobile.data.ATTACHMENT_THUMBNAIL_MAX_BYTES
import dev.dsh.mobile.data.ATTACHMENT_THUMBNAIL_MAX_EDGE_PX
import dev.dsh.mobile.data.ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.MessageAttachment
import dev.dsh.mobile.data.attachmentDisplayName
import dev.dsh.mobile.data.attachmentMetaLabel
import dev.dsh.mobile.data.attachmentThumbnailSampleSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Attachment chips for attachments read back with a historical message. Image chips load a small
 * downsampled thumbnail asynchronously; tapping an image chip opens the in-app full-screen viewer,
 * while its explicit "Open" affordance still launches the cached file externally. File attachments
 * (and loading/error image states) stay icon chips and open externally.
 */
@Composable
fun MessageAttachmentsColumn(
    state: AppState,
    sessionId: String,
    attachments: List<MessageAttachment>,
    isOpening: (String) -> Boolean,
    onOpen: (MessageAttachment) -> Unit,
    onOpenImage: (List<MessageAttachment>, MessageAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            HistoricalAttachmentChip(
                state = state,
                sessionId = sessionId,
                attachment = attachment,
                opening = isOpening(attachment.id),
                onOpen = { onOpen(attachment) },
                onOpenImage = { onOpenImage(attachments, attachment) },
            )
        }
    }
}

@Composable
private fun HistoricalAttachmentChip(
    state: AppState,
    sessionId: String,
    attachment: MessageAttachment,
    opening: Boolean,
    onOpen: () -> Unit,
    onOpenImage: () -> Unit,
) {
    val thumbnail = rememberHistoricalAttachmentThumbnail(state, sessionId, attachment)
    val opensViewer = attachment.kind.equals("image", ignoreCase = true)
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = !opening) {
                if (opensViewer) onOpenImage() else onOpen()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "Image attachment thumbnail",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Icon(
                if (attachment.isImage) Icons.Filled.Image else Icons.Filled.AttachFile,
                contentDescription = if (attachment.isImage) "Image attachment" else "File attachment",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                attachmentDisplayName(attachment.name, attachment.isImage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                attachmentMetaLabel(attachment.isImage, attachment.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (opening) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Open",
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable {
                        // Explicit external open; the rest of the chip opens the in-app viewer.
                        onOpen()
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Loads a cached thumbnail for a historical image attachment. The composable never blocks: the
 * decode runs on [Dispatchers.IO] and the icon chip is shown until it completes (and on failure).
 */
@Composable
private fun rememberHistoricalAttachmentThumbnail(
    state: AppState,
    sessionId: String,
    attachment: MessageAttachment,
): Bitmap? {
    var thumbnail by remember(attachment.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(attachment.id, state.isLive) {
        if (!attachment.isImage || !state.isLive) return@LaunchedEffect
        // Skip sources that are too large to decode cheaply; the icon chip stays.
        if (attachment.bytes > ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES) return@LaunchedEffect
        thumbnail = AttachmentThumbnailLoader.load(state, sessionId, attachment)
    }
    return thumbnail
}

/** Bounded in-memory LRU (<= 24 entries / 16 MB) keyed by attachment id. */
private class ThumbnailLruCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
) {
    private val entries = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        entries.remove(key)?.let { totalBytes -= it.allocationByteCount }
        entries[key] = bitmap
        totalBytes += bitmap.allocationByteCount
        val iterator = entries.entries.iterator()
        while ((entries.size > maxEntries || totalBytes > maxBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            totalBytes -= eldest.value.allocationByteCount
        }
    }
}

/**
 * Shared thumbnail loader: LRU hit first, then a single in-flight download+decode per attachment
 * (duplicate requests await the same result instead of decoding twice).
 */
private object AttachmentThumbnailLoader {
    private val cache = ThumbnailLruCache(
        maxEntries = ATTACHMENT_THUMBNAIL_CACHE_ENTRIES,
        maxBytes = ATTACHMENT_THUMBNAIL_CACHE_BYTES.toLong(),
    )
    private val inFlight = mutableMapOf<String, CompletableDeferred<Bitmap?>>()

    suspend fun load(
        state: AppState,
        sessionId: String,
        attachment: MessageAttachment,
    ): Bitmap? {
        cache.get(attachment.id)?.let { return it }
        val deferred = synchronized(inFlight) {
            inFlight[attachment.id]
                ?: CompletableDeferred<Bitmap?>().also { inFlight[attachment.id] = it }
        }
        if (!deferred.isCompleted) {
            val bitmap = runCatching {
                withContext(Dispatchers.IO) {
                    val file = state.historicalAttachmentFile(sessionId, attachment)
                    decodeAttachmentThumbnail(file)
                }
            }.getOrNull()
            if (bitmap != null) cache.put(attachment.id, bitmap)
            deferred.complete(bitmap)
        }
        return try {
            deferred.await()
        } finally {
            synchronized(inFlight) {
                if (deferred.isCompleted) inFlight.remove(attachment.id)
            }
        }
    }
}

/** Decodes a downsampled thumbnail: bounds first, <=512 px longest edge, <=256 KB target. */
private fun decodeAttachmentThumbnail(file: File): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    if (file.length() > ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = attachmentThumbnailSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
        maxEdgePx = ATTACHMENT_THUMBNAIL_MAX_EDGE_PX,
    )
    while (true) {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        if (bitmap.allocationByteCount <= ATTACHMENT_THUMBNAIL_MAX_BYTES || sample >= 32) {
            return bitmap
        }
        bitmap.recycle()
        sample *= 2
    }
}
