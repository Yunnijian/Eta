package io.github.mangi.eta.agent.virtualscreen.mcp

import android.content.Context
import io.github.mangi.eta.agent.virtualscreen.VirtualScreenSessionManager
import io.github.mangi.eta.agent.virtualscreen.VirtualScreenTools
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenMcpHttpServerTest {
    private lateinit var context: Context
    private lateinit var server: VirtualScreenMcpHttpServer

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        server = VirtualScreenMcpHttpServer(context, requestedPort = 0, token = TOKEN)
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun healthNeedsNoToken() {
        val (status, body) = request("GET", "/health", null)
        assertEquals(200, status)
        assertTrue(JSONObject(body).getBoolean("ok"))
    }

    @Test
    fun toolsRequireTokenAndExposeVirtualScreenTools() {
        assertEquals(401, request("GET", "/tools", null).first)

        val (status, body) = request("GET", "/tools", TOKEN)
        assertEquals(200, status)
        val tools = JSONObject(body).getJSONArray("tools")
        val names = (0 until tools.length())
            .map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
            .toSet()
        assertEquals(VirtualScreenTools.names, names)
    }

    @Test
    fun toolCallRoutesToVirtualScreenStatusWithoutRoot() {
        val (status, body) = request(
            "POST",
            "/tool",
            TOKEN,
            """{"name":"virtual_display","arguments":{"action":"status"}}""",
        )

        assertEquals(200, status)
        val response = JSONObject(body)
        assertEquals(false, response.getBoolean("isError"))
        val content = JSONObject(response.getString("content"))
        assertTrue(content.getBoolean("ok"))
        assertEquals(false, content.getBoolean("running"))
    }

    @Test
    fun unknownToolIsRejected() {
        val (status, _) = request(
            "POST",
            "/tool",
            TOKEN,
            """{"name":"not_a_virtual_tool","arguments":{}}""",
        )

        assertEquals(404, status)
    }

    @Test
    fun mcpRequiresToken() {
        val (status, _) = request(
            "POST",
            "/mcp",
            null,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
        )

        assertEquals(401, status)
    }

    @Test
    fun mcpInitializeReturnsProtocolAndServerInfo() {
        val (status, body) = request(
            "POST",
            "/mcp",
            TOKEN,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""",
        )

        assertEquals(200, status)
        val response = JSONObject(body)
        assertEquals("2.0", response.getString("jsonrpc"))
        assertEquals(1, response.getInt("id"))
        val result = response.getJSONObject("result")
        assertEquals("2025-03-26", result.getString("protocolVersion"))
        assertEquals("eta-virtual-screen", result.getJSONObject("serverInfo").getString("name"))
    }

    @Test
    fun mcpToolsListExposesMcpSchema() {
        val (status, body) = request(
            "POST",
            "/mcp",
            TOKEN,
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
        )

        assertEquals(200, status)
        val tools = JSONObject(body).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertEquals(VirtualScreenTools.names, names)
        val schema = tools.getJSONObject(0).getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type"))
    }

    @Test
    fun mcpToolCallReturnsContentArray() {
        val (status, body) = request(
            "POST",
            "/mcp",
            TOKEN,
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"virtual_display","arguments":{"action":"status"}}}""",
        )

        assertEquals(200, status)
        val result = JSONObject(body).getJSONObject("result")
        assertEquals(false, result.getBoolean("isError"))
        val content = result.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(0).getString("text").contains("\"running\""))
    }

    @Test
    fun mcpUnknownMethodReturnsJsonRpcError() {
        val (status, body) = request(
            "POST",
            "/mcp",
            TOKEN,
            """{"jsonrpc":"2.0","id":4,"method":"not/a/method"}""",
        )

        assertEquals(200, status)
        assertEquals(-32601, JSONObject(body).getJSONObject("error").getInt("code"))
    }

    @Test
    fun maxScreensDefaultsAndClampsToSupportedRange() {
        assertEquals(VirtualScreenSessionManager.DEFAULT_MAX_SCREENS, VirtualScreenSessionManager.maxScreens(context))

        VirtualScreenSessionManager.setMaxScreens(context, 3)
        assertEquals(3, VirtualScreenSessionManager.maxScreens(context))

        VirtualScreenSessionManager.setMaxScreens(context, 99)
        assertEquals(VirtualScreenSessionManager.MAX_SCREENS_LIMIT, VirtualScreenSessionManager.maxScreens(context))

        VirtualScreenSessionManager.setMaxScreens(context, 0)
        assertEquals(1, VirtualScreenSessionManager.maxScreens(context))
    }

    private fun request(
        method: String,
        path: String,
        token: String?,
        body: String? = null,
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.port}$path")
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        connection.disconnect()
        return status to text
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
