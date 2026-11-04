package com.openminis.app.provider

import android.content.Context
import com.openminis.app.R

/**
 * Localized in-chat and settings copy for [CloudProviderGuard.classify].
 * English and Korean live in `values/strings.xml` and `values-ko/strings.xml`.
 */
object ProviderFailureText {
    fun of(context: Context, error: Throwable): String {
        val reason = CloudProviderGuard.classify(error)
        val status = CloudProviderGuard.httpStatus(error)
        return when (reason) {
            CloudProviderGuard.Reason.READY,
            CloudProviderGuard.Reason.OTHER,
            -> error.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.error_empty_response_generic)
            CloudProviderGuard.Reason.BLANK_BUILD_CONFIG ->
                context.getString(R.string.claude_oauth_not_configured)
            CloudProviderGuard.Reason.UNSUPPORTED ->
                context.getString(R.string.chat_error_provider_unsupported)
            CloudProviderGuard.Reason.NETWORK ->
                context.getString(R.string.chat_error_network)
            CloudProviderGuard.Reason.AUTH ->
                context.getString(R.string.chat_error_auth, status ?: 401)
            CloudProviderGuard.Reason.RATE_LIMIT ->
                context.getString(R.string.chat_error_rate_limited)
            CloudProviderGuard.Reason.SERVER ->
                context.getString(R.string.chat_error_server, status ?: 500)
            CloudProviderGuard.Reason.JSON ->
                context.getString(R.string.chat_error_bad_response)
            CloudProviderGuard.Reason.TOKEN_REFRESH ->
                context.getString(R.string.chat_error_token_refresh)
            CloudProviderGuard.Reason.MISSING_MODEL ->
                context.getString(R.string.chat_error_missing_model)
        }
    }
}
