package dev.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the historical attachment thumbnail math and the read-back cache prune plan.
 * The Bitmap decode/LRU itself is Android-only and untested here.
 */
class AttachmentThumbnailTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun `sample size keeps the longest edge within the target`() {
        assertEquals(8, attachmentThumbnailSampleSize(4000, 3000))
        assertEquals(16, attachmentThumbnailSampleSize(8000, 6000))
        assertEquals(1, attachmentThumbnailSampleSize(512, 300))
        assertEquals(2, attachmentThumbnailSampleSize(1025, 100))
        assertEquals(1, attachmentThumbnailSampleSize(100, 100))
        assertEquals(1, attachmentThumbnailSampleSize(0, 0))
    }

    @Test
    fun `chosen sample is the smallest power of two that fits and the bound holds`() {
        val width = 6000
        val height = 2000
        val sample = attachmentThumbnailSampleSize(width, height)
        assertTrue(width / sample <= ATTACHMENT_THUMBNAIL_MAX_EDGE_PX)
        assertTrue(height / sample <= ATTACHMENT_THUMBNAIL_MAX_EDGE_PX)
        assertTrue(sample == 1 || width / (sample / 2) > ATTACHMENT_THUMBNAIL_MAX_EDGE_PX)
    }

    @Test
    fun `prune plan removes expired files but never protected ones`() {
        val entries = listOf(
            AttachmentCacheEntry("expired.png", 10, now - 30 * day),
            AttachmentCacheEntry("protected.png", 10, now - 30 * day),
            AttachmentCacheEntry("fresh.png", 10, now - day),
        )
        val plan = attachmentCachePrunePlan(
            entries = entries,
            nowMs = now,
            protectedFileNames = setOf("protected.png"),
        )
        assertEquals(listOf("expired.png"), plan)
    }

    @Test
    fun `prune plan trims the oldest files until the size budget holds`() {
        val entries = listOf(
            AttachmentCacheEntry("oldest.bin", 60, now - 2 * day),
            AttachmentCacheEntry("middle.bin", 60, now - day),
            AttachmentCacheEntry("newest.bin", 60, now),
        )
        val plan = attachmentCachePrunePlan(
            entries = entries,
            nowMs = now,
            maxTotalBytes = 100,
            maxAgeMs = 100 * day,
        )
        // 180 B > 100 B: delete oldest (120 left), then middle (60 left).
        assertEquals(listOf("oldest.bin", "middle.bin"), plan)
    }

    @Test
    fun `protected files are excluded from size trimming too`() {
        val entries = listOf(
            AttachmentCacheEntry("protected.bin", 60, now - 2 * day),
            AttachmentCacheEntry("older.bin", 60, now - day),
            AttachmentCacheEntry("newer.bin", 60, now),
        )
        val plan = attachmentCachePrunePlan(
            entries = entries,
            nowMs = now,
            maxTotalBytes = 100,
            maxAgeMs = 100 * day,
            protectedFileNames = setOf("protected.bin"),
        )
        assertEquals(listOf("older.bin", "newer.bin"), plan)
    }

    @Test
    fun `nothing is deleted while the cache stays within budget`() {
        val entries = listOf(
            AttachmentCacheEntry("a.bin", 10, now - day),
            AttachmentCacheEntry("b.bin", 10, now),
        )
        assertTrue(
            attachmentCachePrunePlan(entries = entries, nowMs = now, maxAgeMs = 30 * day).isEmpty(),
        )
    }

    @Test
    fun `documented thumbnail and cache budgets are enforced`() {
        assertEquals(15L * 1024L * 1024L, ATTACHMENT_THUMBNAIL_MAX_SOURCE_BYTES)
        assertEquals(512, ATTACHMENT_THUMBNAIL_MAX_EDGE_PX)
        assertEquals(256L * 1024L, ATTACHMENT_THUMBNAIL_MAX_BYTES)
        assertEquals(24, ATTACHMENT_THUMBNAIL_CACHE_ENTRIES)
        assertEquals(16 * 1024 * 1024, ATTACHMENT_THUMBNAIL_CACHE_BYTES)
        assertEquals(100L * 1024L * 1024L, ATTACHMENT_CACHE_MAX_BYTES)
        assertEquals(7L * day, ATTACHMENT_CACHE_MAX_AGE_MS)
    }
}
