package dev.pranav.applock.core.remotelock

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository

/**
 * The remote lock's notification channel: looks for the keyword in other apps' notifications, the
 * only way to see RCS chats, WhatsApp, Telegram and the like. Plain SMS also has its own channel,
 * [RemoteLockSmsReceiver], which works even when the messaging app shows nothing.
 *
 * Android keeps this bound for as long as the user allows notification access. It stays enabled
 * while remote lock is off and ignores everything then, because disabling a listener's component can
 * make Android forget the access it was given. Nothing read here is stored or logged.
 */
class RemoteLockListener : NotificationListenerService() {

    // Notifications without per-message times that already acted, while they are still showing, so
    // an update to one can't act again. Every callback arrives on the main thread.
    private val actedKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        // A message that came in while Android had this unbound still counts, if it's recent.
        try {
            activeNotifications?.forEach(::checkNotification)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Checking the notifications already showing failed", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogUtils.d(TAG, "Notification listener disconnected, asking Android to bind it again")
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, RemoteLockListener::class.java)
            )
        } catch (e: Exception) {
            LogUtils.e(TAG, "Asking Android to bind the notification listener again failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        checkNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        actedKeys.remove(sbn.key)
    }

    private fun checkNotification(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val repository = appLockRepository()
            if (!repository.isRemoteLockEnabled()) return
            val keyword = repository.getRemoteLockKeyword() ?: return
            val notification = sbn.notification ?: return

            val sentAt = keywordSentAt(sbn, notification, keyword) ?: return
            val source = RemoteLock.Source.Notification(sbn.packageName)
            if (RemoteLock.onKeywordMessage(this, sentAt, source) != null) {
                actedKeys += sbn.key
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Checking a notification failed", e)
        }
    }

    /**
     * When the keyword was sent, if this notification carries it as a whole message. A chat
     * notification lists each message with its own time, which tells an older message still on the
     * list from a new one. Other notifications only have their own time, so [actedKeys] keeps an
     * update to one from acting twice.
     */
    private fun keywordSentAt(
        sbn: StatusBarNotification,
        notification: Notification,
        keyword: String
    ): Long? {
        val messagingStyle =
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (messagingStyle != null) {
            return messagingStyle.messages
                .filter { RemoteLock.matches(it.text, keyword) }
                .maxOfOrNull { it.timestamp }
        }

        if (sbn.key in actedKeys) return null
        val extras = notification.extras ?: return null
        val texts = buildList<CharSequence?> {
            add(extras.getCharSequence(Notification.EXTRA_TEXT))
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        }
        if (texts.none { RemoteLock.matches(it, keyword) }) return null
        return notification.`when`.takeIf { it > 0L } ?: sbn.postTime
    }

    companion object {
        private const val TAG = "RemoteLockListener"

        /** Whether the user has given this app notification access. */
        fun hasAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
