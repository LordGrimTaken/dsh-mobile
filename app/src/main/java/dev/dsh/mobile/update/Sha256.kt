package dev.dsh.mobile.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 helpers for verifying downloaded update APKs.
 *
 * The release manifest carries the expected lowercase hex digest; an APK is only handed to the
 * package installer when the digest matches (otherwise the download is deleted and an error shown).
 */
object Sha256 {

    private const val HEX_LENGTH = 64
    private const val BUFFER_SIZE = 8 * 1024
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** True when [value] is a well-formed SHA-256 hex digest (case-insensitive). */
    fun isValidHex(value: String): Boolean {
        if (value.length != HEX_LENGTH) return false
        return value.all { char ->
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }

    /** Lowercase hex SHA-256 of everything readable from [input]; the stream is not closed. */
    fun hexOf(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun hexOf(file: File): String = file.inputStream().use { hexOf(it) }

    fun hexOf(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * Verifies [file] against [expectedHex]. Returns false for an empty/missing file, a malformed
     * expected digest, or any mismatch - callers must treat false as "do not install".
     */
    fun verify(file: File, expectedHex: String): Boolean {
        val expected = expectedHex.trim()
        if (!isValidHex(expected)) return false
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching { hexOf(file).equals(expected, ignoreCase = true) }.getOrDefault(false)
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            out.append(HEX_CHARS[value ushr 4]).append(HEX_CHARS[value and 0x0f])
        }
        return out.toString()
    }
}
