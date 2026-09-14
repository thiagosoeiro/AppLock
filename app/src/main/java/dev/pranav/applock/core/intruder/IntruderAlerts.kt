package dev.pranav.applock.core.intruder

import android.annotation.SuppressLint
import android.content.Context
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.IntruderCaptureMode
import dev.pranav.applock.services.AppLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a run of wrong PINs into an emailed alert: a front-camera photo or video, the phone's
 * location, and which app was being unlocked. This is items 8 and 13 of FUTURE_IMPROVEMENTS.md.
 *
 * The wrong-try count is the one [dev.pranav.applock.data.repository.UnlockAttemptLimiter] already
 * keeps, so every lock screen feeds this without its own counting: the app's own PIN, a locked
 * app's lock screen, Change PIN and turning anti-uninstall off.
 *
 * Everything runs off the calling thread and nothing here throws, so an alert can neither slow down
 * nor break unlocking.
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object IntruderAlerts {
    private const val TAG = "IntruderAlerts"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One alert at a time: capture holds the camera, and a burst of wrong-try events is common.
    private val running = AtomicBoolean(false)

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Called after each counted wrong try, with the running total. Sends an alert when the total
     * reaches the threshold, and again at each multiple of it up to three times, so one long run of
     * guessing can't send an unbounded stream of email.
     */
    fun onWrongTry(failureCount: Int) {
        if (!::appContext.isInitialized) return
        val repository = appContext.appLockRepository()
        if (!repository.isIntruderAlertsEnabled()) return

        val threshold = repository.getIntruderThreshold()
        if (failureCount <= 0 || failureCount % threshold != 0 || failureCount / threshold > 3) return

        if (!running.compareAndSet(false, true)) {
            LogUtils.d(TAG, "An alert is already running; skipping this trigger")
            return
        }
        val lockedPackage = AppLockManager.appOnLockScreen
        scope.launch {
            try {
                runAlert(failureCount, lockedPackage)
            } catch (e: Exception) {
                LogUtils.e(TAG, "Building an intruder alert failed", e)
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * Runs the whole flow now, for the Settings test button, and returns what happened. Ignores the
     * one-at-a-time guard so the user can always test.
     */
    suspend fun sendTest(context: Context): String {
        return try {
            runAlert(failureCount = null, lockedPackage = null)
            val error = context.appLockRepository().getIntruderSendError()
            if (error == null) "Test alert sent" else "Couldn't send: $error"
        } catch (e: Exception) {
            LogUtils.e(TAG, "Test alert failed", e)
            "Couldn't send: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private suspend fun runAlert(failureCount: Int?, lockedPackage: String?) {
        val repository = appContext.appLockRepository()
        val outbox = IntruderOutbox(appContext)
        val id = outbox.newId()
        val createdAt = System.currentTimeMillis()

        val mode = repository.getIntruderCaptureMode()
        val captureNote: String?
        val attachment = if (IntruderCapture.hasCameraPermission(appContext)) {
            val file = outbox.captureFile(id, IntruderCapture.extensionFor(mode))
            when (val result = IntruderCapture.capture(appContext, mode, file)) {
                is IntruderCapture.Result.Captured -> {
                    captureNote = result.note
                    result.file
                }

                is IntruderCapture.Result.Failed -> {
                    captureNote = "Capture failed: ${result.reason}"
                    null
                }
            }
        } else {
            captureNote = "Capture skipped: camera permission not granted"
            null
        }

        val location = if (repository.isIntruderLocationEnabled()) {
            IntruderLocation.describe(appContext)
        } else {
            null
        }

        val text = buildText(failureCount, lockedPackage, mode, captureNote, location)
        outbox.add(id, createdAt, text, attachment)

        val stillWaiting = IntruderSender.sendPending(appContext)
        if (stillWaiting || !outbox.isEmpty()) {
            IntruderSendJob.schedule(appContext)
        }
    }

    private fun buildText(
        failureCount: Int?,
        lockedPackage: String?,
        mode: IntruderCaptureMode,
        captureNote: String?,
        location: String?
    ): String {
        val now = Instant.now()
        val local = LOCAL_FORMAT.withZone(ZoneId.systemDefault()).format(now)
        val utc = LOCAL_FORMAT.withZone(ZoneId.of("UTC")).format(now)
        val target = lockedPackage?.let(::appLabel) ?: "the app's own PIN screen"

        return buildString {
            appendLine("A wrong code was entered on this phone.")
            appendLine()
            appendLine("When: $local (local)")
            appendLine("      $utc (UTC)")
            appendLine("Where: $target")
            if (failureCount != null) {
                appendLine("Wrong tries in a row: $failureCount")
            } else {
                appendLine("This is a test alert sent from Settings.")
            }
            location?.let { appendLine(it) }
            val capture = when (mode) {
                IntruderCaptureMode.PHOTO -> "photo"
                IntruderCaptureMode.VIDEO -> "video"
            }
            appendLine(captureNote ?: "A $capture is attached.")
        }.trimEnd()
    }

    private fun appLabel(packageName: String): String {
        return try {
            val pm = appContext.packageManager
            "${pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))} ($packageName)"
        } catch (_: Exception) {
            packageName
        }
    }

    private val LOCAL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
