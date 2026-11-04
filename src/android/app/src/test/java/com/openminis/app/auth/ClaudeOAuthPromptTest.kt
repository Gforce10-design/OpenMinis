package com.openminis.app.auth

import com.openminis.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A debug build from provider-customization.properties.example leaves the
 * Claude Code identifier prompt blank. That used to throw on the main-thread
 * send coroutine and kill the process. The blank path is a result, and the
 * throwing accessor is a catchable exception.
 */
class ClaudeOAuthPromptTest {

    @Test
    fun `a blank identifier prompt does not throw and is not ready to send`() {
        assertTrue(
            "unit tests build with the example properties file",
            BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.isBlank(),
        )
        assertFalse(ClaudeOAuthPrompt.isConfigured())
        assertEquals(
            ClaudeOAuthPrompt.Apply.Missing,
            ClaudeOAuthPrompt.apply("hello", oauth = true, configuredPrompt = ""),
        )
        assertEquals(
            ClaudeOAuthPrompt.Apply.Missing,
            ClaudeOAuthPrompt.apply("hello", oauth = true, configuredPrompt = "   "),
        )
        assertEquals(
            ClaudeOAuthPrompt.Apply.Missing,
            ClaudeOAuthPrompt.apply("hello", oauth = true, configuredPrompt = null),
        )
        val plain = ClaudeOAuthPrompt.apply("hello", oauth = false, configuredPrompt = "")
        assertEquals(ClaudeOAuthPrompt.Apply.Ready("hello"), plain)

        try {
            @Suppress("UNUSED_VARIABLE")
            val ignored = ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            fail("blank config must not return a prompt")
        } catch (e: ClaudeOAuthNotConfiguredException) {
            assertTrue(e.message!!.contains("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT"))
        }
    }

    @Test
    fun `a configured prompt is prefixed for oauth and stripped for anyone else`() {
        val prefix = "YOU ARE CLAUDE CODE"
        val ready = ClaudeOAuthPrompt.apply("hello", oauth = true, configuredPrompt = prefix)
            as ClaudeOAuthPrompt.Apply.Ready
        assertEquals("$prefix\n\nhello", ready.prompt)

        val already = ClaudeOAuthPrompt.apply(ready.prompt, oauth = true, configuredPrompt = prefix)
            as ClaudeOAuthPrompt.Apply.Ready
        assertEquals(ready.prompt, already.prompt)

        val stripped = ClaudeOAuthPrompt.apply("$prefix\n\nhello", oauth = false, configuredPrompt = prefix)
            as ClaudeOAuthPrompt.Apply.Ready
        assertEquals("hello", stripped.prompt)

        val untouched = ClaudeOAuthPrompt.apply(null, oauth = false, configuredPrompt = prefix)
            as ClaudeOAuthPrompt.Apply.Ready
        assertEquals(null, untouched.prompt)
    }
}
