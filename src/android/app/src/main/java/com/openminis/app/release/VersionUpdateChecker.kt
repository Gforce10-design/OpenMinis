package com.openminis.app.release

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.openminis.app.network.NetworkHttp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * [fork-version-update] Asks GitHub whether this install is behind the fork's
 * latest release, and posts [VersionUpdateNotifier] at most once per version.
 *
 * Failures stay inside this class: no network, HTTP 404, and a rate limit
 * do not throw and do not clear a notification that is already showing.
 */
object VersionUpdateChecker {

    private const val TAG = "VersionUpdate"

    sealed class Outcome {
        data object Notified : Outcome()
        data object UpToDate : Outcome()
        data object AlreadyNotified : Outcome()
        data object NoRelease : Outcome()
        data object NotificationsDisabled : Outcome()
        data object Unavailable : Outcome()
    }

    private val lock = Any()

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun check(context: Context): Outcome = synchronized(lock) {
        val app = context.applicationContext
        val client = runCatching { NetworkHttp.client(NetworkHttp.DEFAULT_TAG, baseClient) }
            .getOrElse { baseClient }
        when (val fetched = GithubLatestReleaseClient.fetch(client)) {
            is GithubLatestReleaseClient.FetchResult.Found ->
                deliverLocked(app, fetched.release, null, null)
            GithubLatestReleaseClient.FetchResult.NoRelease -> {
                Log.i(TAG, "no GitHub release yet")
                Outcome.NoRelease
            }
            GithubLatestReleaseClient.FetchResult.Unavailable -> {
                Log.i(TAG, "release check skipped (network, rate limit, or unreadable response)")
                Outcome.Unavailable
            }
        }
    }

    /**
     * Apply an already-parsed release. [installedVersionName] and
     * [installedVersionCode] default to the package that is actually installed.
     */
    fun deliver(
        context: Context,
        release: GithubLatestRelease,
        installedVersionName: String? = null,
        installedVersionCode: Int? = null,
    ): Outcome = synchronized(lock) {
        deliverLocked(context.applicationContext, release, installedVersionName, installedVersionCode)
    }

    private fun deliverLocked(
        app: Context,
        release: GithubLatestRelease,
        installedVersionName: String?,
        installedVersionCode: Int?,
    ): Outcome {
        if (release.draft || release.prerelease || !VersionUpdatePolicy.isGithubReleasePage(release.htmlUrl)) {
            return Outcome.UpToDate
        }
        val installed = installed(app)
        val name = installedVersionName ?: installed.first
        val code = installedVersionCode ?: installed.second
        val store = VersionUpdateStore(app)
        if (!VersionUpdatePolicy.isNewer(release, name, code)) {
            VersionUpdateNotifier.cancel(app)
            return Outcome.UpToDate
        }
        if (VersionUpdatePolicy.alreadyNotified(release, store.notifiedTag(), store.notifiedVersionCode())) {
            return Outcome.AlreadyNotified
        }
        if (!VersionUpdateNotifier.canPost(app)) {
            Log.i(TAG, "newer release ${release.tag} not posted (notifications disabled)")
            return Outcome.NotificationsDisabled
        }
        if (!VersionUpdateNotifier.post(app, release)) {
            return Outcome.NotificationsDisabled
        }
        store.markNotified(release.tag, release.versionCode)
        Log.i(TAG, "notified ${release.tag} code=${release.versionCode}")
        return Outcome.Notified
    }

    private fun installed(context: Context): Pair<String, Int> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val name = info.versionName ?: ""
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return name to code
    }
}
