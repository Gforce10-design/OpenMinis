package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.debug.HeadlessChatRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA fixes OM-09 (a turn that ends in a provider error is a failed run) and OM-13 (raw network failures retry). */
class QaFixesOm09Om13Test {
    private fun msg(role: String, error: String? = null) = ChatMessage(id = role + error, role = role, content = "x", error = error)

    @Test
    fun `turn error is read from the assistant reply after the last user message`() {
        val err = "The provider rejected the credentials (HTTP 401)."
        assertEquals(err, HeadlessChatRunner.turnErrorOf(listOf(msg("user"), msg("assistant", err))))
        assertNull(HeadlessChatRunner.turnErrorOf(listOf(msg("user"), msg("assistant"))))
        // An error from an earlier turn is not blamed on the latest one.
        assertNull(HeadlessChatRunner.turnErrorOf(listOf(msg("user"), msg("assistant", err), msg("user"), msg("assistant"))))
        assertNull(HeadlessChatRunner.turnErrorOf(emptyList()))
        assertNull(HeadlessChatRunner.turnErrorOf(listOf(msg("user"), msg("assistant", "  "))))
    }

    @Test
    fun `a completed run with a turn error counts as failed`() {
        val ok = HeadlessChatRunner.PromptResult("Completed", "hi", timedOut = false)
        val bad = HeadlessChatRunner.PromptResult("Completed", null, timedOut = false, turnError = "HTTP 401")
        assertFalse(ok.failed)
        assertTrue(bad.failed)
        assertTrue(HeadlessChatRunner.PromptResult("Timeout", null, timedOut = true).failed)
        assertTrue(HeadlessChatRunner.PromptResult("Error", "x", timedOut = false).failed)
    }

    @Test
    fun `raw connectivity exceptions are mapped to NetworkError before the retry decision`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("val actual = mapRawNetworkFailure(unwrapFlowException(e))"))
        val fn = vm.substringAfter("private fun mapRawNetworkFailure(").substringBefore("private fun dynamicMaxTokens(")
        listOf("UnknownHostException", "ConnectException", "SocketTimeoutException").forEach {
            assertTrue(it, fn.contains("java.net.$it"))
        }
        assertTrue(fn.contains("LLMError.NetworkError(cause)"))
    }
}
