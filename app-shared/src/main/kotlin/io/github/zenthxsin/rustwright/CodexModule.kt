package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import io.github.zenthxsin.rustwright.core.agent.AgentService
import io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry
import io.github.zenthxsin.rustwright.core.agent.ExecutionMode
import io.github.zenthxsin.rustwright.core.agent.ProjectAware
import io.github.zenthxsin.rustwright.core.codex.CodexLauncher
import io.github.zenthxsin.rustwright.core.codex.CodexMode
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexTool
import io.github.zenthxsin.rustwright.core.codex.CodexToolResult
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.core.codex.CodexToolset
import io.github.zenthxsin.rustwright.core.codex.McpBridge
import io.github.zenthxsin.rustwright.core.agent.AgentPoints
import io.github.zenthxsin.rustwright.core.agent.Toolset
import io.github.zenthxsin.rustwright.core.storage.KeyValueStore
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.runtime.RwLog
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.workspace.WorkspaceService
import io.github.zenthxsin.rustwright.core.runtime.AppIdentity
import io.github.zenthxsin.rustwright.core.codex.CodexPoints
import io.github.zenthxsin.rustwright.core.codex.CodexCallTool
import io.github.zenthxsin.rustwright.ui.agent.AgentSettingsScreen
import io.github.zenthxsin.rustwright.ui.codex.CodexHost
import io.github.zenthxsin.rustwright.ui.codex.CodexScreen
import io.github.zenthxsin.rustwright.ui.designsystem.ArcDispatcher
import io.github.zenthxsin.rustwright.ui.designsystem.Icons
import io.github.zenthxsin.rustwright.ui.designsystem.NavigateTo
import io.github.zenthxsin.rustwright.ui.designsystem.Screen
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.Toasts
import io.github.zenthxsin.rustwright.core.agent.ToolRisk
import arc.scene.ui.layout.Table
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.zenthxsin.rustwright.core.codex.CodexThreadOptions
import io.github.zenthxsin.rustwright.core.account.NewApiAccount
import io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 助手 = 本机 Codex：app-server 跑在内置的 Alpine 虚拟环境里，模型请求走用户的线路（Responses 接口），
 * Workbench 自己的生图 / 地图 / 模组检查工具以动态工具交给 Codex，在 App 内执行。
 */
object CodexModule : RwModule {
    override val id = "codex"

    /** Codex 自带读写文件与命令；这些旧工具不再交给它。 */
    private val NATIVE_DUPLICATES = setOf(
        "run_command",
        "list_files", "read_file", "grep", "glob", "write_file", "apply_diff", "delete", "create_directory", "move_file",
    )

    override fun register(ctx: RwContext) {
        val stateDispatcher = ctx.platform.persistentDispatcher(ArcDispatcher)
        ctx.scope.coroutineContext[Job]?.invokeOnCompletion { ctx.platform.backgroundWork("codex", false) }
        ctx.services.provideLazy {
            val agent = ctx.service<AgentService>()
            val linux = ctx.services.getOrNull<LinuxEnvironment>()
            val account = ctx.service<NewApiAccount>()
            var launchedFreeKey: String? = null
            var launchedFreeSubject: String? = null
            var launchedFreeOrigin: String? = null
            var launchedPaidKey: String? = null
            var launchedPaidBase: String? = null
            var launchedCustom: io.github.zenthxsin.rustwright.core.account.CustomRelayRoute.Route? = null
            val customRoute = ctx.service<io.github.zenthxsin.rustwright.core.account.CustomRelayRoute>()
            val bridge = mcpBridge(ctx, stateDispatcher)
            val launcher = CodexLauncher {
                check(ctx.platform.signatureOk) { SIGNATURE_BAD }
                val env = linux ?: error("没有内置 Linux，无法启动 Codex")
                // 技能同步是可选准备步骤；路径/权限问题不能使所有原生指令启动失败。
                try {
                    val skillHome = if (env.managed) env.toHost("/root") else File(System.getProperty("user.home"))
                    val skillRoot = File(skillHome, ".agents/skills")
                    val bundled = ctx.extensions.all(AgentPoints.SKILLS)
                    runBlocking(Dispatchers.IO) {
                        BundledSkillSync(skillRoot, skillHome).sync(bundled, disabledToolsets(ctx)).warnings.forEach {
                            RwLog.w("skills", it)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { RwLog.w("skills", "随包技能同步失败：${e.message}") }
                // 管理员自定义线路：付费模型改走自填的地址和密钥（NewAPI 令牌不发往该地址）
                val custom = customRoute.active
                launchedCustom = custom
                val token = custom?.apiKey ?: agent.token?.takeIf { it.isNotBlank() }
                val freeKey = if (account.loggedIn) runBlocking(Dispatchers.IO) { account.ensureRelayKey() } else null
                check(token != null || freeKey != null) { "请先登录 NewAPI" }
                launchedFreeKey = freeKey
                launchedFreeSubject = account.subject
                launchedFreeOrigin = RelayNodes.origin
                launchedPaidKey = token
                // 启动 Codex 前再过一次白名单（令牌只发给内置中转站）
                val base = custom?.baseUrl ?: if (token == null) "${RelayNodes.origin}/v1" else RelayNodes.trusted(agent.settings.baseUrl)
                require(base.startsWith("http") && "'" !in base && "\"" !in base) { "服务地址无效：$base" }
                launchedPaidBase = base
                val bin = System.getProperty("rw.codex") ?: "codex"
                val provider = "{name=\"Workbench\",base_url=\"$base\",env_key=\"NEW_API_KEY\",wire_api=\"responses\",request_max_retries=5,stream_max_retries=5}"
                val freeProvider = "{name=\"Workbench 免费\",base_url=\"${launchedFreeOrigin}/v1\",env_key=\"RUSTWRIGHT_BENEFIT_KEY\",wire_api=\"responses\",request_max_retries=5,stream_max_retries=5}"
                val log = if (env.managed) "/tmp/codex-app-server.log" else File(System.getProperty("java.io.tmpdir"), "codex-app-server.log").path
                // 每组 Workbench 工具是一个独立的 MCP 服务器，经 App 内的 McpBridge 提供
                val port = bridge.start()
                val mcp = toolsets(ctx, stateDispatcher).joinToString("") { set ->
                    "-c 'mcp_servers.${set.id}.url=\"http://127.0.0.1:$port/mcp/${set.id}\"' " +
                        "-c 'mcp_servers.${set.id}.bearer_token_env_var=\"${McpBridge.TOKEN_ENV}\"' "
                }
                // 默认保留 code mode；host 缺失、版本不符或无法启动时回退到直接调用工具。
                // 中转兼容和 MCP 可见性仍需要关闭 responses_lite / supports_search_tool。
                val catalog = if (env.managed) "/root/.codex/rw-models.json" else File(System.getProperty("java.io.tmpdir"), "rw-models.json").path
                val catalogPrep = codexCatalogPreparation(bin, catalog,
                    host = if (env.managed) "/opt/codex/codex-code-mode-host" else null,
                    versionFile = if (env.managed) "/opt/codex/code-mode-host.version" else null,
                )
                env.start(
                    "export HOME=${shellQuote(if (env.managed) "/root" else System.getProperty("user.home"))}; " + catalogPrep +
                    "set --; if [ -s ${shellQuote(catalog)} ]; then set -- -c ${shellQuote("model_catalog_json=\"$catalog\"")}; fi; " +
                    "exec ${shellQuote(bin)} \"\$@\" -c 'model_provider=\"newapi\"' -c 'model_providers.newapi=$provider' " +
                        "-c 'model_providers.rustwright_free=$freeProvider' " +
                        mcp + "-c 'sandbox_mode=\"danger-full-access\"' -c analytics.enabled=false -c check_for_update_on_startup=false app-server --listen stdio:// 2>>$log",
                    "/root", buildMap {
                        put(McpBridge.TOKEN_ENV, bridge.token)
                        token?.let { put("NEW_API_KEY", it) }
                        freeKey?.let { put("RUSTWRIGHT_BENEFIT_KEY", it) }
                    }, mergeStderr = false,
                )
            }
            CodexWorkbench(File(ctx.platform.dataDir, "codex"), launcher, stateDispatcher, ctx.scope,
                defaultModel = { agent.settings.chatModel.ifBlank { "gpt-5.6-sol" } },
                loadInBackground = true,
            ).apply {
                prepareThread = { conversation ->
                    if (CodexWorkbench.isFreeModel(conversation.model)) {
                        check(account.loggedIn) { "使用免费福利请先登录 NewAPI" }
                        val relay = account.freeRelay(launchedFreeOrigin)
                        check(conversation.model in relay.textModels) { "该免费对话模型暂不可用，请重新选择" }
                        check(relay.subject == launchedFreeSubject && relay.token == launchedFreeKey) { "登录账号已变化，请在设置中重新启动${AppBrand.identity.assistant}" }
                        CodexThreadOptions("rustwright_free", buildJsonObject {
                            put("web_search", "disabled")
                            put("features.multi_agent", false)
                        })
                    } else {
                        val custom = customRoute.active
                        if (custom != null || launchedCustom != null) {
                            check(custom == launchedCustom) { "自定义线路已变化，请在开发者工具里重启 Codex 内核" }
                        } else {
                            check(!agent.token.isNullOrBlank()) { "付费模型需要先登录 NewAPI" }
                            check(agent.token == launchedPaidKey && RelayNodes.trusted(agent.settings.baseUrl) == launchedPaidBase) { "线路已变化，请在设置中重新启动${AppBrand.identity.assistant}" }
                        }
                        CodexThreadOptions()
                    }
                }
                tools = { toolsets(ctx, stateDispatcher).flatMap { it.tools } }
                instructions = { p -> projectInstructions(ctx, p) }
                onBusy = {
                    ctx.platform.backgroundWork("codex", it, "${AppBrand.identity.assistant}正在执行任务")
                    ctx.events.post(io.github.zenthxsin.rustwright.core.agent.AgentBusy(it))
                }
                onConversationDeleted = { id ->
                    ctx.extensions.all(CodexPoints.LIFECYCLE).forEach { lifecycle -> lifecycle.deleted(id) }
                }
                onTurnFinished = { conversation, turn ->
                    ctx.extensions.all(CodexPoints.LIFECYCLE).forEach { lifecycle -> lifecycle.finished(conversation, turn) }
                }


            }
        }
    }

    private var bridge: McpBridge? = null

    private fun mcpBridge(ctx: RwContext, stateDispatcher: CoroutineDispatcher): McpBridge = bridge ?: McpBridge(
        toolsets = { toolsets(ctx, stateDispatcher) },
        call = { _, tool, args, threadId, turnId -> ctx.service<CodexWorkbench>().runMcpTool(tool, args, threadId, turnId) },
    ).also { bridge = it }

    /** 停用的工具组（连接器里关掉的），存在设置里。 */
    private const val DISABLED_KEY = "codex.toolsets.disabled"

    fun disabledToolsets(ctx: RwContext): Set<String> =
        ctx.service<KeyValueStore>().get(DISABLED_KEY, SetSerializer(String.serializer())).orEmpty()

    /** 开关一组工具；下次启动助手生效（调用方负责重启）。 */
    fun setToolsetEnabled(ctx: RwContext, id: String, enabled: Boolean) {
        val next = disabledToolsets(ctx).let { if (enabled) it - id else it + id }
        ctx.service<KeyValueStore>().put(DISABLED_KEY, SetSerializer(String.serializer()), next)
    }

    /** 全部工具组（含停用的），按注册顺序；没有工具的组不算。 */
    fun allToolsets(ctx: RwContext): List<Pair<Toolset, Int>> {
        val counts = ctx.service<AgentToolRegistry>().byToolset().mapValues { (_, v) -> v.count { it.exposeToAssistant && it.name !in NATIVE_DUPLICATES } }
        return ctx.extensions.all(AgentPoints.TOOLSETS).mapNotNull { set -> counts[set.id]?.takeIf { it > 0 }?.let { set to it } }
    }

    /** 交给助手的工具组（启用且有工具的），每组一个 MCP 服务器。 */
    private fun toolsets(ctx: RwContext, stateDispatcher: CoroutineDispatcher): List<CodexToolset> {
        val disabled = disabledToolsets(ctx)
        val grouped = ctx.service<AgentToolRegistry>().byToolset()
        return ctx.extensions.all(AgentPoints.TOOLSETS).filter { it.id !in disabled }.mapNotNull { set ->
            val tools = grouped[set.id].orEmpty().filter { it.exposeToAssistant && it.name !in NATIVE_DUPLICATES }
            if (tools.isEmpty()) null else CodexToolset(set.id, set.title, set.description, codexTools(ctx, stateDispatcher, tools))
        }
    }

    private fun codexTools(ctx: RwContext, stateDispatcher: CoroutineDispatcher, tools: List<io.github.zenthxsin.rustwright.core.agent.Tool>): List<CodexTool> {
        val agent = ctx.service<AgentService>()
        return tools.map { tool ->
            // 锈匠的写入类工具自己带审批（diff 预览 + 检查报告），这里只给其余会改东西或花钱的工具加审批
            val selfApproving = tool.handlesApproval
            CodexTool(tool.name, tool.description, tool.parameters, needsApproval = !selfApproving && tool.risk != ToolRisk.READ) { call ->
                // 自动复盘只获准写应用生成的技能；只读会话仍由工具自身明确拒绝。
                if (tool is CodexCallTool) return@CodexTool tool.executeCall(call)
                if (tool is ProjectAware) {
                    val r = executeProjectTool(tool, call, ctx.services.getOrNull<LinuxEnvironment>())
                    return@CodexTool CodexToolResult(!r.isError, r.toAgent)
                }
                val mode = when (call.mode) {
                        CodexMode.ASK -> ExecutionMode.PLAN
                        CodexMode.CODE -> ExecutionMode.ASK
                        CodexMode.AUTO, CodexMode.FULL -> ExecutionMode.AUTO
                }
                val directory = call.project?.let { p ->
                    runCatching {
                        val linux = ctx.services.getOrNull<LinuxEnvironment>()
                        require(if (linux?.managed == true) p.path.startsWith('/') else File(p.path).isAbsolute)
                        val root = (if (linux?.managed == true) linux.toHost(p.path) else File(p.path)).canonicalFile
                        require(root.isDirectory)
                        if (linux?.managed == true) require(linux.toGuest(root) == p.path.trimEnd('/'))
                        root
                    }.getOrNull()
                }
                val context = io.github.zenthxsin.rustwright.core.agent.ToolContext(
                    "codex", call.conversation.id, agent.interaction, directory, mode) {
                    call.conversation.mode == call.mode && call.conversation.projectId == call.project?.id
                }
                val r = tool.execute(context, call.arguments)
                CodexToolResult(!r.isError, r.toAgent)
            }
        }
    }

    internal fun projectInstructions(ctx: RwContext, p: CodexProject): String = buildString {
        append(ctx.services.getOrNull<AppIdentity>()?.instructions ?: AppIdentity().instructions)
        val disabled = disabledToolsets(ctx)
        ctx.extensions.all(AgentPoints.INSTRUCTIONS).filter { it.toolset !in disabled }.forEach {
            append("\n").append(it.text).append("\n")
        }
        ctx.extensions.all(CodexPoints.INSTRUCTIONS).filter { it.toolset == null || it.toolset !in disabled }.forEach {
            it.text(p)?.takeIf(String::isNotBlank)?.let { text -> append("\n").append(text).append("\n") }
        }
        append(" Files tagged with \"${io.github.zenthxsin.rustwright.fused.Attachments.TAG.trim()}\" are user attachments relative to this project, under ${io.github.zenthxsin.rustwright.fused.Attachments.DIR}/.")
        append(" Inspect attached images and read other files before using them. Copy attachments into the project if the user wants to keep them.")
    }

    override fun init(ctx: RwContext) {
        val wb by lazy { ctx.service<CodexWorkbench>() }
        val workspace = ctx.service<WorkspaceService>()
        val linux = ctx.services.getOrNull<LinuxEnvironment>()
        val agent = ctx.service<AgentService>()

        fun syncProjects() {
            workspace.projects().forEach { project ->
                val path = if (linux?.managed == true) linux.toGuest(project.directory) else project.directory.absolutePath
                if (path != null && wb.projects.none { it.path == path }) wb.openProject(project.name, path)
            }
        }

        // 侧栏和头像显示的账号名（登录后异步取一次）
        ctx.scope.launch {
            runCatching { ctx.service<io.github.zenthxsin.rustwright.core.account.NewApiAccount>().info().name }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { accountNameCache = it }
        }
        val host = object : CodexHost {
            override fun blocker(): Pair<String, Pair<String, () -> Unit>>? {
                if (agent.token.isNullOrBlank() && !ctx.service<NewApiAccount>().loggedIn && ctx.service<io.github.zenthxsin.rustwright.core.account.CustomRelayRoute>().active == null) return "请先登录 NewAPI，登录后免费额度和专用线路自动配置。" to ("去登录" to { openSettings() })
                if (linux == null) return "这个平台没有内置 Linux，Codex 无法运行。" to ("知道了" to {})
                if (linux.managed && (!linux.installed || !linux.toHost("/usr/local/bin/codex").exists()))
                    return "需要先安装并初始化虚拟环境（会装好 Codex）。" to ("去终端" to { ctx.events.post(NavigateTo("terminal")) })
                return null
            }

            override fun openSettings() { showSettings?.invoke() }

            override fun openEnvironment() { ctx.events.post(NavigateTo("terminal")) }

            override fun back() { ctx.events.post(NavigateTo("home")) }

            override val accountName: String get() = accountNameCache

            override fun copy(text: String) = ctx.platform.setClipboardText(text)

            override suspend fun export(fileName: String, content: String, mimeType: String) {
                val safe = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val file = withContext(Dispatchers.IO) { File(ctx.platform.cacheDir, safe).apply { writeText(content) } }
                ctx.platform.exportFile(file, safe, mimeType)
            }

            override suspend fun createProject(): CodexProject? {
                val name = Dialogs.input("新建项目", "项目名，中英文都可以")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val project = runCatching { withContext(Dispatchers.IO) { workspace.create(name) } }
                    .getOrElse { Toasts.show("创建失败：${it.message}"); return null }
                val path = if (linux?.managed == true) linux.toGuest(project.directory) else project.directory.absolutePath
                return path?.let { wb.openProject(project.name, it) }
            }

            override fun listFiles(project: CodexProject): List<String> {
                val dir = if (linux?.managed == true) linux.toHost(project.path) else File(project.path)
                return dir.walkTopDown().onEnter { it.name != ".git" && it.name != "node_modules" && !it.name.startsWith(".rw") }
                    .filter { it.isFile }.map { it.relativeTo(dir).path.replace('\\', '/') }.sorted().toList()
            }

            override suspend fun pickAttachment(project: CodexProject): io.github.zenthxsin.rustwright.ui.codex.CodexAttachment? = pickAttachments(project).firstOrNull()

            override suspend fun pickAttachments(project: CodexProject): List<io.github.zenthxsin.rustwright.ui.codex.CodexAttachment> {
                val picked = ctx.platform.pickFiles(listOf("*/*"))
                val dir = hostDir(project)
                return withContext(Dispatchers.IO) {
                    picked.map { file ->
                        val target = io.github.zenthxsin.rustwright.core.runtime.ImportedFiles.copy(file, File(dir, ".rw-attachments"))
                        val image = target.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
                        io.github.zenthxsin.rustwright.ui.codex.CodexAttachment("${project.path}/.rw-attachments/${target.name}", target.name, image)
                    }
                }
            }

            private fun hostDir(project: CodexProject) = if (linux?.managed == true) linux.toHost(project.path) else File(project.path)

            override fun fileSize(project: CodexProject, relativePath: String): Long? =
                File(hostDir(project), relativePath).takeIf { it.isFile }?.length()

            override suspend fun importFile(project: CodexProject, dir: String): Boolean {
                val picked = ctx.platform.pickFiles(listOf("*/*"))
                if (picked.isEmpty()) return false
                return withContext(Dispatchers.IO) {
                    runCatching {
                        val root = hostDir(project).canonicalFile
                        val dest = File(root, dir).canonicalFile
                        require(dest.toPath().startsWith(root.toPath()))
                        picked.forEach { io.github.zenthxsin.rustwright.core.runtime.ImportedFiles.copy(it, dest) }
                        true
                    }.getOrDefault(false)
                }
            }

            override fun createFile(project: CodexProject, relativePath: String): Boolean = runCatching {
                val f = File(hostDir(project), relativePath)
                if (f.exists()) return@runCatching false
                f.parentFile?.mkdirs(); f.createNewFile()
            }.getOrDefault(false)

            override fun openFile(project: CodexProject, relativePath: String) {
                val root = hostDir(project).canonicalFile
                val file = File(root, relativePath).canonicalFile
                if (!file.toPath().startsWith(root.toPath()) || !file.isFile) return Toasts.show("文件不存在或路径无效")
                ctx.services.getOrNull<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>()?.openTool("file", file)
            }


        }

        ctx.service<ScreenRegistry>().register(Screen("agent", "${AppBrand.identity.assistant}", order = 10, icon = Icons.agent) {
            val root = Table()
            lateinit var showChat: () -> Unit
            showSettings = {
                root.clearChildren()
                root.add(AgentSettingsScreen(ctx.service(), ctx.service(), ctx.service(), ctx.scope, { ctx.events.post(it) }) {
                    wb.restart()
                    showChat()
                }).grow()
            }
            val screen by lazy { syncProjects(); CodexScreen(wb, host, ctx.scope) }
            showChat = { root.clearChildren(); syncProjects(); root.add(screen).grow() }
            showChat()
            root
        })
    }

    private var showSettings: (() -> Unit)? = null
    @Volatile private var accountNameCache = ""
}

/** 安装包签名不对时的统一提示。 */
internal val SIGNATURE_BAD get() = "安装包签名校验没通过，可能被修改过。为了账号安全已停用登录和${AppBrand.identity.assistant}，请从官方渠道重新下载 Workbench。"
