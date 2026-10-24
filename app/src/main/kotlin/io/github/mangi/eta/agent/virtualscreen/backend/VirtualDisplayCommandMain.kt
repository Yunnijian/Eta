package io.github.mangi.eta.agent.virtualscreen.backend

import android.os.Process
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 由 Eta 的 Root 执行器启动的虚拟显示引擎入口。
 *
 * 只接受 JSON 行协议；stdin EOF（App 进程退出）时销毁自己持有的 VirtualDisplay。
 */
internal object VirtualDisplayCommandMain {
    const val MARKER = "ETA_VIRTUAL_SCREEN_RESULT:"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != 0) {
            reply(JSONObject().put("ok", false).put("code", "ROOT_REQUIRED"))
            kotlin.system.exitProcess(1)
        }
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        val engine = VirtualDisplayEngine()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = System.`in`.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > MAX_LINE_CHARS) {
                    reply(JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT"))
                    continue
                }
                val request = runCatching { JSONObject(line) }.getOrElse {
                    reply(JSONObject().put("ok", false).put("code", "INVALID_ARGUMENT"))
                    continue
                }
                if (request.optString("action") == "cancel") {
                    engine.cancel()
                    continue
                }
                executor.execute {
                    val result = runCatching {
                        if (request.optString("action") == "create") engine.create(request)
                        else engine.execute(request)
                    }.getOrElse { failure ->
                        val cause = rootCause(failure)
                        JSONObject()
                            .put("ok", false)
                            .put("code", cause.message?.takeIf { it.matches(CODE_REGEX) } ?: "VIRTUAL_SCREEN_OPERATION_FAILED")
                            .put("detail", (cause.javaClass.simpleName + ": " + cause.message.orEmpty()).take(300))
                    }
                    reply(result)
                }
            }
        } finally {
            engine.close()
            executor.shutdownNow()
            kotlin.system.exitProcess(0)
        }
    }

    private fun reply(value: JSONObject) {
        synchronized(System.out) {
            println(MARKER + value)
            System.out.flush()
        }
    }

    private fun rootCause(failure: Throwable): Throwable =
        if (failure is InvocationTargetException) failure.targetException else failure

    private const val MAX_LINE_CHARS = 128_000
    private val CODE_REGEX = Regex("[A-Z0-9_]+")
}
