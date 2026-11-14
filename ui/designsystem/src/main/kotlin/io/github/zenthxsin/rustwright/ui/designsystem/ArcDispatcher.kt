package io.github.zenthxsin.rustwright.ui.designsystem

import arc.Core
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/** 把协程派发到 Arc 主循环（GL 线程），UI 状态只在这里修改。 */
object ArcDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Core.app.post(block)
    }
}
