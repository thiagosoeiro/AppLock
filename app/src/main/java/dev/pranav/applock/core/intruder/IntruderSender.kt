package dev.pranav.applock.core.intruder

import android.content.Context
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Emails every waiting alert. Runs right after an alert is made, and from [IntruderSendJob] once
 * there is a network again. One pass runs at a time, and an alert's ID is also Resend's idempotency
 * key, so a pass cut off after sending can't produce a second email.
 */
object IntruderSender {
    private const val TAG = "IntruderSender"

    /** The subject of every alert: neutral, in case the mailbox is also signed in on this phone. */
    private const val SUBJECT = "System report"

    private val mutex = Mutex()

    /** Returns true when an alert is still waiting and worth trying again once there's a network. */
    suspend fun sendPending(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val repository = context.appLockRepository()
            val outbox = IntruderOutbox(context)
            val alerts = outbox.pending()
            if (alerts.isEmpty()) return@withLock false

            val apiKey = repository.getIntruderApiKey()
            val to = repository.getIntruderEmailTo()
            if (apiKey.isNullOrBlank() || to.isBlank()) {
                // Saving the email settings schedules another try.
                repository.setIntruderSendError("Email isn't set up")
                LogUtils.d(TAG, "${alerts.size} alert(s) waiting, but email isn't set up")
                return@withLock false
            }

            val client = ResendClient(apiKey)
            val from = repository.getIntruderEmailFrom()
            for (alert in alerts) {
                val result = client.send(
                    from = from,
                    to = to,
                    subject = SUBJECT,
                    text = alert.text,
                    attachment = alert.attachment,
                    attachmentName = alert.attachment?.let { attachmentName(it.extension) },
                    idempotencyKey = alert.id
                )
                when (result) {
                    ResendClient.Result.Sent -> {
                        outbox.remove(alert)
                        repository.setIntruderSendError(null)
                        LogUtils.d(TAG, "Sent alert ${alert.id}")
                    }

                    is ResendClient.Result.Retry -> {
                        // The rest would fail the same way now.
                        LogUtils.d(TAG, "Alert ${alert.id} not sent, will retry: ${result.reason}")
                        return@withLock true
                    }

                    // Kept until the settings change or the next alert, and dropped after a week.
                    is ResendClient.Result.Rejected -> {
                        repository.setIntruderSendError(result.reason)
                        LogUtils.e(TAG, "Resend refused alert ${alert.id}: ${result.reason}")
                    }
                }
            }
            false
        }
    }

    private fun attachmentName(extension: String): String =
        if (extension == IntruderOutbox.VIDEO_EXTENSION) "video.$extension" else "photo.$extension"
}
