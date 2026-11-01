package dev.dsh.mobile.data

/** Bytes read from an external ACTION_SEND image; ready to upload through the normal composer. */
data class SharedAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)
