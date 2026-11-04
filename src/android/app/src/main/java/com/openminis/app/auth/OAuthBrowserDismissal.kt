package com.openminis.app.auth

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException

/**
 * [T-oauth-browser-dismissal] The user backed out of the Custom Tab.
 *
 * Custom Tabs have no "dismissed" callback. xAI already treated "the process
 * came back to the foreground and the loopback redirect did not arrive within
 * [GRACE_MS]" as that dismissal and stopped the callback server, which is what
 * lets the Sign In button leave "Signing in…". OpenAI/Codex, Claude, and
 * OpenRouter waited on accept() with no timeout, so backing out left the
 * button on "Signing in…" until the process died. This is that detector, shared.
 *
 * Kimi and Copilot are device-code polls. Coming back to the app is the
 * success path there (the user authorizes in the browser, then returns while
 * polling continues), and each flow already ends on its own expiry or when
 * the code dialog is cancelled. They do not use this.
 */
internal object OAuthBrowserDismissal {
    /** Same grace xAI used: long enough for a redirect that is already in flight. */
    const val GRACE_MS = 1_500L

    const val DISMISSAL_LOG =
        "App resumed without OAuth callback — treating as user dismissal, stopping server"

    /**
     * Watch [ProcessLifecycleOwner]. The first ON_RESUME after the observer is
     * added is the activity that is already in front; only a resume that
     * follows a pause or stop is the user coming back. [stillWaiting] is
     * checked when the grace elapses, so a redirect that lands in that window
     * wins. Returns a disarm function; call it when the sign-in ends so a late
     * resume cannot stop a newer flow.
     */
    fun arm(tag: String, stillWaiting: () -> Boolean, onDismissed: () -> Unit): () -> Unit {
        val main = Handler(Looper.getMainLooper())
        val watch = OAuthBrowserDismissalWatch(
            schedule = { delayMs, block ->
                val runnable = Runnable { block() }
                main.postDelayed(runnable, delayMs)
                // Not a trailing lambda of postDelayed: this is the cancel handle.
                val cancel: () -> Unit = { main.removeCallbacks(runnable) }
                cancel
            },
            stillWaiting = stillWaiting,
            onDismissed = {
                Log.w(tag, DISMISSAL_LOG)
                onDismissed()
            },
        )
        val observer = watch.observer()
        main.post {
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
                .onFailure { Log.w(tag, "OAuth dismissal detector failed to attach: ${it.message}") }
        }
        return {
            watch.disarm()
            main.post {
                runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
            }
        }
    }
}

/**
 * Wire [server] so a dismissal cancels [cont], and start the resume watcher.
 * Call after the Custom Tab is launched. [stillWaiting] must be false once
 * this flow no longer owns [server] (a callback arrived, or a newer sign-in
 * replaced it).
 */
internal fun OAuthCallbackServer.armBrowserDismissal(
    tag: String,
    cont: CancellableContinuation<*>,
    stillWaiting: () -> Boolean,
): () -> Unit {
    onExternalCancel = {
        if (cont.isActive) cont.cancel(CancellationException("OAuth callback cancelled"))
    }
    return OAuthBrowserDismissal.arm(tag, stillWaiting) { stop() }
}

/**
 * The resume/grace decision, with the clock injected so a unit test can drive
 * it without a process. [onPause]/[onStop]/[onResume] run on the main thread
 * (they are lifecycle callbacks). [disarm] may run on the sign-in coroutine.
 */
internal class OAuthBrowserDismissalWatch(
    private val schedule: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    private val stillWaiting: () -> Boolean,
    private val onDismissed: () -> Unit,
    private val graceMs: Long = OAuthBrowserDismissal.GRACE_MS,
) {
    private val lock = Any()

    @Volatile private var sawBackgrounded = false

    @Volatile private var armed = true

    private var cancelPending: (() -> Unit)? = null

    fun onPause() { sawBackgrounded = true }

    fun onStop() { sawBackgrounded = true }

    fun onResume() {
        if (!armed || !sawBackgrounded) return
        // One decision per trip to the background. A second ON_RESUME without
        // another pause must not stack another stop.
        sawBackgrounded = false
        val cancel = schedule(graceMs) {
            if (armed && stillWaiting()) onDismissed()
        }
        val previous: (() -> Unit)?
        val installed: Boolean
        synchronized(lock) {
            if (!armed) {
                installed = false
                previous = null
            } else {
                previous = cancelPending
                cancelPending = cancel
                installed = true
            }
        }
        if (!installed) {
            cancel()
            return
        }
        previous?.invoke()
        // [disarm] may have run after the check above and already dropped
        // [cancel]. Cancelling again is a no-op; skipping it would leave the
        // callback alive when disarm won the race before we published it.
        if (!armed) cancel()
    }

    fun disarm() {
        armed = false
        sawBackgrounded = false
        val previous = synchronized(lock) {
            val pending = cancelPending
            cancelPending = null
            pending
        }
        previous?.invoke()
    }

    fun observer(): DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) = onPause()
        override fun onStop(owner: LifecycleOwner) = onStop()
        override fun onResume(owner: LifecycleOwner) = onResume()
    }
}
