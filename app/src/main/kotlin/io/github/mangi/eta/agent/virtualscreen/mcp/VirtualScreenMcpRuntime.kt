package io.github.mangi.eta.agent.virtualscreen.mcp

import android.content.Context
import android.content.Intent
import android.util.Base64
import io.github.mangi.eta.core.AndroidAgentLogger
import java.security.SecureRandom

/** Eta 内置虚拟屏的对外桥端点；端口与 Token 都由 Eta 自己维护，用户无需配置。 */
internal object VirtualScreenMcpRuntime {
    const val DEFAULT_PORT = 18765
    private const val PREFERENCES = "eta_virtual_screen_mcp"
    private const val TOKEN_KEY = "token"

    data class Endpoint(val port: Int, val token: String) {
        val url: String get() = "http://127.0.0.1:$port"
    }

    @Volatile
    private var server: VirtualScreenMcpHttpServer? = null

    fun endpoint(context: Context): Endpoint = Endpoint(DEFAULT_PORT, token(context))

    fun ensureStarted(context: Context): Endpoint? {
        val endpoint = endpoint(context)
        val active = ensureServer(context) ?: return null
        runCatching {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, VirtualScreenMcpService::class.java),
            )
        }.onFailure { AndroidAgentLogger.warn("Virtual screen MCP service start failed: ${it.javaClass.simpleName}") }
        return if (active.port == endpoint.port) endpoint else null
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

    private fun token(context: Context): String {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
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
