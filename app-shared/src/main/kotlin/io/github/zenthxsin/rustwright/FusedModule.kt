package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.scene.Element
import arc.scene.ui.layout.Table
import io.github.zenthxsin.rustwright.core.agent.AgentService
import io.github.zenthxsin.rustwright.core.account.NewApiAccount
import io.github.zenthxsin.rustwright.core.account.NewApiBenefits
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexWorkbench
import io.github.zenthxsin.rustwright.core.runtime.RwContext
import io.github.zenthxsin.rustwright.core.runtime.RwModule
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.fused.Artifact
import io.github.zenthxsin.rustwright.fused.BenefitsPage
import io.github.zenthxsin.rustwright.fused.BenefitsState
import io.github.zenthxsin.rustwright.fused.EnvStatus
import io.github.zenthxsin.rustwright.fused.environmentStatus
import io.github.zenthxsin.rustwright.fused.FusedHost
import io.github.zenthxsin.rustwright.fused.FusedShell
import io.github.zenthxsin.rustwright.fused.SettingRow
import io.github.zenthxsin.rustwright.fused.ToolEntry
import io.github.zenthxsin.rustwright.fused.ToolGroup
import io.github.zenthxsin.rustwright.core.workspace.WorkspaceService
import io.github.zenthxsin.rustwright.core.runtime.AppIdentity
import io.github.zenthxsin.rustwright.ui.agent.AgentSettingsScreen
import io.github.zenthxsin.rustwright.ui.designsystem.Lucide
import io.github.zenthxsin.rustwright.ui.designsystem.ScreenRegistry
import io.github.zenthxsin.rustwright.ui.kit.K
import io.github.zenthxsin.rustwright.ui.kit.MoreGroup
import io.github.zenthxsin.rustwright.ui.kit.PI
import io.github.zenthxsin.rustwright.ui.kit.SettingItem
import io.github.zenthxsin.rustwright.ui.kit.SettingSection
import io.github.zenthxsin.rustwright.ui.kit.ToolPage
import io.github.zenthxsin.rustwright.ui.kit.UiPoints
import io.github.zenthxsin.rustwright.ui.widgets.Dialogs
import io.github.zenthxsin.rustwright.ui.widgets.MarkdownView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.builtins.serializer
import io.github.zenthxsin.rustwright.ui.designsystem.dp

/** 外壳构造器：有它时 [RustwrightApp] 用融合版界面代替底部导航。 */
class FusedShellProvider(val build: () -> FusedShell)

/**
 * 融合版界面（一个助手 + 侧边栏 + 产物 / 项目 / 设置 / 更多）的装配：把 Codex 工作台、模组、生图、地图、终端接到 [FusedShell]。
 */
object FusedModule : RwModule {
    override val id = "fused"
    private const val KEEPALIVE_PROMPTED = "keepalive.prompted"
    private const val HOME_TOOL_FIRST = "home.toolFirst"

    override fun init(ctx: RwContext) {
        io.github.zenthxsin.rustwright.fused.Version.name = ctx.platform.appVersion
        val host = Host(ctx)
        ctx.services.provide(FusedShellProvider {
            FusedShell(host).also { sh ->
                host.shell = sh
                // 首页样式（对话 / 工具台）记住上次的选择
                val kv = ctx.service<io.github.zenthxsin.rustwright.core.storage.KeyValueStore>()
                sh.toolFirst = runCatching { kv.get(HOME_TOOL_FIRST, Boolean.serializer()) }.getOrNull() == true
                sh.onToolFirstChanged = { v -> runCatching { kv.put(HOME_TOOL_FIRST, Boolean.serializer(), v) } }
                // 签名不对：一打开就说清楚（登录、助手都已停用）
                if (!ctx.platform.signatureOk) arc.Core.app.post { sh.notify(SIGNATURE_BAD, 12f) {} }
            }
        })
        // 工具页和贡献项拿当前项目和外壳能力
        host.ui = object : io.github.zenthxsin.rustwright.ui.kit.ProjectContext {
            override fun projectDir(): File? = host.projectDirPublic()
            override fun projectName(): String = host.shell?.projectName() ?: "未选择项目"
            override fun askRust(text: String) { host.shell?.askRust(text) }
            override fun notify(text: String) { host.shell?.notify(text) }
            override fun suggest(text: String, open: () -> Unit) { host.shell?.notify(text, 6f, open) }
            override fun sheet(page: io.github.zenthxsin.rustwright.ui.kit.SheetPage) { host.shell?.sheets?.open(page) }
            override fun back() { host.shell?.requestBack() }
            override fun closeSheet() { host.shell?.sheets?.close() }
            override fun openTool(id: String, file: File?) {
                if (ctx.extensions.get(ShellPoints.COMMANDS, id)?.execute?.invoke(file) == true) return
                if (id == "file" && file != null) { host.shell?.openFile(file); return }
                file?.let { io.github.zenthxsin.rustwright.ui.kit.ToolRequests.offer(id, it) }
                host.shell?.openToolId(id)
            }
            override fun openPage(title: String, content: Element, desc: String) { host.shell?.openTool(title, content, desc = desc) }
            override fun projects(): List<io.github.zenthxsin.rustwright.ui.kit.ProjectRef> {
                val cur = host.currentProjectId()
                return host.wb.projects.filter { !it.archived }.map { io.github.zenthxsin.rustwright.ui.kit.ProjectRef(it.id, it.name, host.hostDirPublic(it), it.id == cur) }
            }
            override fun switchProject(id: String) { if (host.wb.project(id)?.archived == false) host.setCurrentProject(id) }
            override suspend fun newProject(): Boolean = host.createProject()?.also { host.setCurrentProject(it.id) } != null
            override suspend fun importFile(): File? = ctx.platform.pickFile(listOf("*/*"))
            override suspend fun importFiles(): List<File> = ctx.platform.pickFiles(listOf("*/*"))
            override suspend fun importProject(type: String?): File? {
                val p = host.importProject(type) ?: return null
                host.setCurrentProject(p.id)
                return host.projectDirPublic()
            }
            override suspend fun exportFile(file: File, name: String, mime: String): Boolean = ctx.platform.exportFile(file, name, mime)
            override fun shareFile(file: File, mime: String) = ctx.platform.shareFile(file, mime)
            override fun cacheDir(): File = ctx.platform.cacheDir
        }
        ctx.services.provide<io.github.zenthxsin.rustwright.ui.kit.ProjectContext>(host.ui!!)
        host.registerSettings()
        // 第一次干活时，悬浮窗或电池限制还没开就提醒一次
        ctx.events.on<io.github.zenthxsin.rustwright.core.agent.AgentBusy>(ctx.scope) { e ->
            if (!e.active) return@on
            val kv = ctx.service<io.github.zenthxsin.rustwright.core.storage.KeyValueStore>()
            if (kv.get(KEEPALIVE_PROMPTED, Boolean.serializer()) == true) return@on
            if (ctx.platform.keepAliveItems().none { it.essential && it.granted == false }) return@on
            kv.put(KEEPALIVE_PROMPTED, Boolean.serializer(), true)
            arc.Core.app.post { host.keepAliveSheet(lead = true) }
        }
        // 本体基础工具组：和 App 界面打交道的工具
        ctx.extensions.add(io.github.zenthxsin.rustwright.core.agent.AgentPoints.TOOLSETS, io.github.zenthxsin.rustwright.core.agent.Toolset(
            io.github.zenthxsin.rustwright.core.agent.Toolsets.CORE, "本体基础", "在 App 里打开文件和页面、查看环境状态", order = 0))
        ctx.service<io.github.zenthxsin.rustwright.core.agent.AgentToolRegistry>().apply {
            val core = io.github.zenthxsin.rustwright.core.agent.Toolsets.CORE
            register(OpenInAppTool { host.ui }, toolset = core)
            register(ShowPageTool(ctx.extensions) { host.ui }, toolset = core)
            register(AppStatusTool {
                val tools = ctx.extensions.all(io.github.zenthxsin.rustwright.ui.kit.UiPoints.TOOLS).joinToString(", ") { "${it.id} (${it.name})" }
                "Built-in Linux: ${host.env().detail}\nProject open in the app: ${host.shell?.projectName() ?: "none"}\nTool pages: $tools"
            }, toolset = core)
        }
    }

    private class Host(private val ctx: RwContext) : FusedHost {
        var shell: FusedShell? = null
        override var ui: io.github.zenthxsin.rustwright.ui.kit.ProjectContext? = null
        private val ext get() = ctx.extensions
        override val wb: CodexWorkbench by lazy { ctx.service() }
        override val scope: CoroutineScope get() = ctx.scope
        private val agent by lazy { ctx.service<AgentService>() }
        private val linux = ctx.services.getOrNull<LinuxEnvironment>()
        private val workspace by lazy { ctx.service<WorkspaceService>() }
        private val screens = ctx.service<ScreenRegistry>()
        /** 缓存的工具页；换深浅色（含跟随系统自动切换）时清掉，否则旧页面上建时拷贝的颜色会和新配色混在一起。 */
        private val built = HashMap<String, Element>().also { m -> io.github.zenthxsin.rustwright.ui.designsystem.RwTheme.paletteListeners += { m.clear() } }
        @Volatile private var name = ""
        @Volatile private var balanceText: String? = null
        @Volatile private var benefits = BenefitsState.SignedOut
        private val account by lazy { ctx.service<NewApiAccount>() }
        private val projectFile get() = File(ctx.platform.dataDir, "fused-project.txt")

        @Volatile private var catalog: io.github.zenthxsin.rustwright.core.ai.model.ModelCatalog? = null
        @Volatile private var catalogLoading = false

        /** 中转站模型列表（GET /v1/models），拿到一次就缓存。 */
        private fun loadCatalog() {
            val (base, token) = paidRoute() ?: return
            if (catalogBase != base) { catalog = null; catalogBase = base }
            if (catalog != null || catalogLoading) return
            catalogLoading = true
            ctx.scope.launch {
                runCatching {
                    ctx.service<io.github.zenthxsin.rustwright.core.ai.model.ModelCatalogClient>().fetch(token, base)
                }.onSuccess { if (catalogBase == base) catalog = it }.onFailure { arc.util.Log.warn("[fused] model list: ${it.message}") }
                catalogLoading = false
            }
        }

        private val customRoute by lazy { ctx.service<io.github.zenthxsin.rustwright.core.account.CustomRelayRoute>() }
        @Volatile private var catalogBase: String? = null

        /** 付费模型实际走的 (/v1 地址, 密钥)：管理员开了自定义线路就用它，否则用 NewAPI 专用令牌。 */
        private fun paidRoute(): Pair<String, String>? {
            customRoute.active?.let { return it.baseUrl to it.apiKey }
            val token = agent.token?.takeIf { it.isNotBlank() } ?: return null
            val base = agent.settings.baseUrl.trimEnd('/').ifBlank { io.github.zenthxsin.rustwright.core.ai.Relay.DEFAULT_BASE_URL }
            return (if (base.endsWith("/v1")) base else "$base/v1") to token
        }

        override fun chatModels(): List<io.github.zenthxsin.rustwright.core.codex.CodexModel> {
            loadCatalog()
            val known = wb.models.filterNot { it.id.startsWith(NewApiBenefits.MODEL_PREFIX) }
            val extra = catalog?.chat.orEmpty().filter { e -> !e.id.startsWith(NewApiBenefits.MODEL_PREFIX) && known.none { it.id.equals(e.id, true) } }
                .map { io.github.zenthxsin.rustwright.core.codex.CodexModel(it.id, it.id, emptyList(), null) }
            val free = benefitsState().data?.takeIf { it.textAvailable }?.textModels.orEmpty()
                .map { io.github.zenthxsin.rustwright.core.codex.CodexModel(it, NewApiBenefits.displayModel(it), emptyList(), null) }
            return free + known + extra
        }

        override fun benefitsState(): BenefitsState = when {
            !account.loggedIn -> BenefitsState.SignedOut
            benefits.accountSubject != account.subject -> BenefitsState(loggedIn = true, receivedNanos = 0)
            else -> benefits
        }

        @Synchronized
        override fun refreshBenefits() {
            val subject = account.subject
            if (subject == null) { benefits = BenefitsState.SignedOut; return }
            if (benefits.loading && benefits.accountSubject == subject) return
            benefits = BenefitsState(loggedIn = true, loading = true, accountSubject = subject)
            ctx.scope.launch {
                try {
                    val result = account.benefits()
                    if (benefits.accountSubject == subject && account.subject == subject) benefits = BenefitsState(loggedIn = true, data = result, accountSubject = subject)
                } catch (e: CancellationException) {
                    if (benefits.accountSubject == subject) benefits = BenefitsState(loggedIn = account.loggedIn, failed = true, accountSubject = subject)
                    throw e
                } catch (e: Exception) {
                    if (benefits.accountSubject == subject) benefits = BenefitsState(loggedIn = account.loggedIn, failed = true, accountSubject = subject, errorMessage = account.benefitReadError(e))
                }
            }
        }

        private fun refreshAccount() {
            loadCatalog()
            refreshBenefits()
            ctx.scope.launch {
                val info = runCatching { account.info() }.getOrNull()
                if (info != null) {
                    if (info.name.isNotBlank()) name = info.name
                    balanceText = info.balance.takeIf { b -> b.isNotBlank() }
                    showDevTools(info.role)
                } else if (!account.loggedIn) showDevTools(0) // 读取失败但仍登录（断网）时保持原状
            }
        }

        private fun syncProjects() {
            workspace.projects().forEach { project ->
                val path = if (linux?.managed == true) linux.toGuest(project.directory) else project.directory.absolutePath
                if (path != null && wb.projects.none { it.path == path }) wb.openProject(project.name, path)
            }
        }

        override val accountEmail: String get() = name
        override fun balance(): String? = balanceText

        override fun env(): EnvStatus = environmentStatus(linux, provisioner)

        override fun blocker(): String? {
            if (agent.token.isNullOrBlank() && !account.loggedIn && customRoute.active == null) return "请先登录 NewAPI，登录后免费额度和专用线路自动配置"
            val e = env()
            if (!e.ready) return "虚拟环境${e.detail}，装好后${AppBrand.identity.assistant}就能动手"
            return null
        }

        // 每帧都会被问到（工具页、顶栏）：只在第一次读文件，之后用内存里的值，写的时候同时更新
        private var cachedProjectId: String? = null
        private var projectIdLoaded = false
        override fun currentProjectId(): String? {
            if (!projectIdLoaded) { cachedProjectId = runCatching { projectFile.readText().trim() }.getOrNull(); projectIdLoaded = true }
            return cachedProjectId?.takeIf { id -> wb.project(id) != null }
        }

        override fun setCurrentProject(id: String) {
            cachedProjectId = id; projectIdLoaded = true
            runCatching { projectFile.writeText(id) }
        }

        override suspend fun createProject(): CodexProject? {
            val name = Dialogs.input("新建项目", "项目名，中英文都可以")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val project = runCatching { withContext(Dispatchers.IO) { workspace.create(name) } }
                .getOrElse { shell?.notify("创建失败：${it.message}"); return null }
            val path = if (linux?.managed == true) linux.toGuest(project.directory) else project.directory.absolutePath
            return path?.let { wb.openProject(project.name, it) }
        }

        override fun copy(text: String) = ctx.platform.setClipboardText(text)

        private fun hostDir(p: CodexProject): File = if (linux?.managed == true) linux.toHost(p.path) else File(p.path)
        fun hostDirPublic(p: CodexProject): File = hostDir(p)

        override suspend fun projectDiff(p: CodexProject): String? {
            val env = linux?.takeIf { it.installed } ?: return null
            val root = hostDir(p).canonicalFile
            val cwd = env.toGuest(root) ?: return null
            val runner = io.github.zenthxsin.rustwright.linux.impl.CommandRunner(env)
            val rootCheck = runner.run("git rev-parse --show-toplevel", cwd, 10_000)
            if (rootCheck.exitCode != 0 || rootCheck.timedOut || rootCheck.output.trim() != cwd.trimEnd('/')) return null
            val status = runner.run("git -c core.quotepath=false status --short", cwd, 10_000)
            check(!status.timedOut && status.exitCode == 0) { "无法读取 Git 状态" }
            // 刚 git init、还没有提交时没有 HEAD：改成和空树比较（已暂存 / 已跟踪的文件全算新增），不能直接报错
            val hasHead = runner.run("git rev-parse --verify -q HEAD", cwd, 10_000).let { !it.timedOut && it.exitCode == 0 }
            val base = if (hasHead) "HEAD" else EMPTY_TREE
            val diff = runner.run("git --no-pager -c core.quotepath=false diff --no-ext-diff --no-textconv --no-color $base --", cwd, 20_000)
            check(!diff.timedOut && diff.exitCode == 0) { "无法读取 Git 补丁：${diff.output.take(300)}" }
            return if (status.output.isBlank() && diff.output.isBlank()) "项目没有未提交改动。"
                else status.output + "\n" + diff.output + if (status.truncated || diff.truncated) "\n（内容过长，已截断）" else ""
        }

        override fun projectActions(p: CodexProject): List<io.github.zenthxsin.rustwright.fused.MenuHost.Item> {
            val dir = hostDir(p)
            return ext.all(ShellPoints.PROJECT_ACTIONS).filter { it.available(dir) }.map { action ->
                io.github.zenthxsin.rustwright.fused.MenuHost.Item(action.title, action.icon) {
                    scope.launch { runCatching { action.run(p, dir) }.onFailure { shell?.notify("操作失败：${it.message}") } }
                }
            }
        }

        override suspend fun exportProjectSource(p: CodexProject) {
            val dir = hostDir(p).takeIf { it.isDirectory } ?: return shell?.notify("找不到「${p.name}」的文件夹") ?: Unit
            shell?.notify("正在打包「${p.name}」工程…")
            // 工程 = 整个项目文件夹（含附件和任务笔记），方便换手机或备份
            val out = runCatching {
                withContext(Dispatchers.IO) {
                    val f = File(ctx.platform.cacheDir, "export/${dir.name}-project.zip").apply { parentFile.mkdirs(); delete() }
                    java.util.zip.ZipOutputStream(f.outputStream().buffered()).use { zip ->
                        dir.walkTopDown().onEnter { it == dir || it.name != ".git" }.filter { it.isFile }.forEach { file ->
                            zip.putNextEntry(java.util.zip.ZipEntry(dir.name + "/" + file.relativeTo(dir).invariantSeparatorsPath))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    f
                }
            }.getOrElse { shell?.notify("打包失败：${it.message}"); return }
            if (ctx.platform.exportFile(out, "${p.name}-工程.zip", "application/zip")) shell?.notify("已导出 ${p.name}-工程.zip")
        }

        override suspend fun renameProject(p: CodexProject) {
            val name = Dialogs.input("重命名项目", "新名字", p.name)?.trim()?.takeIf { it.isNotEmpty() && it != p.name } ?: return
            try {
                withContext(Dispatchers.IO) { workspace.rename(hostDir(p), name) }
                wb.renameProject(p.id, name)
                shell?.notify("已改名为「$name」")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { shell?.notify("改名失败：${e.message}") }
        }

        override suspend fun deleteProject(p: CodexProject) {
            if (wb.conversationsOf(p.id).any { it.status.active }) { shell?.notify("「${p.name}」还有任务在跑，先停下再删"); return }
            val dir = hostDir(p)
            val managed = workspace.owner(dir) != null
            val warning = if (managed) "「${p.name}」的文件和对话都会删掉，不能恢复。" else "删除应用中的项目和对话记录，外部目录保留。"
            if (!Dialogs.confirm("删除项目", warning, ok = "删除")) return
            try {
                if (managed) withContext(Dispatchers.IO) { workspace.delete(dir) }
                wb.deleteProject(p.id)
                wb.projects.firstOrNull { !it.archived }?.let { setCurrentProject(it.id) }
                shell?.notify("已删除「${p.name}」")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { shell?.notify("删除失败：${e.message}") }
        }

        override suspend fun importProject(type: String?): CodexProject? {
            val picked = ctx.platform.pickFile(listOf("application/zip", "application/octet-stream", "*/*")) ?: return null
            shell?.notify("正在导入 ${picked.name}…")
            val project = try { withContext(Dispatchers.IO) { workspace.importArchive(picked, type) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { shell?.notify("导入失败：${e.message}"); return null }
            val path = if (linux?.managed == true) linux.toGuest(project.directory) else project.directory.absolutePath
            return path?.let { wb.openProject(project.name, it) }?.also { shell?.notify("已导入「${it.name}」") }
        }

        override fun resolveFile(link: String): File? {
            val raw = link.removePrefix("file://").removePrefix("sandbox:").substringBefore('?').substringBefore('#')
                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val dir = projectDir()
            val candidates = buildList {
                if (raw.startsWith("/")) {
                    if (linux?.managed == true) add(linux.toHost(raw))
                    add(File(raw))
                } else if (dir != null) add(File(dir, raw))
                // 模型常编一个 /mnt/data/xxx：按文件名在项目里找
                val name = raw.substringAfterLast('/')
                if (dir != null && name.isNotBlank()) dir.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name == name }?.let { add(it) }
            }
            return candidates.firstOrNull { runCatching { it.isFile }.getOrDefault(false) }
        }

        override suspend fun saveFile(f: File) {
            val mime = when (f.extension.lowercase()) {
                "zip" -> "application/zip"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"; "gif" -> "image/gif"; "bmp" -> "image/bmp"
                "ini", "txt", "md", "xml" -> "text/plain"; else -> "application/octet-stream"
            }
            if (ctx.platform.exportFile(f, f.name, mime)) shell?.notify("已保存 ${f.name}")
        }

        /** 打开已注册的页面；[fresh] 时每次新建（跟着当前项目走的工具页）。 */
        private fun screen(id: String, fresh: Boolean = false): Element? {
            val s = screens.all.firstOrNull { it.id == id } ?: return null
            return if (fresh) s.build() else built.getOrPut(id) { s.build() }
        }

        private fun entry(t: ToolPage) = ToolEntry(t.id, t.name, t.desc(), t.icon, t.ask, t.build?.let { b ->
            { if (t.fresh) b() else built.getOrPut(t.id) { b() } }
        })

        private fun group(g: MoreGroup?, tools: List<ToolPage>) =
            ToolGroup(g?.title ?: "", g?.tint?.invoke() ?: K.surface3, g?.ink?.invoke() ?: K.text, tools.map(::entry))

        // 「更多」由各模块注册（UiPoints.TOOLS）；没有独立界面的工具走占位页 +「让助手代劳」
        override fun tools(): List<ToolGroup> {
            val tools = ext.all(UiPoints.TOOLS)
            return ext.all(UiPoints.MORE_GROUPS).mapNotNull { g ->
                tools.filter { it.group == g.id }.takeIf { it.isNotEmpty() }?.let { group(g, it) }
            }
        }

        override fun tool(id: String): Pair<ToolEntry, ToolGroup>? {
            val t = ext.get(UiPoints.TOOLS, id) ?: return null
            val g = group(t.group?.let { ext.get(UiPoints.MORE_GROUPS, it) }, listOf(t))
            return g.tools.first() to g
        }

        override fun toolsets(): List<io.github.zenthxsin.rustwright.fused.BuiltinToolset> {
            val disabled = CodexModule.disabledToolsets(ctx)
            return CodexModule.allToolsets(ctx).map { (set, n) ->
                io.github.zenthxsin.rustwright.fused.BuiltinToolset(set.id, set.title, set.description, n, set.id !in disabled)
            }
        }

        override fun setToolsetEnabled(id: String, enabled: Boolean) {
            CodexModule.setToolsetEnabled(ctx, id, enabled)
            // 工具组在启动 Codex 时登记，重启后生效
            wb.restart()
        }

        override fun drawerItems() = ext.all(UiPoints.DRAWER)

        override fun attachActions() = ext.all(UiPoints.ATTACH_ACTIONS)

        override suspend fun pickAttachment(kind: String): File? = pickAttachments(kind).firstOrNull()
        override suspend fun pickAttachments(kind: String): List<File> = when (kind) {
            "camera" -> listOfNotNull(ctx.platform.takePhoto())
            "photo" -> ctx.platform.pickFiles(listOf("image/*"))
            else -> ctx.platform.pickFiles(listOf("*/*"))
        }

        fun projectDirPublic(): File? = projectDir()

        private fun projectDir(): File? {
            val p = shell?.currentProject() ?: return null
            return if (linux?.managed == true) linux.toHost(p.path) else File(p.path)
        }

        override fun library(): List<Artifact> = shell?.currentProject()?.let { libraryOf(it) }.orEmpty()

        override fun artifactFile(a: Artifact): File? {
            val project = a.projectId?.let(wb::project) ?: return null
            return io.github.zenthxsin.rustwright.fused.ArtifactFiles.resolve(hostDir(project), a)
        }

        override suspend fun deleteArtifact(a: Artifact): Boolean {
            val project = a.projectId?.let(wb::project) ?: return false
            val root = hostDir(project)
            return withContext(Dispatchers.IO) {
                io.github.zenthxsin.rustwright.fused.ArtifactFiles.resolve(root, a)?.delete() == true
            }
        }

        override fun filePresentation(file: File): FilePresentation? = ext.all(ShellPoints.FILES).firstOrNull { it.accepts(file) }
        override fun fileKinds(): List<Artifact.Kind> = (ext.all(ShellPoints.FILES).map { it.kind } + listOf(Artifact.Kind.IMAGE, Artifact.Kind.FILE)).distinct().sortedBy { it.order }
        override fun editorSupport(file: File) = ext.all(io.github.zenthxsin.rustwright.ui.editor.api.EditorPoints.SUPPORTS).firstOrNull { it.accepts(file) }?.create?.invoke()
        override fun openRegistered(file: File): Boolean = ext.all(ShellPoints.OPENERS).any { it.open(file) }
        override fun projectStarters(project: CodexProject): List<ProjectStarter> =
            ext.all(ShellPoints.STARTERS).filter { it.available(hostDir(project)) }

        override fun workbenchHome(s: FusedShell): Element? = ext.all(ShellPoints.HOME).firstOrNull()?.build?.invoke(s)

        override fun libraryOf(p: CodexProject): List<Artifact> {
            val dir = hostDir(p).takeIf { it.isDirectory } ?: return emptyList()
            return dir.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "node_modules" && it != File(dir, io.github.zenthxsin.rustwright.fused.Attachments.DIR) }
                .filter { it.isFile }.map { file ->
                    val presentation = filePresentation(file)
                    val image = file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
                    val kind = presentation?.kind ?: if (image) Artifact.Kind.IMAGE else Artifact.Kind.FILE
                    val preview = presentation?.preview?.let { render -> { render(file) } }
                        ?: if (image) ({ io.github.zenthxsin.rustwright.ui.kit.ImageThumb(file, tint = true) }) else null
                    val detail = presentation?.detail?.invoke(file) ?: if (image) pngSize(file) else null
                    Artifact(file.relativeTo(dir).invariantSeparatorsPath, kind, file.name,
                        listOfNotNull(kind.label, detail, age(file.lastModified())).joinToString(" · "), p.id,
                        preview = preview, path = file.absolutePath, card = presentation?.card)
                }.sortedByDescending { File(it.path!!).lastModified() }.toList()
        }

        override suspend fun makeTitle(prompt: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                val (base, token) = paidRoute() ?: return@runCatching null
                val url = java.net.URL("$base/chat/completions")
                val model = agent.settings.chatModel.ifBlank { wb.defaultModel() }
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("model", kotlinx.serialization.json.JsonPrimitive(model))
                    put("max_tokens", kotlinx.serialization.json.JsonPrimitive(40))
                    put("messages", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                            put("content", kotlinx.serialization.json.JsonPrimitive("用不超过10个汉字概括下面这条消息，作为对话标题。只输出标题本身，不要标点和引号。\n\n" + prompt.take(500)))
                        })
                    })
                }.toString()
                val c = url.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $token")
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode !in 200..299) return@runCatching null
                // 只取 choices[0].message.content
                val json = kotlinx.serialization.json.Json.parseToJsonElement(c.inputStream.bufferedReader().readText()) as kotlinx.serialization.json.JsonObject
                val msg = ((json["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.get("message") as? kotlinx.serialization.json.JsonObject
                val text = (msg?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.content
                text?.trim()?.replace(Regex("[\"“”「」『』《》。，！？.!?\\s]"), "")?.take(12)?.ifBlank { null }
            }.getOrNull()
        }

        override fun projectStats(p: CodexProject): String {
            val dir = hostDir(p).takeIf { it.isDirectory } ?: return ""
            ext.all(ShellPoints.INSPECTORS).firstOrNull { it.accepts(dir) }?.let { return it.stats(dir) }
            val files = dir.walkTopDown().onEnter { !it.name.startsWith('.') }.count { it.isFile }
            return "$files 个文件"
        }

        override suspend fun checkTurn(turn: io.github.zenthxsin.rustwright.core.codex.CodexTurn): io.github.zenthxsin.rustwright.fused.TurnCheck? {
            val conversation = wb.conversations.firstOrNull { it.turns.any { item -> item.id == turn.id } } ?: return null
            val project = wb.project(conversation.projectId) ?: return null
            val dir = hostDir(project)
            return ext.all(ShellPoints.INSPECTORS).firstOrNull { it.accepts(dir) }?.check?.invoke(dir, turn)
        }

        override suspend fun lintProject(dir: File): Map<String, Pair<Int, Int>>? {
            val check = ext.all(ShellPoints.INSPECTORS).firstOrNull { it.accepts(dir) }?.check?.invoke(dir, null) ?: return null
            return check.files.associate { (path, problems) -> path to (problems.count { it.severity == "error" } to problems.count { it.severity == "warn" }) }
        }

        override fun editPixel(f: File) { ui?.openTool("pixel-editor", f) }

        override fun produced(turn: io.github.zenthxsin.rustwright.core.codex.CodexTurn): List<Artifact> {
            val end = turn.completedAt ?: return emptyList()
            val from = turn.startedAt - 1000
            val made = library().filter { File(it.path!!).lastModified() in from..end + 3000 }
            val referenced = made.flatMap { artifact ->
                val file = File(artifact.path!!)
                filePresentation(file)?.referencedFiles?.invoke(file).orEmpty()
            }.toSet()
            return made.filterNot { it.path in referenced }.sortedBy { it.kind.order }
        }



        /** PNG 头里的宽高「64×64」。 */
        private fun pngSize(f: File): String? = runCatching {
            f.inputStream().use { i ->
                val b = ByteArray(24); if (i.read(b) < 24) return null
                if (!b.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return null
                fun int(o: Int) = (b[o].toInt() and 255 shl 24) or (b[o + 1].toInt() and 255 shl 16) or (b[o + 2].toInt() and 255 shl 8) or (b[o + 3].toInt() and 255)
                "${int(16)}×${int(20)}"
            }
        }.getOrNull()



        /** 「刚刚」「5 分钟前」「3 小时前」「昨天」「周二」「10月3日」。 */
        private fun age(t: Long): String {
            val now = System.currentTimeMillis()
            val d = now - t
            val zone = java.time.ZoneId.systemDefault()
            val day = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            val today = java.time.LocalDate.now(zone)
            return when {
                d < 60_000 -> "刚刚"
                d < 3_600_000 -> "${d / 60_000} 分钟前"
                day == today -> "${d / 3_600_000} 小时前"
                day == today.minusDays(1) -> "昨天"
                day.isAfter(today.minusDays(7)) -> "周" + "一二三四五六日"[day.dayOfWeek.value - 1]
                else -> "${day.monthValue}月${day.dayOfMonth}日"
            }
        }

        override fun openArtifact(a: Artifact): Element? = a.path?.let(::File)?.let { filePresentation(it)?.open?.invoke(it) }


        // MCP 交给「连接器」页（Codex 的真实 MCP 状态），这里不再显示旧 agent 的 MCP 列表
        private fun settingsScreen(): Element? = shell?.let { sh ->
            io.github.zenthxsin.rustwright.fused.ProfilePage(sh, account, ctx.scope,
                topup = { ctx.platform.openUri(io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.origin + "/console/topup") },
                loginAction = {
                    check(ctx.platform.signatureOk) { SIGNATURE_BAD }
                    account.login(); agent.token = account.ensureRelayKey()
                },
            ) {
                wb.restart()
                refreshAccount()
            }
        }

        /** 颜色模式：跟随系统 / 亮色 / 暗色，选中即生效（整页重建）。 */
        /**
         * 后台运行：悬浮窗、不限制电池、通知。[lead] 时是第一次干活前的提醒，多一句为什么。
         * 点一项就跳到对应的系统页面；回来再打开时状态会更新。
         */
        fun keepAliveSheet(lead: Boolean) {
            val s = shell ?: return
            val items = ctx.platform.keepAliveItems()
            if (items.isEmpty()) return
            val body = Table().top()
            if (lead) body.add(K.para("${AppBrand.identity.assistant}干活时你可以切走去做别的。打开下面几项，手机就不会在后台把任务停掉。", 14f, K.subtle, lh = 21f))
                .growX().pad(0f, dp(22f), dp(14f), dp(22f)).row()
            val g = io.github.zenthxsin.rustwright.ui.kit.ListGroup()
            for (it in items) {
                val state = when (it.granted) { true -> "已开启"; false -> "去开启"; null -> "去设置" }
                g.row(io.github.zenthxsin.rustwright.ui.kit.listRow(it.title, it.description, null, trailing = state, chevron = it.granted != true, minHeight = 64f) {
                    s.sheets.close()
                    ctx.platform.openKeepAliveItem(it.id)
                }.apply { name = "keepalive-" + it.id })
            }
            body.add(g).growX().pad(0f, dp(16f), dp(24f), dp(16f))
            s.sheets.open(io.github.zenthxsin.rustwright.ui.kit.SheetPage("后台运行", body, height = (if (lead) 170f else 130f) + 80f * items.size))
        }

        /** 首页样式：对话为主（跟助手说）或工具台（项目、制作流程、工具平铺，自己动手）。 */
        private fun homeStyleSheet() {
            val s = shell ?: return
            val g = io.github.zenthxsin.rustwright.ui.kit.ListGroup()
            for ((tool, title, desc) in listOf(
                Triple(false, "对话", "打开就是${AppBrand.identity.assistant}，想做什么直接说"),
                Triple(true, "工具台", "项目、制作流程和工具都摆在首页，自己动手更快"),
            )) {
                val on = s.toolFirst == tool
                g.row(io.github.zenthxsin.rustwright.ui.kit.listRow(title, desc, null, minHeight = 64f, trailing = if (on) "当前" else null) {
                    s.sheets.close()
                    if (!on) { s.toolFirst = tool; s.show(FusedShell.Page.HOME) }
                })
            }
            val body = Table().top()
            body.add(g).growX().pad(0f, io.github.zenthxsin.rustwright.ui.designsystem.dp(16f), io.github.zenthxsin.rustwright.ui.designsystem.dp(24f), io.github.zenthxsin.rustwright.ui.designsystem.dp(16f))
            s.sheets.open(io.github.zenthxsin.rustwright.ui.kit.SheetPage("首页样式", body, height = 260f))
        }

        private fun themeSheet() {
            val s = shell ?: return
            val g = io.github.zenthxsin.rustwright.ui.kit.ListGroup()
            for (m in io.github.zenthxsin.rustwright.ui.designsystem.RwTheme.Mode.entries) {
                val on = m == io.github.zenthxsin.rustwright.ui.designsystem.RwTheme.mode
                g.row(io.github.zenthxsin.rustwright.ui.kit.listRow(m.label, null, null, minHeight = 56f, trailing = if (on) "当前" else null) {
                    s.sheets.close()
                    if (!on) ctx.events.post(io.github.zenthxsin.rustwright.ui.designsystem.SetThemeMode(m))
                })
            }
            val body = Table().top()
            body.add(g).growX().pad(0f, io.github.zenthxsin.rustwright.ui.designsystem.dp(16f), io.github.zenthxsin.rustwright.ui.designsystem.dp(24f), io.github.zenthxsin.rustwright.ui.designsystem.dp(16f))
            s.sheets.open(io.github.zenthxsin.rustwright.ui.kit.SheetPage("颜色模式", body, height = 300f))
        }

        private fun open(title: String, e: Element?) {
            val s = shell ?: return
            if (e == null) s.notify("「$title」详情页还没做") else s.openTool(title, e, FusedShell.Page.SETTINGS)
        }

        /** 本体自带的设置分组和行；其他模块可以往任何分组里加行，或加新的分组。 */
        fun registerSettings() {
            ext.addAll(UiPoints.SETTING_SECTIONS, listOf(
                SettingSection(UiPoints.SECTION_ACCOUNT, "账号", order = 0),
                SettingSection(UiPoints.SECTION_AGENT, "${AppBrand.identity.assistant}", order = 10),
                SettingSection(UiPoints.SECTION_SYSTEM, "系统", order = 20),
            ))
            ext.addAll(UiPoints.SETTINGS, listOf(
                SettingItem("core.billing", UiPoints.SECTION_ACCOUNT, "充值与账单", PI.wallet, order = 0,
                    value = { balanceText?.let { if (it.startsWith("¥") || it.startsWith("$")) it else "¥$it" } }) {
                    shell?.let { sh ->
                        open("充值与账单", io.github.zenthxsin.rustwright.fused.BillingPage(sh, { account.loggedIn },
                            topup = { ctx.platform.openUri(io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.origin + "/console/topup") },
                            logs = { ctx.platform.openUri(io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.origin + "/console/log") },
                            login = { openProfile() }))
                    }
                },
                SettingItem("core.usage", UiPoints.SECTION_ACCOUNT, "用量", PI.gauge, order = 10, value = { usageText() }) {
                    shell?.let { io.github.zenthxsin.rustwright.fused.Sheets.usage(it) }
                },
                SettingItem("core.benefits", UiPoints.SECTION_ACCOUNT, "订阅计划", PI.gem, order = 20, value = { benefitsState().summary() }) {
                    open("订阅计划", BenefitsPage(this))
                },
                SettingItem("core.model", UiPoints.SECTION_AGENT, "模型", PI.sliders, order = 0,
                    value = { shell?.let { io.github.zenthxsin.rustwright.fused.Labels.model(it.model, wb) } ?: wb.defaultModel() }) {
                    shell?.let { io.github.zenthxsin.rustwright.fused.Sheets.model(it) }
                },
                SettingItem("core.mode", UiPoints.SECTION_AGENT, "工作模式", PI.shield, order = 10,
                    value = { shell?.let { io.github.zenthxsin.rustwright.fused.Labels.mode(it.mode) } }) {
                    shell?.let { io.github.zenthxsin.rustwright.fused.Sheets.modeSheet(it) }
                },
                SettingItem("core.connectors", UiPoints.SECTION_AGENT, "连接器", PI.plug, order = 20) {
                    shell?.let { open("连接器", io.github.zenthxsin.rustwright.fused.ConnectorsPage(it)) }
                },
                SettingItem("core.ubuntu", UiPoints.SECTION_SYSTEM, "虚拟环境", PI.term, order = 0, value = { env().detail.takeIf { linux?.managed == true } }) {
                    shell?.openToolId("terminal", FusedShell.Page.SETTINGS)
                },
                SettingItem("core.home", UiPoints.SECTION_SYSTEM, "首页样式", PI.grid, order = 5,
                    value = { if (shell?.toolFirst == true) "工具台" else "对话" }) { homeStyleSheet() },
                SettingItem("core.theme", UiPoints.SECTION_SYSTEM, "颜色模式", PI.moon, order = 10,
                    value = { io.github.zenthxsin.rustwright.ui.designsystem.RwTheme.mode.label }) { themeSheet() },
            ))
            // 后台运行：只在有这些设置的平台（Android）显示
            if (ctx.platform.keepAliveItems().isNotEmpty()) ext.add(UiPoints.SETTINGS, SettingItem("core.keepalive", UiPoints.SECTION_SYSTEM, "后台运行", PI.bolt, order = 5, value = {
                ctx.platform.keepAliveItems().count { it.granted == false }.let { if (it == 0) "已就绪" else "还差 $it 项" }
            }) { keepAliveSheet(lead = false) })
        }

        /** 有行的分组，按顺序；没有行的分组不显示。 */
        private fun settingSections(): List<Pair<SettingSection, List<SettingItem>>> {
            val items = ext.all(UiPoints.SETTINGS)
            return ext.all(UiPoints.SETTING_SECTIONS).mapNotNull { sec -> items.filter { it.section == sec.id }.takeIf { it.isNotEmpty() }?.let { sec to it } }
        }

        override fun settings(): List<List<SettingRow>> = settingSections().map { (_, items) ->
            items.map { item -> SettingRow(item.icon, item.title, item.value) { ui?.let(item.open) } }
        }

        override fun settingTitles() = settingSections().map { it.first.title }

        override fun openProfile() = open("个人资料", settingsScreen())

        @Volatile private var devTools: io.github.zenthxsin.rustwright.core.runtime.ExtensionHandle? = null
        @Volatile private var accountRole = 0

        /** 开发者工具默认不注册；登录的 NewAPI 账号是管理员（role ≥ 10）时才加进设置，换号 / 退出登录时撤掉。 */
        override fun showDevTools(role: Int) = synchronized(this) {
            accountRole = role
            val want = role >= 10
            // 确认不是管理员（含已退出登录）：自定义线路一并关掉
            if (!want && customRoute.enabled) { customRoute.revoke(); wb.restart(); catalog = null }
            if (want == (devTools != null)) return
            if (!want) { devTools?.remove(); devTools = null; return }
            devTools = ext.addAll(UiPoints.SETTING_SECTIONS, listOf(SettingSection("core.dev", "开发者", order = 90))).let { sec ->
                val item = ext.add(UiPoints.SETTINGS, SettingItem("core.devtools", "core.dev", "开发者工具", PI.codeBrackets, order = 0,
                    value = { if (accountRole >= 100) "超级管理员" else "管理员" }) { openDevTools() })
                io.github.zenthxsin.rustwright.core.runtime.ExtensionHandle { item.remove(); sec.remove() }
            }
        }

        private fun openDevTools() {
            val sh = shell ?: return
            val origin = { io.github.zenthxsin.rustwright.core.runtime.net.RelayNodes.origin }
            open("开发者工具", io.github.zenthxsin.rustwright.fused.DevToolsPage(sh, ctx.scope,
                diagnostics = {
                    listOf(
                        "版本" to "${ctx.platform.appVersion}（${ctx.platform.appVersionCode}）",
                        "签名" to if (ctx.platform.signatureOk) "正式签名" else "签名不符",
                        "线路" to (customRoute.active?.let { "自定义 · " + java.net.IDN.toUnicode(it.baseUrl.substringAfter("://")) } ?: java.net.IDN.toUnicode(origin().removePrefix("https://"))),
                        "账号" to "${name.ifBlank { "—" }} · ${if (accountRole >= 100) "超级管理员" else "管理员"}",
                        "模型" to (io.github.zenthxsin.rustwright.fused.Labels.model(sh.model, wb)),
                        "虚拟环境" to env().detail,
                        "当前项目" to sh.projectName(),
                    )
                },
                copyText = { ctx.platform.setClipboardText(it) },
                openAdmin = { ctx.platform.openUri(origin() + "/console") },
                restartCodex = { wb.restart() },
                route = customRoute,
                routeChanged = { wb.restart(); catalog = null; loadCatalog() },
                testRoute = { base, key -> ctx.service<io.github.zenthxsin.rustwright.core.ai.model.ModelCatalogClient>().fetch(key, base).let { it.chat.size + it.embedding.size } },
                openTerminal = { sh.openToolId("terminal", FusedShell.Page.SETTINGS) },
            ))
        }

        override fun embeddingModel(): String = agent.settings.embeddingModel

        override fun embeddingModels(): List<String> = catalog?.embedding?.map { it.id }.orEmpty().also { if (it.isEmpty()) loadCatalog() }

        override fun setEmbeddingModel(id: String) = agent.updateSettings { it.copy(embeddingModel = id) }

        /** 「本月 3.2 万 token」：按本机记录的对话用量汇总。 */
        private fun usageText(): String? {
            val t = wb.conversations.sumOf { it.totalTokens }
            if (t <= 0) return null
            return "累计 " + (if (t >= 10_000) String.format("%.1f 万", t / 10_000.0) else "$t") + " token"
        }

        override fun imageModel(): String = agent.settings.imageModel.ifBlank { "未选择" }

        override fun imageModels(): List<String> =
            (listOfNotNull(benefitsState().data?.takeIf { it.generationAvailable }?.imageModel) +
                (listOf(agent.settings.imageModel) + (catalog?.image?.map { it.id }?.takeIf { it.isNotEmpty() } ?: listOf("gpt-image-1", "flux-kontext-pro", "seedream-4")))
                    .filter { it.isNotBlank() && !it.startsWith(NewApiBenefits.MODEL_PREFIX) }).distinct()

        override fun setImageModel(id: String) = agent.updateSettings { it.copy(imageModel = id) }

        private val provisioner = ctx.services.getOrNull<io.github.zenthxsin.rustwright.linux.impl.LinuxProvisioner>()

        override fun preloadAgent() {
            val loggedIn = account.loggedIn || !agent.token.isNullOrBlank()
            // 虚拟环境就绪、已登录才拉起 Codex；否则启动必失败，留到用户登录 / 装好环境后再说
            if (loggedIn && linux?.installed == true && provisioner?.ready != false) wb.warmUp()
            if (account.loggedIn) refreshBenefits()
        }

        override fun permissions() = ctx.platform.keepAliveItems()
        override fun openPermission(id: String) {
            // 首次启动页里已经问过，第一次干活时就不再弹后台运行提醒
            ctx.service<io.github.zenthxsin.rustwright.core.storage.KeyValueStore>().put(KEEPALIVE_PROMPTED, Boolean.serializer(), true)
            ctx.platform.openKeepAliveItem(id)
        }

        override fun setup(): io.github.zenthxsin.rustwright.fused.SetupState? {
            val p = provisioner ?: return null
            return object : io.github.zenthxsin.rustwright.fused.SetupState {
                override val ready get() = p.ready
                override val running get() = p.running
                override val steps = io.github.zenthxsin.rustwright.linux.impl.LinuxProvisioner.Step.entries.map { it.label }
                override val stepIndex get() = p.step?.ordinal ?: if (p.ready) steps.size else 0
                override val progress get() = p.progress
                override val detail get() = p.detail
                override val error get() = p.error
                override fun failureReport() = p.failureReport()
                override fun start() = p.start()
            }
        }

        init {
            refreshAccount()
            syncProjects()
        }
    }
}

/** Git 的空树对象：仓库还没有提交时，/diff 拿它当比较基准。 */
private const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
