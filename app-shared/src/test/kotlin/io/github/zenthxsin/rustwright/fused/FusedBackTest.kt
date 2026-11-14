package io.github.zenthxsin.rustwright.fused

import arc.Core
import arc.graphics.g2d.Font
import arc.graphics.g2d.TextureRegion
import arc.mock.MockApplication
import arc.mock.MockGL20
import arc.mock.MockGraphics
import arc.mock.MockInput
import arc.scene.Element
import arc.scene.Scene
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.codex.CodexLauncher
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.ui.designsystem.BackHandler
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.kit.SheetPage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FusedBackTest {
    @Test fun settingsToolsSheetsDrawerAndUnsavedWorkFollowOneBackRoute() = runTest {
        val oldApp = Core.app; val oldGraphics = Core.graphics; val oldScene = Core.scene
        val oldGl = Core.gl; val oldGl20 = Core.gl20; val oldInput = Core.input
        val field = RwTheme::class.java.getDeclaredField("font").apply { isAccessible = true }
        val cacheField = RwTheme::class.java.getDeclaredField("fontCache").apply { isAccessible = true }
        val oldFont = field.get(null)
        val whiteField = RwTheme::class.java.getDeclaredField("white").apply { isAccessible = true }
        val oldWhite = whiteField.get(null)
        @Suppress("UNCHECKED_CAST") val cache = cacheField.get(null) as MutableMap<String, Font>
        val oldCache = HashMap(cache)
        val root = Files.createTempDirectory("rw-back-test").toFile()
        try {
            Core.app = MockApplication(); Core.graphics = MockGraphics(); Core.input = MockInput()
            Core.gl = MockGL20(); Core.gl20 = Core.gl; Core.scene = Scene()
            val font = Font(Font.FontData(), TextureRegion(), false)
            field.set(null, font)
            whiteField.set(null, TextureRegion())
            // Drawer brand uses this bundled face; tests only lay out, never rasterize it.
            cache["s32.0/600/32.0"] = font
            Core.scene.addStyle(ScrollPane.ScrollPaneStyle::class.java, ScrollPane.ScrollPaneStyle())
            val workbench = CodexWorkbench(root, CodexLauncher { error("no model process in navigation test") }, UnconfinedTestDispatcher(testScheduler), backgroundScope)
            val host = object : FusedHost {
                override val wb = workbench
                override val scope = backgroundScope
                override val accountEmail = ""
                override fun balance(): String? = null
                override fun env() = EnvStatus(true, null, "ready")
                override fun blocker(): String? = null
                override fun currentProjectId(): String? = null
                override fun setCurrentProject(id: String) {}
                override suspend fun createProject(): CodexProject? = null
                override fun copy(text: String) {}
                override fun tools() = emptyList<ToolGroup>()
                override fun library() = emptyList<Artifact>()
                override fun openArtifact(a: Artifact): Element? = null
                override fun settings() = emptyList<List<SettingRow>>()
                override fun imageModel() = "image"
                override fun imageModels() = listOf("image")
                override fun setImageModel(id: String) {}
                override fun setup(): SetupState? = null
            }
            val shell = FusedShell(host)
            Core.scene.add(shell)
            shell.show(FusedShell.Page.SETTINGS)
            shell.openTool("profile", Table())
            shell.sheets.open(SheetPage("model", Table(), height = 540f))
            shell.sheets.push(SheetPage("effort", Table(), height = 540f))
            shell.requestBack()
            assertEquals(1, shell.sheets.depth)
            assertEquals(FusedShell.Page.TOOL, shell.page)
            shell.requestBack()
            assertFalse(shell.sheets.isOpen)
            shell.requestBack()
            assertEquals(FusedShell.Page.SETTINGS, shell.page)
            shell.openDrawer()
            shell.requestBack()
            assertEquals(FusedShell.Page.SETTINGS, shell.page)
            shell.requestBack()
            assertEquals(FusedShell.Page.HOME, shell.page)

            shell.show(FusedShell.Page.MORE)
            shell.openTool("files", Table())
            var confirmed: (() -> Unit)? = null
            var handled = 0
            val editor = object : Table(), BackHandler {
                override fun onBackRequested(leave: () -> Unit): Boolean {
                    handled++
                    confirmed = leave
                    return true
                }
            }
            shell.openTool("editor", editor)
            shell.requestBack()
            assertEquals(1, handled)
            assertEquals(FusedShell.Page.TOOL, shell.page)
            assertNotNull(confirmed).invoke()
            confirmed.invoke() // duplicate callback cannot pop the directory as well
            assertEquals(FusedShell.Page.TOOL, shell.page)
            shell.requestBack()
            assertEquals(FusedShell.Page.MORE, shell.page)
            assertEquals(1, handled)
            shell.requestBack()
            assertEquals(FusedShell.Page.HOME, shell.page)
            repeat(3) { shell.requestBack() }
            assertEquals(FusedShell.Page.HOME, shell.page)

            // A save that completes after leaving and reopening that same editor is stale.
            shell.show(FusedShell.Page.MORE)
            shell.openTool("editor", editor)
            shell.requestBack()
            val oldSaveCompleted = assertNotNull(confirmed)
            shell.show(FusedShell.Page.HOME)
            shell.show(FusedShell.Page.MORE)
            shell.openTool("editor", editor)
            oldSaveCompleted()
            assertEquals(FusedShell.Page.TOOL, shell.page)

            // Chat history retains the exact conversation, skipping deleted destinations.
            shell.show(FusedShell.Page.HOME)
            val first = workbench.newConversation("test-project", model = "test")
            val second = workbench.newConversation("test-project", model = "test")
            shell.openChat(first.id)
            shell.openChat(second.id)
            shell.openTool("artifact", Table())
            shell.requestBack()
            assertEquals(second.id, shell.conversation()?.id)
            shell.requestBack()
            assertEquals(first.id, shell.conversation()?.id)
            shell.openTool("artifact", Table())
            workbench.deleteConversation(first.id)
            shell.requestBack()
            assertEquals(FusedShell.Page.HOME, shell.page)
        } finally {
            field.set(null, oldFont); cache.clear(); cache.putAll(oldCache)
            whiteField.set(null, oldWhite)
            Core.app = oldApp; Core.graphics = oldGraphics; Core.scene = oldScene
            Core.gl = oldGl; Core.gl20 = oldGl20; Core.input = oldInput
            root.deleteRecursively()
        }
    }
}
