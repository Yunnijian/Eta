package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.agent.virtualscreen.mcp.VirtualScreenMcpProvisioning
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.InstallerShellRunner
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class OpenCodeWebRuntimeStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val url: String? = null,
    val code: String? = null,
)

internal sealed interface OpenCodeWebLaunchResult {
    data class Opened(val url: String) : OpenCodeWebLaunchResult
    data class Failed(val code: String) : OpenCodeWebLaunchResult
}

/** 取得启动期前台引用，抓取本机端口后用一次性配对链接完成鉴权并交给系统浏览器。 */
internal class OpenCodeWebLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    private val session = OpenCodeWebSession(
        tasks = object : OpenCodeWebSession.Tasks {
            override fun list() = daemonSupervisor.list()
            override fun start(
                environment: TerminalEnvironment,
                identity: String,
                command: String,
            ) = daemonSupervisor.start(
                command = command, cwd = "/workspace", identity = identity, environment = environment,
            )
            override fun logs(id: String) = daemonSupervisor.readLogs(id)
            override fun stop(id: String) { daemonSupervisor.stop(id) }
        },
        pairing = object : OpenCodeWebSession.Pairing {
            override suspend fun link(environment: TerminalEnvironment, identity: String, port: Int): String? {
                val distribution = environment.linuxDistribution ?: return null
                val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
                val result = InstallerShellRunner.run(
                    command = OpenCodeWebSession.pairCommand(port),
                    timeoutSeconds = PAIR_TIMEOUT_SECONDS,
                    environment = environment,
                    linuxRootfsPath = rootfs.absolutePath,
                    identity = identity,
                )
                if (result.exitCode != 0) return null
                return OpenCodeWebSession.pairLinkFromOutput(result.output)
            }
        },
        openUrl = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: android.content.ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )

    suspend fun launch(environment: TerminalEnvironment): OpenCodeWebLaunchResult = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val distribution = environment.linuxDistribution ?: return@withContext OpenCodeWebLaunchResult.Failed("INVALID_ENVIRONMENT")
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) return@withContext OpenCodeWebLaunchResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs.absolutePath)
            val job = currentCoroutineContext().job
            if (identity == "user" && !AgentExecutionService.acquire(context, LAUNCH_ID) { job.cancel() }) {
                return@withContext OpenCodeWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED")
            }
            try {
                currentCoroutineContext().ensureActive()
                runCatching {
                    VirtualScreenMcpProvisioning.provision(
                        context = context,
                        environment = environment,
                        identity = identity,
                        rootfs = rootfs,
                    )
                }.onFailure {
                    AndroidAgentLogger.warn("Virtual screen MCP provisioning failed: ${it.javaClass.simpleName}")
                }
                session.launch(
                    environment = environment,
                    identity = identity,
                    backend = LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
                    command = OpenCodeWebSession.COMMAND,
                )
            } finally {
                if (identity == "user") AgentExecutionService.release(LAUNCH_ID)
            }
        }
    }

    suspend fun status(environment: TerminalEnvironment): OpenCodeWebRuntimeStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution ?: return@withContext OpenCodeWebRuntimeStatus(code = "INVALID_ENVIRONMENT")
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext OpenCodeWebRuntimeStatus(code = "LINUX_ENVIRONMENT_NOT_READY")
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        if (identity == "root" && !TerminalRuntime.rootAvailable) return@withContext OpenCodeWebRuntimeStatus(code = "ROOT_REQUIRED")
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == LinuxEnvironmentPaths.backendOf(rootfs.path) &&
                OpenCodeWebSession.matchesCommand(it.task.command)
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext OpenCodeWebRuntimeStatus()
        if (!task.running) return@withContext OpenCodeWebRuntimeStatus(taskId = task.task.id, code = "OPENCODE_EXITED")
        val logs = daemonSupervisor.readLogs(task.task.id)
        OpenCodeWebRuntimeStatus(
            taskId = task.task.id,
            running = true,
            url = if (logs.ok) OpenCodeWebSession.serverAddressFromLogs(logs.text) else null,
            code = if (logs.ok) null else logs.code,
        )
    }

    suspend fun stop(environment: TerminalEnvironment): Boolean = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val status = status(environment)
            status.taskId?.let(daemonSupervisor::stop) ?: false
        }
    }

    private companion object {
        val launchMutex = Mutex()
        const val LAUNCH_ID = "opencode-launch"
        const val PAIR_TIMEOUT_SECONDS = 15L
    }
}
