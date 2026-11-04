package com.openminis.app.auth

import com.openminis.app.BuildConfig

/**
 * A public or example build leaves `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` blank.
 * Reading the throwing accessor from a chat coroutine used to escape
 * `Dispatchers.Main` and kill the process. Chat sends use [apply] instead and
 * show an in-chat error on [Apply.Missing].
 */
class ClaudeOAuthNotConfiguredException : IllegalStateException(
    "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT is not configured. Copy " +
        "app/provider-customization.properties.example to " +
        "app/provider-customization.properties and set " +
        "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT before using Claude Code OAuth.",
)

object ClaudeOAuthPrompt {

    fun configuredOrNull(raw: String = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT): String? =
        raw.trim().takeIf { it.isNotEmpty() }

    fun isConfigured(raw: String = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT): Boolean =
        configuredOrNull(raw) != null

    sealed class Apply {
        data class Ready(val prompt: String?) : Apply()
        data object Missing : Apply()
    }

    /**
     * OAuth sends need the identifier prefix. A non-OAuth send strips it when
     * this build has one, so a model switch does not keep Claude Code's
     * identity string. A blank prompt on an OAuth send is [Apply.Missing] and
     * does not throw.
     */
    fun apply(base: String?, oauth: Boolean, configuredPrompt: String?): Apply {
        val prefix = configuredPrompt?.trim()?.takeIf { it.isNotEmpty() }
        if (!oauth) {
            if (base == null || prefix == null) return Apply.Ready(base)
            val bare = if (base.startsWith(prefix)) base.removePrefix(prefix).trimStart('\n') else base
            return Apply.Ready(bare)
        }
        if (prefix == null) return Apply.Missing
        if (base != null && base.startsWith(prefix)) return Apply.Ready(base)
        return Apply.Ready("$prefix\n\n${base.orEmpty()}")
    }
}
