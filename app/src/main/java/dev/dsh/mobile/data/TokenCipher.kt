package dev.dsh.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts host device tokens at rest. The JVM unit tests substitute a fake
 * implementation; on device this is [AndroidKeystoreTokenCipher].
 */
interface TokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * AES-256-GCM token encryption keyed by the Android Keystore. The key material never leaves the
 * Keystore; only the ciphertext (`base64(iv + ciphertext)`) is written to SharedPreferences.
 */
class AndroidKeystoreTokenCipher(
    private val alias: String = DEFAULT_ALIAS,
) : TokenCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (bytes.size <= GCM_IV_BYTES) {
            throw IllegalArgumentException("Encrypted token is truncated")
        }
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val payload = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "dsh_mobile_host_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
