package io.github.zenthxsin.rustwright.platform.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：助手回合、生图等进行中时保持进程，切到后台也不被冻结。
 * 没有通知权限时通知不显示，但服务照常运行。由 [BackgroundKeeper] 启停。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "正在后台处理" }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "后台任务", NotificationManager.IMPORTANCE_LOW))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let { android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE) }
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Rustwright")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        // 系统不让转前台时（后台启动受限）别崩：停掉自己，悬浮窗出现后 BackgroundKeeper 会再启动
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    /** Android 15 起 dataSync 有时长上限，到时必须停止。 */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    companion object {
        private const val CHANNEL = "background"
        private const val ID = 7
        const val EXTRA_LABEL = "label"
    }
}
