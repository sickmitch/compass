package org.compass.cng.offline

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import org.compass.cng.CompassApplication

/** Persistent OS-owned work: no alarms, GPS, polling loop or new scheduling dependency. */
class OfflineUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = scope.launch {
            val manager = (application as CompassApplication).container.offlineAssets
            val success = manager.automaticUpdate()
            jobFinished(params, false)
            schedule(this@OfflineUpdateJobService, manager, retry = !success, replace = true)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val JOB_ID = 42063
        fun schedule(context: Context, manager: OfflineAssetManager, retry: Boolean = false, replace: Boolean = false) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!replace && scheduler.getPendingJob(JOB_ID) != null) return
            val delay = if (retry) Duration.ofHours(6).toMillis() else
                Duration.between(Instant.now(), manager.nextAutomaticCheck()).toMillis().coerceAtLeast(1000)
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, OfflineUpdateJobService::class.java))
                .setMinimumLatency(delay).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true).setPersisted(true)
                .setBackoffCriteria(Duration.ofMinutes(30).toMillis(), JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
}
