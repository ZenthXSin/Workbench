package io.github.zenthxsin.rustwright.core.runtime

/**
 * 功能模块（参考 Mindustry 的 Mod/子系统生命周期）。
 *
 * 生命周期按依赖拓扑顺序执行：
 * 1. [register]：只往 [RwContext.services] 里提供自己的服务，不取用别人的；
 * 2. [init]：取用依赖的服务，注册内容、Agent 工具、页面等扩展；
 * 3. [start]：所有模块就绪后启动后台任务；
 * 4. [stop]：逆序停止。
 */
interface RwModule {
    /** 全局唯一 id，例如 `editor`、`terminal`、`assistant`。 */
    val id: String

    /** 必须先于本模块初始化的模块 id。 */
    val dependsOn: List<String> get() = emptyList()

    fun register(ctx: RwContext) {}
    fun init(ctx: RwContext) {}
    fun start(ctx: RwContext) {}
    fun stop(ctx: RwContext) {}
}
