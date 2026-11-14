package io.github.zenthxsin.rustwright.platform.android

import android.graphics.fonts.SystemFonts
import android.os.Build
import io.github.zenthxsin.rustwright.ui.designsystem.FontFile
import java.io.File

/**
 * 找系统里能显示简体中文的字体，按优先级返回候选（调用方逐个尝试加载）。
 * Arc 的 FreeType 只编了 TrueType 驱动，CFF 格式（如部分机型的 NotoSansCJK.ttc）会加载失败，跳过即可。
 */
object SystemFontLocator {

    fun candidates(): List<FontFile> {
        val result = LinkedHashSet<FontFile>()
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                SystemFonts.getAvailableFonts()
                    .filter { f ->
                        // 繁体子字体同时标 zh-Hant 和 zh-Bopo，只排除 Bopo 以外的 Hant 不够，任一 Hant 即排除
                        val locales = (0 until f.localeList.size()).map { f.localeList[it] }
                        f.file != null && locales.any { it.language == "zh" } && locales.none { it.script == "Hant" }
                    }
                    // 明确标 Hans 的优先，再无衬线优先（NotoSerifCJK 和 NotoSansCJK 都标了 zh），再按接近常规字重
                    .sortedWith(compareBy(
                        { f -> (0 until f.localeList.size()).none { f.localeList[it].script == "Hans" } },
                        { "serif" in it.file!!.name.lowercase() && "sans" !in it.file!!.name.lowercase() },
                        { kotlin.math.abs(it.style.weight - 400) }, { it.style.slant },
                    ))
                    .forEach { result += FontFile(it.file!!.absolutePath, it.ttcIndex) }
            }
        }
        listOf(
            "/system/fonts/MiSansVF.ttf",
            "/system/fonts/HarmonyOS_Sans_SC.ttf",
            "/system/fonts/DroidSansFallbackFull.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/Roboto-Regular.ttf",
        ).filter { File(it).exists() }.forEach { result += FontFile(it, if (it.endsWith("CJK-Regular.ttc")) 2 else 0) }
        return result.toList()
    }
}
