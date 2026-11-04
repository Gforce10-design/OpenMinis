package com.openminis.app.release

import com.openminis.app.data.UpdatePolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * [fork-version-update] One GitHub release, as returned by
 * `GET /repos/Gforce10-design/OpenMinis/releases/latest`.
 *
 * [versionCode] is read from the APK file name
 * (`MinisApp-<versionName>-build<N>-<abi>.apk`, the same protocol the
 * in-app updater uses). The workflow also writes `(build N)` in the title
 * and `versionCode N` in the body; those are fallbacks when the asset name
 * is missing. The tag itself never carries the build number.
 */
data class GithubLatestRelease(
    val tag: String,
    val htmlUrl: String,
    val versionCode: Int?,
    val draft: Boolean,
    val prerelease: Boolean,
)

object GithubLatestReleaseParser {

    private val TITLE_BUILD = Regex("""\(build\s+(\d+)\)""", RegexOption.IGNORE_CASE)
    private val BODY_CODE = Regex("""versionCode\s+(\d+)""")

    /** Null when [body] is not a release object (error JSON, a list, or garbage). */
    fun parse(body: String): GithubLatestRelease? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tag = stringField(root, "tag_name")
        if (tag.isEmpty()) return null
        return GithubLatestRelease(
            tag = tag,
            htmlUrl = stringField(root, "html_url"),
            versionCode = versionCode(root),
            draft = root.optBoolean("draft", false),
            prerelease = root.optBoolean("prerelease", false),
        )
    }

    private fun versionCode(root: JSONObject): Int? {
        val assets = root.optJSONArray("assets")
        var fromApk: Int? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val parsed = UpdatePolicy.parseApkName(stringField(asset, "name")) ?: continue
                val code = parsed.build
                if (fromApk == null || code > fromApk) fromApk = code
            }
        }
        if (fromApk != null) return fromApk
        TITLE_BUILD.find(stringField(root, "name"))?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return BODY_CODE.find(stringField(root, "body"))?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun stringField(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key).trim()
    }
}

/**
 * [fork-version-update] GET the fork's latest release. 404 is "no release".
 * A missing network, a rate limit (403 / 429), and a body that is not a
 * release are all [FetchResult.Unavailable] — callers stay quiet.
 */
object GithubLatestReleaseClient {

    const val LATEST_URL = "https://api.github.com/repos/Gforce10-design/OpenMinis/releases/latest"

    sealed class FetchResult {
        data class Found(val release: GithubLatestRelease) : FetchResult()
        data object NoRelease : FetchResult()
        data object Unavailable : FetchResult()
    }

    fun fetch(client: OkHttpClient, url: String = LATEST_URL): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "OpenMinis-fork")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> FetchResult.NoRelease
                    403, 429 -> FetchResult.Unavailable
                    else -> {
                        if (!response.isSuccessful) return FetchResult.Unavailable
                        val body = response.body?.string().orEmpty()
                        val release = GithubLatestReleaseParser.parse(body) ?: return FetchResult.Unavailable
                        FetchResult.Found(release)
                    }
                }
            }
        } catch (_: IOException) {
            FetchResult.Unavailable
        }
    }
}
