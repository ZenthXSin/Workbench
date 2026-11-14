package io.github.zenthxsin.rustwright.workbench

import arc.backend.sdl.SdlApplication
import arc.backend.sdl.SdlConfig
import io.github.zenthxsin.rustwright.WorkbenchModules
import io.github.zenthxsin.rustwright.RustwrightApp
import io.github.zenthxsin.rustwright.desktop.DesktopPlatform
import io.github.zenthxsin.rustwright.desktop.FileSecretStore
import io.github.zenthxsin.rustwright.desktop.HostShellEnvironment
import io.github.zenthxsin.rustwright.ui.designsystem.FontFile
import java.io.File

/** Runnable generic workbench. Its data directory is independent of product installations. */
fun main() {
    val home = File(System.getProperty("workbench.home") ?: "${System.getProperty("user.home")}/.workbench").apply { mkdirs() }
    val platform = DesktopPlatform(home)
    val fonts = listOf(
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ).filter { File(it).isFile }.map { FontFile(it) }
    val app = RustwrightApp(fonts, platform, HostShellEnvironment(File(home, "workspace")),
        FileSecretStore(File(home, "secrets.properties")), WorkbenchModules.all() + io.github.zenthxsin.rustwright.desktop.DesktopSvgModule)
    SdlApplication(app, SdlConfig().apply {
        title = "Workbench"
        width = 1280; height = 820
        vSyncEnabled = true
    })
    kotlin.system.exitProcess(0)
}
