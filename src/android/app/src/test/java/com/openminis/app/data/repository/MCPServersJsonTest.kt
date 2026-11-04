package com.openminis.app.data.repository

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * servers.json is shared with the in-guest CLI and with an iOS backup of the
 * same file. The CLI replaces it atomically. iOS keeps an `updatedAt` epoch
 * seconds clock on each entry for last-write-wins. A rewrite that tore the
 * file, or dropped that clock, lost servers or let a stale peer win.
 */
@RunWith(RobolectricTestRunner::class)
class MCPServersJsonTest {

    private fun dir(): File =
        File(RuntimeEnvironment.getApplication().filesDir, "minis-global/mcp-servers")

    private fun file(): File = File(dir().apply { mkdirs() }, "servers.json")

    private fun entry(name: String): JSONObject =
        JSONObject(file().readText()).getJSONObject("mcpServers").getJSONObject(name)

    @Test
    fun `assigning a missing createdAt keeps the iOS updatedAt clock`() {
        file().writeText(
            """
            {"mcpServers":{
              "fs":{"command":"npx","args":["srv"],"enabled":true,"updatedAt":1700000000}
            }}
            """.trimIndent(),
        )
        val repo = MCPRepository(RuntimeEnvironment.getApplication())
        val loaded = repo.servers.value.single()
        assertEquals("fs", loaded.id)
        assertEquals(1700000000L, loaded.updatedAt)
        assertTrue("createdAt was filled in", entry("fs").has("createdAt"))
        assertEquals(1700000000L, entry("fs").getLong("updatedAt"))
        assertEquals("npx", entry("fs").getString("command"))
        assertTrue(
            "temp file must not be left behind",
            dir().listFiles().orEmpty().none { it.name.contains(".tmp") },
        )
    }

    @Test
    fun `a local edit stamps updatedAt in epoch seconds and keeps the rest`() {
        file().writeText(
            """
            {"mcpServers":{
              "fs":{"command":"npx","args":["srv"],"enabled":true,"createdAt":1690000000000,"updatedAt":1700000000}
            }}
            """.trimIndent(),
        )
        val repo = MCPRepository(RuntimeEnvironment.getApplication())
        val before = System.currentTimeMillis() / 1000
        assertTrue(repo.update(repo.servers.value.single().copy(note = "kept")))
        val written = entry("fs")
        assertEquals("kept", written.getString("note"))
        assertEquals("npx", written.getString("command"))
        assertEquals(1690000000000L, written.getLong("createdAt"))
        assertTrue(written.getLong("updatedAt") >= before)
        assertTrue(written.getLong("updatedAt") < before + 10)
        assertFalse(written.getLong("updatedAt") > 10_000_000_000L)
    }

    @Test
    fun `a non-numeric updatedAt does not fail the load`() {
        file().writeText(
            """{"mcpServers":{"fs":{"command":"npx","updatedAt":"soon","createdAt":1690000000000}}}""",
        )
        val repo = MCPRepository(RuntimeEnvironment.getApplication())
        assertEquals("fs", repo.servers.value.single().id)
        assertEquals(null, repo.servers.value.single().updatedAt)
    }
}
