package io.github.mangi.eta.agent.virtualscreen.mcp

import android.content.Context
import android.content.Intent
import android.util.Base64
import io.github.mangi.eta.core.AndroidAgentLogger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** Eta 内置虚拟屏的对外桥端点；端口与 Token 都由 Eta 自己维护，用户无需配置。 */
internal object VirtualScreenMcpRuntime {
    const val DEFAULT_PORT = 18765
    private const val PREFERENCES = "eta_virtual_screen_mcp"
    private const val TOKEN_KEY = "token"
    private const val LAN_ENABLED_KEY = "lan_enabled"
    private const val SERVICE_ENABLED_KEY = "service_enabled"

    data class Endpoint(val port: Int, val token: String) {
        val url: String get() = "http://127.0.0.1:$port"
    }

    @Volatile
    private var server: VirtualScreenMcpHttpServer? = null

    fun endpoint(context: Context): Endpoint = Endpoint(DEFAULT_PORT, token(context))

    fun isLanEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(LAN_ENABLED_KEY, false)

    /** 开关意图持久化，App 冷启动时据此恢复服务。 */
    fun isServiceEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(SERVICE_ENABLED_KEY, false)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(SERVICE_ENABLED_KEY, enabled).apply()
    }

    /** 切换监听地址需要重建 ServerSocket；已经运行时立即重启，未运行时只保存设置。 */
    fun setLanEnabled(context: Context, enabled: Boolean): Endpoint? {
        preferences(context).edit().putBoolean(LAN_ENABLED_KEY, enabled).apply()
        val wasRunning = server?.isRunning == true
        if (!wasRunning) return null
        stopServer()
        return ensureStarted(context)
    }

    fun lanAddress(): String? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val candidates = interfaces.filter { it.isUp && !it.isLoopback }.mapNotNull { network ->
            network.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.let { address -> network.name to address.hostAddress }
        }
        return candidates.firstOrNull { it.first == "wlan0" }?.second
            ?: candidates.firstOrNull()?.second
    }

    fun activeEndpoint(context: Context): Endpoint? =
        server?.takeIf { it.isRunning }?.let { Endpoint(it.port, token(context)) }

    fun stop(context: Context) {
        runCatching {
            context.applicationContext.stopService(
                Intent(context.applicationContext, VirtualScreenMcpService::class.java),
            )
        }
        stopServer()
        setServiceEnabled(context, false)
    }

    fun ensureStarted(context: Context): Endpoint? {
        val endpoint = endpoint(context)
        val active = ensureServer(context) ?: return null
        runCatching {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, VirtualScreenMcpService::class.java),
            )
        }.onFailure { AndroidAgentLogger.warn("Virtual screen MCP service start failed: ${it.javaClass.simpleName}") }
        return if (active.port == endpoint.port) {
            setServiceEnabled(context, true)
            endpoint
        } else {
            null
        }
    }

    fun ensureServer(context: Context): VirtualScreenMcpHttpServer? {
        server?.let { return it }
        synchronized(this) {
            server?.let { return it }
            return runCatching {
                VirtualScreenMcpHttpServer(
                    context = context.applicationContext,
                    requestedPort = DEFAULT_PORT,
                    token = token(context),
                    bindAddress = if (isLanEnabled(context)) "0.0.0.0" else "127.0.0.1",
                ).also {
                    it.start()
                    server = it
                    AndroidAgentLogger.info("Virtual screen MCP endpoint started port=${it.port}")
                }
            }.getOrElse { failure ->
                AndroidAgentLogger.warn("Virtual screen MCP endpoint unavailable: ${failure.javaClass.simpleName}")
                null
            }
        }
    }

    fun stopServer() {
        synchronized(this) {
            server?.close()
            server = null
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun token(context: Context): String {
        val preferences = preferences(context)
        preferences.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        preferences.edit().putString(TOKEN_KEY, token).apply()
        return token
    }
}
