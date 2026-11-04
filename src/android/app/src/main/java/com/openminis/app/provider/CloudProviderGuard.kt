package com.openminis.app.provider

import com.openminis.app.auth.ClaudeOAuthNotConfiguredException
import com.openminis.app.auth.OAuthNetworkUnreachableException
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.CodexDiscoveryAuthException
import org.json.JSONException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns provider send / resume / retry / model-list / login failures into a
 * reason the UI can show. A blank build-config value or a network, auth, JSON,
 * or refresh failure must not escape a main-thread coroutine: that is what
 * killed the process when Claude Code's identifier prompt was empty.
 *
 * [classify] never throws.
 */
object CloudProviderGuard {

    const val ANTHROPIC_PROMPT_KEY = "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT"

    enum class Reason {
        /** Safe to send or sign in. */
        READY,
        /** A required build-time value is blank. */
        BLANK_BUILD_CONFIG,
        /** This build can decode the provider but cannot drive a request. */
        UNSUPPORTED,
        NETWORK,
        /** HTTP 401/403, or the credential was rejected. */
        AUTH,
        RATE_LIMIT,
        /** HTTP 5xx from the provider. */
        SERVER,
        JSON,
        TOKEN_REFRESH,
        MISSING_MODEL,
        OTHER,
    }

    /**
     * The only properties entry the public mirror ships blank is the Claude
     * Code identifier prompt, and only OAuth sends need it. Every other cloud
     * provider authenticates with a user-supplied key or a hardcoded client id.
     */
    fun requiredBuildKey(type: ProviderType, oauth: Boolean): String? =
        if (type == ProviderType.anthropic && oauth) ANTHROPIC_PROMPT_KEY else null

    /**
     * @param anthropicPrompt [com.openminis.app.BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT]
     * @param kimiClientId the resolved Kimi client id when [type] is Kimi and
     *   [oauth] is true. Null skips that check. An explicit empty manifest
     *   override disables Kimi login; the official id is used when the
     *   meta-data entry is absent.
     */
    fun readiness(
        type: ProviderType,
        oauth: Boolean,
        anthropicPrompt: String?,
        kimiClientId: String? = null,
    ): Reason {
        if (!type.isUsable) return Reason.UNSUPPORTED
        if (requiredBuildKey(type, oauth) != null && anthropicPrompt.isNullOrBlank()) {
            return Reason.BLANK_BUILD_CONFIG
        }
        if (type == ProviderType.kimiCode && oauth && kimiClientId != null && kimiClientId.isBlank()) {
            return Reason.BLANK_BUILD_CONFIG
        }
        return Reason.READY
    }

    fun listUnavailable(
        type: ProviderType,
        oauth: Boolean,
        anthropicPrompt: String?,
        kimiClientId: String? = null,
    ): Boolean = readiness(type, oauth, anthropicPrompt, kimiClientId) != Reason.READY

    fun classify(error: Throwable): Reason {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        for (e in chain) {
            when (e) {
                is ClaudeOAuthNotConfiguredException -> return Reason.BLANK_BUILD_CONFIG
                is CodexDiscoveryAuthException -> return Reason.AUTH
                is OAuthNetworkUnreachableException -> return Reason.NETWORK
                is LLMError.InvalidApiKey -> return Reason.AUTH
                is LLMError.RateLimited -> return Reason.RATE_LIMIT
                is LLMError.NetworkError -> return Reason.NETWORK
                is LLMError.DecodingError -> return Reason.JSON
                is LLMError.ProviderError -> {
                    if (isMissingModel(e.message)) return Reason.MISSING_MODEL
                    e.httpStatus?.let { return classifyHttp(it) }
                }
                is LLMError.TransientError -> {
                    e.httpStatus?.let { return classifyHttp(it) }
                    return Reason.NETWORK
                }
                is JSONException -> return Reason.JSON
                is SocketTimeoutException, is UnknownHostException -> return Reason.NETWORK
                is IOException -> return Reason.NETWORK
            }
            reasonFromMessage(e.message)?.let { return it }
        }
        return Reason.OTHER
    }

    fun classifyHttp(status: Int): Reason = when (status) {
        401, 403 -> Reason.AUTH
        429 -> Reason.RATE_LIMIT
        in 500..599 -> Reason.SERVER
        else -> Reason.OTHER
    }

    fun httpStatus(error: Throwable): Int? {
        generateSequence(error) { it.cause }.take(8).forEach { e ->
            when (e) {
                is CodexDiscoveryAuthException -> return e.status
                is LLMError.ProviderError -> e.httpStatus?.let { return it }
                is LLMError.TransientError -> e.httpStatus?.let { return it }
            }
            httpInMessage(e.message)?.let { return it }
        }
        return null
    }

    private fun reasonFromMessage(message: String?): Reason? {
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return null
        if (msg.contains("provider_unsupported")) return Reason.UNSUPPORTED
        if (msg.contains("client id override is empty", ignoreCase = true) ||
            msg.contains(ANTHROPIC_PROMPT_KEY)
        ) {
            return Reason.BLANK_BUILD_CONFIG
        }
        if (isMissingModel(msg)) return Reason.MISSING_MODEL
        httpInMessage(msg)?.let { return classifyHttp(it) }
        if (msg.contains("discovery", ignoreCase = true)) return Reason.NETWORK
        if (msg.contains("refresh", ignoreCase = true) && msg.contains("token", ignoreCase = true)) {
            return Reason.TOKEN_REFRESH
        }
        if (msg.contains("no usable token", ignoreCase = true) ||
            msg.contains("invalid api key", ignoreCase = true) ||
            msg.contains("invalid_grant", ignoreCase = true)
        ) {
            return Reason.AUTH
        }
        return null
    }

    private fun isMissingModel(message: String?): Boolean {
        val text = message?.lowercase().orEmpty()
        if (text.isEmpty()) return false
        return text.contains("model_not_found") ||
            text.contains("model_not_supported") ||
            text.contains("model not found") ||
            text.contains("unknown model") ||
            (text.contains("model") && text.contains("does not exist"))
    }

    private fun httpInMessage(message: String?): Int? {
        val msg = message ?: return null
        val match = HTTP_IN_MESSAGE.find(msg) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private val HTTP_IN_MESSAGE = Regex("""(?:HTTP\s+|\()(\d{3})\b""")
}
