package io.github.zenthxsin.rustwright.platform.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.zenthxsin.rustwright.core.runtime.RwLog
import io.github.zenthxsin.rustwright.core.runtime.net.Endpoints
import java.security.MessageDigest

/**
 * 启动时校验安装包签名：签名证书的 SHA-256 必须等于正式签名（由使用此库的产品配置，见 [Endpoints.signer]）。
 * 仅编译出的 debug 变体、独立调试包名和可调试标记同时满足时允许开发签名。
 */
internal object SignatureCheck {
    @Suppress("DEPRECATION")
    fun ok(context: Context): Boolean {
        if (Endpoints.signer().isEmpty()) return true // Optional pinning; configure in the consuming product.
        if (developmentBuild(BuildConfig.DEBUG, context.packageName,
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)) return true
        return runCatching {
            val pm = context.packageManager
            val certs = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                info?.apkContentsSigners
            } else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            val expected = Endpoints.signer()
            // 只信任当前唯一签名者，不凭历史证书或多签名中的某一张放行。
            trustedSigners(certs?.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
            }.orEmpty(), expected)
        }.getOrElse { RwLog.w("signature", "读取签名失败", it); false }
    }

    internal fun developmentBuild(debugBuild: Boolean, packageName: String, debuggable: Boolean): Boolean =
        debugBuild && debuggable && packageName == Endpoints.configured("android.debug_package", "io.github.workbench.debug")

    internal fun trustedSigners(current: List<String>, expected: String): Boolean =
        current.size == 1 && expected.matches(Regex("[0-9a-f]{64}")) && current.single() == expected
}
