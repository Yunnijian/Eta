package io.github.mangi.eta.agent.virtualscreen.mcp

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本机 JSON 桥：供 Linux 侧的 stdio MCP 脚本转发工具目录与调用。
 *
 * 默认只绑定回环地址；开启局域网访问后绑定 0.0.0.0。所有请求都校验
 * Bearer Token（/health 除外），工具调用串行执行，避免多个宿主争用同一块虚拟屏。
 *
 * 除自定义的 /tools、/tool 外，还提供标准 MCP Streamable HTTP 入口（POST /mcp），
 * 便于任意 MCP 客户端直接接入。
 */
internal class VirtualScreenMcpHttpServer(
    context: Context,
    requestedPort: Int,
    private val token: String,
    bindAddress: String = "127.0.0.1",
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val tools = AgentLocalTools(
        context = appContext,
        logger = AndroidAgentLogger,
        browserRunId = "virtual-screen-mcp",
        browserToolsEnabled = { false },
        terminalToolsEnabled = { false },
    )
    private val sessionId = UUID.randomUUID().toString()
    private val mcpTools: JSONArray by lazy(::buildMcpTools)
    private val server = ServerSocket().apply {
        reuseAddress = true
        // 默认显式绑定 IPv4 回环：getLoopbackAddress() 在部分机型返回 ::1，
        // 而 Linux 侧配置固定使用 127.0.0.1。开启局域网访问时改绑 0.0.0.0。
        bind(InetSocketAddress(InetAddress.getByName(bindAddress), requestedPort))
    }
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eta-vs-mcp-http").apply { isDaemon = true }
    }
    private val acceptThread = Thread({ acceptLoop() }, "eta-vs-mcp-accept").apply {
        isDaemon = true
    }
    @Volatile
    private var closed = false

    val port: Int get() = server.localPort

    val isRunning: Boolean get() = !closed && !server.isClosed

    fun start() {
        acceptThread.start()
    }

    private fun acceptLoop() {
        while (!closed && !server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            executor.execute { socket.use(::handleConnection) }
        }
    }

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(output, 400, errorBody("BAD_REQUEST"))
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?').trimEnd('/').ifBlank { "/" }
        val queryToken = target.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }

        if (method == "GET" && path == "/health") {
            return respond(output, 200, JSONObject().put("ok", true).put("port", port).toString())
        }
        if (headers["authorization"] != "Bearer $token" && queryToken != token) {
            return respond(output, 401, errorBody("UNAUTHORIZED"))
        }

        when {
            method == "POST" && (path == "/mcp" || path == "/") -> handleMcp(input, headers, output)
            method == "GET" && path == "/mcp" -> respond(output, 405, errorBody("SSE_UNSUPPORTED"), mcpHeaders())
            method == "DELETE" && path == "/mcp" -> respond(output, 200, "{}", mcpHeaders())
            method == "GET" && path == "/tools" -> {
                val array = JSONArray()
                VirtualScreenMcpTools.openAiSchemas().let { schemas ->
                    for (index in 0 until schemas.length()) array.put(schemas.getJSONObject(index))
                }
                respond(output, 200, JSONObject().put("tools", array).toString())
            }
            method == "POST" && path == "/tool" -> handleTool(input, headers, output)
            else -> respond(output, 404, errorBody("NOT_FOUND"))
        }
    }

    private fun handleTool(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY_BYTES) {
            return respond(output, 413, errorBody("BODY_TOO_LARGE"))
        }
        val body = readExactly(input, length).toString(Charsets.UTF_8)
        val request = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(output, 400, errorBody("INVALID_JSON"))
        val name = request.optString("name")
        if (name !in VirtualScreenMcpTools.names) {
            return respond(output, 404, errorBody("UNKNOWN_TOOL"))
        }
        val arguments = request.optJSONObject("arguments") ?: JSONObject()
        val result = runCatching {
            executeTool(name, arguments)
        }.getOrElse { failure ->
            return respond(
                output,
                200,
                JSONObject()
                    .put("content", failure.message ?: failure.javaClass.simpleName)
                    .put("images", JSONArray())
                    .put("isError", true)
                    .toString(),
            )
        }
        val content = runCatching { JSONObject(result.content) }.getOrNull()
        val images = JSONArray().also { array ->
            result.images.forEach { image ->
                dataUrlBase64(image.reference)?.let { data ->
                    array.put(
                        JSONObject()
                            .put("mimeType", image.mimeType)
                            .put("data", data),
                    )
                }
            }
        }
        respond(
            output,
            200,
            JSONObject()
                .put("content", result.content)
                .put("images", images)
                .put("isError", content?.optBoolean("ok", true) == false)
                .toString(),
        )
    }

    /** 标准 MCP Streamable HTTP：JSON-RPC 2.0，请求-响应式，不提供服务器推送。 */
    private fun handleMcp(input: InputStream, headers: Map<String, String>, output: OutputStream) {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY_BYTES) {
            return respond(output, 413, errorBody("BODY_TOO_LARGE"), mcpHeaders())
        }
        val text = readExactly(input, length).toString(Charsets.UTF_8)
        val payload = runCatching { JSONObject(text) }.getOrNull()
            ?: return respond(output, 200, rpcError(null, -32700, "Parse error").toString(), mcpHeaders())
        if (!payload.has("id")) return respond(output, 202, "", mcpHeaders())
        val id = payload.opt("id")
        val method = payload.optString("method")
        val params = payload.optJSONObject("params") ?: JSONObject()
        val body = when (method) {
            "initialize" -> rpcResult(
                id,
                JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion").ifBlank { MCP_PROTOCOL_VERSION })
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "eta-virtual-screen").put("version", "1.0.0")),
            )
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> rpcResult(id, JSONObject().put("tools", mcpTools))
            "tools/call" -> handleMcpToolCall(id, params)
            "resources/list" -> rpcResult(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> rpcResult(id, JSONObject().put("prompts", JSONArray()))
            else -> rpcError(id, -32601, "Method not found: $method")
        }
        respond(output, 200, body.toString(), mcpHeaders())
    }

    private fun handleMcpToolCall(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name !in VirtualScreenMcpTools.names) {
            return rpcError(id, -32602, "Unknown tool: $name")
        }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val result = runCatching { executeTool(name, arguments) }.getOrElse { failure ->
            return rpcResult(
                id,
                JSONObject()
                    .put("content", JSONArray().put(textContent(failure.message ?: failure.javaClass.simpleName)))
                    .put("isError", true),
            )
        }
        val content = JSONArray().put(textContent(result.content))
        result.images.forEach { image ->
            dataUrlBase64(image.reference)?.let { data ->
                content.put(
                    JSONObject().put("type", "image").put("data", data).put("mimeType", image.mimeType),
                )
            }
        }
        val isError = runCatching { JSONObject(result.content).optBoolean("ok", true) }
            .getOrDefault(true)
            .not()
        return rpcResult(id, JSONObject().put("content", content).put("isError", isError))
    }

    /** 直接走 AgentLocalTools 的执行链：MCP 与 App 内 Agent 使用同一套工具实现。 */
    private fun executeTool(name: String, arguments: JSONObject): AgentModelClient.ToolResult =
        tools.execute(
            AgentModelClient.ToolCall(
                id = "mcp-${UUID.randomUUID()}",
                name = name,
                argumentsJson = arguments.toString(),
            ),
        )

    private fun buildMcpTools(): JSONArray {
        val openAiStyle = VirtualScreenMcpTools.openAiSchemas()
        val converted = JSONArray()
        for (index in 0 until openAiStyle.length()) {
            val function = openAiStyle.getJSONObject(index).optJSONObject("function") ?: continue
            converted.put(
                JSONObject()
                    .put("name", function.optString("name"))
                    .put("description", function.optString("description"))
                    .put(
                        "inputSchema",
                        function.optJSONObject("parameters")
                            ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                    ),
            )
        }
        return converted
    }

    private fun textContent(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    private fun rpcResult(id: Any?, result: Any): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun mcpHeaders(): Map<String, String> = mapOf("Mcp-Session-Id" to sessionId)

    private fun dataUrlBase64(reference: String): String? {
        if (!reference.startsWith("data:", ignoreCase = true)) return null
        val marker = reference.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return null
        return reference.substring(marker + "base64,".length).takeIf(String::isNotBlank)
    }

    private fun respond(
        output: OutputStream,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        runCatching {
            output.write(header)
            output.write(bytes)
            output.flush()
        }
    }

    private fun errorBody(code: String): String =
        JSONObject().put("ok", false).put("code", code).toString()

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) bytes else bytes.copyOf(offset)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { tools.close() }
        runCatching { server.close() }
        executor.shutdownNow()
        runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 120_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MCP_PROTOCOL_VERSION = "2025-06-18"
    }
}
