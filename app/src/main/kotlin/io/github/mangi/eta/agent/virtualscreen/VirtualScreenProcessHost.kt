package io.github.mangi.eta.agent.virtualscreen

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 持有一条 root `app_process` 长驻引擎，stdin/stdout 逐行 JSON。
 *
 * 引擎在 stdin EOF 时销毁虚拟屏；这里的 close 会先做一次显式 close，再关闭管道。
 */
internal class VirtualScreenProcessHost(private val apkPath: String) {
    private val mutex = Mutex()
    private val readExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "eta-virtual-screen-read").apply { isDaemon = true }
    }
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Volatile private var closed = false

    suspend fun request(payload: JSONObject, timeoutMs: Long = 30_000): JSONObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureStarted()
            val input = writer ?: throw VirtualScreenException("ENGINE_NOT_STARTED")
            val output = reader ?: throw VirtualScreenException("ENGINE_NOT_STARTED")
            try {
                input.write(payload.toString())
                input.newLine()
                input.flush()
            } catch (failure: Exception) {
                close()
                throw VirtualScreenException("ENGINE_PIPE_CLOSED", failure.message)
            }
            val future: Future<JSONObject> = readExecutor.submit(Callable { readResponse(output) })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                close()
                throw VirtualScreenException("VIRTUAL_SCREEN_TIMEOUT")
            } catch (failure: Exception) {
                close()
                val cause = failure.cause ?: failure
                if (cause is VirtualScreenException) throw cause
                throw VirtualScreenException("ENGINE_PIPE_CLOSED", cause.message)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val child = process
        runCatching { writer?.close() }
        writer = null
        reader = null
        child?.let {
            runCatching { if (!it.waitFor(1_500, TimeUnit.MILLISECONDS)) it.destroy() }
            runCatching { if (!it.waitFor(500, TimeUnit.MILLISECONDS)) it.destroyForcibly() }
        }
        process = null
        readExecutor.shutdownNow()
    }

    private fun ensureStarted() {
        if (closed) throw VirtualScreenException("ENGINE_CLOSED")
        val existing = process
        if (existing != null && existing.isAlive) return
        closeProcessOnly()
        val mode = detectSuMode()
        val command = "CLASSPATH=${quote(apkPath)} app_process /system/bin " +
            "io.github.mangi.eta.agent.virtualscreen.backend.VirtualDisplayCommandMain"
        val child = try {
            ProcessBuilder(*mode.arguments(command)).redirectErrorStream(false).start()
        } catch (failure: Exception) {
            throw VirtualScreenException("ENGINE_START_FAILED", failure.message)
        }
        process = child
        writer = child.outputStream.bufferedWriter()
        reader = child.inputStream.bufferedReader()
        Thread {
            runCatching {
                child.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (line.isNotBlank()) lastError = line.take(300) }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun readResponse(output: BufferedReader): JSONObject {
        while (true) {
            val line = output.readLine() ?: throw VirtualScreenException(
                "ENGINE_EXITED",
                lastError,
            )
            if (!line.startsWith(VIRTUAL_SCREEN_MARKER)) continue
            return runCatching { JSONObject(line.removePrefix(VIRTUAL_SCREEN_MARKER)) }.getOrElse {
                throw VirtualScreenException("INVALID_ENGINE_RESPONSE")
            }
        }
    }

    @Volatile
    private var lastError: String? = null

    private fun closeProcessOnly() {
        runCatching { writer?.close() }
        writer = null
        reader = null
        process?.let {
            runCatching { it.destroy() }
            runCatching { it.destroyForcibly() }
        }
        process = null
    }

    private fun detectSuMode(): SuCommandMode {
        val modes = SuCommandMode.entries
        for (mode in modes) {
            if (probeUid(mode, "0")) return mode
        }
        throw VirtualScreenException("ROOT_REQUIRED")
    }

    private fun probeUid(mode: SuCommandMode, uid: String): Boolean = runCatching {
        val child = ProcessBuilder(*mode.arguments("id -u", uid)).redirectErrorStream(true).start()
        val finished = child.waitFor(5, TimeUnit.SECONDS)
        if (!finished) child.destroyForcibly()
        finished && child.exitValue() == 0 &&
            child.inputStream.bufferedReader().use { it.readText().trim() } == uid
    }.getOrDefault(false)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /** 引擎不响应 close 时，按 PID 直接强杀；进程退出后系统会释放它持有的虚拟显示。 */
        fun forceKill(enginePid: Int) {
            if (enginePid <= 0) return
            SuCommandMode.entries.forEach { mode ->
                runCatching {
                    val child = ProcessBuilder(*mode.arguments("kill -9 $enginePid"))
                        .redirectErrorStream(true)
                        .start()
                    if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly()
                }
            }
        }
    }
}

private enum class SuCommandMode {
    C_FLAG,
    UID_COMMAND;

    fun arguments(command: String, uid: String = "0"): Array<String> = when (this) {
        C_FLAG -> if (uid == "0") arrayOf("su", "-c", command) else arrayOf("su", uid, "-c", command)
        UID_COMMAND -> arrayOf("su", uid, "sh", "-c", command)
    }
}
