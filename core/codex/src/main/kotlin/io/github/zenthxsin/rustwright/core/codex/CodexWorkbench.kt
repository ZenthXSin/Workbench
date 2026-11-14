package io.github.zenthxsin.rustwright.core.codex

import io.github.zenthxsin.rustwright.core.runtime.RwLog

import kotlinx.coroutines.CompletableDeferred

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

/** 启动 `codex app-server --listen stdio://`（stdout 只能是协议消息）。 */
fun interface CodexLauncher {
    fun start(): Process
}

data class CodexThreadOptions(val provider: String = "newapi", val config: JsonObject? = null)

/**
 * 本机 Codex 工作台：项目、会话、任务轮次、审批、排队与引导，持久化在 [dir]。
 *
 * 全部状态只在 [main] 上读写；App 前台在 Arc，Android 暂停时由平台安全交给单一后台执行者。
 * UI 只在前台观察 [version] 变化后重绘，不能与后台同时访问这些可变对象。
 * 一个 app-server 进程承载所有会话的线程，按 threadId 分发事件。
 */
class CodexWorkbench(
    private val dir: File,
    private val launcher: CodexLauncher,
    private val main: CoroutineDispatcher,
    private val scope: CoroutineScope,
    /** 默认模型（新会话）。 */
    var defaultModel: () -> String = { "gpt-5.6-sol" },
    /**
     * Rustwright 工具（全部组摊平）。新线程不再附带，工具经 [McpBridge] 以 MCP 服务器交给 Codex；
     * 这里留着给旧线程里登记过的动态工具（item/tool/call）用。
     */
    var tools: () -> List<CodexTool> = { emptyList() },
    /** 附加的开发者说明（场景、项目约定）。 */
    var instructions: (CodexProject) -> String = { "" },
    /** 文件持久化与进程创建；测试可替换为可控制的调度器。 */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val loadInBackground: Boolean = false,
) {
    val projects = mutableListOf<CodexProject>()
    val conversations = mutableListOf<CodexConversation>()

    /** 状态版本号，任何变化都会递增。 */
    @Volatile var version = 0
        private set

    /** 可选模型（来自 app-server 的 model/list）。 */
    var models: List<CodexModel> = emptyList()
        private set

    /** 进程状态说明（启动中 / 已退出原因），空表示正常。 */
    var runtimeStatus: String = ""
        private set

    /** 聚合所有活跃会话，仅变化时同步通知；平台据此保持后台服务。回调不能操作 UI。 */
    var onBusy: (Boolean) -> Unit = {}

    /**
     * 会话成功移除并取消本地任务后通知，项目级联删除也会触发。
     * 若取消中的工具稍后才退出，会在退出后再次通知，故必须幂等；回调不能操作 UI。
     */
    var onConversationDeleted: (String) -> Unit = {}

    /** 仅当前轮结束时通知一次；接收方在状态线程捕获快照后自行调度 IO。 */
    var onTurnFinished: (CodexConversation, CodexTurn) -> Unit = { _, _ -> }

    /** 每次发送前验证账号与线路；包含已载入的会话，失败时不会发送模型请求。 */
    var prepareThread: suspend (CodexConversation) -> CodexThreadOptions = { CodexThreadOptions() }

    private var busy = false

    private class Connection {
        val messages = kotlinx.coroutines.channels.Channel<Triple<String, JsonElement?, JsonObject>>(128)
        var messageJob: Job? = null
        var process: Process? = null
        var rpc: CodexRpc? = null
        lateinit var ready: Deferred<CodexRpc>
    }

    private class TurnRun(parent: Job?) {
        val job = SupervisorJob(parent)
        val started = CompletableDeferred<String>()
        var turnId: String? = null
        var completed = false
        var remoteInterrupt: Job? = null
    }

    private var connection: Connection? = null
    private val rpc: CodexRpc? get() = connection?.rpc
    private val startLock = Mutex()
    private val loadedThreads = HashSet<String>()
    /** 已加载线程当前用的线路（model provider）。线路在加载时定死，换线路要先卸载再恢复。 */
    private val threadProviders = HashMap<String, String>()
    private val runs = HashMap<String, TurnRun>()
    private val commandTokens = HashMap<String, Any>()
    private val turnStartLocks = HashMap<String, Mutex>()
    private val stoppingTurns = HashMap<String, Job>()
    /** turn/start 发出后即使用户停止，也要等到轮次 ID 才能中断远端。 */
    private val startingTurns = HashMap<String, TurnRun>()
    private val retiredTurns = HashSet<Pair<String, String>>()
    private val persistence = CodexPersistence(scope, io)
    /** 本地请求超时；测试可缩短以覆盖服务端已执行但没有响应的情况。 */
    internal var turnStartTimeoutMillis = 60_000L
    private val restored = CompletableDeferred<Unit>()
    private var projectsNeedSave = false
    private val conversationsNeedSave = linkedSetOf<String>()
    private val deletedProjectPaths = hashSetOf<String>()

    init {
        if (loadInBackground) scope.launch(io) {
            val state = readState()
            withContext(main) { restore(state) }
        }.invokeOnCompletion { error ->
            if (!restored.isCompleted) restored.completeExceptionally(error ?: IllegalStateException("历史加载未完成"))
        } else restore(readState())
    }

    private fun changed() {
        version++
        val active = conversations.any { it.status.active }
        if (active != busy) {
            busy = active
            onBusy(active)
        }
    }

    // region 项目与会话

    fun project(id: String) = projects.firstOrNull { it.id == id }
    fun conversation(id: String) = conversations.firstOrNull { it.id == id }
    fun conversationsOf(projectId: String) = conversations.filter { it.projectId == projectId }.sortedByDescending { it.updatedAt }

    /** 打开（或新建）指向 [path] 的项目。 */
    fun openProject(name: String, path: String): CodexProject {
        projects.firstOrNull { it.path == path }?.let { it.archived = false; changed(); return it }
        val p = CodexProject(UUID.randomUUID().toString(), name, path)
        projects += p
        saveProjects(); changed()
        return p
    }

    fun renameProject(id: String, name: String) { project(id)?.let { it.name = name; saveProjects(); changed() } }

    fun setProjectArchived(id: String, archived: Boolean) { project(id)?.let { it.archived = archived; saveProjects(); changed() } }

    /** 删除项目记录和会话（不删除 Linux 里的文件）。 */
    fun deleteProject(id: String) {
        if (!restored.isCompleted) project(id)?.let { deletedProjectPaths += it.path }
        val children = conversationsOf(id)
        // 与单会话删除一致：先停止任务，避免留下找不到项目的活跃会话。
        if (children.any { it.status.active }) return
        children.forEach { deleteConversation(it.id) }
        projects.removeAll { it.id == id }
        saveProjects(); changed()
    }

    fun newConversation(projectId: String, mode: CodexMode = CodexMode.CODE, model: String = defaultModel(), effort: String? = null): CodexConversation {
        val c = CodexConversation(UUID.randomUUID().toString(), projectId, model = model, effort = effort, mode = mode)
        conversations += c
        save(c); changed()
        return c
    }

    fun renameConversation(id: String, title: String) { conversation(id)?.let { it.title = title; save(it); changed() } }

    fun setArchived(id: String, archived: Boolean) { conversation(id)?.let { it.archived = archived; save(it); changed() } }

    fun deleteConversation(id: String) {
        val c = conversation(id) ?: return
        if (c.status.active) return
        if (c.allowGoalContinuation) interrupt(c.id)
        commandTokens.remove(c.id)
        conversations.remove(c)
        runs.remove(c.id)?.job?.cancel()
        startingTurns.remove(c.id)?.job?.cancel()
        turnStartLocks.remove(c.id)
        // 不取消正在发送的远端中断，只移除已删除会话的索引。
        stoppingTurns.remove(c.id)
        retiredTurns.removeAll { it.first == c.id }
        clearApprovals(c)
        persistence.delete(File(convDir, "$id.json"))
        changed()
        onConversationDeleted(id)
    }

    fun setMode(id: String, mode: CodexMode) {
        conversation(id)?.let {
            // 已发出的命令不能通过改标签撤销权限；和模型一样，仅空闲时切换。
            if (it.status.active || it.allowGoalContinuation) return
            it.mode = mode
            it.threadId?.let(loadedThreads::remove)
            save(it); changed()
        }
    }
    fun setModel(id: String, model: String, effort: String?): Boolean {
        val c = conversation(id) ?: return false
        // 运行中的一轮保留原模型；空闲时随便切。免费 / 付费换线路也不用新建会话：
        // 下次发消息前 ensureThread 发现线路变了，会先卸载线程再用新线路恢复，聊天记录照样带上。
        if (c.status.active || c.allowGoalContinuation) return false
        if (isFreeModel(c.model) != isFreeModel(model)) c.threadId?.let(loadedThreads::remove)
        c.model = model; c.effort = effort; save(c); changed()
        return true
    }

    // endregion

    // region 任务

    /**
     * 发送消息：空闲时开始新一轮；运行中则排队（[steer] 为 true 时追加到当前任务）。
     */
    fun send(conversationId: String, text: String, steer: Boolean = false, images: List<String> = emptyList(), autoRetry: Boolean = false,
        skills: List<CodexSkillRef> = emptyList(), operation: CodexOperation = CodexOperation.MESSAGE) {
        val c = conversation(conversationId) ?: return
        val prompt = text.trim()
        if (prompt.isEmpty() && images.isEmpty()) return
        // 用户自己发消息：之前排着的自动重试作废，重试计数清零
        if (!autoRetry) cancelRetry(c.id, reset = true)
        if (c.status.active) {
            require(operation == CodexOperation.MESSAGE) { "请等待当前任务结束后再执行此指令" }
            if (steer && c.activeTurnId != null) runs[c.id]?.let { run ->
                scope.launch(main + run.job) { steer(c, prompt) }
            }
            else { c.queue += CodexQueued(UUID.randomUUID().toString(), prompt, images, skills); save(c); changed() }
            return
        }
        if (c.turns.isEmpty() && c.title == "新会话") c.title = prompt.lineSequence().first().take(40)
        // 目标续行和用户新消息不能同时争抢下一轮；先暂停远端目标，再开始显式请求。
        val pauseGoal = c.allowGoalContinuation
        if (pauseGoal) c.allowGoalContinuation = false
        c.status = CodexStatus.STARTING
        lastPrompts[c.id] = prompt to images
        c.pendingPrompt = prompt
        c.lastError = null
        c.updatedAt = System.currentTimeMillis()
        project(c.projectId)?.updatedAt = c.updatedAt
        val run = TurnRun(scope.coroutineContext[Job])
        runs[c.id] = run
        run.job.invokeOnCompletion {
            // cancel 不等待正在执行的工具片段。等该轮所有子任务退出后再释放一次，
            // 防止删除同时已进入的工具重新建立会话缓存。
            scope.launch(main) { if (conversation(c.id) == null) onConversationDeleted(c.id) }
        }
        changed()
        scope.launch(main + run.job) { startTurn(c, run, prompt, images, skills, operation, pauseGoal) }
    }

    /** 每个对话连续自动重试了几次；成功一轮、用户自己发消息或点停止后清零。 */
    private val autoRetries = HashMap<String, Int>()
    private val retryJobs = HashMap<String, Job>()
    /** 每个对话最近一次真正发出的消息（原文、图片），自动重试时原样重发。 */
    private val lastPrompts = HashMap<String, Pair<String, List<String>>>()

    private fun cancelRetry(conversationId: String, reset: Boolean) {
        retryJobs.remove(conversationId)?.cancel()
        if (reset) autoRetries.remove(conversationId)
    }

    /**
     * 一轮失败后自动重发同一条消息，最多 [MAX_AUTO_RETRIES] 次，间隔 2、4、8、16、30 秒。
     * 重发前隐藏失败那一轮（和「重新生成」一样），对话里不会堆出重复的提问和报错。
     */
    private fun scheduleRetry(c: CodexConversation, t: CodexTurn) {
        // A lost response can follow completed tools or billing. Whole-turn retries require user action.
        if (MAX_AUTO_RETRIES == 0) return
        val n = (autoRetries[c.id] ?: 0) + 1
        val error = t.error ?: return
        if (n > MAX_AUTO_RETRIES) {
            autoRetries.remove(c.id)
            t.error = "$error\n已自动重试 $MAX_AUTO_RETRIES 次仍然失败"
            c.lastError = t.error
            return
        }
        // 优先用发出时记下的原文和图片；Codex 在请求失败前不一定回显 userMessage
        val (text, images) = lastPrompts[c.id] ?: (t.items.firstOrNull { it.type == "userMessage" }?.text?.takeIf { it.isNotBlank() }?.let { it to emptyList() } ?: return)
        autoRetries[c.id] = n
        val wait = minOf(30L, 1L shl n)
        t.error = "$error\n$wait 秒后自动重试（第 $n / $MAX_AUTO_RETRIES 次）"
        c.lastError = t.error
        retryJobs.remove(c.id)?.cancel()
        retryJobs[c.id] = scope.launch(main) {
            kotlinx.coroutines.delay(wait * 1000)
            retryJobs.remove(c.id)
            val cc = conversation(c.id) ?: return@launch
            if (cc.status.active || cc.turns.lastOrNull { it.id !in retiredHidden(cc) }?.id != t.id) return@launch
            deleteMessage(cc.id, t.id, "assistant")
            deleteMessage(cc.id, t.id, "user")
            send(cc.id, text, images = images, autoRetry = true)
        }
    }

    /** 已经隐藏了提问和回复的轮次（重新生成、自动重试留下的）。 */
    private fun retiredHidden(c: CodexConversation): Set<String> =
        c.turns.filter { "user" in it.deletedRoles && "assistant" in it.deletedRoles }.mapTo(HashSet()) { it.id }

    /** 删除一条消息的显示（不影响 Codex 的线程历史）。 */
    fun deleteMessage(conversationId: String, turnId: String, role: String) {
        val c = conversation(conversationId) ?: return
        val t = c.turns.firstOrNull { it.id == turnId } ?: return
        if (role !in t.deletedRoles) t.deletedRoles += role
        save(c); changed()
    }

    /** 打断当前任务，然后立即发送这条消息（优先于队列）。 */
    fun interruptAndSend(conversationId: String, text: String, images: List<String> = emptyList()) {
        val c = conversation(conversationId) ?: return
        if (!c.status.active) { send(conversationId, text, images = images); return }
        val stopping = stopTurn(c)
        scope.launch(main) {
            stopping?.join()
            send(conversationId, text, images = images)
        }
    }

    fun cancelQueued(conversationId: String, queuedId: String) {
        val c = conversation(conversationId) ?: return
        if (c.queue.removeAll { it.id == queuedId }) { save(c); changed() }
    }

    private fun isCurrent(c: CodexConversation, run: TurnRun) = runs[c.id] === run && run.job.isActive

    private suspend fun startTurn(c: CodexConversation, run: TurnRun, prompt: String, images: List<String>,
        skills: List<CodexSkillRef>, operation: CodexOperation, pauseGoal: Boolean, retryOnProviderMismatch: Boolean = true) {
        try {
            turnStartLocks.getOrPut(c.id) { Mutex() }.withLock {
                stoppingTurns[c.id]?.let { stopping ->
                    stopping.join()
                    if (stoppingTurns[c.id] === stopping) stoppingTurns.remove(c.id)
                }
                val rpc = ensureThread(c)
                currentCoroutineContext().ensureActive()
                if (pauseGoal) {
                    val paused = rpc.request("thread/goal/set", buildJsonObject {
                        put("threadId", c.threadId); put("origin", "user"); put("status", "paused")
                    }).objOrNull()?.obj("goal")
                    currentCoroutineContext().ensureActive()
                    if (isCurrent(c, run)) c.goal = paused?.let(::parseGoal)
                    // 暂停确认前可能已有服务端续行；先收回它的中断确认。
                    stoppingTurns[c.id]?.join()
                    currentCoroutineContext().ensureActive()
                }
                startingTurns[c.id] = run
                try {
                    // 一旦请求写入 stdio，就必须收回 turn ID；取消等待会留下无法中断的远端轮次。
                    // 本地工具仍由 run.job 取消，新一轮在此锁后等待旧轮次的中断请求完成。
                    withContext(NonCancellable) {
                        try {
                            val method = when (operation) {
                                CodexOperation.MESSAGE -> "turn/start"
                                CodexOperation.REVIEW -> "review/start"
                                CodexOperation.COMPACT -> "thread/compact/start"
                            }
                            val result = rpc.request(method, buildJsonObject {
                                put("threadId", c.threadId)
                                if (operation == CodexOperation.REVIEW) {
                                    put("delivery", "inline")
                                    putJsonObject("target") { put("type", "custom"); put("instructions", prompt) }
                                    return@buildJsonObject
                                }
                                if (operation == CodexOperation.COMPACT) return@buildJsonObject
                                putJsonArray("input") {
                                    if (prompt.isNotEmpty()) addJsonObject { put("type", "text"); put("text", prompt) }
                                    images.forEach { path -> addJsonObject { put("type", "localImage"); put("path", path) } }
                                    skills.forEach { skill -> addJsonObject { put("type", "skill"); put("name", skill.name); put("path", skill.path) } }
                                }
                                put("model", c.model)
                                c.effort?.let { put("effort", it) }
                                put("cwd", project(c.projectId)?.path)
                                put("approvalPolicy", c.mode.approvalPolicy)
                                put("sandboxPolicy", c.mode.sandboxPolicy)
                                putJsonObject("collaborationMode") {
                                    put("mode", if (c.planMode) "plan" else "default")
                                    putJsonObject("settings") {
                                        put("model", c.model)
                                        put("reasoning_effort", c.effort?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                                        put("developer_instructions", kotlinx.serialization.json.JsonNull)
                                    }
                                }
                            }, turnStartTimeoutMillis).objOrNull()
                            val turnId = result?.obj("turn")?.str("id") ?: run.turnId
                                ?: (if (operation == CodexOperation.COMPACT) kotlinx.coroutines.withTimeoutOrNull(turnStartTimeoutMillis) { run.started.await() } else null)
                                ?: throw CodexException("Codex 没有返回任务轮次", "missing_turn_id")
                            run.turnId = turnId
                            if (isCurrent(c, run)) {
                                c.activeTurnId = turnId
                                if (c.status == CodexStatus.STARTING) c.status = CodexStatus.RUNNING
                            } else {
                                requestInterrupt(c, run, rpc, turnId)?.join()
                            }
                        } catch (e: Exception) {
                            val startedId = run.turnId
                            if (startedId != null) {
                                if (isCurrent(c, run)) {
                                    stopTurn(c)
                                    c.status = CodexStatus.ERROR
                                    c.lastError = e.message ?: e.toString()
                                    save(c); changed()
                                }
                                requestInterrupt(c, run, rpc, startedId)?.join()
                            }
                            else if ((e as? CodexException)?.code in setOf("runtime_timeout", "missing_turn_id")) {
                                // 请求可能已被服务端执行，但没有轮次 ID 时无法精确停止。
                                // 关闭这个连接，避免留下失去客户端控制的命令/工具。
                                connection?.takeIf { it.rpc === rpc }?.let { closeConnection(it, CodexException("Codex 启动任务时失去响应，已停止进程", "runtime_stopped")) }
                            }
                            throw e
                        }
                    }
                } finally {
                    if (startingTurns[c.id] === run) startingTurns.remove(c.id)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isCurrent(c, run)) return
            val tid = c.threadId
            if (retryOnProviderMismatch && tid != null && run.turnId == null && isProviderMismatch(e)) {
                // 老会话没记下线路、Codex 在 turn/start 才发现换了线路：卸掉旧线程，开新线程带上文字记录，再发一次
                val live = connection?.rpc
                if (live != null) dropThread(live, c, tid) else { c.threadId = null; c.threadProvider = null; c.restoreNext = true }
                save(c)
                return startTurn(c, run, prompt, images, skills, operation, pauseGoal, retryOnProviderMismatch = false)
            }
            runs.remove(c.id)
            run.job.cancel()
            c.status = CodexStatus.ERROR
            c.activeTurnId = null
            c.lastError = e.message ?: e.toString()
            clearApprovals(c)
        }
        if (runs[c.id] == null) turnStartLocks.remove(c.id)
        save(c); changed()
    }

    private suspend fun steer(c: CodexConversation, prompt: String) {
        val turnId = c.activeTurnId ?: return
        try {
            ensureThread(c).request("turn/steer", buildJsonObject {
                put("threadId", c.threadId)
                put("expectedTurnId", turnId)
                put("clientUserMessageId", UUID.randomUUID().toString())
                putJsonArray("input") { addJsonObject { put("type", "text"); put("text", prompt) } }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 任务已结束等情况：改为排队，不丢消息
            c.queue += CodexQueued(UUID.randomUUID().toString(), prompt)
            c.lastError = "引导失败，已加入队列：${e.message}"
            save(c); changed()
        }
    }

    /** 停止当前任务。 */
    fun interrupt(conversationId: String) {
        val c = conversation(conversationId) ?: return
        cancelRetry(c.id, reset = true)
        commandTokens.remove(c.id)
        if (c.allowGoalContinuation) {
            c.allowGoalContinuation = false
            val thread = c.threadId
            val currentRpc = rpc
            if (thread != null && currentRpc != null) scope.launch(main) {
                runCatching { currentRpc.request("thread/goal/set", buildJsonObject {
                    put("threadId", thread); put("origin", "user"); put("status", "paused")
                }, 5000) }
            }
            save(c); changed()
        }
        if (!c.status.active) return
        stopTurn(c)
    }

    /** 先同步停止本地执行，远端确认不能延后本地取消或覆盖后来开始的新一轮。 */
    private fun stopTurn(c: CodexConversation): Job? {
        val run = runs.remove(c.id)
        val turnId = c.activeTurnId ?: run?.turnId
        run?.job?.cancel()
        if (turnId != null) retiredTurns += c.id to turnId
        c.turns.lastOrNull()?.takeIf { it.status == "inProgress" }?.status = "interrupted"
        c.status = CodexStatus.INTERRUPTED
        c.activeTurnId = null
        clearApprovals(c)
        save(c); changed()
        return if (turnId != null) rpc?.let { requestInterrupt(c, run, it, turnId) } else null
    }

    private fun requestInterrupt(c: CodexConversation, run: TurnRun?, rpc: CodexRpc, turnId: String): Job? {
        if (this.rpc !== rpc || rpc.closed) return null
        if (conversation(c.id) === c) retiredTurns += c.id to turnId
        if (run?.completed == true) return null
        run?.remoteInterrupt?.let { return it }
        val threadId = c.threadId ?: return null
        return scope.launch(main) {
            runCatching { rpc.request("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) }, 5000) }
        }.also {
            run?.remoteInterrupt = it
            if (conversation(c.id) === c) stoppingTurns[c.id] = it
        }
    }

    private fun clearApprovals(c: CodexConversation) {
        c.approvals.toList().forEach { pendingTools.remove(it.id)?.invoke(false) }
        c.approvals.clear()
    }

    /** 回答审批 / 提问。 */
    fun answer(approvalId: String, answer: CodexAnswer) {
        val c = conversations.firstOrNull { conv -> conv.approvals.any { it.id == approvalId } } ?: return
        val a = c.approvals.first { it.id == approvalId }
        val answer = if (c.mode == CodexMode.ASK && a.kind != CodexApproval.Kind.QUESTION)
            CodexAnswer.Decline else answer
        c.approvals.remove(a)
        if (c.approvals.isEmpty() && c.status == CodexStatus.WAITING) c.status = CodexStatus.RUNNING
        changed()
        if (a.method == CodexApproval.MCP_TOOL_APPROVAL) {
            runCatching { rpc?.respond(a.requestId, elicitationAnswer(answer != CodexAnswer.Decline)) }
            return
        }
        if (a.kind == CodexApproval.Kind.TOOL) {
            pendingTools.remove(a.id)?.invoke(answer == CodexAnswer.Accept || answer == CodexAnswer.AcceptForSession)
            return
        }
        val accept = answer == CodexAnswer.Accept || answer == CodexAnswer.AcceptForSession
        val result: JsonElement = when (a.kind) {
            CodexApproval.Kind.PERMISSIONS -> buildJsonObject {
                put("permissions", if (accept) a.params["permissions"] ?: JsonObject(emptyMap()) else JsonObject(emptyMap()))
                put("scope", "turn")
            }
            CodexApproval.Kind.QUESTION -> buildJsonObject {
                putJsonObject("answers") {
                    (answer as? CodexAnswer.Answers)?.answers?.forEach { (id, values) ->
                        putJsonObject(id) { putJsonArray("answers") { values.forEach { add(it) } } }
                    }
                }
            }
            CodexApproval.Kind.OTHER -> buildJsonObject { put("action", "decline"); put("content", kotlinx.serialization.json.JsonNull) }
            else -> buildJsonObject { put("decision", if (answer == CodexAnswer.AcceptForSession) "acceptForSession" else if (accept) "accept" else "decline") }
        }
        runCatching { rpc?.respond(a.requestId, result) }
    }

    // endregion

    // region 进程与线程

    // ---- MCP 服务器（Codex 的 config.toml [mcp_servers.*]，助手实际用的就是这些） ----

    /** MCP 启动状态每推送一次加一（连接器页据此自动刷新）。 */
    @Volatile var mcpStatusVersion = 0
        private set

    /** 最近一次推送的 MCP 启动状态：名字 → (状态, 出错原因)。 */
    private val mcpStartup = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()
    private var mcpGeneration = 0L

    private fun resetMcpStatus() {
        mcpStartup.clear()
        mcpGeneration++
        mcpStatusVersion++
        changed()
    }

    /**
     * 每个已配置的 MCP 服务器：连接状态、工具数、出错信息，以及配置里的命令 / 地址。
     * 状态查询要等所有服务器都连上（慢的能等几十秒），超时就先用推送来的启动状态顶上，不整页报错。
     */
    suspend fun mcpServers(): List<CodexMcpServer> = withContext(main) {
        val rpc = ensureRpc()
        val generation = mcpGeneration
        fun checkCurrent() {
            if (this@CodexWorkbench.rpc !== rpc || generation != mcpGeneration)
                throw CancellationException("连接器状态查询已过期")
        }
        val targets = HashMap<String, String>()
        runCatching { rpc.request("config/read", JsonObject(emptyMap())) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()?.objOrNull()?.let { r ->
            val servers = (r.obj("config") ?: r).obj("mcp_servers")
            servers?.forEach { (name, v) ->
                val o = v.objOrNull() ?: return@forEach
                val t = o.str("url") ?: listOfNotNull(o.str("command"), (o["args"] as? JsonArray)?.mapNotNull { it.text }?.joinToString(" ")).joinToString(" ")
                targets[name] = t.trim()
            }
        }
        checkCurrent()
        val list = runCatching { rpc.request("mcpServerStatus/list", buildJsonObject { put("detail", "toolsAndAuthOnly") }, timeoutMillis = 45_000).objOrNull() }
            .onFailure { checkCurrent(); if (it is CancellationException || mcpStartup.isEmpty()) throw it }.getOrNull()
        checkCurrent()
        val data = (list?.get("data") as? JsonArray).orEmpty()
        fun startup(name: String): Pair<String?, String?> = mcpStartup[name]?.let { (st, err) ->
            (when (st) { "ready" -> "connected"; "failed" -> "failed"; "starting" -> "starting"; else -> null }) to err
        } ?: (null to null)
        val out = data.mapNotNull { e ->
            val o = e.objOrNull() ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val tools = when (val t = o["tools"]) { is JsonArray -> t.size; is JsonObject -> t.size; else -> 0 }
            val (st, err) = startup(name)
            // 列表里有工具就算已连接；runtimeStatus 新版常是 null，用推送的状态补
            val status = o.str("runtimeStatus") ?: if (tools > 0) "connected" else st
            CodexMcpServer(name, status, tools, o.str("toolsError") ?: err.takeIf { tools == 0 }, targets[name].orEmpty())
        }
        // 配了但状态列表里没有的（还没启动过 / 查询超时）也列出来，带上推送来的状态
        val names = (targets.keys + mcpStartup.keys).filter { n -> out.none { it.name == n } }
        out + names.map { n -> val (st, err) = startup(n); CodexMcpServer(n, st, 0, err, targets[n].orEmpty()) }
    }

    /** 新增 / 覆盖一个 MCP 服务器：[target] 是 http(s) 地址，或「命令 参数…」（在 Codex 所在的环境里运行）。 */
    suspend fun putMcpServer(name: String, target: String) {
        val value = if (target.startsWith("http://") || target.startsWith("https://")) buildJsonObject { put("url", target) }
        else {
            val parts = target.trim().split(Regex("\\s+"))
            buildJsonObject {
                put("command", parts.first())
                putJsonArray("args") { parts.drop(1).forEach { add(it) } }
            }
        }
        writeMcp(name, value)
    }

    suspend fun removeMcpServer(name: String) = writeMcp(name, kotlinx.serialization.json.JsonNull)

    private suspend fun writeMcp(name: String, value: JsonElement) {
        val rpc = ensureRpc()
        rpc.request("config/value/write", buildJsonObject {
            put("keyPath", "mcp_servers.$name")
            put("value", value)
            put("mergeStrategy", "replace")
        })
        reloadMcp()
    }

    /** 让 Codex 按 config.toml 重新连接所有 MCP 服务器。 */
    suspend fun reloadMcp() = withContext(main) {
        val rpc = ensureRpc()
        resetMcpStatus()
        runCatching { rpc.request("config/mcpServer/reload", JsonObject(emptyMap())) }
            .onFailure { if (it is CancellationException) throw it }
        Unit
    }

    private suspend fun ensureRpc(): CodexRpc = withContext(main) {
        val current = startLock.withLock {
            connection?.let { existing ->
                if (existing.rpc?.closed != true) return@withLock existing
                closeConnection(existing, CodexException("Codex 进程已退出", "runtime_stopped"))
            }
            Connection().also { created ->
                connection = created
                loadedThreads.clear()
                threadProviders.clear()
                // 应用级共享启动，不从某个会话继承取消。一个会话停止不会误杀其余会话。
                created.ready = scope.async(main, start = CoroutineStart.LAZY) { startConnection(created) }
                created.ready.start()
            }
        }
        current.ready.await()
    }

    private suspend fun startConnection(current: Connection): CodexRpc {
        runtimeStatus = "正在启动 Codex…"; changed()
        try {
            // ProcessBuilder.start 不可挂起，切回主线程前取消会丢失进程引用；先拿回引用再清理。
            withContext(NonCancellable) { current.process = withContext(io) { launcher.start() } }
            currentCoroutineContext().ensureActive()
            if (connection !== current) throw CancellationException("Codex 已重新启动")
            val p = checkNotNull(current.process)
            current.messageJob = scope.launch(main) {
                var count = 0
                for ((method, id, params) in current.messages) {
                    if (connection === current) onMessage(method, id, params)
                    if (++count % 16 == 0) kotlinx.coroutines.yield()
                }
            }.also { job -> job.invokeOnCompletion { current.messages.close() } }
            val created = CodexRpc(p.inputStream, p.outputStream,
                onMessage = { method, id, params ->
                    kotlinx.coroutines.runBlocking { current.messages.send(Triple(method, id, params)) }
                },
                onClosed = { e -> scope.launch(main) { if (connection === current) closeConnection(current, e) } },
            )
            current.rpc = created
            created.initialize()
            try {
                models = parseModels(created.request("model/list", JsonObject(emptyMap())))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 模型列表失败不妨碍用户以已配置的模型开始任务。
            }
            currentCoroutineContext().ensureActive()
            if (connection !== current) throw CancellationException("Codex 已重新启动")
            runtimeStatus = ""
            changed()
            return created
        } catch (e: CancellationException) {
            current.rpc?.close()
            current.process?.destroy()
            throw e
        } catch (e: Exception) {
            if (connection === current) closeConnection(current, CodexException("Codex 启动失败：${e.message}", "runtime_stopped"))
            else { current.rpc?.close(); current.process?.destroy() }
            throw e
        }
    }

    /** 预先启动进程并读取模型列表（不发模型请求）。 */
    fun warmUp() { scope.launch(main) { runCatching { ensureRpc() } } }

    /** 线路或配置变化后重启进程（运行中的任务会中断）。 */
    fun restart() {
        closeConnection(connection, CodexException("Codex 已重新启动", "runtime_stopped"))
    }

    internal suspend fun commandRequest(method: String, params: JsonObject): JsonObject = withContext(main) {
        val current = ensureRpc()
        val result = current.request(method, params).objOrNull() ?: throw CodexException("Codex 返回了无效结果")
        if (rpc !== current) throw CancellationException("Codex 连接已变化")
        result
    }

    internal suspend fun <T> threadCommand(id: String,
        block: suspend (CodexConversation, CodexRpc, String, () -> Unit) -> T): T = withContext(main) {
        val c = conversation(id) ?: throw CodexException("会话不存在")
        require(!c.status.active) { "请等待当前任务结束后再执行此指令" }
        val token = Any()
        commandTokens[id] = token
        val mode = c.mode; val model = c.model; val projectId = c.projectId
        val validate = {
            if (conversation(id) !== c || commandTokens[id] !== token || c.mode != mode || c.model != model || c.projectId != projectId)
                throw CancellationException("会话指令已停止")
        }
        c.status = CodexStatus.STARTING; changed()
        try {
            turnStartLocks.getOrPut(id) { Mutex() }.withLock {
                validate()
                val current = ensureThread(c)
                validate()
                block(c, current, c.threadId!!, validate)
            }
        } finally {
            if (conversation(id) === c && commandTokens[id] === token) {
                commandTokens.remove(id)
                if (c.status == CodexStatus.STARTING && runs[id] == null) c.status = CodexStatus.IDLE
                save(c); changed()
                c.queue.firstOrNull()?.takeIf { c.status == CodexStatus.IDLE }?.let { next ->
                    c.queue.removeAt(0)
                    send(c.id, next.text, images = next.images, skills = next.skills)
                }
            }
        }
    }

    fun setPlanMode(id: String, enabled: Boolean) {
        val c = conversation(id) ?: throw CodexException("会话不存在")
        require(!c.status.active && !c.allowGoalContinuation) { "请先停止任务或暂停目标，再切换计划模式" }
        c.planMode = enabled; save(c); changed()
    }

    /** Codex 拒绝在已有线程上换线路时的报错（app-server ModelProviderError.ModelProviderMismatch）。 */
    internal fun isProviderMismatch(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("but your current provider is", ignoreCase = true) || m.contains("modelProviderMismatch", ignoreCase = true)
    }

    private suspend fun dropThread(rpc: CodexRpc, c: CodexConversation, threadId: String) {
        if (threadId in loadedThreads || threadId in threadProviders) runCatching { rpc.request("thread/unsubscribe", buildJsonObject { put("threadId", threadId) }) }
        loadedThreads -= threadId
        threadProviders -= threadId
        if (c.threadId == threadId) { c.threadId = null; c.threadProvider = null }
        c.restoreNext = true
    }

    private suspend fun ensureThread(c: CodexConversation): CodexRpc {
        val rpc = ensureRpc()
        val expectedMode = c.mode; val expectedModel = c.model; val expectedProject = c.projectId
        val route = prepareThread(c)
        val project = project(c.projectId) ?: throw CodexException("项目不存在")
        var threadId = c.threadId
        if (threadId != null && threadId in loadedThreads && threadProviders[threadId] == route.provider) return rpc
        val knownProvider = threadId?.let { threadProviders[it] } ?: c.threadProvider
        if (threadId != null && knownProvider != null && knownProvider != route.provider) {
            // Codex 不允许已有线程换线路（resume / turn/start 都会报 ModelProviderMismatch：
            // "This chat uses X, but your current provider is Y"）：卸掉旧线程，开新线程并把对话文字带过去
            dropThread(rpc, c, threadId)
            threadId = null
        }
        fun params(threadId: String?, restored: String?) = buildJsonObject {
            put("cwd", project.path)
            put("model", c.model)
            put("modelProvider", route.provider)
            route.config?.let { put("config", it) }
            if (threadId == null) put("allowProviderModelFallback", false)
            put("approvalPolicy", c.mode.approvalPolicy)
            put("sandbox", c.mode.sandbox)
            put("developerInstructions", developerInstructions(c, project) + restored.orEmpty())
            if (threadId != null) {
                put("threadId", threadId)
                put("excludeTurns", true)
            }
        }
        val result = try {
            val restore = if (threadId == null && c.restoreNext && c.turns.isNotEmpty()) restoredHistory(c) else null
            rpc.request(if (threadId == null) "thread/start" else "thread/resume", params(threadId, restore)).objOrNull()
        } catch (e: CodexException) {
            // Codex 那边这段对话的记录没了（换过虚拟环境、清过 Codex 数据），或这段对话开在别的线路上：
            // 开新线程，把对话的文字记录带过去接着聊
            val lost = e.message?.contains("no rollout found", ignoreCase = true) == true
            if (threadId == null || !(lost || isProviderMismatch(e))) throw e
            dropThread(rpc, c, threadId)
            rpc.request("thread/start", params(null, restoredHistory(c))).objOrNull()
        }
        val id = result?.obj("thread")?.str("id") ?: throw CodexException("Codex 没有返回会话线程")
        if (conversation(c.id) !== c || c.mode != expectedMode || c.model != expectedModel || c.projectId != expectedProject)
            throw CancellationException("会话上下文已变化")
        c.threadId = id
        c.threadProvider = route.provider
        c.restoreNext = false
        loadedThreads += id
        threadProviders[id] = route.provider
        save(c)
        return rpc
    }

    /** 新线程接不上旧记录时附在开发者说明后面的对话文字（最近的在后，总长有上限）。 */
    internal fun restoredHistory(c: CodexConversation): String {
        val lines = c.turns.flatMap { t ->
            t.items.mapNotNull { i ->
                val text = i.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                when (i.type) {
                    "userMessage" -> "User: " + text.take(2000)
                    "agentMessage" -> "Assistant: " + text.take(2000)
                    else -> null
                }
            }
        }
        if (lines.isEmpty()) return ""
        var body = lines.joinToString("\n\n")
        if (body.length > RESTORED_HISTORY_CHARS) body = "…" + body.takeLast(RESTORED_HISTORY_CHARS)
        return "\n\nThe earlier part of this conversation could not be restored from Codex's own records " +
            "(the session history was lost, e.g. the built-in Linux environment was reinstalled, or the model provider was switched, which Codex threads cannot do). " +
            "This is its text; continue from it naturally without mentioning the restore unless asked.\n\n" + body
    }

    private fun developerInstructions(c: CodexConversation, p: CodexProject): String = buildString {
        append(when (c.mode) {
            CodexMode.ASK -> "This conversation is in read-only mode. Inspect files and answer questions without modifying files."
            CodexMode.FULL -> "This task has full access to the built-in Alpine Linux environment of the Rustwright Android app. Implement the user's request using the available files, commands and network without requesting approval. Ask the user when essential information is missing."
            else -> "Work in ${p.path} inside the built-in Alpine Linux environment of the Rustwright Android app and implement the user's requested changes. Modify only this project unless explicitly requested."
        })
        append("\nReply in the user's language (usually Simplified Chinese).")
        instructions(p).takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
    }

    private fun closeConnection(current: Connection?, e: CodexException) {
        if (connection !== current) return
        resetMcpStatus()
        current?.messages?.close()
        current?.messageJob?.cancel()
        connection = null
        current?.ready?.cancel()
        current?.rpc?.close()
        current?.process?.destroy()
        loadedThreads.clear()
        threadProviders.clear()
        commandTokens.clear()
        stoppingTurns.values.forEach { it.cancel() }
        stoppingTurns.clear()
        startingTurns.clear()
        turnStartLocks.clear()
        retiredTurns.clear()
        runtimeStatus = e.message ?: "Codex 已退出"
        for (c in conversations) {
            val run = runs.remove(c.id)
            run?.job?.cancel()
            if (!c.status.active && run == null) continue
            c.turns.lastOrNull()?.takeIf { it.status == "inProgress" }?.status = "interrupted"
            c.status = CodexStatus.INTERRUPTED
            c.activeTurnId = null
            c.lastError = runtimeStatus
            clearApprovals(c)
            save(c)
        }
        pendingTools.clear()
        changed()
    }

    // endregion

    // region 事件

    private fun conversationFor(params: JsonObject): CodexConversation? {
        val threadId = params.str("threadId") ?: params.obj("thread")?.str("id") ?: return null
        return conversations.firstOrNull { it.threadId == threadId }
    }

    private suspend fun onMessage(method: String, id: JsonElement?, params: JsonObject) {
        val rpc = rpc ?: return
        if (id != null) { onRequest(rpc, method, id, params); return }
        // MCP 服务器启动状态（starting / ready / failed）：Codex 主动推送，连接器页优先用它
        if (method == "mcpServer/startupStatus/updated") {
            val name = params.str("name") ?: return
            mcpStartup[name] = (params.str("status") ?: "") to (params.str("error") ?: params.str("failureReason"))
            mcpStatusVersion++
            changed()
            return
        }
        val c = conversationFor(params) ?: return
        if (method == "thread/goal/updated" || method == "thread/goal/cleared") {
            c.goal = params.obj("goal")?.let(::parseGoal)
            if (c.goal?.status != "active") c.allowGoalContinuation = false
            save(c); changed(); return
        }
        val eventTurnId = params.str("turnId") ?: params.obj("turn")?.str("id")
        if (method == "turn/started") {
            val turnId = eventTurnId ?: return
            if ((c.id to turnId) in retiredTurns) return
            var run = startingTurns[c.id] ?: runs[c.id]?.takeIf { it.turnId == turnId }
            if (run == null && c.allowGoalContinuation && c.goal?.status == "active" &&
                (c.status == CodexStatus.IDLE || c.status == CodexStatus.STARTING && runs[c.id] == null)) {
                val mode = c.mode; val model = c.model; val projectId = c.projectId
                // 这里在唯一的消息循环里：线路 / 账号检查可能联网，限时 3 秒，超时就停掉这次续行，
                // 免得所有对话的事件都排队、消息队列满了把读取线程也堵住
                val route = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(GOAL_ROUTE_TIMEOUT_MS) { prepareThread(c) }
                        ?: throw CodexException("检查线路超时，目标续行已停止")
                }
                currentCoroutineContext().ensureActive()
                if (route.isFailure || conversation(c.id) !== c || !c.allowGoalContinuation ||
                    c.mode != mode || c.model != model || c.projectId != projectId || this.rpc !== rpc) {
                    c.allowGoalContinuation = false
                    if (conversation(c.id) === c) { c.lastError = route.exceptionOrNull()?.message ?: "目标续行已停止"; save(c); changed() }
                } else {
                    run = TurnRun(scope.coroutineContext[Job])
                    runs[c.id] = run
                }
            }
            if (run != null && (run.turnId == null || run.turnId == turnId)) {
                run.turnId = turnId
                run.started.complete(turnId)
            }
            if (run == null || !isCurrent(c, run)) {
                requestInterrupt(c, run, rpc, turnId)
                return
            }
            if (run.turnId != null && run.turnId != turnId) return
            run.turnId = turnId
        } else if (eventTurnId != null && (c.id to eventTurnId) in retiredTurns && method != "thread/tokenUsage/updated") {
            // 旧轮次的结束通知不能把新一轮置为空闲，尤其不能触发自动续发队列。
            return
        }
        when (method) {
            "turn/started" -> {
                val turn = params.obj("turn") ?: return
                val turnId = turn.str("id") ?: return
                if (c.turns.none { it.id == turnId }) c.turns += CodexTurn(turnId)
                c.activeTurnId = turnId
                if (c.status != CodexStatus.WAITING) c.status = CodexStatus.RUNNING
            }
            "turn/completed" -> {
                val turn = params.obj("turn") ?: return
                val t = turnOf(c, turn.str("id"))
                t.status = turn.str("status") ?: "completed"
                t.error = turn.obj("error")?.str("message")
                t.completedAt = System.currentTimeMillis()
                // 汇总视图可能只带部分条目，补上没收到过的
                (turn["items"] as? JsonArray)?.forEach { e -> e.objOrNull()?.let { mergeItem(t, it, completed = true) } }
                val run = runs[c.id]
                if (c.activeTurnId != t.id || run == null || run.turnId != t.id) { save(c); changed(); return }
                run.completed = true
                retiredTurns += c.id to t.id
                runs.remove(c.id)
                run.job.cancel()
                c.activeTurnId = null
                c.pendingPrompt = null
                clearApprovals(c)
                c.status = if (t.status == "failed") CodexStatus.ERROR else CodexStatus.IDLE
                if (t.error != null) c.lastError = t.error
                if (t.status == "failed" && t.error != null) scheduleRetry(c, t)
                else if (t.status == "completed") autoRetries.remove(c.id)
                c.updatedAt = System.currentTimeMillis()
                save(c)
                runCatching { onTurnFinished(c, t) }
                    .onFailure { RwLog.w("codex", "轮次完成处理失败", it) }
                c.queue.firstOrNull()?.takeIf { c.status == CodexStatus.IDLE }?.let { next ->
                    c.queue.removeAt(0)
                    send(c.id, next.text, images = next.images, skills = next.skills)
                }
            }
            "item/started", "item/completed" -> {
                val item = params.obj("item") ?: return
                mergeItem(turnOf(c, params.str("turnId")), item, completed = method == "item/completed")
            }
            "item/agentMessage/delta", "item/plan/delta" -> itemOf(c, params)?.let { it.text += params.str("delta").orEmpty().take((MAX_OUTPUT - it.text.length).coerceAtLeast(0)) }
            "item/reasoning/summaryTextDelta" -> itemOf(c, params, "reasoning")?.let {
                val index = params.long("summaryIndex")?.toInt() ?: 0
                if (index != it.summaryIndex && it.text.isNotEmpty()) it.text += "\n\n"
                it.summaryIndex = index
                it.text += params.str("delta").orEmpty().take((MAX_OUTPUT - it.text.length).coerceAtLeast(0))
            }
            "item/commandExecution/outputDelta" -> itemOf(c, params, "commandExecution")?.let {
                if (it.output.length < MAX_OUTPUT) it.output += params.str("delta").orEmpty().take(MAX_OUTPUT - it.output.length)
            }
            "turn/diff/updated" -> turnOf(c, params.str("turnId")).diff = params.str("diff")
            "turn/plan/updated" -> turnOf(c, params.str("turnId")).plan = (params["plan"] as? JsonArray).orEmpty().mapNotNull { e ->
                e.objOrNull()?.let { CodexPlanStep(it.str("step").orEmpty(), it.str("status") ?: "pending") }
            }
            "thread/tokenUsage/updated" -> params.obj("tokenUsage")?.let { u ->
                u.obj("total")?.long("totalTokens")?.let { c.totalTokens = it }
                u.obj("last")?.long("totalTokens")?.let { c.contextTokens = it }
                fun JsonObject.counts() = CodexTokenCounts(long("inputTokens") ?: 0, long("cachedInputTokens") ?: 0,
                    long("outputTokens") ?: 0, long("reasoningOutputTokens") ?: 0, long("totalTokens") ?: 0)
                u.obj("last")?.counts()?.let { c.lastUsage = it; if (c.baseTokens == 0L && it.input > 0) c.baseTokens = it.input }
                u.obj("total")?.counts()?.let { c.totalUsage = it }
                u.long("modelContextWindow")?.let { c.contextWindow = it }
                if (!c.status.active) save(c)
            }
            "error" -> {
                val message = params.obj("error")?.str("message") ?: params.str("message") ?: return
                val retrying = (params["willRetry"] as? JsonPrimitive)?.content == "true"
                c.lastError = if (retrying) "$message（正在重试）" else message
            }
            else -> return
        }
        changed()
    }

    private fun onRequest(rpc: CodexRpc, method: String, id: JsonElement, params: JsonObject) {
        val c = conversationFor(params)
        if (c == null) { runCatching { rpc.reject(id) }; return }
        val run = runs[c.id]
        val turnId = params.str("turnId")
        if (run == null || !isCurrent(c, run) || (turnId != null && (turnId != run.turnId || (c.id to turnId) in retiredTurns))) {
            runCatching { rpc.reject(id, "该任务已停止") }
            return
        }
        if (method == "item/tool/call") { callTool(rpc, c, id, params); return }
        if (method !in APPROVAL_METHODS) { runCatching { rpc.reject(id) }; return }
        if (c.mode == CodexMode.ASK && method != "item/tool/requestUserInput") {
            val denied = when (method) {
                "item/permissions/requestApproval" -> buildJsonObject {
                    putJsonObject("permissions") {}; put("scope", "turn")
                }
                "mcpServer/elicitation/request" -> elicitationAnswer(false)
                else -> buildJsonObject { put("decision", "decline") }
            }
            runCatching { rpc.respond(id, denied) }
            return
        }
        if (method == "mcpServer/elicitation/request" && params.obj("_meta")?.str("codex_approval_kind") == "mcp_tool_call") {
            mcpToolApproval(rpc, c, id, params)
            return
        }
        if (c.mode.autoApproves && method != "item/tool/requestUserInput" && method != "mcpServer/elicitation/request") {
            val result = if (method == "item/permissions/requestApproval")
                buildJsonObject { put("permissions", params["permissions"] ?: JsonObject(emptyMap())); put("scope", "turn") }
            else buildJsonObject { put("decision", "accept") }
            runCatching { rpc.respond(id, result) }
            return
        }
        c.approvals += CodexApproval(UUID.randomUUID().toString(), c.id, id, method, params)
        c.status = CodexStatus.WAITING
        changed()
    }

    private val pendingTools = HashMap<String, (Boolean) -> Unit>()

    /**
     * Codex 调用会改东西的 MCP 工具前的确认：自动模式直接同意，只读模式拒绝，其余和 Rustwright 工具一样显示审批卡片。
     */
    private fun mcpToolApproval(rpc: CodexRpc, c: CodexConversation, id: JsonElement, params: JsonObject) {
        // Approval is enforced at tools/call with the exact arguments and project snapshot.
        // Codex's preflight is not an execution capability.
        runCatching { rpc.respond(id, elicitationAnswer(c.mode != CodexMode.ASK)) }
    }

    private fun elicitationAnswer(accept: Boolean) = buildJsonObject {
        put("action", if (accept) "accept" else "decline")
        if (accept) putJsonObject("content") {} else put("content", kotlinx.serialization.json.JsonNull)
    }

    /**
     * 执行 [McpBridge] 收到的一次工具调用：按 Codex 带来的线程 ID 找到会话，在那一轮的作用域里执行，
     * 项目、模式、模型都在派发时捕获。会话停止时返回失败。
     */
    suspend fun runMcpTool(tool: CodexTool, args: JsonObject, threadId: String?, turnId: String?): CodexToolResult {
        val prepared = withContext(main) {
            val c = threadId?.let { t -> conversations.firstOrNull { it.threadId == t } }
                ?: return@withContext null to CodexToolResult(false, "找不到发起调用的会话")
            val run = runs[c.id]
            if (run == null || !isCurrent(c, run) || (turnId == null || run.turnId == null || turnId != run.turnId))
                return@withContext null to CodexToolResult(false, "任务已停止")
            if (c.mode == CodexMode.ASK && tool.needsApproval) return@withContext null to CodexToolResult(false, "只读模式下不能执行 ${tool.name}")
            val call = CodexToolCall(args, project(c.projectId), c)
            if (tool.needsApproval && !call.mode.autoApproves) {
                val gate = CompletableDeferred<Boolean>(run.job)
                val approval = CodexApproval(UUID.randomUUID().toString(), c.id, JsonPrimitive("mcp-execute"), CodexApproval.TOOL_APPROVAL, buildJsonObject {
                    put("tool", tool.name); put("arguments", args); put("reason", tool.description)
                })
                pendingTools[approval.id] = { gate.complete(it) }
                c.approvals += approval; c.status = CodexStatus.WAITING; changed()
                val allowed = try { gate.await() } finally {
                    pendingTools.remove(approval.id); c.approvals.remove(approval)
                    if (c.approvals.isEmpty() && c.status == CodexStatus.WAITING) c.status = CodexStatus.RUNNING
                    changed()
                }
                if (!allowed || !isCurrent(c, run) || c.mode != call.mode || c.projectId != call.project?.id)
                    return@withContext null to CodexToolResult(false, "操作未获批准或上下文已变化")
            }
            (run to call) to null
        }
        prepared.second?.let { return it }
        val (run, call) = prepared.first!!
        return try {
            scope.async(run.job) { tool.execute(call) }.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            CodexToolResult(false, "任务已停止")
        } catch (e: Exception) {
            CodexToolResult(false, "工具执行失败：${e.message}")
        }
    }

    private fun callTool(rpc: CodexRpc, c: CodexConversation, id: JsonElement, params: JsonObject) {
        val turn = runs[c.id] ?: return
        // 在主线程捕获项目，会话切换或停止不能改变一次工具调用的目标。
        val project = project(c.projectId)
        val name = params.str("tool")
        val tool = tools().firstOrNull { it.name == name }
        val args = params.obj("arguments") ?: JsonObject(emptyMap())
        val call = CodexToolCall(args, project, c)
        fun reply(r: CodexToolResult) = runCatching {
            rpc.respond(id, buildJsonObject {
                put("success", r.success)
                putJsonArray("contentItems") { addJsonObject { put("type", "inputText"); put("text", r.text) } }
            })
        }
        if (tool == null) { reply(CodexToolResult(false, "未知工具：$name")); return }
        val run = {
            scope.launch(turn.job) {
                try {
                    val r = tool.execute(call)
                    currentCoroutineContext().ensureActive()
                    reply(r)
                } catch (e: CancellationException) {
                    reply(CodexToolResult(false, "任务已停止"))
                    throw e
                } catch (e: Exception) {
                    reply(CodexToolResult(false, "工具执行失败：${e.message}"))
                }
            }
        }
        if (!tool.needsApproval || c.mode.autoApproves) { run(); return }
        if (c.mode == CodexMode.ASK) { reply(CodexToolResult(false, "只读模式下不能执行 $name")); return }
        val approval = CodexApproval(UUID.randomUUID().toString(), c.id, id, CodexApproval.TOOL_APPROVAL, buildJsonObject {
            put("tool", name); put("arguments", args); put("reason", tool.description)
        })
        pendingTools[approval.id] = { ok -> if (ok) run() else reply(CodexToolResult(false, "用户拒绝了 $name")) }
        c.approvals += approval
        c.status = CodexStatus.WAITING
        changed()
    }

    private fun turnOf(c: CodexConversation, turnId: String?): CodexTurn {
        val id = turnId ?: c.activeTurnId ?: c.turns.lastOrNull()?.id ?: "pending"
        return c.turns.firstOrNull { it.id == id } ?: CodexTurn(id).also { c.turns += it }
    }

    private fun itemOf(c: CodexConversation, params: JsonObject, type: String = "agentMessage"): CodexItem? {
        val itemId = params.str("itemId") ?: return null
        val turn = turnOf(c, params.str("turnId"))
        return turn.items.firstOrNull { it.id == itemId } ?: CodexItem(itemId, type, status = "inProgress").also { turn.items += it }
    }

    private fun mergeItem(turn: CodexTurn, json: JsonObject, completed: Boolean) {
        val id = json.str("id") ?: return
        val type = json.str("type") ?: "unknown"
        val item = turn.items.firstOrNull { it.id == id } ?: CodexItem(id, type).also { turn.items += it }
        json.str("status")?.let { item.status = it } ?: run { item.status = if (completed) "completed" else "inProgress" }
        when (type) {
            "userMessage" -> item.text = (json["content"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.objOrNull() ?: return@mapNotNull null
                o.str("text") ?: o.str("path")?.let { "[图片] " + it.substringAfterLast('/') }
            }.joinToString("\n")
            "agentMessage", "plan" -> json.str("text")?.takeIf { it.isNotEmpty() || completed }?.let { item.text = it }
            "reasoning" -> {
                val summary = (json["summary"] as? JsonArray).orEmpty().mapNotNull { it.text }.joinToString("\n\n")
                if (summary.isNotEmpty()) item.text = summary
            }
            "commandExecution" -> {
                item.command = json.str("command")
                item.cwd = json.str("cwd")
                json.str("aggregatedOutput")?.let { item.output = it.take(MAX_OUTPUT) }
                item.exitCode = json.long("exitCode")?.toInt()
                item.durationMs = json.long("durationMs")
            }
            "fileChange" -> item.changes = (json["changes"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.objOrNull() ?: return@mapNotNull null
                val kind = o.obj("kind")?.str("type") ?: o.str("kind") ?: "update"
                CodexFileChange(o.str("path") ?: return@mapNotNull null, kind, o.str("diff").orEmpty())
            }
            "mcpToolCall", "dynamicToolCall" -> {
                item.server = json.str("server")
                item.tool = json.str("tool")
                json["arguments"]?.let { item.arguments = if (it is JsonPrimitive) it.content else it.toString() }
                json["result"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { r ->
                    val texts = (r.objOrNull()?.get("content") as? JsonArray).orEmpty().mapNotNull { it.objOrNull()?.str("text") }
                    item.result = (if (texts.isNotEmpty()) texts.joinToString("\n") else r.toString()).take(MAX_OUTPUT)
                }
                (json["contentItems"] as? JsonArray)?.mapNotNull { it.objOrNull()?.str("text") }?.takeIf { it.isNotEmpty() }?.let { item.result = it.joinToString("\n").take(MAX_OUTPUT) }
                item.error = json.obj("error")?.str("message")
                item.durationMs = json.long("durationMs")
            }
            "webSearch" -> item.text = json.str("query").orEmpty()
            // 助手用 view_image 看图：只留路径（步骤详情里据此显示预览）
            "imageView" -> item.text = json.str("path").orEmpty()
            "contextCompaction" -> {}
            else -> if (item.text.isEmpty()) item.text = json.toString().take(2000)
        }
    }

    // endregion

    // region 持久化

    private val convDir get() = File(dir, "conversations")

    private fun readState(): Pair<List<CodexProject>, List<CodexConversation>> {
        val loadedProjects = mutableListOf<CodexProject>()
        val loadedConversations = mutableListOf<CodexConversation>()
        runCatching {
            File(dir, "projects.json").takeIf { it.isFile }?.let {
                require(it.length() <= 4 * 1024 * 1024)
                loadedProjects += CodexRpc.json.decodeFromString(ListSerializer(CodexProject.serializer()), it.readText())
            }
        }
        convDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                require(f.length() <= 64L * 1024 * 1024)
                CodexRpc.json.decodeFromString(CodexConversation.serializer(), f.readText()).also {
                    require(it.id == f.nameWithoutExtension && it.id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                }
            }.getOrNull()?.let { c ->
                // 上次退出时仍在运行的任务已无法继续
                if (c.status.active) {
                    c.status = CodexStatus.INTERRUPTED
                    c.activeTurnId = null
                    c.turns.lastOrNull()?.takeIf { it.status == "inProgress" }?.status = "interrupted"
                }
                loadedConversations += c
            }
        }
        return loadedProjects to loadedConversations
    }

    private fun restore(state: Pair<List<CodexProject>, List<CodexConversation>>) {
        val projectIds = HashMap<String, String>()
        for (p in state.first) {
            if (p.path in deletedProjectPaths) continue
            val existing = projects.firstOrNull { it.path == p.path || it.id == p.id }
            if (existing == null) projects += p else projectIds[p.id] = existing.id
        }
        for (c in state.second) if (conversations.none { it.id == c.id } && state.first.none { it.id == c.projectId && it.path in deletedProjectPaths }) {
            c.projectId = projectIds[c.projectId] ?: c.projectId
            conversations += c
            if (c.projectId in projectIds.values) conversationsNeedSave += c.id
        }
        restored.complete(Unit)
        if (projectsNeedSave) { projectsNeedSave = false; saveProjects() }
        conversationsNeedSave.toList().forEach { id -> conversation(id)?.let(::save) }
        conversationsNeedSave.clear()
        changed()
    }

    private fun saveProjects() {
        if (!restored.isCompleted) { projectsNeedSave = true; return }
        persistence.writeJson(File(dir, "projects.json"), CodexRpc.json.encodeToJsonElement(ListSerializer(CodexProject.serializer()), projects))
    }

    private fun save(c: CodexConversation) {
        if (conversation(c.id) !== c) return
        if (!restored.isCompleted) { conversationsNeedSave += c.id; return }
        persistence.writeJson(File(convDir, "${c.id}.json"), CodexRpc.json.encodeToJsonElement(CodexConversation.serializer(), c))
    }

    private fun write(file: File, text: String) = persistence.write(file, text)

    /** 正常关闭应用前可等待所有状态落盘；作用域直接取消或进程被系统杀死时不保证排空。 */
    suspend fun flush() { restored.await(); persistence.flush() }

    // endregion

    companion object {
        /** 目标续行时在消息循环里检查线路的最长等待。 */
        const val GOAL_ROUTE_TIMEOUT_MS = 3_000L
        /** 一轮失败后最多自动重试几次。 */
        const val MAX_AUTO_RETRIES = 0
        fun isFreeModel(model: String) = model.startsWith(io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.benefitModelPrefix())

        const val MAX_OUTPUT = 200_000
        private const val RESTORED_HISTORY_CHARS = 12_000
        private val APPROVAL_METHODS = setOf(
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval",
            "item/tool/requestUserInput", "mcpServer/elicitation/request",
        )

        internal fun parseModels(result: JsonElement): List<CodexModel> =
            (result.objOrNull()?.get("data") as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e.objOrNull() ?: return@mapNotNull null
                val id = o.str("id") ?: o.str("model") ?: return@mapNotNull null
                val efforts = (o["supportedReasoningEfforts"] as? JsonArray).orEmpty().mapNotNull { r -> r.objOrNull()?.str("reasoningEffort") ?: r.text }
                CodexModel(id, o.str("displayName") ?: id, efforts, o.str("defaultReasoningEffort"))
            }
    }
}

data class CodexModel(val id: String, val name: String, val efforts: List<String>, val defaultEffort: String?)

