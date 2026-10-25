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
)

/**
 * 单个虚拟屏会话：跨 Agent run 保留，显式 close 或 App 进程退出时销毁。
 * 引擎自己在 idleTimeoutMinutes 后释放显示；这里检测到 DISPLAY_GONE 后会清空状态。
 */
internal object VirtualScreenSessionManager {
    const val DEFAULT_IDLE_TIMEOUT_MINUTES = 10
    private const val PREFERENCES = "eta_virtual_screen"
    private const val IDLE_TIMEOUT_KEY = "session_timeout_minutes"
    private const val DEFAULT_SCREEN_KEY = "default_device_screen"

    /** AI 操作设备时优先使用的屏幕；只影响系统提示中的默认操作路径。 */
    enum class DefaultDeviceScreen(val value: String) {
        MAIN("main"),
        VIRTUAL("virtual"),
    }

    private val mutex = Mutex()
    private var host: VirtualScreenProcessHost? = null
    private var state: VirtualScreenState? = null

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

    /** 把新的空闲超时同步给已经在跑的引擎；没有会话时只保存设置。 */
    suspend fun applyIdleTimeout(minutes: Int): Boolean = mutex.withLock {
        val current = state ?: return@withLock false
        val activeHost = host ?: return@withLock false
        runCatching {
            activeHost.request(
                JSONObject()
                    .put("action", "set_idle_timeout")
                    .put("handleId", current.handleId)
                    .put("idleTimeoutMinutes", minutes),
                timeoutMs = 5_000,
            ).requireOk()
        }.onSuccess {
            state = current.copy(config = current.config.copy(idleTimeoutMinutes = minutes))
        }.isSuccess
    }

    suspend fun create(context: Context, config: VirtualScreenConfig = defaultConfig(context)): VirtualScreenState =
        mutex.withLock {
            state?.let { return@withLock it }
            val started = VirtualScreenProcessHost(context.applicationInfo.sourceDir).also { host = it }
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
            )
            state = created
            created
        }

    suspend fun close() {
        mutex.withLock {
            val current = state
            val activeHost = host
            state = null
            host = null
            if (current != null) {
                runCatching {
                    activeHost?.request(
                        JSONObject().put("action", "close").put("handleId", current.handleId),
                        timeoutMs = 10_000,
                    )
                }
            }
            activeHost?.close()
        }
    }

    suspend fun status(context: Context): JSONObject = mutex.withLock {
        val current = state
        if (current == null) {
            return@withLock JSONObject().put("running", false).put("backend", "root")
        }
        val activeHost = host
        val probe = runCatching {
            activeHost?.request(
                JSONObject().put("action", "probe").put("handleId", current.handleId),
                timeoutMs = 5_000,
            )?.requireOk()
        }.getOrNull()
        if (probe == null) {
            state = null
            host = null
            runCatching { activeHost?.close() }
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

    fun currentDisplayId(): Int? = state?.displayId

    suspend fun observe(context: Context): JSONObject = action(context) {
        JSONObject().put("action", "observe")
    }

    suspend fun launch(
        context: Context,
        component: String,
        uri: String?,
        restartApproved: Boolean,
    ): JSONObject = action(context, timeoutMs = 90_000) {
        JSONObject()
            .put("action", "launch")
            .put("component", component)
            .put("uri", uri.orEmpty())
            .put("restartApproved", restartApproved)
            .put("ownerPackage", context.packageName)
    }

    suspend fun tap(context: Context, x: Int, y: Int): JSONObject = action(context) {
        JSONObject().put("action", "tap").put("x", x).put("y", y)
    }

    suspend fun longPress(context: Context, x: Int, y: Int, durationMs: Int): JSONObject = action(context) {
        JSONObject().put("action", "long_press").put("x", x).put("y", y).put("durationMs", durationMs)
    }

    suspend fun swipe(context: Context, points: List<Pair<Int, Int>>, durationMs: Int): JSONObject = action(context) {
        JSONObject()
            .put("action", "swipe")
            .put("points", org.json.JSONArray(points.map { listOf(it.first, it.second) }))
            .put("durationMs", durationMs)
    }

    suspend fun key(context: Context, keyCode: Int): JSONObject = action(context) {
        JSONObject().put("action", "key").put("keyCode", keyCode)
    }

    suspend fun text(context: Context, value: String, replace: Boolean): JSONObject = action(context) {
        JSONObject().put("action", "text").put("value", value).put("replace", replace)
    }

    private suspend fun action(
        context: Context,
        timeoutMs: Long = 30_000,
        payload: () -> JSONObject,
    ): JSONObject = mutex.withLock {
        val current = state ?: throw VirtualScreenException("NO_VIRTUAL_SCREEN", "请先创建虚拟屏")
        val activeHost = host ?: throw VirtualScreenException("NO_VIRTUAL_SCREEN", "请先创建虚拟屏")
        try {
            activeHost.request(
                payload().put("handleId", current.handleId),
                timeoutMs = timeoutMs,
            )
        } catch (failure: VirtualScreenException) {
            if (failure.code in RECOVERABLE_CODES) {
                state = null
                host = null
                runCatching { activeHost.close() }
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
