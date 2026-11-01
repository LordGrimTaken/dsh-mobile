package dev.dsh.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** SHA-256 helper used to verify a downloaded update APK before install. */
class Sha256Test {

    private val abcDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptyDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `computes known sha256 digests`() {
        assertEquals(abcDigest, Sha256.hexOf("abc".toByteArray()))
        assertEquals(abcDigest, Sha256.hexOf(ByteArrayInputStream("abc".toByteArray())))
        assertEquals(emptyDigest, Sha256.hexOf(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `validates hex format`() {
        assertTrue(Sha256.isValidHex(abcDigest))
        assertTrue(Sha256.isValidHex(abcDigest.uppercase()))
        assertFalse(Sha256.isValidHex(""))
        assertFalse(Sha256.isValidHex(abcDigest.dropLast(1)))
        assertFalse(Sha256.isValidHex("$abcDigest'"))
        assertFalse(Sha256.isValidHex("z".repeat(64)))
        assertFalse(Sha256.isValidHex("g" + abcDigest.drop(1)))
    }

    @Test
    fun `verifies a file against a known digest`() {
        val file = tempFile(APK_BYTES)
        try {
            val digest = Sha256.hexOf(file)
            assertEquals(Sha256.hexOf(APK_BYTES), digest)

            assertTrue("matching digest must verify", Sha256.verify(file, digest))
            assertTrue("uppercase digest must verify", Sha256.verify(file, digest.uppercase()))
            assertTrue("surrounding whitespace is tolerated", Sha256.verify(file, "  $digest  "))

            assertFalse("wrong digest must not verify", Sha256.verify(file, "0".repeat(64)))
            assertFalse("malformed digest must not verify", Sha256.verify(file, "not-a-sha256"))
            assertFalse("truncated digest must not verify", Sha256.verify(file, digest.dropLast(2)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `verify fails safely for missing or empty files`() {
        val missing = File(
            System.getProperty("java.io.tmpdir"),
            "dsh-mobile-does-not-exist-${System.nanoTime()}.apk",
        )
        assertFalse(Sha256.verify(missing, abcDigest))

        val empty = tempFile(ByteArray(0))
        try {
            assertFalse(Sha256.verify(empty, emptyDigest))
            assertFalse(Sha256.verify(empty, abcDigest))
        } finally {
            empty.delete()
        }
    }

    private fun tempFile(bytes: ByteArray): File {
        val file = Files.createTempFile("dsh-mobile-", ".apk").toFile()
        file.writeBytes(bytes)
        return file
    }

    private companion object {
        val APK_BYTES = "fake apk payload for sha256 verification".toByteArray()
    }
}
