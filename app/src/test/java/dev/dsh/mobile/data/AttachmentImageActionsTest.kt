package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the image viewer's Save to Photos / Copy image helpers, focused on the QA bug:
 * a `*.png` filename can hide JPEG bytes, so the output MIME/extension must come from the cached
 * bytes or a supported declared MIME, never the filename.
 */
class AttachmentImageActionsTest {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray()

    private val jpegHeader = bytes(0xFF, 0xD8, 0xFF, 0xDB, 0x00, 0x04)
    private val pngHeader = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val webpHeader = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")
    private val gifHeader = ascii("GIF89a")

    private fun heifHeader(brand: String): ByteArray =
        bytes(0, 0, 0, 0x20) + ascii("ftyp") + ascii(brand) + bytes(0, 0, 0, 0)

    @Test
    fun `png filename with declared jpeg and jpeg bytes saves as jpeg`() {
        // The live-tool trap: roadmap_0_6.png but the attached MIME/bytes are JPEG.
        val output = resolveAttachmentImageOutput(
            declaredMimeType = "image/jpeg",
            header = jpegHeader,
        )
        assertEquals("image/jpeg", output.mimeType)
        assertEquals("jpg", output.extension)
    }

    @Test
    fun `png filename with declared jpeg and png bytes saves as png`() {
        val output = resolveAttachmentImageOutput(
            declaredMimeType = "image/jpeg",
            header = pngHeader,
        )
        assertEquals("png bytes win over the declared MIME", "image/png", output.mimeType)
        assertEquals("png", output.extension)
    }

    @Test
    fun `declared supported mime is used when bytes are not identifiable`() {
        val output = resolveAttachmentImageOutput("image/jpeg", null)
        assertEquals("image/jpeg", output.mimeType)
        assertEquals("jpg", output.extension)
        assertEquals(
            "image/heic",
            resolveAttachmentImageOutput("image/heif", ByteArray(0)).mimeType,
        )
    }

    @Test
    fun `unknown and avif inputs resolve safely`() {
        // Unknown bytes + unknown/absent MIME: safe PNG fallback, never the filename.
        val unknownOutput = resolveAttachmentImageOutput("application/octet-stream", bytes(1, 2, 3))
        assertEquals("image/png", unknownOutput.mimeType)
        assertEquals("png", unknownOutput.extension)
        assertEquals(
            "image/png",
            resolveAttachmentImageOutput(null, null).mimeType,
        )
        assertEquals(
            "image/png",
            resolveAttachmentImageOutput("image/svg+xml", ascii("<svg>")).mimeType,
        )
        // AVIF is identified from its ftyp brand (honest MIME/extension, not a lying .png).
        assertEquals("avif", resolveAttachmentImageOutput("image/png", heifHeader("avif")).extension)
        assertEquals(
            "image/avif",
            resolveAttachmentImageOutput("image/png", heifHeader("avif")).mimeType,
        )
    }

    @Test
    fun `magic bytes cover png jpeg webp gif and heic`() {
        assertEquals("image/png", attachmentImageMimeFromBytes(pngHeader))
        assertEquals("image/jpeg", attachmentImageMimeFromBytes(jpegHeader))
        assertEquals("image/webp", attachmentImageMimeFromBytes(webpHeader))
        assertEquals("image/gif", attachmentImageMimeFromBytes(gifHeader))
        assertEquals("image/heic", attachmentImageMimeFromBytes(heifHeader("heic")))
        assertEquals("image/heic", attachmentImageMimeFromBytes(heifHeader("mif1")))
        assertEquals(null, attachmentImageMimeFromBytes(bytes(0, 0, 0)))
        assertEquals(null, attachmentImageMimeFromBytes(null))
    }

    @Test
    fun `extension derives from the resolved mime only`() {
        assertEquals("jpg", attachmentImageExtensionForMime("image/jpeg"))
        assertEquals("png", attachmentImageExtensionForMime("image/png"))
        assertEquals("webp", attachmentImageExtensionForMime("image/webp"))
        assertEquals("heic", attachmentImageExtensionForMime("image/heic"))
        assertEquals("png", attachmentImageExtensionForMime("image/svg+xml"))
        assertEquals(null, attachmentImageMimeFromDeclared("image/avif-not-real"))
        assertEquals("image/jpeg", attachmentImageMimeFromDeclared("image/jpg"))
        assertEquals("image/heic", attachmentImageMimeFromDeclared("image/heif"))
    }

    @Test
    fun `declared mime that is not a supported image type falls back`() {
        assertEquals(null, attachmentImageMimeFromDeclared("application/octet-stream"))
        assertEquals(null, attachmentImageMimeFromDeclared("video/mp4"))
        assertEquals(null, attachmentImageMimeFromDeclared(null))
    }

    @Test
    fun `display names are timestamped unique candidates`() {
        assertEquals(
            "dsh-mobile-1234.png",
            attachmentSavedImageDisplayName(1234L, "png"),
        )
        assertEquals(
            "dsh-mobile-1234-1.png",
            attachmentSavedImageDisplayName(1234L, "png", attempt = 1),
        )
        assertEquals(
            "dsh-mobile-1234-2.jpg",
            attachmentSavedImageDisplayName(1234L, "jpg", attempt = 2),
        )
        assertNotEquals(
            attachmentSavedImageDisplayName(1234L, "png"),
            attachmentSavedImageDisplayName(1234L, "png", attempt = 1),
        )
        assertEquals(
            "dsh-mobile-9.jpg",
            attachmentSavedImageDisplayName(9L, ".JPEG"),
        )
        assertEquals(
            "dsh-mobile-9.png",
            attachmentSavedImageDisplayName(9L, "  "),
        )
        assertEquals("dsh-mobile-9.png", attachmentSavedImageDisplayName(9L, "png", attempt = -1))
    }

    @Test
    fun `media store path and API branch match the requirement`() {
        assertEquals("Pictures/DSH Mobile", ATTACHMENT_IMAGE_RELATIVE_PATH_SAVED)
        assertFalse(attachmentSaveUsesMediaStore(26))
        assertFalse(attachmentSaveUsesMediaStore(28))
        assertTrue(attachmentSaveUsesMediaStore(29))
        assertTrue(attachmentSaveUsesMediaStore(35))
        assertEquals("Save image", attachmentSaveActionLabel(28))
        assertEquals("Save to Photos", attachmentSaveActionLabel(29))
    }

    @Test
    fun `actions are disabled while another image action is busy`() {
        assertTrue(attachmentViewerActionEnabled(busy = false))
        assertFalse(attachmentViewerActionEnabled(busy = true))
    }

    @Test
    fun `status copy distinguishes success failure and missing file`() {
        assertEquals("Image copied", ATTACHMENT_COPY_SUCCESS_COPY)
        assertEquals("Couldn't copy this image.", ATTACHMENT_COPY_FAILURE_COPY)
        assertEquals("Saved to Pictures/DSH Mobile", ATTACHMENT_SAVE_SUCCESS_COPY)
        assertEquals("Couldn't save this image.", ATTACHMENT_SAVE_FAILURE_COPY)
        assertEquals("Image saved", ATTACHMENT_SAF_SAVE_SUCCESS_COPY)
        assertEquals("Save cancelled", ATTACHMENT_SAVE_CANCELLED_COPY)
        assertTrue(ATTACHMENT_MISSING_FILE_COPY.contains("cached file is missing"))
        assertNotEquals(ATTACHMENT_SAVE_SUCCESS_COPY, ATTACHMENT_SAVE_FAILURE_COPY)
        assertEquals("Save", ATTACHMENT_SAVE_ACTION_LABEL)
        assertEquals("Copy", ATTACHMENT_COPY_ACTION_LABEL)
    }
}
