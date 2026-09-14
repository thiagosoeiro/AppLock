package dev.pranav.applock.core.remotelock

import android.content.Context
import dev.pranav.applock.core.broadcast.AutomationReceiver
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.PhoneLocker
import dev.pranav.applock.core.utils.appLockRepository
import java.text.Normalizer
import java.util.Locale

/**
 * Locks things down when a message made of just the user's keyword arrives, by SMS or in another
 * app's notification: protection goes on, every app re-locks, and the phone locks too if the user
 * chose that. This is item 14 of FUTURE_IMPROVEMENTS.md.
 *
 * It only ever locks. The keyword stays readable in the chat and may show in a notification, so
 * someone holding the phone can learn it; a keyword that unlocked anything would hand them the phone.
 */
object RemoteLock {
    private const val TAG = "RemoteLock"

    /** The shortest keyword accepted, counted after [normalize]. */
    const val MIN_KEYWORD_LENGTH = 8

    // Anything older is history, such as a chat notification that still lists the message.
    private const val MAX_MESSAGE_AGE_MS = 15 * 60 * 1000L

    // How far another app's message time may run ahead of this phone's clock.
    private const val MAX_CLOCK_AHEAD_MS = 60 * 1000L

    // One SMS comes in by both channels, moments apart, and should act once.
    private const val REPEAT_WINDOW_MS = 60 * 1000L

    private val WHITESPACE = Regex("\\s+")

    private val lock = Any()

    /** Where a keyword message came in. */
    sealed interface Source {
        object Sms : Source
        data class Notification(val packageName: String) : Source
    }

    private enum class PhoneLock(val logText: String) {
        LOCKED("locked"),
        FAILED("could not be locked"),
        OFF("left alone, its switch is off")
    }

    /**
     * The form messages and the keyword are compared in: letters or digits at both ends, runs of
     * white space as one space, lower case. So "Are you home yet?" matches "are you  home yet".
     */
    fun normalize(text: CharSequence): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .trim { !it.isLetterOrDigit() }
            .replace(WHITESPACE, " ")
            .lowercase(Locale.ROOT)

    /**
     * A keyword needs a letter as well as length: Android 15+ hides notification text that looks like
     * a one-time code from apps like this one, so a keyword of digits might never be seen.
     */
    fun isValidKeyword(keyword: String): Boolean {
        val normalized = normalize(keyword)
        return normalized.length >= MIN_KEYWORD_LENGTH && normalized.any { it.isLetter() }
    }

    /**
     * True when [text] is the keyword and nothing more, give or take case, spacing and punctuation at
     * either end.
     */
    fun matches(text: CharSequence?, keyword: String): Boolean {
        if (text.isNullOrBlank()) return false
        val expected = normalize(keyword)
        return expected.isNotEmpty() && normalize(text) == expected
    }

    /**
     * Acts on a message that matched the keyword. [sentAt] is when it was sent, as far as its channel
     * can tell. Returns false when it was ignored: switched off, too old, a message already seen, or
     * a remote lock ran moments ago. Never throws, so a channel can call it from Android's callback.
     */
    fun onKeywordMessage(context: Context, sentAt: Long, source: Source): Boolean {
        val appContext = context.applicationContext
        try {
            if (!claim(appContext, sentAt, source)) return false
        } catch (e: Exception) {
            LogUtils.e(TAG, "Checking a keyword message failed", e)
            return false
        }
        lockDown(appContext, source)
        return true
    }

    /**
     * Decides whether this message may act, and records it, under one lock: the SMS receiver and the
     * notification listener can see the same message at the same moment.
     */
    private fun claim(context: Context, sentAt: Long, source: Source): Boolean {
        val repository = context.appLockRepository()
        if (!repository.isRemoteLockEnabled()) return false

        val from = describeForLog(source)
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            if (sentAt < now - MAX_MESSAGE_AGE_MS || sentAt > now + MAX_CLOCK_AHEAD_MS) {
                LogUtils.d(TAG, "Keyword by $from ignored: not sent within the last 15 minutes")
                return false
            }
            if (sentAt <= repository.getRemoteLockLastMessageAt()) {
                LogUtils.d(TAG, "Keyword by $from ignored: that message was already seen")
                return false
            }
            repository.setRemoteLockLastMessageAt(sentAt)

            val sinceLastLock = now - repository.getRemoteLockLastLockAt()
            if (sinceLastLock in 0 until REPEAT_WINDOW_MS) {
                LogUtils.d(
                    TAG,
                    "Keyword by $from ignored: a remote lock ran ${sinceLastLock / 1000} s ago"
                )
                return false
            }
            repository.setRemoteLockLastLockAt(now)
            true
        }
    }

    /**
     * Protection on and every app re-locked, the same way the shield and automation do it, then the
     * phone. Each part is tried even if the one before failed.
     */
    private fun lockDown(context: Context, source: Source) {
        val repository = context.appLockRepository()
        val protectionWasOn = repository.isProtectEnabled()
        try {
            AutomationReceiver.setProtection(context, true)
            AutomationReceiver.notifyStateChanged(context, true)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Switching protection on failed", e)
        }

        val phone = try {
            when {
                !repository.isRemoteLockPhoneEnabled() -> PhoneLock.OFF
                PhoneLocker.lockPhone(context, "remote lock by message") -> PhoneLock.LOCKED
                else -> PhoneLock.FAILED
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Locking the phone failed", e)
            PhoneLock.FAILED
        }

        // Trusted Wi-Fi still relaxes the shield after a remote lock, by the user's choice.
        val onTrustedWifi =
            repository.isTrustedWifiEnabled() && TrustedNetworkMonitor.isOnTrustedNetwork()

        val protection = if (protectionWasOn) "was already on" else "switched on"
        val trusted = if (onTrustedWifi) "; on trusted Wi-Fi, so locked apps still open" else ""
        LogUtils.d(
            TAG,
            "Remote lock by ${describeForLog(source)}: protection $protection; " +
                    "phone ${phone.logText}$trusted"
        )
    }

    // The log gets exported, so it names the channel and the app, never the message or its sender.
    private fun describeForLog(source: Source): String = when (source) {
        Source.Sms -> "SMS"
        is Source.Notification -> "a notification from ${source.packageName}"
    }
}
