package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the full-screen attachment viewer's pure zoom/pan and pager helpers. */
class AttachmentViewerMathTest {

    private fun image(id: String, kind: String = "image") = MessageAttachment(
        id = id,
        kind = kind,
        name = "$id.png",
        mimeType = "image/png",
        bytes = 1_024,
    )

    @Test
    fun `scale is clamped to 1x through 6x`() {
        assertEquals(1f, clampAttachmentViewerScale(0.4f))
        assertEquals(1f, clampAttachmentViewerScale(1f))
        assertEquals(3f, clampAttachmentViewerScale(3f))
        assertEquals(6f, clampAttachmentViewerScale(42f))
        assertEquals(6f, ATTACHMENT_VIEWER_MAX_SCALE)
    }

    @Test
    fun `double tap toggles between fit and 2_5x`() {
        assertEquals(2.5f, attachmentViewerDoubleTapScale(1f))
        assertEquals(2.5f, attachmentViewerDoubleTapScale(1f))
        assertEquals(1f, attachmentViewerDoubleTapScale(2.5f))
        assertEquals(1f, attachmentViewerDoubleTapScale(6f))
        assertEquals(ATTACHMENT_VIEWER_DOUBLE_TAP_SCALE, attachmentViewerDoubleTapScale(0.9f))
    }

    @Test
    fun `pan bounds grow with zoom and never exceed the expected half overflow`() {
        assertEquals(0f, attachmentViewerMaxPan(viewportPx = 1_000f, contentPx = 1_000f, scale = 1f))
        assertEquals(500f, attachmentViewerMaxPan(viewportPx = 1_000f, contentPx = 1_000f, scale = 2f))
        assertEquals(2_500f, attachmentViewerMaxPan(viewportPx = 1_000f, contentPx = 1_000f, scale = 6f))
        assertEquals(
            0f,
            attachmentViewerMaxPan(viewportPx = 1_000f, contentPx = 400f, scale = 2f),
        )
        assertEquals(0f, attachmentViewerMaxPan(viewportPx = 0f, contentPx = 1_000f, scale = 2f))
    }

    @Test
    fun `pan offsets are clamped into the bounds`() {
        assertEquals(500f, clampAttachmentViewerPan(9_999f, 500f))
        assertEquals(-500f, clampAttachmentViewerPan(-9_999f, 500f))
        assertEquals(120f, clampAttachmentViewerPan(120f, 500f))
        assertEquals(0f, clampAttachmentViewerPan(10f, 0f))
    }

    @Test
    fun `viewer image selection keeps only kind image rows with ids in order`() {
        val attachments = listOf(
            image("a", kind = "image"),
            image("b", kind = "file"),
            image("", kind = "image"),
            image("c", kind = "IMAGE"),
        )
        assertEquals(listOf("a", "c"), imageAttachmentsForViewer(attachments).map { it.id })
        assertTrue(imageAttachmentsForViewer(emptyList()).isEmpty())
    }

    @Test
    fun `viewer index and pager neighbours are safe at the bounds`() {
        val images = listOf(image("a"), image("b"), image("c"))
        assertEquals(1, attachmentViewerIndexOf(images, "b"))
        assertEquals(-1, attachmentViewerIndexOf(images, "missing"))
        assertEquals(1, attachmentViewerNeighbourIndex(current = 0, delta = 1, size = 3))
        assertEquals(2, attachmentViewerNeighbourIndex(current = 1, delta = 1, size = 3))
        assertEquals(2, attachmentViewerNeighbourIndex(current = 2, delta = 1, size = 3))
        assertEquals(0, attachmentViewerNeighbourIndex(current = 0, delta = -1, size = 3))
        assertEquals(1, attachmentViewerNeighbourIndex(current = 2, delta = -1, size = 3))
        assertEquals(0, attachmentViewerNeighbourIndex(current = 0, delta = 1, size = 0))
    }

    @Test
    fun `fitted content keeps aspect ratio for letterboxed images`() {
        // A portrait image in a landscape viewport: fit by height, not width.
        val (portraitW, portraitH) = attachmentViewerFittedContent(1_000f, 1_000f, 500f, 2_000f)
        assertEquals(250f, portraitW)
        assertEquals(1_000f, portraitH)

        val (landscapeW, landscapeH) = attachmentViewerFittedContent(1_000f, 1_000f, 2_000f, 500f)
        assertEquals(1_000f, landscapeW)
        assertEquals(250f, landscapeH)

        // Degenerate inputs fall back to the viewport.
        assertEquals(1_000f to 500f, attachmentViewerFittedContent(1_000f, 500f, 0f, 0f))
    }

    @Test
    fun `viewer decode budgets are bounded`() {
        assertEquals(2_048, ATTACHMENT_VIEWER_MAX_EDGE_PX)
        assertEquals(24L * 1024L * 1024L, ATTACHMENT_VIEWER_MAX_BYTES)
    }
}
