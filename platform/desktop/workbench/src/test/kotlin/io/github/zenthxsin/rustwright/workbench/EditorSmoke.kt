package io.github.zenthxsin.rustwright.workbench
import arc.Core
import arc.backend.sdl.*
import arc.math.geom.Vec2
import arc.input.KeyCode
import arc.scene.Element
import io.github.zenthxsin.rustwright.*
import io.github.zenthxsin.rustwright.desktop.*
import io.github.zenthxsin.rustwright.ui.designsystem.FontFile
import io.github.zenthxsin.rustwright.ui.editor.*
import io.github.zenthxsin.rustwright.ui.kit.*
import io.github.zenthxsin.rustwright.ui.widgets.code.Pos
import java.io.File

/** Actual SDL editor smoke test; uses an isolated home and no account or network service. */
fun main() {
    val home = File(System.getProperty("workbench.home")).apply { mkdirs() }
    val root = File(home, "project").apply { mkdirs() }
    val config = File(root, "settings.ini").apply { writeText("[settings]\nenabled: true\ncount: 1\n") }
    val notes = File(root, "notes.md").apply { writeText("# Notes\n\n**Preview works**\n") }
    val image = File(root, "image.png")
    javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", image)
    var failed = false; var count = 0; var frame = 0; lateinit var page: EditorPage
    var shownSheet: SheetPage? = null
    fun verify(ok: Boolean, name: String) { check(ok) { name }; count++; println("[editor-smoke] PASS $name") }
    fun tap(name: String) {
        val e = Core.scene.root.find<Element>(name) ?: error("Missing $name")
        val v = e.localToStageCoordinates(Vec2(e.width / 2, e.height / 2))
        Core.scene.touchDown(v.x.toInt(), v.y.toInt(), 0, KeyCode.mouseLeft)
        Core.scene.touchUp(v.x.toInt(), v.y.toInt(), 0, KeyCode.mouseLeft)
    }
    val ctx = object : ProjectContext {
        override fun projectDir() = root
        override fun projectName() = "Editor smoke"
        override fun askRust(text: String) {}
        override fun notify(text: String) { println("[editor-smoke] notice $text") }
        override fun sheet(page: SheetPage) { shownSheet = page }
        override fun closeSheet() { shownSheet = null }
    }
    val platform = DesktopPlatform(home)
    val app = object : RustwrightApp(listOf(FontFile("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc")), platform,
        HostShellEnvironment(File(home, "workspace")), FileSecretStore(File(home, "secrets.properties")), WorkbenchModules.all() + DesktopSvgModule) {
        private var nextScenarioTick = System.nanoTime()
        override fun update() {
            super.update()
            // Xvfb may ignore vsync and render hundreds of frames per second.
            // Keep interaction waits tied to elapsed time, including coroutine debounce.
            val now = System.nanoTime()
            if (now < nextScenarioTick) return
            nextScenarioTick = now + 16_666_667L
            runCatching {
                when (++frame) {
                    30 -> {
                        verify(context().services.getOrNull<io.github.zenthxsin.rustwright.pic.api.PicService>() != null, "generic image service is assembled")
                        val registry = context().service<io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry>()
                        verify(registry.toolsFor(io.github.zenthxsin.rustwright.core.agent.AgentScene("pic")).any { it.name == "generate_image" }, "generic image tools are available")
                        verify(io.github.zenthxsin.rustwright.core.runtime.WorkbenchLicense.text().contains("GNU AFFERO GENERAL PUBLIC LICENSE"), "bundled license is readable")
                        page = EditorPage({ ctx }, context().scope, initialFile = config)
                        page.setFillParent(true); Core.scene.add(page)
                        verify(page.document.text.contains("enabled: true"), "open config with full editor")
                    }
                    60 -> {
                        val e = page.editorView; e.setCursor(e.doc.end()); e.insert("extra: text")
                        verify(e.doc.text.contains("extra: text"), "insert through abstract document")
                        e.undo(); verify(!e.doc.text.contains("extra: text"), "undo")
                        e.redo(); verify(e.doc.text.contains("extra: text"), "redo")
                        val before = e.doc.text; e.editable = false; e.insert("NO"); verify(e.doc.text == before, "readonly")
                        e.editable = true; e.setText("ret"); e.setCursor(e.doc.end()); Core.scene.setKeyboardFocus(e)
                    }
                    100 -> {
                        val e = page.editorView; verify(e.onTab?.invoke() == true, "generic completion accepts Tab")
                        verify(e.doc.text == "return", "word completion result")
                        Core.scene.keyTyped('\n'); verify(e.doc.text.endsWith("\n"), "Enter inserts newline")
                        tap("搜索"); verify(page.children.any { it is EditorSearchPanel && it.visible }, "search opens")
                    }
                    120 -> {
                        page.remove(); Core.scene.add(page)
                        val e = page.editorView; e.setText("ret"); e.setCursor(e.doc.end()); Core.scene.setKeyboardFocus(e)
                    }
                    145 -> {
                        val e = page.editorView
                        verify(e.onTab?.invoke() == true && e.doc.text == "return", "completion resumes after navigation")
                        Core.scene.keyTyped('\n')
                        tap("侧栏"); verify(page.children.any { it is EditorSidebar && it.visible }, "project and outline sidebar opens")
                    }
                    150 -> { page.onBackRequested {}; page.openFile(notes) }
                    200 -> {
                        verify(page.currentFile == notes, "switch tab after save")
                        verify(config.readText() == "return\n", "switch preserves saved text")
                        verify(page.document.text.contains("# Notes"), "open Markdown")
                        tap("辅助编辑")
                    }
                    215 -> {
                        tap("预览")
                        verify(shownSheet != null, "Markdown preview builds through menu")
                        page.openFile(image)
                    }
                    250 -> {
                        verify(page.currentFile == image && !page.editorView.visible, "binary tab previews image")
                        val pixels = arc.util.ScreenUtils.getFrameBufferPixmap(0, 0, Core.graphics.width, Core.graphics.height)
                        arc.graphics.PixmapIO.writePng(Core.files.absolute(File(home, "editor.png").path), pixels); pixels.dispose()
                        page.remove()
                        val pixel = io.github.zenthxsin.rustwright.pic.impl.PixelEditorPanel(image, context().scope, onBack = {}) {}
                        pixel.setFillParent(true); Core.scene.add(pixel)
                    }
                    275 -> {
                        verify(Core.scene.root.children.any { it is io.github.zenthxsin.rustwright.pic.impl.PixelEditorPanel }, "generic pixel editor opens")
                        val pixels = arc.util.ScreenUtils.getFrameBufferPixmap(0, 0, Core.graphics.width, Core.graphics.height)
                        arc.graphics.PixmapIO.writePng(Core.files.absolute(File(home, "pixel-editor.png").path), pixels); pixels.dispose()
                        println("[editor-smoke] COMPLETE $count checks"); Core.app.exit()
                    }
                }
            }.onFailure { failed = true; it.printStackTrace(); Core.app.exit() }
        }
    }
    SdlApplication(app, SdlConfig().apply { title = "Editor smoke"; width = 412; height = 892; vSyncEnabled = false })
    kotlin.system.exitProcess(if (failed) 1 else 0)
}
