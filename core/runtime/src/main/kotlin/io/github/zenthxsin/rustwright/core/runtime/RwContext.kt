package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** 运行时调度器：[main] 为 UI（Arc GL）线程，由平台层提供。 */
class RwDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
)

/** 模块可见的运行时上下文。 */
class RwContext(
    val platform: Platform,
    val dispatchers: RwDispatchers,
    val services: Services = Services(),
    val events: Events = Events(),
    /** 贡献点注册表：页面、设置项、助手工具与技能等都往这里注册。 */
    val extensions: Extensions = Extensions(),
) {
    /** 应用级作用域，[ModuleHost.stop] 时取消。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    inline fun <reified T : Any> service(): T = services.get()
}
