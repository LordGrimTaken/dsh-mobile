package dev.dsh.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.dsh.mobile.data.ATTACHMENT_COPY_FAILURE_COPY
import dev.dsh.mobile.data.ATTACHMENT_COPY_SUCCESS_COPY
import dev.dsh.mobile.data.ATTACHMENT_IMAGE_RELATIVE_PATH_SAVED
import dev.dsh.mobile.data.ATTACHMENT_MISSING_FILE_COPY
import dev.dsh.mobile.data.ATTACHMENT_SAF_SAVE_SUCCESS_COPY
import dev.dsh.mobile.data.ATTACHMENT_SAVE_CANCELLED_COPY
import dev.dsh.mobile.data.ATTACHMENT_SAVE_FAILURE_COPY
import dev.dsh.mobile.data.ATTACHMENT_SAVE_SUCCESS_COPY
import dev.dsh.mobile.data.ATTACHMENT_VIEWER_MAX_BYTES
import dev.dsh.mobile.data.ATTACHMENT_VIEWER_MAX_EDGE_PX
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.MessageAttachment
import dev.dsh.mobile.data.attachmentSaveActionLabel
import dev.dsh.mobile.data.attachmentSaveUsesMediaStore
import dev.dsh.mobile.data.attachmentSavedImageDisplayName
import dev.dsh.mobile.data.attachmentViewerActionEnabled
import dev.dsh.mobile.data.attachmentViewerMetaLabel
import dev.dsh.mobile.data.resolveAttachmentImageOutput
import dev.dsh.mobile.data.attachmentThumbnailSampleSize
import dev.dsh.mobile.data.attachmentViewerDoubleTapScale
import dev.dsh.mobile.data.attachmentViewerIndexOf
import dev.dsh.mobile.data.attachmentViewerMaxPan
import dev.dsh.mobile.data.attachmentViewerFittedContent
import dev.dsh.mobile.data.attachmentViewerNeighbourIndex
import dev.dsh.mobile.data.clampAttachmentViewerPan
import dev.dsh.mobile.data.clampAttachmentViewerScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen in-app image viewer for historical message attachments: dark backdrop, pinch/pan with
 * clamped bounds, double-tap 1x/2.5x toggle, previous/next paging for multiple images in one
 * message, share through the FileProvider, and an off-main cached download/decode with retry.
 * The external "Open" path on the attachment chip is unchanged.
 */
@Composable
fun AttachmentImageViewer(
    state: AppState,
    sessionId: String,
    images: List<MessageAttachment>,
    initialAttachmentId: String,
    onClose: () -> Unit,
) {
    if (images.isEmpty()) return
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var index by remember(images, initialAttachmentId) {
        val found = attachmentViewerIndexOf(images, initialAttachmentId)
        mutableIntStateOf(if (found < 0) 0 else found)
    }
    val current = images.getOrNull(index) ?: return
    var bitmap by remember(current.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(current.id) { mutableStateOf(true) }
    var error by remember(current.id) { mutableStateOf<String?>(null) }
    var reloadToken by remember(current.id) { mutableIntStateOf(0) }
    var scale by remember(current.id) { mutableStateOf(1f) }
    var offset by remember(current.id) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var sharing by remember(current.id) { mutableStateOf(false) }
    var saving by remember(current.id) { mutableStateOf(false) }
    var copying by remember(current.id) { mutableStateOf(false) }
    // Pre-Q SAF: the chosen document URI comes back after the picker; the file waits here.
    var pendingSafFile by remember { mutableStateOf<File?>(null) }

    val loadCachedFile: suspend () -> File? = {
        runCatching {
            withContext(Dispatchers.IO) {
                state.historicalAttachmentFile(sessionId, current)
            }
        }.getOrNull()?.takeIf { file -> file.isFile && file.length() > 0L }
    }

    val safSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { uri ->
        val source = pendingSafFile
        pendingSafFile = null
        if (uri == null || source == null) {
            saving = false
            Toast.makeText(context, ATTACHMENT_SAVE_CANCELLED_COPY, Toast.LENGTH_SHORT).show()
        } else {
            scope.launch {
                val saved = runCatching {
                    withContext(Dispatchers.IO) { copyFileToSafUri(context, source, uri) }
                }.isSuccess
                saving = false
                Toast.makeText(
                    context,
                    if (saved) ATTACHMENT_SAF_SAVE_SUCCESS_COPY else ATTACHMENT_SAVE_FAILURE_COPY,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val saveCurrent: () -> Unit = {
        if (attachmentViewerActionEnabled(saving || copying || sharing)) {
            saving = true
            scope.launch {
                val file = loadCachedFile()
                if (file == null) {
                    saving = false
                    Toast.makeText(context, ATTACHMENT_MISSING_FILE_COPY, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Sniff the cached bytes and trust them/declared MIME over the attachment filename
                // (live tool attachments can be JPEG bytes named *.png).
                val output = withContext(Dispatchers.IO) {
                    resolveAttachmentImageOutput(
                        declaredMimeType = current.mimeType,
                        header = readImageHeader(file),
                    )
                }
                if (attachmentSaveUsesMediaStore(Build.VERSION.SDK_INT)) {
                    val saved = runCatching {
                        withContext(Dispatchers.IO) {
                            saveImageToMediaStore(
                                context = context,
                                source = file,
                                mimeType = output.mimeType,
                                extension = output.extension,
                            )
                        }
                    }.getOrDefault(false)
                    saving = false
                    Toast.makeText(
                        context,
                        if (saved) ATTACHMENT_SAVE_SUCCESS_COPY else ATTACHMENT_SAVE_FAILURE_COPY,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    pendingSafFile = file
                    val suggested = attachmentSavedImageDisplayName(
                        System.currentTimeMillis(),
                        output.extension,
                    )
                    runCatching { safSaveLauncher.launch(suggested) }.onFailure {
                        pendingSafFile = null
                        saving = false
                        Toast.makeText(
                            context,
                            ATTACHMENT_SAVE_FAILURE_COPY,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    val copyCurrent: () -> Unit = {
        if (attachmentViewerActionEnabled(saving || copying || sharing)) {
            copying = true
            scope.launch {
                val file = loadCachedFile()
                if (file == null) {
                    copying = false
                    Toast.makeText(context, ATTACHMENT_MISSING_FILE_COPY, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val copied = runCatching {
                    withContext(Dispatchers.IO) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.update_provider",
                            file,
                        )
                        // newUri populates the ClipData and lets the paste target read the URI.
                        val clip = ClipData.newUri(
                            context.contentResolver,
                            current.name.ifBlank { "DSH image" },
                            uri,
                        )
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                            ?: error("clipboard unavailable")
                        clipboard.setPrimaryClip(clip)
                    }
                }.isSuccess
                copying = false
                Toast.makeText(
                    context,
                    if (copied) ATTACHMENT_COPY_SUCCESS_COPY else ATTACHMENT_COPY_FAILURE_COPY,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // Load/reuse the cached attachment file off the main thread; bounds-first downsample decode.
    LaunchedEffect(current.id, reloadToken) {
        loading = true
        error = null
        bitmap = null
        val decoded = runCatching {
            withContext(Dispatchers.IO) {
                val file = state.historicalAttachmentFile(sessionId, current)
                decodeViewerBitmap(file)
            }
        }.getOrNull()
        bitmap = decoded
        if (decoded == null) error = "Couldn't load this image."
        loading = false
    }
    // Attachment changes always start fitted.
    LaunchedEffect(current.id) {
        scale = 1f
        offset = Offset.Zero
    }

    val shareCurrent: () -> Unit = {
        if (!sharing) {
            sharing = true
            scope.launch {
                val file = runCatching {
                    withContext(Dispatchers.IO) {
                        state.historicalAttachmentFile(sessionId, current)
                    }
                }.getOrNull()
                if (file == null || !file.isFile || file.length() == 0L) {
                    error = "The cached file is missing. Retry to download it again."
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.update_provider",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = current.mimeType.ifBlank { "image/*" }
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Share image")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { error = "Couldn't share this image." }
                }
                sharing = false
            }
        }
    }

    // Actual fitted content size (letterboxed), used for correct pan limits on non-square images.
    val fittedContent = remember(bitmap, viewport) {
        val image = bitmap
        if (image == null) {
            viewport.width.toFloat() to viewport.height.toFloat()
        } else {
            attachmentViewerFittedContent(
                viewport.width.toFloat(),
                viewport.height.toFloat(),
                image.width.toFloat(),
                image.height.toFloat(),
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .pointerInput(current.id, fittedContent) {
                    detectTapGestures(
                        onDoubleTap = {
                            val target = attachmentViewerDoubleTapScale(scale)
                            scale = target
                            offset = if (target <= 1f) {
                                Offset.Zero
                            } else {
                                val maxX = attachmentViewerMaxPan(
                                    viewport.width.toFloat(),
                                    fittedContent.first,
                                    target,
                                )
                                val maxY = attachmentViewerMaxPan(
                                    viewport.height.toFloat(),
                                    fittedContent.second,
                                    target,
                                )
                                Offset(
                                    clampAttachmentViewerPan(offset.x, maxX),
                                    clampAttachmentViewerPan(offset.y, maxY),
                                )
                            }
                        },
                    )
                }
                .pointerInput(current.id, fittedContent) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = clampAttachmentViewerScale(scale * zoom)
                        scale = newScale
                        offset = if (newScale <= 1f) {
                            Offset.Zero
                        } else {
                            val maxX = attachmentViewerMaxPan(
                                viewport.width.toFloat(),
                                fittedContent.first,
                                newScale,
                            )
                            val maxY = attachmentViewerMaxPan(
                                viewport.height.toFloat(),
                                fittedContent.second,
                                newScale,
                            )
                            Offset(
                                clampAttachmentViewerPan(offset.x + pan.x, maxX),
                                clampAttachmentViewerPan(offset.y + pan.y, maxY),
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(color = Color.White)

                error != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Retry",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                error = null
                                reloadToken += 1
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }

                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = current.name.ifBlank { "Image attachment" },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }

        // Top bar: close, name/size, counter, share.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close image viewer", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    current.name.ifBlank { "Image attachment" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    attachmentViewerMetaLabel(
                        isImage = current.isImage,
                        bytes = current.bytes,
                        index = index,
                        count = images.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(4.dp))
            if (saving || copying || sharing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(
                    "Save",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = saveCurrent)
                        .semantics {
                            contentDescription = attachmentSaveActionLabel(Build.VERSION.SDK_INT)
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Text(
                    "Copy",
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = copyCurrent)
                        .semantics { contentDescription = "Copy image" }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                IconButton(onClick = shareCurrent) {
                    Icon(Icons.Filled.Share, contentDescription = "Share image", tint = Color.White)
                }
            }
        }

        // Previous / next paging for messages with multiple images.
        if (images.size > 1) {
            if (index > 0) {
                IconButton(
                    onClick = {
                        index = attachmentViewerNeighbourIndex(index, -1, images.size)
                    },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        Icons.Filled.ChevronLeft,
                        contentDescription = "Previous image",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            if (index < images.size - 1) {
                IconButton(
                    onClick = {
                        index = attachmentViewerNeighbourIndex(index, 1, images.size)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "Next image",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }

        Text(
            "Pinch to zoom · double-tap to fit",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

/**
 * Bounds-first downsample for the full-screen viewer: longest edge <= [ATTACHMENT_VIEWER_MAX_EDGE_PX]
 * and decoded size capped at [ATTACHMENT_VIEWER_MAX_BYTES] so a large photo can never OOM.
 */
private fun decodeViewerBitmap(file: File): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = attachmentThumbnailSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
        maxEdgePx = ATTACHMENT_VIEWER_MAX_EDGE_PX,
    )
    while (true) {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        if (bitmap.allocationByteCount <= ATTACHMENT_VIEWER_MAX_BYTES || sample >= 32) {
            return bitmap
        }
        bitmap.recycle()
        sample *= 2
    }
}

/**
 * API 29+ MediaStore export: insert a pending row in Pictures/DSH Mobile with a unique display
 * name, stream the cached bytes, then clear IS_PENDING. Deletes the row on any failure so no
 * orphan/pending entry is left behind. Returns false (never throws) for the caller's Toast.
 */
private fun saveImageToMediaStore(
    context: Context,
    source: File,
    mimeType: String,
    extension: String,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val timestamp = System.currentTimeMillis()
    var displayName = attachmentSavedImageDisplayName(timestamp, extension)
    var attempt = 0
    while (attempt < 20 && mediaStoreDisplayNameExists(context, collection, displayName)) {
        attempt += 1
        displayName = attachmentSavedImageDisplayName(timestamp, extension, attempt)
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, ATTACHMENT_IMAGE_RELATIVE_PATH_SAVED)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("MediaStore returned no output stream")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (error: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

private fun mediaStoreDisplayNameExists(
    context: Context,
    collection: Uri,
    displayName: String,
): Boolean = runCatching {
    context.contentResolver.query(
        collection,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
        arrayOf(displayName),
        null,
    )?.use { cursor -> cursor.moveToFirst() } ?: false
}.getOrDefault(false)

/** Pre-Q SAF export: copy the cached bytes into the user-chosen document URI. */
private fun copyFileToSafUri(context: Context, source: File, target: Uri) {
    context.contentResolver.openOutputStream(target)?.use { output ->
        source.inputStream().use { input -> input.copyTo(output) }
    } ?: error("SAF returned no output stream")
}

/** First 32 bytes of the cached file for magic-byte sniffing; null when unreadable/empty. */
private fun readImageHeader(source: File): ByteArray? = runCatching {
    source.inputStream().use { input ->
        val buffer = ByteArray(32)
        val read = input.read(buffer)
        if (read <= 0) null else buffer.copyOf(read)
    }
}.getOrNull()
