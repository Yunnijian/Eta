package io.github.mangi.eta.agent.virtualscreen.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

/** 常驻前台服务，保证外部 Agent 调用虚拟屏时 Eta 进程不被回收。 */
internal class VirtualScreenMcpService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure {
            stopSelf()
            return
        }
        VirtualScreenMcpRuntime.ensureServer(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VirtualScreenMcpRuntime.ensureServer(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        VirtualScreenMcpRuntime.stopServer()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "虚拟屏外部接入", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("虚拟屏 MCP 已启用")
            .setContentText("DeepSeek Harness、OpenCode、KimiCode 可调用 Eta 虚拟屏")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val CHANNEL = "eta_virtual_screen_mcp"
        const val NOTIFICATION_ID = 4210
    }
}
