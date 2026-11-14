package io.github.zenthxsin.rustwright.core.runtime

import kotlinx.coroutines.cancel

/** 模块装配与生命周期驱动（相当于 Mindustry ClientLauncher 里那串 add(...)）。 */
class ModuleHost(val ctx: RwContext, modules: List<RwModule>) {

    /** 按依赖拓扑排序后的模块。 */
    val modules: List<RwModule> = sort(modules)

    private var started = false

    fun start() {
        check(!started) { "ModuleHost 已启动" }
        started = true
        // 生命周期里注册的贡献记在该模块名下，停止时一并撤销
        modules.forEach { m -> ctx.extensions.withOwner(m.id) { m.register(ctx) } }
        modules.forEach { m -> ctx.extensions.withOwner(m.id) { m.init(ctx) } }
        modules.forEach { m -> ctx.extensions.withOwner(m.id) { m.start(ctx) } }
    }

    fun stop() {
        if (!started) return
        started = false
        modules.asReversed().forEach { m ->
            runCatching { m.stop(ctx) }
            ctx.extensions.removeOwner(m.id)
        }
        ctx.scope.cancel()
    }

    companion object {
        fun sort(modules: List<RwModule>): List<RwModule> {
            val byId = modules.associateBy { it.id }
            check(byId.size == modules.size) { "模块 id 重复：${modules.groupBy { it.id }.filter { it.value.size > 1 }.keys}" }
            val result = ArrayList<RwModule>(modules.size)
            val state = HashMap<String, Int>() // 1 = 访问中，2 = 完成
            fun visit(m: RwModule, chain: List<String>) {
                when (state[m.id]) {
                    2 -> return
                    1 -> error("模块循环依赖：${(chain + m.id).joinToString(" → ")}")
                }
                state[m.id] = 1
                m.dependsOn.forEach { dep ->
                    visit(byId[dep] ?: error("模块 ${m.id} 依赖的 $dep 不存在"), chain + m.id)
                }
                state[m.id] = 2
                result += m
            }
            modules.forEach { visit(it, emptyList()) }
            return result
        }
    }
}
