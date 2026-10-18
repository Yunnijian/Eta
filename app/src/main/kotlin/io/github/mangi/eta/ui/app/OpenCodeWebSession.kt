package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.delay

/**
 * OpenCode 启动和复用的事务边界：只有本次创建且未打开浏览器的任务会被回收。
 *
 * 与 Kimi 直接从日志里读出带 token 的地址不同，OpenCode 的 Web UI 需要一次性配对链接才能完成鉴权：
 * 守护任务只暴露本机服务，拿到端口后还要用 `opencode pair` 换取可直开的链接。
 */
internal class OpenCodeWebSession(
    private val tasks: Tasks,
    private val pairing: Pairing,
    private val openUrl: (String) -> Boolean,
    private val waitAttempts: Int = 30,
    private val waitIntervalMs: Long = 500,
    private val pairAttempts: Int = 3,
    private val pairIntervalMs: Long = 500,
) {
    interface Tasks {
        fun list(): List<DetachedTaskStatus>
        fun start(environment: TerminalEnvironment, identity: String): DaemonStartResult
        fun logs(id: String): DaemonLogsResult
        fun stop(id: String)
    }

    /** 在服务就绪后换取一次性配对链接；返回 null 表示本次尝试失败，可重试。 */
    interface Pairing {
        suspend fun link(environment: TerminalEnvironment, identity: String, port: Int): String?
    }

    suspend fun launch(
        environment: TerminalEnvironment,
        identity: String,
        backend: LinuxExecutionBackend,
    ): OpenCodeWebLaunchResult {
        var createdTaskId: String? = null
        var opened = false
        try {
            val existing = tasks.list().firstOrNull {
                it.running && it.task.environment == environment && it.task.identity == identity &&
                    it.task.backend == backend && matchesCommand(it.task.command)
            }
            val taskId = existing?.task?.id ?: when (val started = tasks.start(environment, identity)) {
                is DaemonStartResult.Started -> started.task.id.also { createdTaskId = it }
                is DaemonStartResult.Failed -> return OpenCodeWebLaunchResult.Failed(started.code)
            }
            repeat(waitAttempts) {
                val status = tasks.list().firstOrNull { it.task.id == taskId }
                if (status == null || !status.running) return OpenCodeWebLaunchResult.Failed("OPENCODE_EXITED")
                val logs = tasks.logs(taskId)
                if (!logs.ok) return OpenCodeWebLaunchResult.Failed(logs.code.ifBlank { "LOGS_UNAVAILABLE" })
                val port = portFromLogs(logs.text)
                if (port != null) {
                    // 服务刚起来时未必能立刻配对，短重试几次再判定失败。
                    val link = awaitPairLink(environment, identity, port)
                        ?: return OpenCodeWebLaunchResult.Failed("PAIR_FAILED")
                    opened = openUrl(link)
                    return if (opened) {
                        OpenCodeWebLaunchResult.Opened(link)
                    } else {
                        OpenCodeWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
                    }
                }
                delay(waitIntervalMs)
            }
            return OpenCodeWebLaunchResult.Failed("URL_TIMEOUT")
        } finally {
            if (!opened) createdTaskId?.let(tasks::stop)
        }
    }

    private suspend fun awaitPairLink(
        environment: TerminalEnvironment,
        identity: String,
        port: Int,
    ): String? {
        repeat(pairAttempts) { attempt ->
            val link = pairing.link(environment, identity, port)
            if (link != null) return link
            if (attempt < pairAttempts - 1) delay(pairIntervalMs)
        }
        return null
    }

    companion object {
        /** 必须带 `--service`（否则配对返回 UnauthorizedError）并显式绑回环，避免把 Web UI 暴露到局域网。 */
        const val COMMAND = "opencode serve --service --hostname 127.0.0.1 --port 4096"

        fun matchesCommand(command: String): Boolean = command.trim() == COMMAND

        fun pairCommand(port: Int): String = "opencode pair --url http://127.0.0.1:$port --yes"

        /** 守护任务在 "server listening on http://127.0.0.1:<port>" 那行报出端口。 */
        fun portFromLogs(text: String): Int? = listeningLine(text)
            ?.let { PORT_REGEX.find(it)?.groups?.get("port")?.value?.toIntOrNull() }

        /** 状态展示用的本机服务地址；可直开的链接需配对后才存在。 */
        fun serverAddressFromLogs(text: String): String? = listeningLine(text)
            ?.let { SERVER_URL_REGEX.find(it)?.value }

        fun pairLinkFromOutput(text: String): String? = PAIR_LINK_REGEX.find(text)?.value

        private fun listeningLine(text: String): String? =
            text.lineSequence().firstOrNull { it.contains("server listening on") }

        private val PORT_REGEX = Regex("""http://127\.0\.0\.1:(?<port>\d+)""")
        private val SERVER_URL_REGEX = Regex("""http://127\.0\.0\.1:\d+""")
        // 配对 code 字符集收紧到 URL safe，避免把日志里的 ANSI 序列尾巴吃进来。
        private val PAIR_LINK_REGEX = Regex("""http://127\.0\.0\.1:\d+/auth/connect/[A-Za-z0-9_\-]+""")
    }
}
