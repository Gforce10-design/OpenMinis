package com.openminis.app.provider

import com.openminis.app.BuildConfig
import com.openminis.app.auth.ClaudeOAuthNotConfiguredException
import com.openminis.app.auth.OAuthNetworkUnreachableException
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.CodexDiscoveryAuthException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Every cloud provider: a blank build-config value must not be a crash, and a
 * representative network or auth failure must classify as an in-chat or
 * settings error. The example properties file leaves the Claude prompt blank;
 * no other provider reads a build-config secret.
 */
class CloudProviderGuardTest {

    @Test
    fun `anthropic api key ignores a blank oauth prompt and surfaces 401`() {
        assertNull(CloudProviderGuard.requiredBuildKey(ProviderType.anthropic, oauth = false))
        assertEquals(
            CloudProviderGuard.Reason.READY,
            CloudProviderGuard.readiness(ProviderType.anthropic, oauth = false, anthropicPrompt = ""),
        )
        assertEquals(CloudProviderGuard.Reason.AUTH, CloudProviderGuard.classify(LLMError.InvalidApiKey()))
        assertEquals(CloudProviderGuard.Reason.AUTH, CloudProviderGuard.classifyHttp(401))
    }

    @Test
    fun `anthropic oauth blank prompt is unavailable and a network error is not a crash`() {
        assertEquals(
            CloudProviderGuard.ANTHROPIC_PROMPT_KEY,
            CloudProviderGuard.requiredBuildKey(ProviderType.anthropic, oauth = true),
        )
        assertTrue(
            "unit tests build from the example properties file",
            BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT.isBlank(),
        )
        assertEquals(
            CloudProviderGuard.Reason.BLANK_BUILD_CONFIG,
            CloudProviderGuard.readiness(
                ProviderType.anthropic,
                oauth = true,
                anthropicPrompt = BuildConfig.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT,
            ),
        )
        assertTrue(
            CloudProviderGuard.listUnavailable(
                ProviderType.anthropic,
                oauth = true,
                anthropicPrompt = "  ",
            ),
        )
        assertEquals(
            CloudProviderGuard.Reason.READY,
            CloudProviderGuard.readiness(ProviderType.anthropic, oauth = true, anthropicPrompt = "id"),
        )
        assertEquals(
            CloudProviderGuard.Reason.BLANK_BUILD_CONFIG,
            CloudProviderGuard.classify(ClaudeOAuthNotConfiguredException()),
        )
        assertEquals(
            CloudProviderGuard.Reason.NETWORK,
            CloudProviderGuard.classify(UnknownHostException("api.anthropic.com")),
        )
    }

    @Test
    fun `openai api key has no build config and a 401 is auth`() {
        assertReadyWithoutBuildConfig(ProviderType.openAI, oauth = false)
        assertEquals(CloudProviderGuard.Reason.AUTH, CloudProviderGuard.classifyHttp(401))
        assertEquals(
            CloudProviderGuard.Reason.AUTH,
            CloudProviderGuard.classify(LLMError.InvalidApiKey("incorrect api key")),
        )
    }

    @Test
    fun `openai codex oauth has no build config and discovery 401 is auth`() {
        assertReadyWithoutBuildConfig(ProviderType.openAI, oauth = true)
        val error = CodexDiscoveryAuthException(401)
        assertEquals(CloudProviderGuard.Reason.AUTH, CloudProviderGuard.classify(error))
        assertEquals(401, CloudProviderGuard.httpStatus(error))
    }

    @Test
    fun `openai responses has no build config and 429 is rate limit`() {
        assertReadyWithoutBuildConfig(ProviderType.openAIResponses, oauth = false)
        assertReadyWithoutBuildConfig(ProviderType.openAIResponses, oauth = true)
        assertEquals(CloudProviderGuard.Reason.RATE_LIMIT, CloudProviderGuard.classify(LLMError.RateLimited()))
        assertEquals(CloudProviderGuard.Reason.RATE_LIMIT, CloudProviderGuard.classifyHttp(429))
    }

    @Test
    fun `gemini api key has no build config and 403 is auth`() {
        assertReadyWithoutBuildConfig(ProviderType.gemini, oauth = false)
        assertEquals(
            CloudProviderGuard.Reason.AUTH,
            CloudProviderGuard.classify(LLMError.InvalidApiKey("permission denied")),
        )
        assertEquals(CloudProviderGuard.Reason.AUTH, CloudProviderGuard.classifyHttp(403))
    }

    @Test
    fun `gemini oauth has no properties entry and a timeout is network`() {
        // Google OAuth is not offered in Settings. Its client secret is a
        // hardcoded placeholder, not a build-config field, so a blank
        // properties file does not make the provider throw.
        assertNull(CloudProviderGuard.requiredBuildKey(ProviderType.gemini, oauth = true))
        assertReadyWithoutBuildConfig(ProviderType.gemini, oauth = true)
        assertEquals(
            CloudProviderGuard.Reason.NETWORK,
            CloudProviderGuard.classify(SocketTimeoutException("oauth2.googleapis.com")),
        )
    }

    @Test
    fun `openrouter api key has no build config and a bad json body is a read error`() {
        assertReadyWithoutBuildConfig(ProviderType.openRouter, oauth = false)
        assertEquals(
            CloudProviderGuard.Reason.JSON,
            CloudProviderGuard.classify(JSONException("Value null at key of type java.lang.String")),
        )
        assertEquals(
            CloudProviderGuard.Reason.JSON,
            CloudProviderGuard.classify(LLMError.DecodingError(JSONException("truncated"))),
        )
    }

    @Test
    fun `openrouter oauth has no build config and a 401 exchange is auth`() {
        assertReadyWithoutBuildConfig(ProviderType.openRouter, oauth = true)
        assertEquals(
            CloudProviderGuard.Reason.AUTH,
            CloudProviderGuard.classify(Exception("OpenRouter key exchange failed (401): unauthorized")),
        )
    }

    @Test
    fun `xai api key has no build config and 429 is rate limit`() {
        assertReadyWithoutBuildConfig(ProviderType.xAI, oauth = false)
        assertEquals(
            CloudProviderGuard.Reason.RATE_LIMIT,
            CloudProviderGuard.classify(LLMError.ProviderError("slow down", httpStatus = 429)),
        )
    }

    @Test
    fun `xai oauth discovery failure is a provider error and not a crash`() {
        assertReadyWithoutBuildConfig(ProviderType.xAI, oauth = true)
        assertEquals(
            CloudProviderGuard.Reason.SERVER,
            CloudProviderGuard.classify(IllegalStateException("xAI OIDC discovery failed: HTTP 503")),
        )
        assertEquals(
            CloudProviderGuard.Reason.NETWORK,
            CloudProviderGuard.classify(IllegalStateException("xAI OIDC discovery missing endpoints")),
        )
        assertEquals(
            CloudProviderGuard.Reason.OTHER,
            CloudProviderGuard.classify(IllegalArgumentException("xAI auth endpoint host must be x.ai")),
        )
    }

    @Test
    fun `kimi api key stays available when the oauth client id override is empty`() {
        assertEquals(
            CloudProviderGuard.Reason.READY,
            CloudProviderGuard.readiness(
                ProviderType.kimiCode,
                oauth = false,
                anthropicPrompt = "",
                kimiClientId = "",
            ),
        )
        assertEquals(
            CloudProviderGuard.Reason.NETWORK,
            CloudProviderGuard.classify(IOException("api.kimi.com")),
        )
    }

    @Test
    fun `kimi oauth empty client id is unavailable and the login throw is blank config`() {
        assertTrue(
            CloudProviderGuard.listUnavailable(
                ProviderType.kimiCode,
                oauth = true,
                anthropicPrompt = null,
                kimiClientId = "",
            ),
        )
        assertEquals(
            CloudProviderGuard.Reason.READY,
            CloudProviderGuard.readiness(
                ProviderType.kimiCode,
                oauth = true,
                anthropicPrompt = "",
                kimiClientId = "17e5f671-d194-4dfb-9706-5516cb48c098",
            ),
        )
        assertEquals(
            CloudProviderGuard.Reason.BLANK_BUILD_CONFIG,
            CloudProviderGuard.classify(Exception("Kimi login unavailable — client id override is empty")),
        )
    }

    @Test
    fun `github copilot has no build config and a missing session token is auth`() {
        assertReadyWithoutBuildConfig(ProviderType.githubCopilot, oauth = true)
        assertEquals(
            CloudProviderGuard.Reason.AUTH,
            CloudProviderGuard.classify(Exception("Copilot token exchange returned no usable token.")),
        )
        assertEquals(
            CloudProviderGuard.Reason.NETWORK,
            CloudProviderGuard.classify(OAuthNetworkUnreachableException(UnknownHostException("github.com"))),
        )
    }

    @Test
    fun `antigravity is unavailable in this build and its factory error is unsupported`() {
        assertEquals(
            CloudProviderGuard.Reason.UNSUPPORTED,
            CloudProviderGuard.readiness(ProviderType.antigravity, oauth = true, anthropicPrompt = ""),
        )
        assertEquals(
            CloudProviderGuard.Reason.UNSUPPORTED,
            CloudProviderGuard.readiness(ProviderType.unsupported, oauth = false, anthropicPrompt = "id"),
        )
        assertTrue(
            CloudProviderGuard.listUnavailable(ProviderType.antigravity, oauth = false, anthropicPrompt = null),
        )
        // ProviderFactory throws this for a type this build cannot drive.
        assertEquals(
            CloudProviderGuard.Reason.UNSUPPORTED,
            CloudProviderGuard.classify(LLMError.ProviderError("provider_unsupported")),
        )
    }

    @Test
    fun `missing model and token refresh and server errors stay classified`() {
        assertEquals(
            CloudProviderGuard.Reason.MISSING_MODEL,
            CloudProviderGuard.classify(
                LLMError.ProviderError("model_not_supported", httpStatus = 404),
            ),
        )
        assertEquals(
            CloudProviderGuard.Reason.TOKEN_REFRESH,
            CloudProviderGuard.classify(Exception("Token refresh transient error — keeping token")),
        )
        assertEquals(
            CloudProviderGuard.Reason.SERVER,
            CloudProviderGuard.classify(LLMError.TransientError("overloaded", httpStatus = 503)),
        )
        assertEquals(503, CloudProviderGuard.httpStatus(LLMError.TransientError("overloaded", httpStatus = 503)))
    }

    @Test
    fun `every provider type is covered by readiness`() {
        val seen = ProviderType.entries.map { type ->
            type to CloudProviderGuard.readiness(type, oauth = true, anthropicPrompt = "")
        }
        assertEquals(ProviderType.entries.size, seen.size)
        assertTrue(seen.all { it.second != CloudProviderGuard.Reason.OTHER })
    }

    @Test
    fun `english and korean strings exist for each surfaced reason`() {
        val res = java.io.File("src/main/res")
        val root = if (res.isDirectory) res else {
            generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
                .map { java.io.File(it, "src/android/app/src/main/res") }
                .first { it.isDirectory }
        }
        val keys = listOf(
            "chat_error_network",
            "chat_error_auth",
            "chat_error_rate_limited",
            "chat_error_server",
            "chat_error_bad_response",
            "chat_error_token_refresh",
            "chat_error_missing_model",
            "chat_error_provider_unsupported",
            "claude_oauth_not_configured",
            "claude_oauth_provider_unavailable",
        )
        val en = java.io.File(root, "values/strings.xml").readText()
        val ko = java.io.File(root, "values-ko/strings.xml").readText()
        for (key in keys) {
            assertTrue("missing en $key", en.contains("name=\"$key\""))
            assertTrue("missing ko $key", ko.contains("name=\"$key\""))
        }
    }

    private fun assertReadyWithoutBuildConfig(type: ProviderType, oauth: Boolean) {
        assertNull(CloudProviderGuard.requiredBuildKey(type, oauth))
        assertEquals(
            CloudProviderGuard.Reason.READY,
            CloudProviderGuard.readiness(type, oauth, anthropicPrompt = "", kimiClientId = null),
        )
        assertFalse(CloudProviderGuard.listUnavailable(type, oauth, anthropicPrompt = null))
    }
}
