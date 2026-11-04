package com.openminis.app.release

import com.openminis.app.data.UpdatePolicy

/**
 * [fork-version-update] Order of this fork's versions.
 *
 * The fork release workflow (`.github/workflows/fork-release.yml`) does not
 * invent its own numbering. It publishes the APK that Gradle already stamped:
 *  - `versionName` is the upstream name plus the fork suffix `-gf`
 *    (`FORK_VERSION_SUFFIX=-gf`, see `forkVersionName` in `app/build.gradle.kts`),
 *    for example `1.15-gf`;
 *  - `versionCode` is the upstream build counter and is not part of the tag;
 *  - the GitHub tag is `v` + that `versionName`, for example `v1.15-gf`.
 *
 * `-gf` marks the fork build. It is not a pre-release, so `1.15-gf` and `1.15`
 * are the same version. Numeric parts compare as integers (`1.10` > `1.9`),
 * and a missing patch is zero (`1.15` == `1.15.0`). Anything after another
 * `-` (an upstream beta, if one were ever tagged) still sorts below the
 * release, via [UpdatePolicy.compareVersionNames].
 */
object ForkVersion {

    /** Suffix the fork workflow appends to `versionName` and therefore to the tag. */
    const val FORK_SUFFIX = "-gf"

    /**
     * Strip one leading `v`/`V` and one trailing [FORK_SUFFIX]. The remaining
     * text is what [compare] orders.
     */
    fun normalize(raw: String): String {
        var t = raw.trim()
        if (t.length >= 2 && (t[0] == 'v' || t[0] == 'V') && t[1].isDigit()) {
            t = t.substring(1)
        }
        if (t.endsWith(FORK_SUFFIX)) t = t.removeSuffix(FORK_SUFFIX)
        return t
    }

    /** Negative if [a] is older, zero if equal, positive if [a] is newer. */
    fun compare(a: String, b: String): Int =
        UpdatePolicy.compareVersionNames(normalize(a), normalize(b))

    /** Tag as shown in the notification: always with a leading `v`. */
    fun displayTag(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        return if (t[0] == 'v' || t[0] == 'V') t else "v$t"
    }
}
