package io.github.zenthxsin.rustwright.ui.editor

import arc.scene.Element
import arc.scene.ui.TextField
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.ui.designsystem.RwTheme
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.editor.api.EditorField
import io.github.zenthxsin.rustwright.ui.kit.*

internal object BuiltinFieldControls {
    fun build(field: EditorField, boolean: Boolean): Element {
        val row = Table().left()
        row.add(K.text(field.key, 13f, K.muted)).left().padRight(dp(12f))
        if (boolean) {
            row.add(Tap(K.surface2, 8f) { field.setValue(if (field.value.equals("true", true)) "false" else "true") }.apply {
                name = "切换 ${field.key}"; margin(dp(8f)); add(K.text(field.value, 14f, K.text))
            }).growX()
        } else {
            val input = TextField(field.value, RwTheme.Styles.plainField).apply {
                messageText = "值"; changed { field.setValueLive(text) }
            }
            row.add(input).growX().height(dp(36f))
        }
        return row
    }
}
