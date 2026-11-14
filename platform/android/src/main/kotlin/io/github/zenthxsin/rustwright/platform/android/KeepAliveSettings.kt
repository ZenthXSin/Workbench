package io.github.zenthxsin.rustwright.platform.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.zenthxsin.rustwright.core.runtime.KeepAliveItem
import io.github.zenthxsin.rustwright.core.runtime.RwLog

/**
 * 后台保活要用户打开的几项，以及打开它们的系统页面。
 * 悬浮窗 + 不限制电池 + 通知；厂商的「自启动 / 后台管理」查不到状态，不再列出。
 */
internal object KeepAliveSettings {
    const val OVERLAY = "overlay"
    const val BATTERY = "battery"
    const val NOTIFY = "notify"

    fun items(ctx: Context): List<KeepAliveItem> = buildList {
        add(KeepAliveItem(OVERLAY, "悬浮窗", "后台时任务不被冻结",
            BackgroundKeeper.canDrawOverlays(ctx), essential = true))
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            add(KeepAliveItem(BATTERY, "不限制电池", "锁屏后任务不暂停", pm.isIgnoringBatteryOptimizations(ctx.packageName), essential = true))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(KeepAliveItem(NOTIFY, "通知", "通知栏显示任务进度",
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
        }
    }

    fun open(activity: Activity, id: String) {
        val pkg = Uri.parse("package:${activity.packageName}")
        val tries: List<Intent> = when (id) {
            OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
            BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            NOTIFY -> {
                if (Build.VERSION.SDK_INT >= 33 && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) &&
                    activity.getSharedPreferences("keepalive", 0).getBoolean("notifyAsked", false).not()
                ) {
                    activity.getSharedPreferences("keepalive", 0).edit().putBoolean("notifyAsked", true).apply()
                    activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                    return
                }
                listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            else -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        for (intent in tries) {
            val ok = runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) return
        }
        RwLog.w("keepalive", "打不开 $id 的设置页")
    }
}
