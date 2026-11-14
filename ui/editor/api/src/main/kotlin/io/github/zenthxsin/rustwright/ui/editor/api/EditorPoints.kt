package io.github.zenthxsin.rustwright.ui.editor.api
import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint
import java.io.File
object EditorPoints { val SUPPORTS = ExtensionPoint<EditorContribution>("editor.supports") }
class EditorContribution(
    override val id: String,
    val accepts: (File) -> Boolean,
    val create: () -> EditorSupport,
    override val order: Int = 100,
) : Contribution
