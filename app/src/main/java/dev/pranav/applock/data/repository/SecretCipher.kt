package dev.pranav.applock.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets the app has to read back, such as the email API key, under an AES key in the
 * Android Keystore. A backup or a copy of the app's data carries the stored value but never the key,
 * so anywhere else, including a restore onto this phone, the secret simply reads as not set.
 *
 * Values are stored as `v1:iv:ciphertext`, both parts Base64.
 */
class SecretCipher {

    /** Encrypts [plain], making the key on first use. Throws if the Keystore can't. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key(create = true)))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$PREFIX${encode(cipher.iv)}:${encode(sealed)}"
    }

    /** The secret in [stored], or null if it can't be read here. Never makes a key. */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            if (parts.size != 2) return null
            val key = key(create = false) ?: return null

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decode(parts[0])))
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt a stored secret", e)
            null
        }
    }

    private fun key(create: Boolean): SecretKey? {
        synchronized(LOCK) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            if (!create) return null

            // Replacing an unreadable key only loses the one value being overwritten right now.
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val TAG = "SecretCipher"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "stored_secret_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val PREFIX = "v1:"

        /** Each repository creates its own cipher, so they share one lock around making the key. */
        private val LOCK = Any()
    }
}
