package io.github.zenthxsin.rustwright.fused
import io.github.zenthxsin.rustwright.ui.editor.EditorPage
import java.io.File
internal class TextEditorPage(s: FusedShell, file: File) : EditorPage({ s.host.ui }, s.host.scope, initialFile = file, supportFactory = { s.host.editorSupport(it) ?: io.github.zenthxsin.rustwright.ui.editor.BuiltinEditorSupport() })
