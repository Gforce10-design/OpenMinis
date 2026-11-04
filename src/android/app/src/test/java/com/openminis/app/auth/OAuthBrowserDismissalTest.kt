package com.openminis.app.auth

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-oauth-browser-dismissal] Backing out of a Custom Tab must end the sign-in.
 * The decision is the one xAI already shipped: ignore the resume that is
 * replayed when the observer is added, then 1.5s after a real return stop the
 * callback server if the redirect has not arrived.
 */
class OAuthBrowserDismissalTest {

    private class Scheduler {
        var now = 0L
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<Task>()

        private class Task(val at: Long, val block: () -> Unit) {
            var cancelled = false
            var ran = false
        }

        fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
            delays += delayMs
            val task = Task(now + delayMs, block)
            tasks += task
            return { task.cancelled = true }
        }

        fun advance(ms: Long) {
            now += ms
            for (task in tasks) {
                if (task.cancelled || task.ran || task.at > now) continue
                task.ran = true
                task.block()
            }
        }
    }

    private fun watch(
        scheduler: Scheduler,
        stillWaiting: () -> Boolean = { true },
        onDismissed: () -> Unit,
    ) = OAuthBrowserDismissalWatch(
        schedule = scheduler::schedule,
        stillWaiting = stillWaiting,
        onDismissed = onDismissed,
    )

    @Test
    fun `the grace matches the xAI resume window and the log line`() {
        assertEquals(1_500L, OAuthBrowserDismissal.GRACE_MS)
        assertEquals(
            "App resumed without OAuth callback — treating as user dismissal, stopping server",
            OAuthBrowserDismissal.DISMISSAL_LOG,
        )
    }

    @Test
    fun `a resume before the tab backgrounds the app is ignored`() {
        val clock = Scheduler()
        var stopped = 0
        val watch = watch(clock) { stopped++ }
        watch.onResume()
        clock.advance(OAuthBrowserDismissal.GRACE_MS)
        assertTrue(clock.delays.isEmpty())
        assertEquals(0, stopped)
    }

    @Test
    fun `returning from the tab stops the server once the grace elapses`() {
        val clock = Scheduler()
        var stopped = 0
        val watch = watch(clock) { stopped++ }
        watch.onPause()
        watch.onResume()
        assertEquals(listOf(OAuthBrowserDismissal.GRACE_MS), clock.delays)
        clock.advance(OAuthBrowserDismissal.GRACE_MS - 1)
        assertEquals("still inside the grace", 0, stopped)
        clock.advance(1)
        assertEquals(1, stopped)
    }

    @Test
    fun `a redirect that arrives during the grace wins`() {
        val clock = Scheduler()
        var waiting = true
        var stopped = 0
        val watch = watch(clock, stillWaiting = { waiting }) { stopped++ }
        watch.onStop()
        watch.onResume()
        waiting = false
        clock.advance(OAuthBrowserDismissal.GRACE_MS)
        assertEquals(0, stopped)
    }

    @Test
    fun `disarming before the grace cancels the stop`() {
        val clock = Scheduler()
        var stopped = 0
        val watch = watch(clock) { stopped++ }
        watch.onPause()
        watch.onResume()
        watch.disarm()
        clock.advance(OAuthBrowserDismissal.GRACE_MS)
        assertEquals(0, stopped)
    }

    @Test
    fun `a second resume without another pause does not schedule again`() {
        val clock = Scheduler()
        val watch = watch(clock) {}
        watch.onPause()
        watch.onResume()
        watch.onResume()
        assertEquals(1, clock.delays.size)
    }

    @Test
    fun `browser sign-ins arm the shared detector and device-code flows do not`() {
        val browser = listOf(
            "auth/OpenAIOAuthManager.kt",
            "auth/ClaudeOAuthManager.kt",
            "auth/OpenRouterOAuthManager.kt",
            "auth/XAIOAuthManager.kt",
        )
        for (file in browser) {
            val src = ProductionSources.read(file)
            assertTrue("$file waits with the shared detector", src.contains("armBrowserDismissal("))
            assertTrue("$file clears the cancel hook once a code arrives", src.contains("onExternalCancel = null"))
        }
        assertFalse(
            "the old GlobalScope delay must not come back",
            ProductionSources.read("auth/XAIOAuthManager.kt").contains("GlobalScope"),
        )
        // Device code: returning to the app is how the user finishes authorizing.
        // Each poll already ends on expiry, and the dialog cancel cancels the job.
        for (file in listOf("auth/KimiOAuthManager.kt", "auth/CopilotOAuthManager.kt")) {
            val src = ProductionSources.read(file)
            assertFalse(src.contains("OAuthBrowserDismissal"))
            assertFalse(src.contains("armBrowserDismissal"))
            assertTrue(src.contains("expiresInSeconds"))
        }
    }
}
