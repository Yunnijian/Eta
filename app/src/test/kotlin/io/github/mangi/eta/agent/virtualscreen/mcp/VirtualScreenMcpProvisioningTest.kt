package io.github.mangi.eta.agent.virtualscreen.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenMcpProvisioningTest {
    private val endpoint = VirtualScreenMcpRuntime.Endpoint(port = 18765, token = "test-token")

    @Test
    fun dshProfileEntryContainsStdioClientAndBridgeEnv() {
        val entry = VirtualScreenMcpProvisioning.dshProfileEntryYaml(endpoint)

        assertTrue(entry.contains("name: '@deepseek-ai/dsh-mcp-client'"))
        assertTrue(entry.contains("serverName: eta"))
        assertTrue(entry.contains("transport: stdio"))
        assertTrue(entry.contains("args: ['/workspace/.eta/eta-mcp-bridge.mjs']"))
        assertTrue(entry.contains("ETA_MCP_URL: 'http://127.0.0.1:18765'"))
        assertTrue(entry.contains("ETA_MCP_AUTH: 'test-token'"))
    }

    @Test
    fun kimiConfigKeepsOtherServersAndAddsEta() {
        val existing = JSONObject(
            """{"mcpServers":{"other":{"command":"other-mcp","args":[]}}}""",
        )

        val merged = JSONObject(VirtualScreenMcpProvisioning.kimiConfigJson(existing, endpoint))
        val servers = merged.getJSONObject("mcpServers")

        assertEquals("other-mcp", servers.getJSONObject("other").getString("command"))
        val eta = servers.getJSONObject("eta")
        assertEquals("node", eta.getString("command"))
        assertEquals(
            "/workspace/.eta/eta-mcp-bridge.mjs",
            eta.getJSONArray("args").getString(0),
        )
        assertEquals(
            "http://127.0.0.1:18765",
            eta.getJSONObject("env").getString("ETA_MCP_URL"),
        )
        assertEquals("test-token", eta.getJSONObject("env").getString("ETA_MCP_AUTH"))
    }

    @Test
    fun openCodeConfigKeepsOtherKeysAndAddsLocalServer() {
        val existing = JSONObject("""{"theme":"dark","mcp":{"servers":{"other":{"type":"local"}}}}""")

        val merged = JSONObject(
            VirtualScreenMcpProvisioning.openCodeConfigJson(existing, endpoint),
        )
        val eta = merged.getJSONObject("mcp").getJSONObject("servers").getJSONObject("eta")

        assertEquals("dark", merged.getString("theme"))
        assertEquals(
            "local",
            merged.getJSONObject("mcp").getJSONObject("servers").getJSONObject("other")
                .getString("type"),
        )
        assertEquals("local", eta.getString("type"))
        assertEquals("node", eta.getJSONArray("command").getString(0))
        assertEquals(
            "/workspace/.eta/eta-mcp-bridge.mjs",
            eta.getJSONArray("command").getString(1),
        )
        assertEquals("test-token", eta.getJSONObject("environment").getString("ETA_MCP_AUTH"))
    }
}
