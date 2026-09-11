package dev.pranav.applock.data.repository

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dev.pranav.applock.core.utils.SecurityUtils
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Hashes and checks the PIN, password and pattern under a key in the Android Keystore, which never
 * leaves the phone's secure hardware. A hash copied off the phone can't be checked anywhere else, so
 * even a short PIN can't be guessed from it.
 *
 * Values are stored as `v2:salt:mac`, the mac being an HMAC under that key of the SHA-256 an older
 * `salt:hash` value holds. So an older value upgrades without the PIN, and one the Keystore couldn't
 * upgrade is still checked the old way.
 */
class CredentialHasher(context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Holds the ID of the phone the key was made on. Android never backs up this folder, so a missing
     * marker, or one copied from another phone, means no key was made for this install.
     */
    private val keyMarker = File(context.noBackupFilesDir, KEY_MARKER)

    /** Hashes a new PIN, password or pattern, falling back to `salt:hash` if the Keystore fails. */
    fun hash(value: String): String {
        val salt = SecurityUtils.generateSalt()
        return try {
            keyedHash(salt, SecurityUtils.saltedDigest(value, salt), key(create = true))
        } catch (e: Exception) {
            // Still salted, and the upgrade at the next start tries the key again.
            Log.w(TAG, "Keystore unavailable, storing an unkeyed hash", e)
            SecurityUtils.hashPassword(value, salt)
        }
    }

    /** Whether [input] matches [stored], in any format the app has stored. Never makes a key. */
    fun verify(input: String, stored: String): Boolean {
        // Before isSaltedHash, which rejects the three parts of a current value.
        if (isCurrent(stored)) {
            return try {
                val parts = stored.split(":")
                if (parts.size != 3) return false

                val salt = Base64.decode(parts[1], Base64.NO_WRAP)
                val expectedMac = Base64.decode(parts[2], Base64.NO_WRAP)
                val actualMac = mac(SecurityUtils.saltedDigest(input, salt), key(create = false))

                MessageDigest.isEqual(actualMac, expectedMac)
            } catch (_: Exception) {
                false
            }
        }

        if (SecurityUtils.isSaltedHash(stored)) return SecurityUtils.verifyPassword(input, stored)

        return SecurityUtils.constantTimeEquals(
            SecurityUtils.sanitizePassword(stored),
            SecurityUtils.sanitizePassword(input)
        )
    }

    fun isCurrent(stored: String): Boolean = stored.startsWith(PREFIX)

    /**
     * Brings an older value up to `v2` without the PIN. If the Keystore fails, a salted hash stays as
     * it is and plain text becomes one.
     */
    fun upgrade(stored: String): String {
        if (isCurrent(stored)) return stored
        if (!SecurityUtils.isSaltedHash(stored)) return hash(stored)

        return try {
            val parts = stored.split(":")
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val hash = Base64.decode(parts[1], Base64.NO_WRAP)
            keyedHash(salt, hash, key(create = true))
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable, keeping the unkeyed hash", e)
            stored
        }
    }

    /**
     * Whether this install has no key of its own, so stored `v2` values came from a backup or another
     * phone and can never match. Any error answers false, so a Keystore hiccup never looks like that.
     */
    fun isRestoredWithoutKey(): Boolean {
        return try {
            !isKeyMadeHere() && !keyStore().containsAlias(KEY_ALIAS)
        } catch (_: Exception) {
            false
        }
    }

    private fun keyedHash(salt: ByteArray, digest: ByteArray, key: SecretKey): String {
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val macBase64 = Base64.encodeToString(mac(digest, key), Base64.NO_WRAP)

        return "$PREFIX$saltBase64:$macBase64"
    }

    private fun mac(data: ByteArray, key: SecretKey): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key)
        return mac.doFinal(data)
    }

    private fun key(create: Boolean): SecretKey {
        synchronized(LOCK) {
            (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            // Once this install has made a key, one that fails to load is an error, not a missing
            // key: a new key under the same alias would leave every stored hash unable to match.
            check(create && !isKeyMadeHere()) { "Credential key unavailable" }

            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build()
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(spec)
            val key = generator.generateKey()
            keyMarker.writeText(phoneId())
            return key
        }
    }

    private fun isKeyMadeHere(): Boolean = keyMarker.exists() && keyMarker.readText() == phoneId()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    // Only compared with the marker, on this phone; it's never sent anywhere.
    @SuppressLint("HardwareIds")
    private fun phoneId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    companion object {
        private const val TAG = "CredentialHasher"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "credential_hmac"
        private const val KEY_MARKER = "credential_key"
        private const val PREFIX = "v2:"

        /** Each repository creates its own hasher, so they share one lock around making the key. */
        private val LOCK = Any()
    }
}
