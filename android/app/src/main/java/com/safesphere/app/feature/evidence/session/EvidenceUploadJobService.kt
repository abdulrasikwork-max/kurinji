package com.safesphere.app.feature.evidence.session

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.safesphere.app.SafeSphereApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings evidence uploads back after the app process is gone (swiped away, killed by the system, phone was offline):
 * Android's own JobScheduler (no new library) starts this job once ANY network is available, which starts the app
 * process; the EvidenceVault then resumes its normal upload loop, and the job just keeps the process alive until
 * nothing of the signed-in person is left to upload (or its time is up, then it is run again with back-off).
 *
 * Not persisted across a reboot (that would need RECEIVE_BOOT_COMPLETED, deliberately not requested): after a restart
 * of the phone, uploads resume when the app is opened. Nothing is uploaded while signed out.
 */
class EvidenceUploadJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val vault = (application as SafeSphereApp).container.evidenceVault
        work = scope.launch {
            val settled = vault.awaitUploadsSettled(RUN_LIMIT_MILLIS)
            jobFinished(params, !settled)
        }
        return true // still working (asynchronously)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        // Android stopped us early (network lost, time limit): run again later if anything is still pending.
        return (application as SafeSphereApp).container.evidenceVault.hasPendingUploads.value
    }

    override fun onDestroy() {
        work?.cancel()
        super.onDestroy()
    }

    private companion object {
        /** Below the ~10-minute limit of a regular job. */
        const val RUN_LIMIT_MILLIS = 8 * 60 * 1000L
    }
}

/** Schedules [EvidenceUploadJobService] whenever the person has evidence waiting to reach the vault. */
class AndroidEvidenceUploadScheduler(context: Context) : EvidenceUploadScheduler {

    private val appContext = context.applicationContext

    override fun onPendingUploads(pending: Boolean) {
        // Nothing to cancel when uploads are done: a running job sees that itself and finishes without rescheduling.
        if (!pending) return
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return // already waiting or running
        val job = JobInfo.Builder(JOB_ID, ComponentName(appContext, EvidenceUploadJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        runCatching { scheduler.schedule(job) } // a refusal (job quota) only means: resume on the next app start
    }

    private companion object {
        const val JOB_ID = 5_001
        const val BACKOFF_MILLIS = 30_000L
    }
}
