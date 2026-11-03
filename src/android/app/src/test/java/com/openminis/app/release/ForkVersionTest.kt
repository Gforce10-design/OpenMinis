package com.openminis.app.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fork-version-update] Tag order matches the fork release workflow:
 * tag = `v` + versionName, and versionName is `<upstream>-gf`.
 */
class ForkVersionTest {

    @Test
    fun `leading v and the fork suffix do not change the version`() {
        assertEquals(0, ForkVersion.compare("v1.15-gf", "1.15-gf"))
        assertEquals(0, ForkVersion.compare("v1.15-gf", "1.15"))
        assertEquals(0, ForkVersion.compare("V1.15-gf", "1.15.0"))
        assertEquals("1.15", ForkVersion.normalize("v1.15-gf"))
        assertEquals("v1.15-gf", ForkVersion.displayTag("1.15-gf"))
        assertEquals("v1.15-gf", ForkVersion.displayTag("v1.15-gf"))
    }

    @Test
    fun `numeric order, not text order`() {
        assertTrue(ForkVersion.compare("v1.10-gf", "v1.9-gf") > 0)
        assertTrue(ForkVersion.compare("v1.16-gf", "1.15-gf") > 0)
        assertTrue(ForkVersion.compare("v1.15.1-gf", "v1.15-gf") > 0)
        assertTrue(ForkVersion.compare("v1.15-gf", "v1.16-gf") < 0)
        assertTrue(ForkVersion.compare("v2-gf", "v1.15.9-gf") > 0)
    }

    @Test
    fun `a pre-release sorts below the same numbers`() {
        assertTrue(ForkVersion.compare("v1.16-beta.1-gf", "v1.16-gf") < 0)
        assertTrue(ForkVersion.compare("v1.16-beta.10-gf", "v1.16-beta.9-gf") > 0)
    }
}
