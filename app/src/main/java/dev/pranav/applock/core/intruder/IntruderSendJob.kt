package dev.pranav.applock.core.intruder

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.pranav.applock.core.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Retries waiting alerts once the phone has a network, for example after a thief turns airplane
 * mode off again. The job is persisted, so it survives a reboot, and backs off between failures.
 */
class IntruderSendJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val retry = try {
                IntruderSender.sendPending(applicationContext)
            } catch (e: Exception) {
                LogUtils.e(TAG, "Sending waiting alerts failed", e)
                true
            }
            jobFinished(params, retry)
        }
        return true
    }

    // Stopped mid-send, for example because the network dropped. The idempotency key stops a repeat.
    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IntruderSendJob"
        private const val JOB_ID = 4201
        private const val BACKOFF_MS = 30_000L

        /**
         * Schedules a send for when there is a network. Replaces a job already waiting, which only
         * brings the next try forward; a send it interrupts is covered by the idempotency key.
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, IntruderSendJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()

            if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
                LogUtils.d(TAG, "Scheduled a send for when there is a network")
            } else {
                LogUtils.e(TAG, "Could not schedule a send")
            }
        }
    }
}
