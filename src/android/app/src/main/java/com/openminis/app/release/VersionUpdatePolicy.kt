package com.openminis.app.release

import java.net.URI

/**
 * [fork-version-update] Whether a fetched release should produce a notification.
 *
 * Newer means the tag's version (leading `v` and the fork suffix `-gf`
 * removed) is semantically greater than the installed `versionName`, or the
 * names match and the release's `versionCode` — the upstream build counter,
 * from the APK name — is greater than the installed `versionCode`. A higher
 * build of an older name is not offered: the tag is the version the workflow
 * publishes.
 *
 * The same tag and build are not announced twice. A draft, a prerelease, or
 * a page that is not an https GitHub release URL is ignored.
 */
object VersionUpdatePolicy {

    fun isNewer(
        release: GithubLatestRelease,
        installedVersionName: String,
        installedVersionCode: Int,
    ): Boolean {
        val byName = ForkVersion.compare(release.tag, installedVersionName)
        if (byName > 0) return true
        if (byName < 0) return false
        val code = release.versionCode ?: return false
        return code > installedVersionCode
    }

    /**
     * True when [release] is the same version already announced, or an older
     * one. A higher build of the same tag (the workflow rewrites one tag when
     * `versionName` does not change) is not the same version.
     */
    fun alreadyNotified(
        release: GithubLatestRelease,
        notifiedTag: String?,
        notifiedVersionCode: Int?,
    ): Boolean {
        if (notifiedTag.isNullOrBlank()) return false
        val byName = ForkVersion.compare(release.tag, notifiedTag)
        if (byName > 0) return false
        if (byName < 0) return true
        val code = release.versionCode
        val previous = notifiedVersionCode
        if (code == null || previous == null) return true
        return code <= previous
    }

    fun shouldNotify(
        release: GithubLatestRelease,
        installedVersionName: String,
        installedVersionCode: Int,
        notifiedTag: String?,
        notifiedVersionCode: Int?,
    ): Boolean {
        if (release.draft || release.prerelease) return false
        if (!isGithubReleasePage(release.htmlUrl)) return false
        if (!isNewer(release, installedVersionName, installedVersionCode)) return false
        if (alreadyNotified(release, notifiedTag, notifiedVersionCode)) return false
        return true
    }

    /** Browser target: the release `html_url`, and only an https GitHub page. */
    fun isGithubReleasePage(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" || host.endsWith(".github.com")
    }
}
