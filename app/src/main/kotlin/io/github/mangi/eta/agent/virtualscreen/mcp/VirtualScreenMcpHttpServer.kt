package io.github.mangi.eta.agent.virtualscreen.mcp

import android.content.Context
import io.github.mangi.eta.agent.model.AgentVirtualScreenToolCatalog
import io.github.mangi.eta.agent.virtualscreen.VirtualScreenTools
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本机 JSON 桥：供 Linux 侧的 stdio MCP 脚本转发工具目录与调用。
 *
 * 默认只绑定回环地址；开启局域网访问后绑定 0.0.0.0。所有请求都校验
 * Bearer Token（/health 除外），工具调用串行执行，避免多个宿主争用同一块虚拟屏。
 */
internal class VirtualScreenMcpHttpServer(
    context: Context,
    requestedPort: Int,
    private val token: String,
    bindAddress: String = "127.0.0.1",
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val tools = VirtualScreenTools(appContext)
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
        val path = parts[1].substringBefore('?')
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
        if (headers["authorization"] != "Bearer $token") {
            return respond(output, 401, errorBody("UNAUTHORIZED"))
        }

        when {
            method == "GET" && path == "/tools" -> {
                val array = JSONArray()
                AgentVirtualScreenToolCatalog.appendTo(array)
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
        if (name !in VirtualScreenTools.names) {
            return respond(output, 404, errorBody("UNKNOWN_TOOL"))
        }
        val arguments = request.optJSONObject("arguments") ?: JSONObject()
        val result = runCatching {
            tools.execute(name, arguments)
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
                .put("isError", content?.optBoolean("ok") == false)
                .toString(),
        )
    }

    private fun dataUrlBase64(reference: String): String? {
        if (!reference.startsWith("data:", ignoreCase = true)) return null
        val marker = reference.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return null
        return reference.substring(marker + "base64,".length).takeIf(String::isNotBlank)
    }

    private fun respond(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Internal Server Error"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
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
        runCatching { server.close() }
        executor.shutdownNow()
        runCatching { executor.awaitTermination(1, TimeUnit.SECONDS) }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 120_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
    }
}
