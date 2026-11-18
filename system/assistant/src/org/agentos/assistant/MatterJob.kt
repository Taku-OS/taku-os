package org.agentos.assistant

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Android owns wakeups. Daily slots are wall-clock/timezone calculations. */
class MatterJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val worker = Thread({
            try {
                val runtime = MatterRuntime.get(this)
                runtime.migrateCards()
                runtime.recoverPanelUpdates()
                runtime.tick()
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }, "matter-due")
        worker.isDaemon = true
        worker.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val DUE = 0x4d4154
        private const val RECOVERY = 0x4d4155

        @JvmStatic
        fun schedule(context: Context, due: Long, pendingPanels: Boolean = false) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, MatterJob::class.java)
            if (due < 0) {
                jobs.cancel(DUE)
                if (!pendingPanels) { jobs.cancel(RECOVERY); return }
            } else jobs.schedule(JobInfo.Builder(DUE, component)
                .setMinimumLatency(Math.max(1000L, due - System.currentTimeMillis()))
                .setPersisted(true).build())
            if (jobs.getPendingJob(RECOVERY) == null) {
                jobs.schedule(JobInfo.Builder(RECOVERY, component)
                    .setPeriodic(30 * 60_000L).setPersisted(true).build())
            }
        }
    }
}
