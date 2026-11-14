package io.github.zenthxsin.rustwright.fused

import arc.Core
import arc.graphics.Color
import arc.graphics.Texture
import arc.graphics.g2d.Shapes
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import arc.scene.event.Touchable
import arc.scene.ui.layout.Table
import arc.util.Log
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.dp
import io.github.zenthxsin.rustwright.ui.kit.ImageThumb
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.Tap
import java.io.File

/** Default image card; business cards are supplied by file presentations. */
internal object ArtifactCards {
    fun card(a: Artifact, open: (Artifact) -> Unit): Element? {
        a.card?.let { return it(a, open) }
        val f = a.path?.let(::File) ?: return null
        return if (a.kind == Artifact.Kind.IMAGE) image(a, f, open) else null
    }
    private fun base(a: Artifact, open: (Artifact) -> Unit) = Tap(K.surface, 14f) { open(a) }.apply {
        border = K.cardBorder; clipRadius = dp(14f); name = a.name
    }
    private fun image(a: Artifact, f: File, open: (Artifact) -> Unit): Element {
        val c = base(a, open)
        c.add(ImageThumb(f, tint = true)).size(dp(180f), dp(150f)).row()
        val info = Table().left()
        info.add(K.text(a.name, 14f, K.text, 500).apply { setEllipsis(true) }).width(dp(156f)).left().row()
        info.add(K.text(a.meta, 12f, K.subtle).apply { setEllipsis(true) }).width(dp(156f)).left().padTop(dp(4f))
        c.add(info).pad(dp(10f), dp(12f), dp(10f), dp(12f))
        return c
    }

}
