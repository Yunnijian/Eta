package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.terminal.DaemonLogsResult
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import kotlinx.coroutines.delay

/**
 * DeepSeek Harness 的启动事务边界：只有本次创建且未打开浏览器的任务会被回收。
 *
 * `dsh web` 自己托管前端并在启动时打印带一次性 token 的本机地址，因此无需像 OpenCode
 * 那样再执行一次配对命令；抓到该地址即可直接交给系统浏览器。
 */
internal class DshWebSession(
    private val tasks: Tasks,
    private val openUrl: (String) -> Boolean,
    private val waitAttempts: Int = 60,
    private val waitIntervalMs: Long = 500,
) {
    interface Tasks {
        fun list(): List<DetachedTaskStatus>
        fun start(environment: TerminalEnvironment, identity: String): DaemonStartResult
        fun logs(id: String): DaemonLogsResult
        fun stop(id: String)
    }

    suspend fun launch(
        environment: TerminalEnvironment,
        identity: String,
        backend: LinuxExecutionBackend,
    ): DshWebLaunchResult {
        var createdTaskId: String? = null
        var opened = false
        try {
            val existing = tasks.list().firstOrNull {
                it.running && it.task.environment == environment && it.task.identity == identity &&
                    it.task.backend == backend && matchesCommand(it.task.command)
            }
            val taskId = existing?.task?.id ?: when (val started = tasks.start(environment, identity)) {
                is DaemonStartResult.Started -> started.task.id.also { createdTaskId = it }
                is DaemonStartResult.Failed -> return DshWebLaunchResult.Failed(started.code)
            }
            repeat(waitAttempts) {
                val status = tasks.list().firstOrNull { it.task.id == taskId }
                if (status == null || !status.running) return DshWebLaunchResult.Failed("DSH_EXITED")
                val logs = tasks.logs(taskId)
                if (!logs.ok) return DshWebLaunchResult.Failed(logs.code.ifBlank { "LOGS_UNAVAILABLE" })
                val link = linkFromLogs(logs.text)
                if (link != null) {
                    opened = openUrl(link)
                    return if (opened) {
                        DshWebLaunchResult.Opened(link)
                    } else {
                        DshWebLaunchResult.Failed("BROWSER_UNAVAILABLE")
                    }
                }
                delay(waitIntervalMs)
            }
            return DshWebLaunchResult.Failed("URL_TIMEOUT")
        } finally {
            if (!opened) createdTaskId?.let(tasks::stop)
        }
    }

    companion object {
        /**
         * 显式绑回环，避免把 Web UI 暴露到局域网；proot 里没有 bwrap/systemd，
         * 沙箱降级为完全放行。端口交给系统分配，实际地址从启动日志读取。
         */
        const val COMMAND =
            "DSH_TELEMETRY_DISABLED=1 DSH_PERMISSION_MODE=danger-full-access " +
                "dsh web --no-open --host 127.0.0.1 --port 0"

        fun matchesCommand(command: String): Boolean = command.trim() == COMMAND

        /** 启动时打印 `dsh web: http://127.0.0.1:<port>/?token=<token>`。 */
        fun linkFromLogs(text: String): String? = TOKEN_LINK_REGEX.find(text)?.value

        /** 状态展示用的本机服务地址；不含一次性 token。 */
        fun serverAddressFromLogs(text: String): String? = SERVER_URL_REGEX.find(text)?.value

        private val TOKEN_LINK_REGEX = Regex("""http://127\.0\.0\.1:\d+/\?token=[A-Za-z0-9_\-]+""")
        private val SERVER_URL_REGEX = Regex("""http://127\.0\.0\.1:\d+""")
    }
}

internal sealed interface DshWebLaunchResult {
    data class Opened(val url: String) : DshWebLaunchResult
    data class Failed(val code: String) : DshWebLaunchResult
}
