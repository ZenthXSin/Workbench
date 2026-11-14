package io.github.zenthxsin.rustwright.platform.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 崩溃报告页（独立进程、纯 Android 控件，不依赖 Arc / GL，保证崩溃后一定能显示）。
 * 打开即自动复制到剪贴板。
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReporter.pending(this) ?: "没有崩溃记录。"
        CrashReporter.copy(report)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x15, 0x17, 0x1c))
            setPadding((16 * dp).toInt(), (32 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "${io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity.name} 出错了\n报告已复制到剪贴板，可以直接粘贴发给开发者。"
            setTextColor(Color.rgb(0xe0, 0x7a, 0x3a)); textSize = 16f
        })
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                setTextColor(Color.rgb(0xc9, 0xd1, 0xd9)); textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun button(label: String, action: () -> Unit) = buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        button("再次复制") { CrashReporter.copy(report); Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show() }
        button("重新启动") {
            CrashReporter.clear(this)
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
            finish()
        }
        button("关闭") { CrashReporter.clear(this); finish() }
        root.addView(buttons)
        setContentView(root)
    }
}
