package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
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

internal data class DshWebRuntimeStatus(
    val taskId: String? = null,
    val running: Boolean = false,
    val url: String? = null,
    val code: String? = null,
)

/** 取得启动期前台引用，常驻 `dsh web` 后把带 token 的本机地址交给系统浏览器。 */
internal class DshWebLauncher(
    private val context: Context,
    private val daemonSupervisor: DetachedTaskSupervisor,
) {
    private val session = DshWebSession(
        tasks = object : DshWebSession.Tasks {
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

    suspend fun launch(environment: TerminalEnvironment): DshWebLaunchResult = launchMutex.withLock {
        withContext(Dispatchers.IO) {
            val distribution = environment.linuxDistribution ?: return@withContext DshWebLaunchResult.Failed("INVALID_ENVIRONMENT")
            val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) return@withContext DshWebLaunchResult.Failed("LINUX_ENVIRONMENT_NOT_READY")
            val identity = TerminalRuntime.defaultIdentity(environment, rootfs.absolutePath)
            val job = currentCoroutineContext().job
            if (identity == "user" && !AgentExecutionService.acquire(context, LAUNCH_ID) { job.cancel() }) {
                return@withContext DshWebLaunchResult.Failed("BACKGROUND_START_NOT_ALLOWED")
            }
            try {
                currentCoroutineContext().ensureActive()
                session.launch(
                    environment = environment,
                    identity = identity,
                    backend = LinuxEnvironmentPaths.backendOf(rootfs.absolutePath),
                    command = DshWebSession.COMMAND,
                )
            } finally {
                if (identity == "user") AgentExecutionService.release(LAUNCH_ID)
            }
        }
    }

    suspend fun status(environment: TerminalEnvironment): DshWebRuntimeStatus = withContext(Dispatchers.IO) {
        val distribution = environment.linuxDistribution ?: return@withContext DshWebRuntimeStatus(code = "INVALID_ENVIRONMENT")
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.path)) return@withContext DshWebRuntimeStatus(code = "LINUX_ENVIRONMENT_NOT_READY")
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs.path)
        if (identity == "root" && !TerminalRuntime.rootAvailable) return@withContext DshWebRuntimeStatus(code = "ROOT_REQUIRED")
        val matches = daemonSupervisor.list().filter {
            it.task.environment == environment && it.task.identity == identity &&
                it.task.backend == LinuxEnvironmentPaths.backendOf(rootfs.path) &&
                DshWebSession.matchesCommand(it.task.command)
        }
        val task = matches.lastOrNull { it.running } ?: matches.lastOrNull()
            ?: return@withContext DshWebRuntimeStatus()
        if (!task.running) return@withContext DshWebRuntimeStatus(taskId = task.task.id, code = "DSH_EXITED")
        val logs = daemonSupervisor.readLogs(task.task.id)
        DshWebRuntimeStatus(
            taskId = task.task.id,
            running = true,
            url = if (logs.ok) DshWebSession.serverAddressFromLogs(logs.text) else null,
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
        const val LAUNCH_ID = "dsh-launch"
    }
}
