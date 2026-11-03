package com.openminis.app.release

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * [fork-version-update] One notification, on its own channel. The tap opens
 * the release page in the browser so the user can download the APK. Nothing
 * here installs a package.
 */
internal object VersionUpdateNotifier {

    const val CHANNEL_ID = "fork_version_update"
    const val NOTIFICATION_ID = 8126
    private const val REQUEST_CODE = 8126

    /** Korean on every locale: the notice is the fork's download prompt. */
    const val CHANNEL_NAME = "버전 업데이트"
    const val CHANNEL_DESCRIPTION =
        "새 OpenMinis 버전이 나오면 알려 줍니다. 알림을 누르면 릴리스 페이지가 열리고, APK는 직접 받아서 설치합니다."
    const val TITLE = "새 버전"

    fun body(displayTag: String): String = "새 버전 ${displayTag}가 있어요. 눌러서 APK를 받으세요."

    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    /** The browser intent stored on the notification. Exposed for tests. */
    fun browserIntent(htmlUrl: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun post(context: Context, release: GithubLatestRelease): Boolean {
        if (!canPost(context)) return false
        val app = context.applicationContext
        ensureChannel(app)
        val tag = ForkVersion.displayTag(release.tag)
        val body = body(tag)
        val pending = PendingIntent.getActivity(
            app,
            REQUEST_CODE,
            browserIntent(release.htmlUrl),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(TITLE)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }
}
