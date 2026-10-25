package io.github.mangi.eta.agent.virtualscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import io.github.mangi.eta.agent.virtualscreen.backend.VirtualScreenConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class VirtualScreenState(
    val displayId: Int,
    val handleId: String,
    val config: VirtualScreenConfig,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val enginePid: Int = 0,
)

/**
 * 虚拟屏会话池：按会话键隔离，各自持有独立的 Root 引擎进程。
 * 同时存在的屏幕数受设置项「虚拟屏数量」限制，显式 close 或 App 进程退出时销毁。
 * 引擎自己在 idleTimeoutMinutes 后释放显示；这里检测到 DISPLAY_GONE 后清空对应会话。
 */
internal object VirtualScreenSessionManager {
    const val DEFAULT_IDLE_TIMEOUT_MINUTES = 10
    const val DEFAULT_MAX_SCREENS = 1
    const val MAX_SCREENS_LIMIT = 4
    private const val PREFERENCES = "eta_virtual_screen"
    private const val IDLE_TIMEOUT_KEY = "session_timeout_minutes"
    private const val DEFAULT_SCREEN_KEY = "default_device_screen"
    private const val MAX_SCREENS_KEY = "max_screens"

    /** AI 操作设备时优先使用的屏幕；只影响系统提示中的默认操作路径。 */
    enum class DefaultDeviceScreen(val value: String) {
        MAIN("main"),
        VIRTUAL("virtual"),
    }

    private class Session(
        val host: VirtualScreenProcessHost,
        var state: VirtualScreenState,
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    fun idleTimeoutMinutes(context: Context): Int = preferences(context)
        .getInt(IDLE_TIMEOUT_KEY, DEFAULT_IDLE_TIMEOUT_MINUTES)

    fun defaultDeviceScreen(context: Context): DefaultDeviceScreen {
        val stored = preferences(context).getString(DEFAULT_SCREEN_KEY, null)
        return DefaultDeviceScreen.entries.firstOrNull { it.value == stored } ?: DefaultDeviceScreen.MAIN
    }

    fun setDefaultDeviceScreen(context: Context, screen: DefaultDeviceScreen) {
        preferences(context).edit().putString(DEFAULT_SCREEN_KEY, screen.value).apply()
    }

    fun setIdleTimeoutMinutes(context: Context, minutes: Int) {
        preferences(context).edit().putInt(IDLE_TIMEOUT_KEY, minutes.coerceIn(0, 240)).apply()
    }

    fun maxScreens(context: Context): Int = preferences(context)
        .getInt(MAX_SCREENS_KEY, DEFAULT_MAX_SCREENS)
        .coerceIn(1, MAX_SCREENS_LIMIT)

    fun setMaxScreens(context: Context, count: Int) {
        preferences(context).edit().putInt(MAX_SCREENS_KEY, count.coerceIn(1, MAX_SCREENS_LIMIT)).apply()
    }

    /** 把新的空闲超时同步给所有在跑的引擎；没有会话时只保存设置。 */
    suspend fun applyIdleTimeout(minutes: Int): Boolean = mutex.withLock {
        var applied = false
        sessions.values.forEach { session ->
            val current = session.state
            val acknowledged = runCatching {
                session.host.request(
                    JSONObject()
                        .put("action", "set_idle_timeout")
                        .put("handleId", current.handleId)
                        .put("idleTimeoutMinutes", minutes),
                    timeoutMs = 5_000,
                ).requireOk()
                true
            }.getOrDefault(false)
            if (acknowledged) {
                session.state = current.copy(config = current.config.copy(idleTimeoutMinutes = minutes))
                applied = true
            }
        }
        applied
    }

    suspend fun create(
        context: Context,
        key: String,
        config: VirtualScreenConfig = defaultConfig(context),
    ): VirtualScreenState = mutex.withLock {
        sessions[key]?.state?.let { return@withLock it }
        val limit = maxScreens(context)
        if (sessions.size >= limit) {
            throw VirtualScreenException(
                "VIRTUAL_SCREEN_LIMIT_REACHED",
                "同时最多 $limit 块虚拟屏，请先关闭不再使用的会话，或在设置中调高虚拟屏数量",
            )
        }
        val started = VirtualScreenProcessHost(context.applicationInfo.sourceDir)
        try {
            val response = started.request(
                JSONObject()
                    .put("action", "create")
                    .put("width", config.width)
                    .put("height", config.height)
                    .put("density", config.density)
                    .put("rotation", config.rotation)
                    .put("allowScreenOff", config.allowScreenOff)
                    .put("idleTimeoutMinutes", config.idleTimeoutMinutes)
                    .put("ownerPackage", context.packageName)
                    .put("ownerApk", context.applicationInfo.sourceDir),
                timeoutMs = 30_000,
            ).requireOk()
            val created = VirtualScreenState(
                displayId = response.getInt("displayId"),
                handleId = response.getString("handleId"),
                config = config,
                width = response.getInt("width"),
                height = response.getInt("height"),
                rotation = response.getInt("rotation"),
                enginePid = response.optInt("enginePid", 0),
            )
            sessions[key] = Session(started, created)
            created
        } catch (failure: Throwable) {
            // 创建过程中失败（超时、响应丢失）时可能已在引擎里建好显示，
            // 必须连带终止进程释放它，避免留下 App 侧无法再寻址的孤儿虚拟屏。
            runCatching { started.close() }
            throw failure
        }
    }

    suspend fun close(key: String) {
        mutex.withLock {
            val session = sessions.remove(key) ?: return@withLock
            val current = session.state
            val acknowledged = runCatching {
                session.host.request(
                    JSONObject().put("action", "close").put("handleId", current.handleId),
                    timeoutMs = 10_000,
                ).requireOk()
                true
            }.getOrDefault(false)
            session.host.close()
            // 引擎被长任务占住时不会响应 close；直接按 PID 强杀，进程退出即释放虚拟显示。
            if (!acknowledged) VirtualScreenProcessHost.forceKill(current.enginePid)
        }
    }

    suspend fun status(context: Context, key: String): JSONObject = mutex.withLock {
        val session = sessions[key]
            ?: return@withLock JSONObject().put("running", false).put("backend", "root")
        val current = session.state
        val probe = runCatching {
            session.host.request(
                JSONObject().put("action", "probe").put("handleId", current.handleId),
                timeoutMs = 5_000,
            ).requireOk()
        }.getOrNull()
        if (probe == null) {
            sessions.remove(key)
            runCatching { session.host.close() }
            return@withLock JSONObject().put("running", false).put("backend", "root")
        }
        return@withLock JSONObject()
            .put("running", true)
            .put("backend", "root")
            .put("display_id", current.displayId)
            .put("width", current.width)
            .put("height", current.height)
            .put("rotation", current.rotation)
            .put("interactive", probe.optBoolean("interactive", true))
    }

    fun currentDisplayId(key: String): Int? = sessions[key]?.state?.displayId

    suspend fun observe(context: Context, key: String): JSONObject = action(context, key) {
        JSONObject().put("action", "observe")
    }

    suspend fun launch(
        context: Context,
        key: String,
        component: String,
        uri: String?,
        restartApproved: Boolean,
    ): JSONObject = action(context, key, timeoutMs = 90_000) {
        JSONObject()
            .put("action", "launch")
            .put("component", component)
            .put("uri", uri.orEmpty())
            .put("restartApproved", restartApproved)
            .put("ownerPackage", context.packageName)
    }

    suspend fun tap(context: Context, key: String, x: Int, y: Int): JSONObject = action(context, key) {
        JSONObject().put("action", "tap").put("x", x).put("y", y)
    }

    suspend fun longPress(context: Context, key: String, x: Int, y: Int, durationMs: Int): JSONObject =
        action(context, key) {
            JSONObject().put("action", "long_press").put("x", x).put("y", y).put("durationMs", durationMs)
        }

    suspend fun swipe(
        context: Context,
        key: String,
        points: List<Pair<Int, Int>>,
        durationMs: Int,
    ): JSONObject = action(context, key) {
        JSONObject()
            .put("action", "swipe")
            .put("points", org.json.JSONArray(points.map { listOf(it.first, it.second) }))
            .put("durationMs", durationMs)
    }

    suspend fun key(context: Context, key: String, keyCode: Int): JSONObject = action(context, key) {
        JSONObject().put("action", "key").put("keyCode", keyCode)
    }

    suspend fun text(context: Context, key: String, value: String, replace: Boolean): JSONObject =
        action(context, key) {
            JSONObject().put("action", "text").put("value", value).put("replace", replace)
        }

    private suspend fun action(
        context: Context,
        key: String,
        timeoutMs: Long = 30_000,
        payload: () -> JSONObject,
    ): JSONObject = mutex.withLock {
        val session = sessions[key]
            ?: throw VirtualScreenException("NO_VIRTUAL_SCREEN", "请先创建虚拟屏")
        val current = session.state
        try {
            session.host.request(
                payload().put("handleId", current.handleId),
                timeoutMs = timeoutMs,
            )
        } catch (failure: VirtualScreenException) {
            if (failure.code in RECOVERABLE_CODES) {
                sessions.remove(key)
                runCatching { session.host.close() }
            }
            throw failure
        }
    }

    fun defaultConfig(context: Context): VirtualScreenConfig {
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val fallback = context.resources.displayMetrics
        val width = (if (metrics.widthPixels > 0) metrics.widthPixels else fallback.widthPixels).coerceIn(320, 4096)
        val height = (if (metrics.heightPixels > 0) metrics.heightPixels else fallback.heightPixels).coerceIn(480, 4096)
        val density = (if (metrics.densityDpi > 0) metrics.densityDpi else fallback.densityDpi).coerceIn(120, 640)
        return VirtualScreenConfig(
            width = width,
            height = height,
            density = density,
            idleTimeoutMinutes = idleTimeoutMinutes(context),
        )
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val RECOVERABLE_CODES = setOf("DISPLAY_GONE", "STALE_VIRTUAL_SCREEN", "ENGINE_EXITED", "ENGINE_PIPE_CLOSED")
}
