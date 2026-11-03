package com.openminis.app.release

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [fork-version-update] Daily check. A transient failure (no network, rate
 * limit) retries with the work request's backoff. A definitive answer
 * succeeds so the next period is the next check.
 */
class VersionUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (VersionUpdateChecker.check(applicationContext)) {
            VersionUpdateChecker.Outcome.Unavailable -> Result.retry()
            else -> Result.success()
        }
    }
}
