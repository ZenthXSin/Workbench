package io.github.zenthxsin.rustwright.ui.designsystem

import arc.scene.Scene
import arc.scene.ui.Dialog

/** Page-local navigation or an asynchronous unsaved-work confirmation, always on Arc. */
fun interface BackHandler {
    /** Return true when consumed. Call [leave] only after the page can safely be left. */
    fun onBackRequested(leave: () -> Unit): Boolean
}

/** A scene-root overlay that must close before navigating the page underneath. */
fun interface BackOverlay {
    fun dismissForBack(): Boolean
}

object BackNavigation {
    fun dismissOverlay(scene: Scene): Boolean {
        val children = scene.root.children
        for (i in children.size - 1 downTo 0) {
            val child = children[i]
            if (!child.visible) continue
            if (child is Dialog && child.isShown) {
                // Remove now: repeated presses must not cancel the same fading dialog twice.
                // hidden callbacks still resolve suspended Dialogs.input/choose as cancelled.
                child.hide(null)
                return true
            }
            if (child is BackOverlay && child.dismissForBack()) return true
        }
        return false
    }
}

/** Actual visited destinations, retaining page state when returning from a child tool. */
class BackHistory<T>(root: T) {
    private val entries = arrayListOf(root)
    val current: T get() = entries.last()
    val depth: Int get() = entries.size

    fun visit(next: T, replace: Boolean = false) {
        if (current == next) return
        val existing = entries.indexOfLast { it == next }
        when {
            existing >= 0 -> while (entries.lastIndex > existing) entries.removeAt(entries.lastIndex)
            replace -> entries[entries.lastIndex] = next
            else -> entries += next
        }
    }

    fun back(): T? {
        if (entries.size == 1) return null
        entries.removeAt(entries.lastIndex)
        return current
    }
}
