package dev.pranav.applock.core.remotelock

import android.content.Context
import dev.pranav.applock.core.broadcast.AutomationReceiver
import dev.pranav.applock.core.intruder.IntruderLocation
import dev.pranav.applock.core.intruder.IntruderOutbox
import dev.pranav.applock.core.intruder.IntruderSendJob
import dev.pranav.applock.core.intruder.IntruderSender
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.PhoneLocker
import dev.pranav.applock.core.utils.appLockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    // At most one confirmation email this often, so repeating the keyword can't use up Resend's
    // daily allowance or push waiting intruder alerts out of the outbox.
    private const val EMAIL_INTERVAL_MS = 10 * 60 * 1000L

    private val WHITESPACE = Regex("\\s+")

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** What a remote lock did, for the confirmation email. */
    private class Outcome(
        val source: Source,
        val at: Long,
        val protectionWasOn: Boolean,
        val phone: PhoneLock,
        val onTrustedWifi: Boolean
    )

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
     * can tell. Returns null when it was ignored: switched off, too old, a message already seen, or a
     * remote lock ran moments ago. Otherwise returns the job emailing the confirmation, which ends at
     * once when no email is due. Never throws, so a channel can call it from Android's callback.
     */
    fun onKeywordMessage(context: Context, sentAt: Long, source: Source): Job? {
        val appContext = context.applicationContext
        try {
            if (!claim(appContext, sentAt, source)) return null
        } catch (e: Exception) {
            LogUtils.e(TAG, "Checking a keyword message failed", e)
            return null
        }
        val outcome = lockDown(appContext, source)
        return scope.launch(Dispatchers.IO) {
            try {
                emailConfirmation(appContext, outcome)
            } catch (e: Exception) {
                LogUtils.e(TAG, "Queuing the remote lock email failed", e)
            }
        }
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
    private fun lockDown(context: Context, source: Source): Outcome {
        val lockedAt = System.currentTimeMillis()
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
        return Outcome(source, lockedAt, protectionWasOn, phone, onTrustedWifi)
    }

    /**
     * Emails what the remote lock did through the intruder alert email settings and outbox, so
     * whoever sent the message learns it worked. With no network it waits in the outbox like an
     * alert, and goes out once there is one.
     */
    private suspend fun emailConfirmation(context: Context, outcome: Outcome) {
        val repository = context.appLockRepository()
        if (!repository.isRemoteLockEmailEnabled()) return
        if (!repository.isIntruderEmailConfigured()) {
            LogUtils.d(TAG, "No remote lock email: the email isn't set up")
            return
        }
        val sinceLastEmail = outcome.at - repository.getRemoteLockLastEmailAt()
        if (sinceLastEmail in 0 until EMAIL_INTERVAL_MS) {
            LogUtils.d(TAG, "No remote lock email: one was queued ${sinceLastEmail / 60_000} min ago")
            return
        }
        repository.setRemoteLockLastEmailAt(outcome.at)

        val location = if (repository.isIntruderLocationEnabled()) {
            IntruderLocation.describe(context)
        } else {
            null
        }
        val outbox = IntruderOutbox(context)
        outbox.add(outbox.newId(), outcome.at, buildText(context, outcome, location), emptyList())
        LogUtils.d(
            TAG,
            "Remote lock email queued; location ${if (location != null) "included" else "off"}"
        )

        val stillWaiting = IntruderSender.sendPending(context)
        if (stillWaiting || !outbox.isEmpty()) {
            IntruderSendJob.schedule(context)
        }
    }

    private fun buildText(context: Context, outcome: Outcome, location: String?): String {
        val at = Instant.ofEpochMilli(outcome.at)
        val local = TIME_FORMAT.withZone(ZoneId.systemDefault()).format(at)
        val utc = TIME_FORMAT.withZone(ZoneId.of("UTC")).format(at)
        val receivedAs = when (val source = outcome.source) {
            Source.Sms -> "an SMS"
            is Source.Notification -> "a notification from ${appLabel(context, source.packageName)}"
        }
        val protection = if (outcome.protectionWasOn) {
            "Protection: was already on, and every app is locked again"
        } else {
            "Protection: switched on, and every app is locked again"
        }
        val phone = when (outcome.phone) {
            PhoneLock.LOCKED -> "Phone: locked"
            PhoneLock.FAILED -> "Phone: could not be locked. That needs the accessibility service, " +
                    "or device admin with \"Lock the screen\"."
            PhoneLock.OFF -> "Phone: not locked, since \"Also lock the phone\" is off"
        }

        return buildString {
            appendLine("This phone received the remote lock message.")
            appendLine()
            appendLine("When: $local (local)")
            appendLine("      $utc (UTC)")
            appendLine("Received as: $receivedAs")
            appendLine(protection)
            appendLine(phone)
            if (outcome.onTrustedWifi) {
                appendLine(
                    "Trusted Wi-Fi: the phone is on a trusted network, so locked apps still open " +
                            "while it stays connected."
                )
            }
            location?.let { appendLine(it) }
        }.trimEnd()
    }

    private fun appLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            "${pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))} ($packageName)"
        } catch (_: Exception) {
            packageName
        }
    }

    // The log gets exported, so it names the channel and the app, never the message or its sender.
    private fun describeForLog(source: Source): String = when (source) {
        Source.Sms -> "SMS"
        is Source.Notification -> "a notification from ${source.packageName}"
    }
}
