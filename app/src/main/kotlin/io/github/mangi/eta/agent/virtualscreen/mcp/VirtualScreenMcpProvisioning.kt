package io.github.mangi.eta.agent.virtualscreen.mcp

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.terminal.InstallerShellRunner
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 stdio MCP 桥和宿主配置写进各自的持久化配置文件。
 *
 * 不再依赖启动命令参数或环境变量：DSH 的 cordis.patch.yml、OpenCode 的
 * global opencode.json、Kimi 的 project mcp.json 都由 Eta 自动合并。
 */
internal object VirtualScreenMcpProvisioning {
    private const val WORKSPACE = "/workspace"
    private const val BRIDGE_PATH = "$WORKSPACE/.eta/eta-mcp-bridge.mjs"
    private const val DSH_PROFILE_PATCH = "/root/.dsh/profiles/web/cordis.patch.yml"
    private const val OPENCODE_CONFIG = "/root/.config/opencode/opencode.json"
    private const val KIMI_CONFIG = "$WORKSPACE/.kimi-code/mcp.json"
    private const val BRIDGE_ASSET = "virtual-screen/eta-mcp-bridge.mjs"
    private const val WRITE_TIMEOUT_SECONDS = 30L
    private val BASE64_LINE = Regex("^[A-Za-z0-9+/=]{16,}$")

    suspend fun provision(
        context: Context,
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
    ): VirtualScreenMcpRuntime.Endpoint? {
        AndroidAgentLogger.info(
            "Virtual screen MCP provisioning start env=${environment.wireName} identity=$identity",
        )
        val endpoint = VirtualScreenMcpRuntime.ensureStarted(context) ?: return null
        val bridge = runCatching {
            context.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        if (!writeFile(environment, identity, rootfs, BRIDGE_PATH, bridge)) return null

        val dsh = runCatching {
            appendDshEntry(environment, identity, rootfs, endpoint)
        }.onFailure {
            AndroidAgentLogger.warn("Virtual screen MCP DSH config failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        val openCode = runCatching {
            mergeOpenCodeConfig(environment, identity, rootfs, endpoint)
        }.onFailure {
            AndroidAgentLogger.warn("Virtual screen MCP OpenCode config failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        val kimi = runCatching {
            mergeKimiConfig(environment, identity, rootfs, endpoint)
        }.onFailure {
            AndroidAgentLogger.warn("Virtual screen MCP Kimi config failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        AndroidAgentLogger.info(
            "Virtual screen MCP provisioned env=${environment.wireName} dsh=$dsh " +
                "opencode=$openCode kimi=$kimi",
        )
        return endpoint
    }

    fun dshProfileEntryYaml(endpoint: VirtualScreenMcpRuntime.Endpoint): String =
        "\n" + """
            - insert:
                - id: eta-virtual-screen
                  name: '@deepseek-ai/dsh-mcp-client'
                  config:
                    serverName: eta
                    transport: stdio
                    command: node
                    args: ['$BRIDGE_PATH']
                    env:
                      ETA_MCP_URL: '${endpoint.url}'
                      ETA_MCP_AUTH: '${endpoint.token}'
                    toolCallTimeoutMs: 120000
        """.trimIndent() + "\n"

    fun openCodeConfigJson(
        existing: JSONObject?,
        endpoint: VirtualScreenMcpRuntime.Endpoint,
    ): String {
        val root = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
        val mcp = root.optJSONObject("mcp") ?: JSONObject()
        val servers = mcp.optJSONObject("servers") ?: JSONObject()
        servers.put(
            "eta",
            JSONObject()
                .put("type", "local")
                .put("command", JSONArray().put("node").put(BRIDGE_PATH))
                .put(
                    "environment",
                    JSONObject()
                        .put("ETA_MCP_URL", endpoint.url)
                        .put("ETA_MCP_AUTH", endpoint.token),
                ),
        )
        mcp.put("servers", servers)
        root.put("mcp", mcp)
        return root.toString()
    }

    fun kimiConfigJson(
        existing: JSONObject?,
        endpoint: VirtualScreenMcpRuntime.Endpoint,
    ): String {
        val root = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
        val servers = root.optJSONObject("mcpServers") ?: JSONObject()
        servers.put(
            "eta",
            JSONObject()
                .put("command", "node")
                .put("args", JSONArray().put(BRIDGE_PATH))
                .put(
                    "env",
                    JSONObject()
                        .put("ETA_MCP_URL", endpoint.url)
                        .put("ETA_MCP_AUTH", endpoint.token),
                ),
        )
        root.put("mcpServers", servers)
        return root.toString()
    }

    private suspend fun appendDshEntry(
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
        endpoint: VirtualScreenMcpRuntime.Endpoint,
    ): Boolean {
        val content = dshProfileEntryYaml(endpoint)
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "mkdir -p ${shellQuote(DSH_PROFILE_PATCH.substringBeforeLast('/'))} && " +
            "touch ${shellQuote(DSH_PROFILE_PATCH)} && " +
            "if ! grep -q 'eta-virtual-screen' ${shellQuote(DSH_PROFILE_PATCH)} 2>/dev/null; then " +
            "printf %s '$encoded' | base64 -d >> ${shellQuote(DSH_PROFILE_PATCH)}; fi; " +
            "chmod 600 ${shellQuote(DSH_PROFILE_PATCH)} 2>/dev/null || true; " +
            "test -s ${shellQuote(DSH_PROFILE_PATCH)}"
        return runShell(environment, identity, rootfs, command)
    }

    private suspend fun mergeOpenCodeConfig(
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
        endpoint: VirtualScreenMcpRuntime.Endpoint,
    ): Boolean {
        val existing = readFile(environment, identity, rootfs, OPENCODE_CONFIG)
            ?.let(::parseJsonObject)
        return writeFile(
            environment,
            identity,
            rootfs,
            OPENCODE_CONFIG,
            openCodeConfigJson(existing, endpoint),
        )
    }

    private suspend fun mergeKimiConfig(
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
        endpoint: VirtualScreenMcpRuntime.Endpoint,
    ): Boolean {
        val existing = readFile(environment, identity, rootfs, KIMI_CONFIG)?.let(::parseJsonObject)
        return writeFile(
            environment,
            identity,
            rootfs,
            KIMI_CONFIG,
            kimiConfigJson(existing, endpoint),
        )
    }

    private fun parseJsonObject(text: String): JSONObject? {
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        runCatching { JSONObject(stripJsonComments(text)) }.getOrNull()?.let { return it }
        return null
    }

    private fun stripJsonComments(input: String): String {
        val output = StringBuilder(input.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < input.length) {
            val character = input[index]
            if (inString) {
                output.append(character)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                index++
                continue
            }
            if (character == '"') {
                inString = true
                output.append(character)
                index++
                continue
            }
            if (character == '/' && index + 1 < input.length && input[index + 1] == '/') {
                while (index < input.length && input[index] != '\n') index++
                continue
            }
            if (character == '/' && index + 1 < input.length && input[index + 1] == '*') {
                index += 2
                while (index + 1 < input.length &&
                    !(input[index] == '*' && input[index + 1] == '/')
                ) {
                    index++
                }
                index += 2
                continue
            }
            output.append(character)
            index++
        }
        return output.toString().replace(Regex(",(\\s*[}\\]])"), "$1")
    }

    private suspend fun readFile(
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
        path: String,
    ): String? {
        val command = "if [ -f ${shellQuote(path)} ]; then base64 ${shellQuote(path)} | tr -d '\\n'; fi"
        val result = runCatching {
            InstallerShellRunner.run(
                command = command,
                timeoutSeconds = WRITE_TIMEOUT_SECONDS,
                environment = environment,
                linuxRootfsPath = rootfs.absolutePath,
                identity = identity,
            )
        }.getOrNull() ?: return null
        if (result.exitCode != 0) return null
        val encoded = result.output.lineSequence()
            .map(String::trim)
            .filter(BASE64_LINE::matches)
            .lastOrNull()
            ?: return null
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun writeFile(
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
        path: String,
        content: String,
    ): Boolean {
        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val directory = path.substringBeforeLast('/')
        val command = "mkdir -p ${shellQuote(directory)} && " +
            "printf %s '$encoded' | base64 -d > ${shellQuote(path)} && " +
            "chmod 600 ${shellQuote(path)} 2>/dev/null || true; " +
            "test -s ${shellQuote(path)}"
        return runShell(environment, identity, rootfs, command)
    }

    private suspend fun runShell(
        environment: TerminalEnvironment,
        identity: String,
        rootfs: File,
        command: String,
    ): Boolean {
        val result = runCatching {
            InstallerShellRunner.run(
                command = command,
                timeoutSeconds = WRITE_TIMEOUT_SECONDS,
                environment = environment,
                linuxRootfsPath = rootfs.absolutePath,
                identity = identity,
            )
        }.getOrElse { failure ->
            AndroidAgentLogger.warn("Virtual screen MCP shell failed: ${failure.javaClass.simpleName}")
            return false
        }
        if (result.exitCode != 0) {
            AndroidAgentLogger.warn(
                "Virtual screen MCP shell exit=${result.exitCode} outputChars=${result.output.length}",
            )
        }
        return result.exitCode == 0
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
