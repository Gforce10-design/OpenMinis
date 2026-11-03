package com.openminis.app.release

import android.content.Context

/**
 * [fork-version-update] Remembers which release was already announced, and
 * whether the notification permission has been requested. Its own prefs file,
 * not the inbound task API's.
 */
internal class VersionUpdateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun notifiedTag(): String? = prefs.getString(KEY_TAG, null)?.takeIf { it.isNotBlank() }

    fun notifiedVersionCode(): Int? = if (prefs.contains(KEY_CODE)) prefs.getInt(KEY_CODE, 0) else null

    fun markNotified(tag: String, versionCode: Int?) {
        prefs.edit().apply {
            putString(KEY_TAG, tag)
            if (versionCode != null) putInt(KEY_CODE, versionCode) else remove(KEY_CODE)
        }.apply()
    }

    fun hasAskedNotificationPermission(): Boolean = prefs.getBoolean(KEY_ASKED, false)

    fun markAskedNotificationPermission() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    companion object {
        const val PREFS = "fork_version_update"
        private const val KEY_TAG = "notified_tag"
        private const val KEY_CODE = "notified_version_code"
        private const val KEY_ASKED = "asked_post_notifications"
    }
}
