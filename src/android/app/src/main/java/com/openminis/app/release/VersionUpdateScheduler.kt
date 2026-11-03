package com.openminis.app.release

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * [fork-version-update] Checks once when the process starts, and again about
 * once a day while a network is available. Does not listen on a port and does
 * not share the inbound task API's notification.
 */
object VersionUpdateScheduler {

    private const val TAG = "VersionUpdate"
    const val PERIODIC_WORK_NAME = "fork_version_update_daily"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dailyRequest(): PeriodicWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        return PeriodicWorkRequestBuilder<VersionUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
    }

    /** From [com.openminis.app.MinisApp.onCreate]. Safe to call more than once. */
    fun install(context: Context) {
        val app = context.applicationContext
        runCatching {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                dailyRequest(),
            )
        }.onFailure { Log.w(TAG, "daily check not scheduled (${it.javaClass.simpleName})") }
        checkSoon(app)
    }

    /** Run one check off the main thread. Used at startup and after the user grants notifications. */
    fun checkSoon(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { VersionUpdateChecker.check(app) }
                .onFailure { Log.w(TAG, "check failed (${it.javaClass.simpleName})") }
        }
    }
}

/**
 * [fork-version-update] Android 13+ needs a runtime grant before a notification
 * can be posted. Ask once. A denial is stored and not asked again; the checker
 * then stays quiet instead of posting.
 */
object VersionUpdatePermission {

    private const val TAG = "VersionUpdate"

    fun maybeAsk(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            activity,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        val store = VersionUpdateStore(activity)
        if (store.hasAskedNotificationPermission()) return
        store.markAskedNotificationPermission()
        runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { Log.w(TAG, "notification permission request failed (${it.javaClass.simpleName})") }
    }
}
