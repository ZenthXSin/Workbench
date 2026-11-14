package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.core.agent.AgentPoints
import io.github.zenthxsin.rustwright.core.agent.Toolset
import io.github.zenthxsin.rustwright.core.agent.Toolsets
import io.github.zenthxsin.rustwright.pic.impl.draw.PixelateImageTool
import io.github.zenthxsin.rustwright.pic.impl.rustpic.GenerateImageTool
import io.github.zenthxsin.rustwright.pic.impl.rustpic.ListImagesTool

import io.github.zenthxsin.rustwright.core.agent.AgentPersona
import io.github.zenthxsin.rustwright.core.agent.AgentScene
import io.github.zenthxsin.rustwright.core.agent.AgentService
import io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.pic.api.PicService
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.Screen
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import java.io.File
import io.github.zenthxsin.rustwright.core.account.NewApiAccount

/** 生图模块（原 RustPic）：提供 [PicService]，注册生图页与助手的生图工具。线路复用助手设置里的令牌与生图模型。 */
object PicModule : RwModule {
    override val id = "pic"
    override val dependsOn = listOf("agent")

    override fun register(ctx: RwContext) {
        ctx.services.provideLazy<io.github.zenthxsin.rustwright.pic.api.ImageWorkspace> { ProjectImageWorkspace(svgRasterizer = ctx.services.getOrNull()) }
        ctx.services.provideLazy<PicService> {
            PicServiceImpl(
                dir = File(ctx.platform.dataDir, "workspace/images"),
                events = ctx.events,
                channel = { ctx.service<AgentService>().channel },
                decoder = ArcImageDecoder,
                onBusy = { ctx.platform.backgroundWork("pic", it, "正在生成图片") },
                benefitRelay = { origin -> ctx.service<NewApiAccount>().freeRelay(origin).let { PicBenefitRelay(it.baseUrl, it.token, it.subject, it.imageModel) } },
                benefitReady = { ctx.service<NewApiAccount>().loggedIn },
                benefitOption = { ctx.service<NewApiAccount>().benefits().takeIf { it.generationAvailable }?.imageModel },
            )
        }
    }

    override fun init(ctx: RwContext) {
        val domains = ctx.extensions.all(io.github.zenthxsin.rustwright.pic.api.PicPoints.DOMAINS)
        val scenes = (listOf("pic") + domains.flatMap { it.scenes }).distinct().map(::AgentScene).toTypedArray()
        registerPicSkills(ctx)
        val service = ctx.service<PicService>()
        ctx.service<AgentToolRegistry>().apply {
            io.github.zenthxsin.rustwright.pic.impl.draw.projectRasterTools(ctx.service()).forEach { register(it, *scenes, toolset = "rustwright-pic") }
            // 画图与贴图：生图、图库（rustpic/），像素化（draw/）
            register(GenerateImageTool(service) { ctx.services.getOrNull<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>()?.projectDir() }, *scenes, toolset = "rustwright-pic")
            register(io.github.zenthxsin.rustwright.pic.impl.rustpic.StoryboardFrameTool(ctx.service(), service), *scenes, toolset = "rustwright-pic")
            register(ListImagesTool(service), *scenes, toolset = "rustwright-pic")
            register(PixelateImageTool(service), *scenes, toolset = "rustwright-pic")
        }
        ctx.extensions.add(AgentPoints.TOOLSETS, Toolset("rustwright-pic", "画图与贴图", "AI 生图、图库、像素化贴图", order = 10))
        ctx.service<AgentPersona>().setScenePrompt(
            AgentScene("pic"),
            "用户在生图页。先和用户把画面讨论清楚（主体、风格、配色、朝向），确定后再调用 generate_image；" +
                "游戏精灵可用 mode=sprite；尺寸、朝向和风格遵循用户要求。" +
                "免费生图与其他生成工具共享每日次数；普通线路按服务计费。不要连续多次试错，结果未确认时仅重试原请求。" + domains.joinToString("\n") { it.generationGuidance },
        )
        ctx.service<ScreenRegistry>().register(Screen("pic", "生图", order = 20, icon = Icons.palette) {
            PicScreen(service, ctx.platform, ctx.scope)
        })
        // 融合版「更多 → 生图」
        ctx.service<ScreenRegistry>().register(Screen("imagegen", "生图", order = 22, icon = Icons.palette) {
            ImageGenToolPage({ ctx.services.getOrNull<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>() }, service,
                { ctx.service<AgentService>().channel?.imageModel }, ctx.scope)
        })
        // 融合版「更多 → 调色板 / 切图与拼图」
        val projectCtx = { ctx.services.getOrNull<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>() }
        ctx.service<ScreenRegistry>().register(Screen("palette", "调色板", order = 23, icon = Icons.palette) { PaletteToolPage(projectCtx, ctx.scope) })
        ctx.service<ScreenRegistry>().register(Screen("slice", "切图与拼图", order = 24, icon = Icons.palette) { SliceToolPage(projectCtx, ctx.scope) })
        // 不进「更多」网格（group = null），工具台首页和助手按 id 打开
        run {
            val U = io.github.zenthxsin.rustwright.ui.kit.UiPoints
            val I = io.github.zenthxsin.rustwright.ui.kit.PI
            fun tool(id: String, name: String, icon: String, desc: String, build: () -> arc.scene.Element) =
                ctx.extensions.add(U.TOOLS, io.github.zenthxsin.rustwright.ui.kit.ToolPage(id, name, icon, null, desc = { desc }, fresh = true, build = build))
            tool("pixel", "像素化", I.grid, "把图片转成像素贴图") { PixelToolPage(projectCtx, ctx.scope) }
            tool("imagegen", "生图", I.bolt, "用文字生成贴图") {
                ImageGenToolPage(projectCtx, service, { ctx.service<AgentService>().channel?.imageModel }, ctx.scope)
            }
            tool("palette", "调色板", I.palette, "提取、减少贴图的颜色") { PaletteToolPage(projectCtx, ctx.scope) }
            tool("slice", "切图与拼图", I.crop, "切帧、拼成序列图") { SliceToolPage(projectCtx, ctx.scope) }
        }
        // 融合版「更多 → 像素化」
        ctx.service<ScreenRegistry>().register(Screen("pixel", "像素化", order = 21, icon = Icons.palette) {
            PixelToolPage({ ctx.services.getOrNull<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>() }, ctx.scope)
        })
    }
}
