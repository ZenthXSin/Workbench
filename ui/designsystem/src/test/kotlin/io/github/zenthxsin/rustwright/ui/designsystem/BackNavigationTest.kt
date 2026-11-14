package io.github.zenthxsin.rustwright.ui.designsystem

import arc.Core
import arc.graphics.Color
import arc.graphics.g2d.Font
import arc.graphics.g2d.TextureRegion
import arc.input.KeyCode
import arc.mock.MockApplication
import arc.mock.MockGL20
import arc.mock.MockGraphics
import arc.mock.MockInput
import arc.scene.Element
import arc.scene.Scene
import arc.scene.ui.Dialog
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackNavigationTest {
    private val oldScene = Core.scene
    private val oldApp = Core.app
    private val oldGraphics = Core.graphics
    private val oldInput = Core.input
    private val oldGl = Core.gl
    private val oldGl20 = Core.gl20

    @BeforeTest fun scene() {
        Core.app = MockApplication()
        Core.graphics = MockGraphics()
        Core.input = MockInput()
        Core.gl = MockGL20()
        Core.gl20 = Core.gl
        Core.scene = Scene()
        Core.scene.addStyle(Dialog.DialogStyle::class.java, Dialog.DialogStyle().apply {
            titleFont = Font(Font.FontData(), TextureRegion(), false)
            titleFontColor = Color.white
        })
    }

    @AfterTest fun restore() {
        Core.scene = oldScene
        Core.app = oldApp
        Core.graphics = oldGraphics
        Core.input = oldInput
        Core.gl = oldGl
        Core.gl20 = oldGl20
    }

    @Test fun nestedConfirmationAndPopoverCloseBeforeLeavingSettings() {
        val pages = BackHistory("home")
        pages.visit("settings")
        pages.visit("api settings")
        val app = object : RwArcApp(emptyList()) {
            override fun build(scene: Scene) = Unit
            override fun onBackRequested() { pages.back() }
        }
        var cancelled = 0
        val dialog = Dialog("").apply { hidden { cancelled++ } }
        Core.scene.add(dialog)
        val popover = object : Element(), BackOverlay {
            override fun dismissForBack(): Boolean { remove(); return true }
        }
        Core.scene.add(popover)
        app.requestBack()
        assertNull(popover.scene)
        assertTrue(dialog.isShown)
        assertEquals("api settings", pages.current)
        app.requestBack()
        assertEquals(1, cancelled)
        assertFalse(dialog.isShown)
        assertEquals("api settings", pages.current)
        app.requestBack()
        assertEquals("settings", pages.current)
        app.requestBack()
        assertEquals("home", pages.current)
        repeat(3) { app.requestBack() }
        assertEquals("home", pages.current)
        assertEquals(1, pages.depth)
    }

    @Test fun newestDialogWinsOverOlderPopoverAndCancelsOnce() {
        var popoverClosed = 0
        val popover = object : Element(), BackOverlay {
            override fun dismissForBack(): Boolean { popoverClosed++; remove(); return true }
        }
        Core.scene.add(popover)
        var firstCancelled = 0
        var secondCancelled = 0
        val first = Dialog("").apply { hidden { firstCancelled++ } }
        val second = Dialog("").apply { hidden { secondCancelled++ } }
        Core.scene.add(first)
        Core.scene.add(second)
        assertTrue(BackNavigation.dismissOverlay(Core.scene))
        assertEquals(1, secondCancelled)
        assertEquals(0, firstCancelled)
        assertEquals(0, popoverClosed)
        assertTrue(BackNavigation.dismissOverlay(Core.scene))
        assertEquals(1, firstCancelled)
        assertTrue(BackNavigation.dismissOverlay(Core.scene))
        assertEquals(1, popoverClosed)
        assertFalse(BackNavigation.dismissOverlay(Core.scene))
    }

    @Test fun focusedDialogAndRepeatedPhysicalKeyCannotAlsoNavigatePage() {
        var pageBack = 0
        val app = object : RwArcApp(emptyList()) {
            override fun build(scene: Scene) = Unit
            override fun onBackRequested() { pageBack++ }
        }
        app.installBackHandling(Core.scene)
        var cancelled = 0
        val dialog = Dialog("").apply { closeOnBack(); hidden { cancelled++ } }
        Core.scene.add(dialog)
        Core.scene.setKeyboardFocus(dialog)
        assertTrue(Core.scene.keyDown(KeyCode.back))
        assertEquals(1, cancelled)
        assertEquals(0, pageBack)
        Core.scene.keyDown(KeyCode.back) // repeat while held
        assertEquals(0, pageBack)
        Core.scene.keyUp(KeyCode.back)
        Core.scene.keyDown(KeyCode.back)
        assertEquals(1, pageBack)
        Core.scene.keyUp(KeyCode.back)
        Core.scene.keyDown(KeyCode.escape)
        assertEquals(2, pageBack)
        Core.scene.keyUp(KeyCode.escape)
    }

    @Test fun fileEditorReturnsToSameDirectoryAndRootDoesNotAccumulate() {
        data class Page(val name: String, val state: Any = Any())
        val home = Page("home")
        val more = Page("more")
        val files = Page("files/units")
        val editor = Page("ini")
        val history = BackHistory(home)
        history.visit(more)
        history.visit(files)
        history.visit(editor)
        assertSame(files, history.back())
        history.visit(editor)
        history.visit(more)
        assertEquals(2, history.depth)
        assertSame(home, history.back())
        assertNull(history.back())
    }
}
