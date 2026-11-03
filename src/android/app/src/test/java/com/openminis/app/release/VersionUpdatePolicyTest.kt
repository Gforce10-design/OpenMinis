package com.openminis.app.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [fork-version-update] When a parsed release becomes a notification. */
class VersionUpdatePolicyTest {

    private val page = "https://github.com/Gforce10-design/OpenMinis/releases/tag/v1.16-gf"

    private fun release(
        tag: String = "v1.16-gf",
        code: Int? = 38,
        url: String = page,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = GithubLatestRelease(tag, url, code, draft, prerelease)

    @Test
    fun `a higher tag is newer, and the same name needs a higher versionCode`() {
        assertTrue(VersionUpdatePolicy.isNewer(release(), "1.15-gf", 37))
        assertTrue(VersionUpdatePolicy.isNewer(release(tag = "v1.16-gf", code = 38), "1.15", 37))
        assertFalse(VersionUpdatePolicy.isNewer(release(tag = "v1.15-gf", code = 37), "1.15-gf", 37))
        assertFalse(VersionUpdatePolicy.isNewer(release(tag = "v1.15-gf", code = 37), "1.15", 37))
        assertTrue("same versionName, newer build", VersionUpdatePolicy.isNewer(release(tag = "v1.15-gf", code = 38), "1.15-gf", 37))
        assertFalse("older name, even with a higher build", VersionUpdatePolicy.isNewer(release(tag = "v1.14-gf", code = 99), "1.15-gf", 37))
        assertFalse(VersionUpdatePolicy.isNewer(release(tag = "v1.15-gf", code = null), "1.15-gf", 37))
    }

    @Test
    fun `the same version is not announced twice, a later build is`() {
        val next = release()
        assertTrue(VersionUpdatePolicy.shouldNotify(next, "1.15-gf", 37, null, null))
        assertFalse(VersionUpdatePolicy.shouldNotify(next, "1.15-gf", 37, "v1.16-gf", 38))
        assertFalse(VersionUpdatePolicy.shouldNotify(next, "1.15-gf", 37, "1.16-gf", null))
        assertTrue(VersionUpdatePolicy.shouldNotify(release(code = 39), "1.15-gf", 37, "v1.16-gf", 38))
        assertFalse("already on that build", VersionUpdatePolicy.shouldNotify(next, "1.16-gf", 38, null, null))
    }

    @Test
    fun `drafts, prereleases, and non-GitHub pages are ignored`() {
        assertFalse(VersionUpdatePolicy.shouldNotify(release(draft = true), "1.15-gf", 37, null, null))
        assertFalse(VersionUpdatePolicy.shouldNotify(release(prerelease = true), "1.15-gf", 37, null, null))
        assertFalse(VersionUpdatePolicy.shouldNotify(release(url = ""), "1.15-gf", 37, null, null))
        assertFalse(VersionUpdatePolicy.shouldNotify(release(url = "http://github.com/Gforce10-design/OpenMinis/releases/tag/v1.16-gf"), "1.15-gf", 37, null, null))
        assertFalse(VersionUpdatePolicy.shouldNotify(release(url = "https://evil.example/apk"), "1.15-gf", 37, null, null))
        assertTrue(VersionUpdatePolicy.isGithubReleasePage(page))
    }
}
