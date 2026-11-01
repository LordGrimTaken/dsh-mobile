package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic workspace-image polish: unknown (0/negative) bytes must not render as a misleading
 * "0 B", while known sizes, names, chips, viewer paging and non-ASCII names keep working.
 */
class AttachmentWorkspaceImageTest {

    private fun image(
        id: String = "ws_1",
        name: String = "screenshot.png",
        bytes: Long = 0L,
        mimeType: String = "image/png",
    ) = MessageAttachment(
        id = id,
        kind = "image",
        name = name,
        mimeType = mimeType,
        bytes = bytes,
    )

    @Test
    fun `unknown sizes have no label but known sizes keep today's formatting`() {
        assertNull(attachmentSizeLabel(0L))
        assertNull(attachmentSizeLabel(-1L))
        assertNull(attachmentSizeLabel(Long.MIN_VALUE))
        assertEquals("1 B", attachmentSizeLabel(1L))
        assertEquals("1023 B", attachmentSizeLabel(1023L))
        assertEquals("1 KB", attachmentSizeLabel(1024L))
        assertEquals("2 KB", attachmentSizeLabel(2048L))
        assertEquals("1.0 MB", attachmentSizeLabel(1024L * 1024L))
        assertEquals("1.5 MB", attachmentSizeLabel((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `chip meta hides the size only when unknown`() {
        assertEquals("IMAGE", attachmentMetaLabel(isImage = true, bytes = 0L))
        assertEquals("IMAGE", attachmentMetaLabel(isImage = true, bytes = -5L))
        assertEquals("IMAGE · 2 KB", attachmentMetaLabel(isImage = true, bytes = 2048L))
        assertEquals("FILE", attachmentMetaLabel(isImage = false, bytes = 0L))
        assertEquals("FILE · 1.0 MB", attachmentMetaLabel(isImage = false, bytes = 1024L * 1024L))
        assertTrue(attachmentMetaLabel(isImage = true, bytes = 0L).startsWith("IMAGE"))
    }

    @Test
    fun `display name falls back safely and preserves non-ASCII names`() {
        assertEquals("screenshot.png", attachmentDisplayName("screenshot.png", isImage = true))
        assertEquals("截图 1.png", attachmentDisplayName("截图 1.png", isImage = true))
        assertEquals("Image", attachmentDisplayName("", isImage = true))
        assertEquals("Image", attachmentDisplayName("   ", isImage = true))
        assertEquals("Attachment", attachmentDisplayName("", isImage = false))
    }

    @Test
    fun `synthetic workspace images are included in the viewer paging list`() {
        val attachments = listOf(
            image(id = "ws_1", bytes = 0L),
            image(id = "ws_2", name = "截图 2.png", bytes = 0L),
            MessageAttachment(id = "ws_3", kind = "file", name = "readme.txt"),
        )
        val images = imageAttachmentsForViewer(attachments)
        assertEquals(listOf("ws_1", "ws_2"), images.map { it.id })
        assertEquals(0, attachmentViewerIndexOf(images, "ws_1"))
        assertEquals(1, attachmentViewerIndexOf(images, "ws_2"))
        assertEquals(-1, attachmentViewerIndexOf(images, "ws_3"))
        assertEquals(1, attachmentViewerNeighbourIndex(0, 1, images.size))
        assertEquals(0, attachmentViewerNeighbourIndex(1, -1, images.size))
        assertEquals(1, attachmentViewerNeighbourIndex(0, 1, images.size))
    }

    @Test
    fun `full-screen viewer hides unknown size but keeps paging`() {
        // QA gap: the viewer must use the same unknown-size-aware label as the chips.
        val synthetic = attachmentViewerMetaLabel(
            isImage = true,
            bytes = 0L,
            index = 0,
            count = 2,
        )
        assertEquals("IMAGE · 1/2", synthetic)
        assertFalse(synthetic.contains("0 B"))
        assertEquals(
            "IMAGE · 2/2",
            attachmentViewerMetaLabel(isImage = true, bytes = -1L, index = 1, count = 2),
        )
        assertEquals(
            "IMAGE · 2 KB · 1/2",
            attachmentViewerMetaLabel(isImage = true, bytes = 2048L, index = 0, count = 2),
        )
        assertEquals(
            "IMAGE · 1.0 MB",
            attachmentViewerMetaLabel(isImage = true, bytes = 1024L * 1024L, index = 0, count = 1),
        )
        assertEquals(
            "FILE",
            attachmentViewerMetaLabel(isImage = false, bytes = 0L, index = 0, count = 1),
        )
    }

    @Test
    fun `zero byte images still carry their image flags for chips and viewer`() {
        val synthetic = image()
        assertTrue(synthetic.isImage)
        assertEquals("image/png", synthetic.mimeType)
        assertNull(attachmentSizeLabel(synthetic.bytes))
    }
}
