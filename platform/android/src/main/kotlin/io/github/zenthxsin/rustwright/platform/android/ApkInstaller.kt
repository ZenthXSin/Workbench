package io.github.zenthxsin.rustwright.platform.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** 更新包安装前校验：包名、版本号、签名必须与当前应用一致（移植自 RustPic UpdateInstaller）。 */
object ApkInstaller {
    @Suppress("DEPRECATION")
    fun validate(context: Context, apk: File, expectedVersionCode: Int) {
        val pm = context.packageManager
        val modern = Build.VERSION.SDK_INT >= 28
        val flags = if (modern) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(pm.getPackageArchiveInfo(apk.path, flags)) { "下载文件不是有效的 APK" }
        require(archive.packageName == context.packageName) { "安装包不属于 Rustwright" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        val version = if (modern) archive.longVersionCode else archive.versionCode.toLong()
        val current = if (modern) installed.longVersionCode else installed.versionCode.toLong()
        require(version == expectedVersionCode.toLong()) { "安装包版本与更新信息不一致" }
        require(version > current) { "安装包版本不高于当前版本" }
        val expected = if (modern) installed.signingInfo?.apkContentsSigners else installed.signatures
        val actual = if (modern) archive.signingInfo?.apkContentsSigners else archive.signatures
        require(!expected.isNullOrEmpty() && !actual.isNullOrEmpty() && expected.toSet() == actual.toSet()) { "安装包签名与当前应用不一致" }
    }

    fun intent(context: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(RwFileProvider.uriFor(context, apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
