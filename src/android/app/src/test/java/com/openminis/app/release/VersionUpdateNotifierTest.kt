package com.openminis.app.release

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [fork-version-update] A newer fork release posts one notification whose tap
 * opens the release page. The inbound task API's channel and id stay unused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VersionUpdateNotifierTest {

    private val htmlUrl = "https://github.com/Gforce10-design/OpenMinis/releases/tag/v1.16-gf"

    private val newer = GithubLatestRelease(
        tag = "v1.16-gf",
        htmlUrl = htmlUrl,
        versionCode = 38,
        draft = false,
        prerelease = false,
    )

    @Before
    fun reset() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(VersionUpdateStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun context() = RuntimeEnvironment.getApplication().also {
        Shadows.shadowOf(it).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `a newer version posts a notification that opens the release page`() {
        val app = context()
        val outcome = VersionUpdateChecker.deliver(
            app,
            newer,
            installedVersionName = "1.15-gf",
            installedVersionCode = 37,
        )
        assertEquals(VersionUpdateChecker.Outcome.Notified, outcome)

        val nm = app.getSystemService(NotificationManager::class.java)
        val shadow = Shadows.shadowOf(nm)
        val notification = shadow.getNotification(VersionUpdateNotifier.NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(VersionUpdateNotifier.CHANNEL_ID, notification.channelId)
        assertNotEquals("inbound_task_api", notification.channelId)
        assertNotEquals(9473, VersionUpdateNotifier.NOTIFICATION_ID)
        assertEquals(
            "새 버전 v1.16-gf가 있어요. 눌러서 APK를 받으세요.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        val pending = notification.contentIntent
        assertNotNull(pending)
        val intent = Shadows.shadowOf(pending).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(htmlUrl, intent.dataString)
        assertTrue(intent.categories.contains(Intent.CATEGORY_BROWSABLE))
        assertEquals(1, shadow.size())

        assertEquals(
            VersionUpdateChecker.Outcome.AlreadyNotified,
            VersionUpdateChecker.deliver(app, newer, "1.15-gf", 37),
        )
        assertEquals("the same version is not posted again", 1, shadow.size())
    }

    @Test
    fun `the installed version and a missing grant post nothing`() {
        val app = context()
        val same = newer.copy(tag = "v1.15-gf", versionCode = 37)
        assertEquals(
            VersionUpdateChecker.Outcome.UpToDate,
            VersionUpdateChecker.deliver(app, same, "1.15-gf", 37),
        )
        val nm = app.getSystemService(NotificationManager::class.java)
        assertEquals(0, Shadows.shadowOf(nm).size())

        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            VersionUpdateChecker.Outcome.NotificationsDisabled,
            VersionUpdateChecker.deliver(app, newer, "1.15-gf", 37),
        )
        assertEquals(0, Shadows.shadowOf(nm).size())
    }

    @Test
    fun `the daily work request waits for a network`() {
        val request = VersionUpdateScheduler.dailyRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(24, TimeUnit.MILLISECONDS.toHours(request.workSpec.intervalDuration))
        assertEquals(VersionUpdateWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals("fork_version_update_daily", VersionUpdateScheduler.PERIODIC_WORK_NAME)
    }
}
