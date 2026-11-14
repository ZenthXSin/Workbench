package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.agent.AgentScene
import io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.shell.AgentShell
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.Screen
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.kit.MoreGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.ToolPage
import io.github.zenthxsin.rustwright.ui.kit.UiPoints

/**
 * 内置 Linux：终端页、助手的 [AgentShell]（命令和文件操作在终端里可见地执行）、run_command 工具（全局可用）。
 * 依赖平台提供的 [LinuxEnvironment]。
 */
object LinuxModule : RwModule {
    override val id = "linux"
    override val dependsOn = listOf("agent")

    private var sessions: TerminalSessions? = null

    override fun register(ctx: RwContext) {
        val env = ctx.services.getOrNull<LinuxEnvironment>() ?: return
        val dispatcher = ctx.platform.persistentDispatcher(ArcDispatcher)
        val s = TerminalSessions(env, ctx.scope, dispatcher).also { sessions = it }
        ctx.services.provide<AgentShell>(TerminalAgentShell(env, s, dispatcher))
    }

    override fun init(ctx: RwContext) {
        val env = ctx.services.getOrNull<LinuxEnvironment>() ?: return
        val sessions = sessions ?: return
        val shell = ctx.service<AgentShell>()
        ctx.service<AgentToolRegistry>().register(RunCommandTool(shell), AgentScene.ALWAYS)
        ctx.services.provide(LinuxProvisioner(
            env, shell, ctx.scope,
            diagnosticsDir = java.io.File(ctx.platform.dataDir, "linux-diagnostics"),
            onBusy = { ctx.platform.backgroundWork("linux-setup", it, "正在准备虚拟环境") },
            addons = { ctx.extensions.all(io.github.zenthxsin.rustwright.core.shell.EnvironmentPoints.ADDONS) },
        ))
        val terminal = {
            FusedTerminal(env, sessions, shell, ctx, ctx.scope) { ctx.events.post(io.github.zenthxsin.rustwright.ui.designsystem.NavigateTo("env")) }
        }
        val envPage = {
            EnvToolPage(env, ctx.services.getOrNull<LinuxProvisioner>(), ctx.platform.dataDir,
                { ctx.services.getOrNull<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>() }, ctx.scope)
        }
        ctx.service<ScreenRegistry>().register(Screen("env", "环境管理", order = 91, icon = Icons.terminal, build = envPage))
        ctx.service<ScreenRegistry>().register(Screen("terminal", "终端", order = 90, icon = Icons.terminal, build = terminal))
        // 「更多 → 环境」
        val status = { LinuxStatus.of(env, ctx.services.getOrNull<LinuxProvisioner>()).detail }
        ctx.extensions.add(UiPoints.MORE_GROUPS, MoreGroup(UiPoints.GROUP_ENV, "环境", order = 0))
        ctx.extensions.add(UiPoints.TOOLS, ToolPage("terminal", "终端", PI.term, UiPoints.GROUP_ENV, order = 0,
            desc = { "Alpine Linux · " + status() }, build = terminal))
        ctx.extensions.add(UiPoints.TOOLS, ToolPage("env", "环境管理", PI.server, UiPoints.GROUP_ENV, order = 10,
            desc = { "软件包、存储、重装系统" }, ask = "帮我看看虚拟环境：装了哪些软件包、占了多少空间", fresh = true, build = envPage))
    }

    override fun stop(ctx: RwContext) {
        sessions?.shutdown()
        sessions = null
    }
}
