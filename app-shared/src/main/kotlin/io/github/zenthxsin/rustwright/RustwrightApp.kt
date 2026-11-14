package io.github.zenthxsin.rustwright

import arc.Core
import arc.scene.Scene
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.runtime.ModuleHost
import io.github.zenthxsin.rustwright.core.runtime.Platform
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwDispatchers
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.FontFile
import io.github.zenthxsin.rustwright.ui.designsystem.NavBar
import io.github.zenthxsin.rustwright.ui.designsystem.RwArcApp
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenShown
import io.github.zenthxsin.rustwright.ui.designsystem.NavigateTo
import io.github.zenthxsin.rustwright.ui.designsystem.SetThemeMode
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import kotlinx.coroutines.Dispatchers

/** 应用外壳：装配模块，把模块注册的页面放进底部导航。 */
open class RustwrightApp(
    fonts: List<FontFile>,
    private val platform: Platform,
    private val linux: LinuxEnvironment,
    private val secrets: SecretStore,
    private val modules: List<RwModule>,
) : RwArcApp(fonts) {

    private lateinit var host: ModuleHost
    private lateinit var ctx: RwContext
    private val content = Table()
    private var current: String? = null

    override fun build(scene: Scene) {
        ctx = RwContext(platform, RwDispatchers(ArcDispatcher, Dispatchers.IO, Dispatchers.Default))
        val screens = ScreenRegistry()
        registry = screens
        ctx.services.provide(screens)
        ctx.services.provide<LinuxEnvironment>(linux)
        ctx.services.provide<SecretStore>(secrets)
        host = ModuleHost(ctx, modules)
        host.start()
        io.github.zenthxsin.rustwright.core.runtime.AppBrand.identity = ctx.services.getOrNull<io.github.zenthxsin.rustwright.core.runtime.AppIdentity>() ?: io.github.zenthxsin.rustwright.core.runtime.AppIdentity()

        applyTheme(loadThemeMode())
        val fused = ctx.services.getOrNull<FusedShellProvider>()
        if (fused != null) {
            // 融合版有深浅两套（K），按保存的颜色模式来
            platform.applySystemBars(RwTheme.dark, io.github.zenthxsin.rustwright.ui.kit.K.bg.argb8888())
            // 融合版界面：一个助手 + 侧边栏，原来的各页面作为「更多」里的工具打开
            fusedProvider = fused
            mountShell(scene, null)
            ctx.events.on<NavigateTo>(ctx.scope) { e ->
                Core.app.post {
                    // 旧页面 id → 「更多」里的工具 id
                    val id = ctx.extensions.get(ShellPoints.NAVIGATION, e.id)?.target ?: e.id
                    shell?.openToolId(id)
                }
            }
            ctx.events.on<SetThemeMode>(ctx.scope) { e -> Core.app.post { switchFusedTheme(e.mode) } }
            return
        }
        buildRoot(scene, screens)
        screens.all.firstOrNull()?.let { show(it.id, screens) }
        ctx.events.on<NavigateTo>(ctx.scope) { e -> Core.app.post { if (screens.all.any { it.id == e.id }) show(e.id, screens) } }
        ctx.events.on<SetThemeMode>(ctx.scope) { e -> Core.app.post { setTheme(e.mode) } }
    }

    private var fusedProvider: FusedShellProvider? = null

    /** 建融合版外壳挂到场景上；[page] 不为空时打开那一页（换主题后回到设置）。 */
    private fun mountShell(scene: Scene, page: io.github.zenthxsin.rustwright.fused.FusedShell.Page?) {
        val sh = fusedProvider?.build() ?: return
        shell = sh
        sh.setFillParent(true)
        scene.add(sh)
        if (page != null) sh.show(page, arc.scene.ui.layout.Switcher.NONE)
        // 冷启动（不是换颜色模式重建）才放启动动画，顺便预加载各页面和弹层
        else if (System.getProperty("rw.nosplash") == null) sh.startSplash()
    }

    /** 融合版换颜色模式：存下来、换色表，整页重建后回到设置页。 */
    private fun switchFusedTheme(mode: RwTheme.Mode) {
        runCatching { themeFile.writeText(mode.name) }
        applyTheme(mode)
        platform.applySystemBars(RwTheme.dark, io.github.zenthxsin.rustwright.ui.kit.K.bg.argb8888())
        val old = shell
        old?.remove()
        mountShell(Core.scene, io.github.zenthxsin.rustwright.fused.FusedShell.Page.SETTINGS)
    }

    private var root: Table? = null
    private var shell: io.github.zenthxsin.rustwright.fused.FusedShell? = null

    /** 融合版外壳（启动器做截图、自动化时用）。 */
    fun fusedShell() = shell

    override fun onBackRequested() {
        shell?.let { it.requestBack(); return }
        if ((built[current] as? BackHandler)?.onBackRequested {} == true) return
        val first = registry?.all?.firstOrNull()?.id ?: return
        if (current != first) registry?.let { show(first, it) }
    }

    private fun buildRoot(scene: Scene, screens: ScreenRegistry) {
        root?.remove()
        val nav = NavBar(screens.all, { current }) { show(it, screens) }
        root = Table(RwTheme.solid(RwTheme.Colors.bg)).apply {
            setFillParent(true)
            add(content).grow().row()
            add(nav).growX()
        }
        scene.add(root)
    }

    // ---- 主题 ----

    private val themeFile get() = java.io.File(platform.dataDir, "ui-theme.txt")

    private fun loadThemeMode(): RwTheme.Mode =
        runCatching { RwTheme.Mode.valueOf(themeFile.readText().trim()) }.getOrDefault(RwTheme.Mode.SYSTEM)

    private fun applyTheme(mode: RwTheme.Mode) {
        RwTheme.applyMode(mode, platform.systemDarkMode)
        platform.applySystemBars(RwTheme.dark, RwTheme.Colors.bg.argb8888())
    }

    /** 切换主题：保存、刷新颜色和样式，丢掉已构建的页面后重建（页面状态之外的数据都在服务里，不受影响）。 */
    private fun setTheme(mode: RwTheme.Mode) {
        runCatching { themeFile.writeText(mode.name) }
        applyTheme(mode)
        val screens = registry ?: return
        val shown = current
        current = null
        content.clear()
        built.clear()
        buildRoot(Core.scene, screens)
        (shown ?: screens.all.firstOrNull()?.id)?.let { show(it, screens) }
    }

    private var themeCheck = 0

    override fun update() {
        // 跟随系统时，系统切换深浅色后重建
        if (::ctx.isInitialized && RwTheme.mode == RwTheme.Mode.SYSTEM && ++themeCheck % 120 == 0 && platform.systemDarkMode != RwTheme.dark) {
            if (shell != null) switchFusedTheme(RwTheme.Mode.SYSTEM) else setTheme(RwTheme.Mode.SYSTEM)
        }
        super.update()
    }

    private val built = HashMap<String, arc.scene.Element>()
    private var registry: ScreenRegistry? = null

    /** 运行时上下文（启动器做截图、自动化时用）。 */
    fun context(): RwContext = ctx

    /** 已注册页面的 id，按导航顺序。 */
    fun pageIds(): List<String> = registry?.all?.map { it.id }.orEmpty()

    private fun show(id: String, screens: ScreenRegistry) {
        io.github.zenthxsin.rustwright.ui.widgets.Popover.dismissAll()
        if (current == id) return
        current = id
        Core.scene.setKeyboardFocus(null)
        content.clear()
        // 每个页面只构建一次并复用：切页不丢状态（未保存的编辑、进行中的任务），也不会重复挂监听
        content.add(built.getOrPut(id) { screens.all.first { it.id == id }.build() }).grow()
        ctx.events.post(ScreenShown(id))
    }

    override fun dispose() {
        if (::host.isInitialized) host.stop()
    }
}
