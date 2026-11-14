package io.github.zenthxsin.rustwright.ui.kit

import arc.Core
import arc.graphics.g2d.Font
import arc.graphics.g2d.TextureRegion
import arc.mock.MockApplication
import arc.mock.MockGL20
import arc.mock.MockGraphics
import arc.mock.MockInput
import arc.scene.Scene
import arc.scene.ui.ScrollPane
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SheetBackTest {
    @Test fun modelThenEffortBackRestoresModelBeforeDismissing() {
        val oldApp = Core.app; val oldGraphics = Core.graphics; val oldScene = Core.scene
        val oldGl = Core.gl; val oldGl20 = Core.gl20; val oldInput = Core.input
        val fontField = RwTheme::class.java.getDeclaredField("font").apply { isAccessible = true }
        val oldFont = fontField.get(null)
        try {
            Core.app = MockApplication(); Core.graphics = MockGraphics(); Core.input = MockInput()
            Core.gl = MockGL20(); Core.gl20 = Core.gl; Core.scene = Scene()
            Core.scene.addStyle(ScrollPane.ScrollPaneStyle::class.java, ScrollPane.ScrollPaneStyle())
            fontField.set(null, Font(Font.FontData(), TextureRegion(), false))
            val host = SheetHost()
            host.open(SheetPage("model", Table(), height = 540f))
            host.push(SheetPage("effort", Table(), height = 540f))
            assertEquals(2, host.depth)
            host.pop()
            assertTrue(host.isOpen)
            assertEquals(1, host.depth)
            host.pop()
            assertFalse(host.isOpen)
            host.open(SheetPage("attachment", Table(), height = 380f))
            assertEquals(1, host.depth)
            host.close()
            assertFalse(host.isOpen)
        } finally {
            fontField.set(null, oldFont)
            Core.app = oldApp; Core.graphics = oldGraphics; Core.scene = oldScene
            Core.gl = oldGl; Core.gl20 = oldGl20; Core.input = oldInput
        }
    }
}
