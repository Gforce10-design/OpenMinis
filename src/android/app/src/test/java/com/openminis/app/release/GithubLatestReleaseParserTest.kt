package com.openminis.app.release

import com.openminis.app.data.UpdatePolicy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [fork-version-update] The body of `GET .../releases/latest`. */
class GithubLatestReleaseParserTest {

    private val releaseJson = """
        {
          "tag_name": "v1.16-gf",
          "name": "OpenMinis 1.16-gf (build 1)",
          "html_url": "https://github.com/Gforce10-design/OpenMinis/releases/tag/v1.16-gf",
          "draft": false,
          "prerelease": false,
          "body": "Fork build of OpenMinis 1.16-gf (versionCode 2).",
          "assets": [
            {
              "name": "SHA256SUMS",
              "browser_download_url": "https://github.com/Gforce10-design/OpenMinis/releases/download/v1.16-gf/SHA256SUMS"
            },
            {
              "name": "MinisApp-1.16-gf-build38-arm64-v8a.apk",
              "browser_download_url": "https://github.com/Gforce10-design/OpenMinis/releases/download/v1.16-gf/MinisApp-1.16-gf-build38-arm64-v8a.apk"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the fork APK file name is the versionCode`() {
        val parsed = UpdatePolicy.parseApkName("MinisApp-1.15-gf-build37-arm64-v8a.apk")
        assertEquals("1.15-gf", parsed!!.version)
        assertEquals(37, parsed.build)
        assertEquals("arm64-v8a", parsed.abi)

        val release = GithubLatestReleaseParser.parse(releaseJson)!!
        assertEquals("v1.16-gf", release.tag)
        assertEquals(
            "https://github.com/Gforce10-design/OpenMinis/releases/tag/v1.16-gf",
            release.htmlUrl,
        )
        assertEquals("the APK name wins over the title and the body", 38, release.versionCode)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
    }

    @Test
    fun `title then body supply the build when no APK is named`() {
        val fromTitle = GithubLatestReleaseParser.parse(
            """{"tag_name":"v1.16-gf","html_url":"https://github.com/Gforce10-design/OpenMinis/releases/tag/v1.16-gf","name":"OpenMinis 1.16-gf (build 40)","assets":[]}""",
        )!!
        assertEquals(40, fromTitle.versionCode)

        val fromBody = GithubLatestReleaseParser.parse(
            """{"tag_name":"v1.16-gf","html_url":"https://github.com/x.github.com/r","body":"versionCode 41"}""",
        )!!
        assertEquals(41, fromBody.versionCode)
    }

    @Test
    fun `an error payload, a list, and garbage are not a release`() {
        assertNull(GithubLatestReleaseParser.parse("""{"message":"Not Found","documentation_url":"https://docs.github.com"}"""))
        assertNull(GithubLatestReleaseParser.parse("""{"message":"API rate limit exceeded"}"""))
        assertNull(GithubLatestReleaseParser.parse("[]"))
        assertNull(GithubLatestReleaseParser.parse("{"))
        assertNull(GithubLatestReleaseParser.parse(""))
        assertNull(GithubLatestReleaseParser.parse("""{"tag_name":""}"""))
        assertNull(GithubLatestReleaseParser.parse("""{"tag_name":null}"""))
    }

    @Test
    fun `http status maps to no release or a quiet skip`() {
        val server = MockWebServer()
        server.start()
        try {
            val client = OkHttpClient()
            val url = server.url("/repos/Gforce10-design/OpenMinis/releases/latest").toString()

            server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
            val found = GithubLatestReleaseClient.fetch(client, url) as GithubLatestReleaseClient.FetchResult.Found
            assertEquals("v1.16-gf", found.release.tag)
            assertEquals(GithubLatestReleaseClient.LATEST_URL, recordedPathIsLatest(server))

            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
            assertEquals(GithubLatestReleaseClient.FetchResult.NoRelease, GithubLatestReleaseClient.fetch(client, url))

            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
            assertEquals(GithubLatestReleaseClient.FetchResult.Unavailable, GithubLatestReleaseClient.fetch(client, url))

            server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
            assertEquals(GithubLatestReleaseClient.FetchResult.Unavailable, GithubLatestReleaseClient.fetch(client, url))

            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"message":"nope"}"""))
            assertEquals(GithubLatestReleaseClient.FetchResult.Unavailable, GithubLatestReleaseClient.fetch(client, url))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a dead connection is a quiet skip`() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/releases/latest").toString()
        server.shutdown()
        val result = GithubLatestReleaseClient.fetch(OkHttpClient.Builder().build(), url)
        assertEquals(GithubLatestReleaseClient.FetchResult.Unavailable, result)
        assertTrue(result is GithubLatestReleaseClient.FetchResult.Unavailable)
    }

    private fun recordedPathIsLatest(server: MockWebServer): String {
        val recorded = server.takeRequest()
        assertEquals("/repos/Gforce10-design/OpenMinis/releases/latest", recorded.path)
        assertEquals("OpenMinis-fork", recorded.getHeader("User-Agent"))
        return GithubLatestReleaseClient.LATEST_URL
    }
}
