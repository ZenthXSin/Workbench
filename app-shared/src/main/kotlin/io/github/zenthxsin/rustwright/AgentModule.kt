package io.github.zenthxsin.rustwright

import kotlinx.coroutines.launch
import io.github.zenthxsin.rustwright.core.account.NewApiAccount
import io.github.zenthxsin.rustwright.core.agent.AgentPersona
import io.github.zenthxsin.rustwright.core.agent.AgentService
import io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry
import io.github.zenthxsin.rustwright.core.agent.Skills
import io.github.zenthxsin.rustwright.core.ai.model.ModelCatalogClient
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwLog
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.storage.KeyValueStore
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import io.github.zenthxsin.rustwright.ui.agent.AgentPorts
import java.io.File

/**
 * 助手：在 register 阶段提供工具注册表、人设、Agent 服务、账号与模型目录，供功能模块在 init 阶段注册工具与场景提示；
 * 页面由 [CodexModule] 注册（助手的执行核心是本机 Codex）。
 */
object AgentModule : RwModule {
    override val id = "agent"

    override fun register(ctx: RwContext) {
        val secrets = ctx.service<SecretStore>()
        ctx.services.provide(KeyValueStore(File(ctx.platform.dataDir, "settings.json")))
        ctx.services.provide(AgentToolRegistry())
        ctx.services.provideLazy { io.github.zenthxsin.rustwright.core.agent.ToolCapabilityCatalog(ctx.service(), ctx.extensions) }
        ctx.services.provide(AgentPersona())
        ctx.services.provide(ModelCatalogClient())
        val account = NewApiAccount(secrets, ctx.platform)
        ctx.services.provide(account)
        ctx.services.provide(io.github.zenthxsin.rustwright.core.account.CustomRelayRoute(secrets) { account.subject })
        ctx.services.provideLazy { AgentService(ctx.service(), secrets, ctx.service(), AgentPorts, AgentPorts) }
    }

    override fun init(ctx: RwContext) {
        runCatching { Skills.install(Skills.fromAssets(ctx.platform, "skills")) }
            .onFailure { RwLog.w("agent", "随包技能加载失败", it) }
        // 中转站节点测速：服务地址还是默认节点（没手填自定义地址）时，换成最快能通的那个
        ctx.scope.launch {
            val origin = runCatching { io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.probe() }.getOrNull() ?: return@launch
            // 只记第几个节点，不把地址打进日志（地址不明文存放，见 Endpoints）
            RwLog.i("agent", "中转站节点：#${io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.ORIGINS.indexOf(origin)}")
            val agent = ctx.service<AgentService>()
            val cur = agent.settings.baseUrl
            if (cur.isBlank() || io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.isKnown(cur)) {
                val next = "$origin/v1"
                if (cur.trimEnd('/') != next) agent.updateSettings { it.copy(baseUrl = next) }
            }
        }
    }
}
