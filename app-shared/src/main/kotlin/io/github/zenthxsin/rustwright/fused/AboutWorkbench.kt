package io.github.zenthxsin.rustwright.fused

import arc.Core
import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.runtime.WorkbenchLicense
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs

/** A product-independent place for the foundation's attribution and license. */
internal object AboutWorkbench {
    suspend fun show() {
        val notice = "${AppBrand.identity.name} ${Version.name}\n\n" +
            "基于 ${WorkbenchLicense.PROJECT}\n${WorkbenchLicense.COPYRIGHT}\n" +
            "项目出处：${WorkbenchLicense.ORIGIN}\n许可证：${WorkbenchLicense.LICENSE}\n\n" +
            "允许免费商用。无任何担保。分发修改版或提供符合 AGPL 条款的网络服务时，须提供对应源码并保留出处。"
        when (Dialogs.choose("关于", notice, listOf("关闭", "对应源码", "许可证"))) {
            1 -> Core.app.openURI(WorkbenchLicense.source)
            2 -> Dialogs.choose("AGPL-3.0", WorkbenchLicense.text(), listOf("关闭"))
        }
    }
}
