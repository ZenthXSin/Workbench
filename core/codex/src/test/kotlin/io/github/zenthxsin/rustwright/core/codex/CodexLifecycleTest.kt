package io.github.zenthxsin.rustwright.core.codex

import io.github.zenthxsin.rustwright.core.runtime.LifecycleDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 无模型/网络：以可控制的 stdio 假进程覆盖停止、重启和持久化的真实调用时序。 */
class CodexLifecycleTest {
    @Test fun explicitMessagePausesGoalBeforeStartingAndRejectsTheRacingAutoTurn() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}""")
            f.barrier()
            f.onMain {
                c.goal = CodexGoal("goal", "active"); c.allowGoalContinuation = true
                f.wb.send(c.id, "new task")
            }
            val pause = f.server.take("thread/goal/set")
            assertEquals("paused", pause.obj("params")?.str("status"))
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"auto-racing"}}""")
            val interrupt = f.server.take("turn/interrupt")
            assertEquals("auto-racing", interrupt.obj("params")?.str("turnId"))
            assertEquals(1, f.server.count("turn/start"))
            f.server.reply(pause, """{"goal":{"objective":"goal","status":"paused"}}""")
            f.server.reply(interrupt)
            val next = f.server.take("turn/start")
            assertTrue("new task" in next.toString())
            f.server.reply(next, """{"turn":{"id":"explicit"}}""")
            f.barrier()
            f.onMain { assertFalse(c.allowGoalContinuation); assertEquals("explicit", c.activeTurnId) }
        }
    }


    @Test fun planAndSelectedSkillsUseStructuredTurnInputIncludingTheQueue() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.setPlanMode(c.id, true); f.wb.send(c.id, "plan", skills = listOf(CodexSkillRef("rw", "/root/.agents/skills/rw/SKILL.md"))) }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val turn = f.server.take("turn/start")
            val params = turn.obj("params")!!
            assertEquals("plan", params.obj("collaborationMode")?.str("mode"))
            assertEquals("test-model", params.obj("collaborationMode")?.obj("settings")?.str("model"))
            assertTrue("\"type\":\"skill\"" in params.toString())
            assertTrue("/root/.agents/skills/rw/SKILL.md" in params.toString())
            f.server.reply(turn, """{"turn":{"id":"turn-1"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-1"}}""")
            f.barrier()
            f.onMain { f.wb.send(c.id, "next", skills = listOf(CodexSkillRef("project-skill", "/workspace/mod/.agents/skills/a/SKILL.md"))) }
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}""")
            val next = f.server.take("turn/start")
            assertTrue("project-skill" in next.toString())
            f.server.reply(next, """{"turn":{"id":"turn-2"}}""")
            f.barrier()
        }
    }

    @Test fun reviewUsesNativeInlineReviewInsteadOfSendingSlashText() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.send(c.id, "检查兼容性", operation = CodexOperation.REVIEW) }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val review = f.server.take("review/start")
            assertEquals("inline", review.obj("params")?.str("delivery"))
            assertEquals("custom", review.obj("params")?.obj("target")?.str("type"))
            assertEquals("检查兼容性", review.obj("params")?.obj("target")?.str("instructions"))
            assertEquals(0, f.server.count("turn/start"))
            f.server.reply(review, """{"turn":{"id":"review-1"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"review-1"}}""")
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"review-1","status":"completed"}}""")
            f.barrier()
            f.onMain { assertEquals(CodexStatus.IDLE, c.status); assertNull(c.pendingPrompt) }
        }
    }

    @Test fun compactWaitsForItsAsynchronousTurnAndCanInterruptALateStart() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.send(c.id, "压缩", operation = CodexOperation.COMPACT) }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val compact = f.server.take("thread/compact/start")
            f.server.reply(compact)
            f.barrier()
            f.onMain { assertEquals(CodexStatus.STARTING, c.status); f.wb.interrupt(c.id) }
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"compact-1"}}""")
            val interrupt = f.server.take("turn/interrupt")
            assertEquals("compact-1", interrupt.obj("params")?.str("turnId"))
            f.server.reply(interrupt)
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"compact-1","status":"interrupted"}}""")
            f.barrier()
            f.onMain { assertEquals(CodexStatus.INTERRUPTED, c.status); assertNull(c.activeTurnId) }
            assertEquals(0, f.server.count("turn/start"))
        }
    }

    @Test fun compactCompletesWithoutInventingATurnId() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.send(c.id, "压缩", operation = CodexOperation.COMPACT) }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            f.server.reply(f.server.take("thread/compact/start"))
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"compact-1"}}""")
            f.barrier()
            f.onMain { assertEquals("compact-1", c.activeTurnId) }
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"compact-1","status":"completed"}}""")
            f.barrier()
            f.onMain { assertEquals(CodexStatus.IDLE, c.status); assertNull(c.pendingPrompt) }
        }
    }

    @Test fun explicitGoalUsesBudgetAndReceivesNativeContinuationAndStopPausesIt() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val setting = async { withContext(f.main) { f.wb.setGoal(c.id, "完成模组并验证", budget = 1000) } }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val request = f.server.take("thread/goal/set")
            assertEquals("user", request.obj("params")?.str("origin"))
            assertEquals(1000L, request.obj("params")?.long("tokenBudget"))
            f.server.reply(request, """{"goal":{"objective":"完成模组并验证","status":"active","tokenBudget":1000,"tokensUsed":10}}""")
            assertEquals(1000L, setting.await().tokenBudget)
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"goal-1"}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.RUNNING, c.status)
                assertEquals("goal-1", c.activeTurnId)
                assertFalse(f.wb.setModel(c.id, "another", null))
                f.wb.interrupt(c.id)
                assertFalse(c.allowGoalContinuation)
            }
            val pause = f.server.take("thread/goal/set")
            assertEquals("paused", pause.obj("params")?.str("status"))
            f.server.reply(pause, """{"goal":{"objective":"完成模组并验证","status":"paused"}}""")
            f.server.reply(f.server.take("turn/interrupt"))
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"goal-late"}}""")
            val late = f.server.take("turn/interrupt")
            assertEquals("goal-late", late.obj("params")?.str("turnId"))
            f.server.reply(late)
            f.barrier()
            f.onMain { assertEquals(CodexStatus.INTERRUPTED, c.status) }
        }
    }

    @Test fun goalContinuationRevalidatesAccountBeforeExecutingTools() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}""")
            f.barrier()
            f.onMain {
                c.goal = CodexGoal("goal", "active"); c.allowGoalContinuation = true
                f.wb.prepareThread = { throw CodexException("quota exhausted") }
            }
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"unauthorized"}}""")
            val interrupted = f.server.take("turn/interrupt")
            assertEquals("unauthorized", interrupted.obj("params")?.str("turnId"))
            f.server.reply(interrupted)
            f.barrier()
            f.onMain { assertFalse(c.allowGoalContinuation); assertEquals("quota exhausted", c.lastError) }
        }
    }

    @Test fun stoppedGoalCommandCannotReenableItAfterTheNextMessageStarts() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val setting = async { withContext(f.main) { runCatching { f.wb.setGoal(c.id, "goal", budget = 1000) } } }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val request = f.server.take("thread/goal/set")
            f.onMain { f.wb.interrupt(c.id); f.wb.send(c.id, "只回答问题") }
            val pause = f.server.take("thread/goal/set")
            f.server.reply(pause, """{"goal":{"objective":"goal","status":"paused"}}""")
            f.server.reply(request, """{"goal":{"objective":"goal","status":"active"}}""")
            assertTrue(setting.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
            val next = f.server.take("turn/start")
            f.server.reply(next, """{"turn":{"id":"new-turn"}}""")
            f.barrier()
            f.onMain { assertFalse(c.allowGoalContinuation); assertEquals("new-turn", c.activeTurnId) }
        }
    }

    @Test fun metadataCommandDrainsMessagesQueuedWhileLoadingAndDoesNotAuthorizeReadGoals() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val reading = async { withContext(f.main) { f.wb.getGoal(c.id) } }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val request = f.server.take("thread/goal/get")
            f.onMain { f.wb.send(c.id, "next") }
            f.server.reply(request, """{"goal":{"objective":"old goal","status":"active"}}""")
            assertEquals("old goal", reading.await()?.objective)
            val next = f.server.take("turn/start")
            assertTrue("next" in next.toString())
            f.server.reply(next, """{"turn":{"id":"next-turn"}}""")
            f.barrier()
            f.onMain { assertFalse(c.allowGoalContinuation); assertTrue(c.queue.isEmpty()) }
        }
    }

    @Test fun unsupportedGoalEndpointReturnsAnErrorAndDoesNotClaimAnActiveGoal() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val setting = async { withContext(f.main) { runCatching { f.wb.setGoal(c.id, "goal") } } }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            f.server.fail(f.server.take("thread/goal/set"), "Method not found")
            assertTrue(setting.await().exceptionOrNull()?.message.orEmpty().contains("Method not found"))
            f.onMain { assertFalse(c.allowGoalContinuation); assertNull(c.goal); assertEquals(CodexStatus.IDLE, c.status) }
            assertEquals(0, f.server.count("turn/start"))
        }
    }

    @Test fun skillCatalogPreservesScopeAndUsesNativeEnableConfiguration() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val listing = async { f.wb.listSkills(c.projectId) }
            f.initialize()
            val request = f.server.take("skills/list")
            assertTrue("forceReload" in request.toString())
            f.server.reply(request, """{"data":[{"cwd":"/workspace/mod","skills":[{"name":"global","description":"global skill","path":"/root/.agents/skills/global/SKILL.md","scope":"user","enabled":true},{"name":"local","description":"local skill","path":"/workspace/mod/.agents/skills/local/SKILL.md","scope":"repo","enabled":false}],"errors":[{"path":"broken","message":"missing description"}]}]}""")
            val result = listing.await()
            assertEquals(listOf("user", "repo"), result.skills.map { it.scope })
            assertFalse(result.skills.last().enabled)
            assertTrue("missing description" in result.errors.single())
            val enable = async { f.wb.setSkillEnabled(result.skills.last(), true) }
            val config = f.server.take("skills/config/write")
            assertEquals("/workspace/mod/.agents/skills/local/SKILL.md", config.obj("params")?.str("path"))
            assertEquals(JsonPrimitive(true), config.obj("params")?.get("enabled"))
            f.server.reply(config)
            enable.await()
        }
    }

    @Test fun pluginCatalogUsesRealMarketplaceForInstallationAndIdForRemoval() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val listing = async { f.wb.listPlugins(c.projectId) }
            f.initialize()
            f.server.reply(f.server.take("plugin/list"), """{"marketplaces":[{"name":"official","plugins":[{"id":"drawing@official","name":"drawing","installed":false,"enabled":false,"availability":"AVAILABLE","interface":{"shortDescription":"draw images"}}]}],"marketplaceLoadErrors":[]}""")
            val plugin = listing.await().plugins.single()
            assertEquals("draw images", plugin.description)
            val installing = async { f.wb.installPlugin(plugin) }
            val request = f.server.take("plugin/install")
            assertEquals("official", request.obj("params")?.str("remoteMarketplaceName"))
            assertEquals("drawing", request.obj("params")?.str("pluginName"))
            f.server.reply(request)
            installing.await()
            val removing = async { f.wb.uninstallPlugin(plugin) }
            val removal = f.server.take("plugin/uninstall")
            assertEquals("drawing@official", removal.obj("params")?.str("pluginId"))
            f.server.reply(removal)
            removing.await()
        }
    }


    @Test fun completionHookRunsOnceForTheCurrentTurn() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            val finished = mutableListOf<String>()
            f.onMain { f.wb.onTurnFinished = { conversation, turn -> finished += "${conversation.id}:${turn.id}:${turn.status}" } }
            val event = """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}"""
            f.server.notify("turn/completed", event)
            f.barrier()
            f.server.notify("turn/completed", event)
            f.barrier()
            f.onMain { assertEquals(listOf("${c.id}:turn-1:completed"), finished) }
        }
    }

    @Test fun completionHookReceivesInterruptedStatusWithoutTreatingItAsSuccess() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            val finished = mutableListOf<String>()
            f.onMain { f.wb.onTurnFinished = { _, turn -> finished += turn.status } }
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"interrupted","items":[]}}""")
            f.barrier()
            f.onMain { assertEquals(listOf("interrupted"), finished) }
        }
    }

    @Test fun failingCompletionHookDoesNotBlockTheQueuedTask() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.onMain {
                f.wb.onTurnFinished = { _, _ -> error("disk unavailable") }
                c.queue += CodexQueued("queued", "continue")
            }
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}""")
            val next = f.server.take("turn/start")
            assertTrue("continue" in next.toString())
            f.server.reply(next, """{"turn":{"id":"turn-2"}}""")
            f.barrier()
        }
    }

    @Test fun readOnlyUsesSandboxAndRejectsWriteAndPermissionRequests() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.setMode(c.id, CodexMode.ASK); f.wb.send(c.id, "inspect") }
            f.initialize()
            val thread = f.server.take("thread/start")
            assertEquals("read-only", thread.obj("params")?.str("sandbox"))
            assertEquals("never", thread.obj("params")?.str("approvalPolicy"))
            f.server.reply(thread, """{"thread":{"id":"thread-1"}}""")
            val turn = f.server.take("turn/start")
            assertEquals("readOnly", turn.obj("params")?.obj("sandboxPolicy")?.str("type"))
            assertEquals("never", turn.obj("params")?.str("approvalPolicy"))
            f.server.reply(turn, """{"turn":{"id":"turn-1"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-1"}}""")
            for ((i, method) in listOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                    "item/permissions/requestApproval", "mcpServer/elicitation/request").withIndex()) {
                val id = 500L + i
                f.server.request(id, method, """{"threadId":"thread-1","turnId":"turn-1","permissions":{"network":true}}""")
                val result = f.server.takeResponse(id).obj("result")!!
                when (i) {
                    2 -> assertEquals(JsonObject(emptyMap()), result.obj("permissions"))
                    3 -> assertEquals("decline", result.str("action"))
                    else -> assertEquals("decline", result.str("decision"))
                }
            }
            var wrote = false
            val tool = CodexTool("write", "write", JsonObject(emptyMap()), true) {
                wrote = true; CodexToolResult(true, "unexpected")
            }
            assertFalse(f.wb.runMcpTool(tool, JsonObject(emptyMap()), "thread-1", "turn-1").success)
            f.onMain {
                assertFalse(wrote)
                assertTrue(c.approvals.isEmpty())
                f.wb.setMode(c.id, CodexMode.FULL)
                assertEquals(CodexMode.ASK, c.mode) // active turns cannot change execution policy
            }
            f.server.request(510, "item/tool/requestUserInput", """{"threadId":"thread-1","turnId":"turn-1","questions":[]}""")
            f.barrier()
            f.onMain { assertEquals(CodexApproval.Kind.QUESTION, c.approvals.single().kind) }
        }
    }

    @Test fun unsupportedReadOnlySandboxDoesNotRetryWithFullAccess() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            val failed = CompletableDeferred<Unit>()
            f.onMain {
                f.wb.setMode(c.id, CodexMode.ASK)
                f.wb.onBusy = { if (!it) failed.complete(Unit) }
                f.wb.send(c.id, "inspect")
            }
            f.initialize()
            val thread = f.server.take("thread/start")
            assertEquals("read-only", thread.obj("params")?.str("sandbox"))
            f.server.fail(thread, "read-only sandbox unavailable")
            withTimeout(5000) { failed.await() }
            f.onMain { assertEquals(CodexStatus.ERROR, c.status) }
            assertEquals(1, f.server.count("thread/start"))
            assertEquals(0, f.server.count("turn/start"))
        }
    }

    @Test fun restartDiscardsMcpStatusAndRejectsOldInFlightQuery() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            f.server.notify("mcpServer/startupStatus/updated", """{"name":"old","status":"ready"}""")
            f.barrier()
            val oldQuery = async { runCatching { f.wb.mcpServers() } }
            f.server.reply(f.server.take("config/read"), """{"config":{"mcp_servers":{}}}""")
            f.server.take("mcpServerStatus/list")
            val before = f.onMain { f.wb.mcpStatusVersion }
            f.onMain { f.wb.restart() }
            assertTrue(withTimeout(5000) { oldQuery.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            f.onMain { assertTrue(f.wb.mcpStatusVersion > before) }
            f.server = FakeServer()
            val current = async { f.wb.mcpServers() }
            f.initialize()
            f.server.reply(f.server.take("config/read"), """{"config":{"mcp_servers":{"fresh":{"url":"http://localhost/mcp"}}}}""")
            f.server.reply(f.server.take("mcpServerStatus/list"), """{"data":[]}""")
            val entries = withTimeout(5000) { current.await() }
            assertEquals(listOf("fresh"), entries.map { it.name })
            assertNull(entries.single().status)
        }
    }

    @Test fun reloadClearsRemovedServersAndKeepsCurrentStartupFallback() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            f.server.notify("mcpServer/startupStatus/updated", """{"name":"removed","status":"ready"}""")
            f.barrier()
            val reload = async { f.wb.reloadMcp() }
            f.server.reply(f.server.take("config/mcpServer/reload"))
            reload.await()
            f.server.notify("mcpServer/startupStatus/updated", """{"name":"new","status":"starting"}""")
            f.barrier()
            val query = async { f.wb.mcpServers() }
            f.server.reply(f.server.take("config/read"), """{"config":{"mcp_servers":{}}}""")
            f.server.fail(f.server.take("mcpServerStatus/list"), "temporarily unavailable")
            val entries = query.await()
            assertEquals(listOf("new"), entries.map { it.name })
            assertEquals("starting", entries.single().status)
        }
    }

    @Test fun cancelledMcpQueryCannotReturnCachedReadyState() = runBlocking {
        Fixture().use { f ->
            f.startTurn()
            f.server.notify("mcpServer/startupStatus/updated", """{"name":"cached","status":"ready"}""")
            f.barrier()
            var returned = false
            val query = async { f.wb.mcpServers(); returned = true }
            f.server.reply(f.server.take("config/read"))
            f.server.take("mcpServerStatus/list")
            query.cancel()
            withTimeout(5000) { query.join() }
            assertFalse(returned)
        }
    }

    @Test
    fun deletionReleasesAgainAfterAlreadyEnteredToolFinallyExits() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val releasedAfterTool = CompletableDeferred<Unit>()
            var cacheExists = false
            var releaseCount = 0
            f.onMain {
                f.wb.onConversationDeleted = {
                    cacheExists = false
                    if (++releaseCount == 2) releasedAfterTool.complete(Unit)
                }
                f.wb.tools = { listOf(CodexTool("late_cache", "test", buildJsonObject { put("type", "object") }, false) {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        finish.await()
                        cacheExists = true
                    }
                    CodexToolResult(true, "finished")
                }) }
            }
            val c = f.startTurn()
            f.server.request(500, "item/tool/call", """{"threadId":"thread-1","turnId":"turn-1","tool":"late_cache","arguments":{}}""")
            withTimeout(5000) { entered.await() }
            f.onMain {
                f.wb.interrupt(c.id)
                f.wb.deleteConversation(c.id)
                assertEquals(1, releaseCount)
                assertFalse(cacheExists)
            }
            finish.complete(Unit)
            withTimeout(5000) { releasedAfterTool.await() }
            f.onMain { assertFalse(cacheExists) }
        }
    }

    @Test
    fun pauseContinuesToolsAndQueuedTurnsThenDefersApprovalUntilResume() = runBlocking {
        val background = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val foreground = PausableDispatcher(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
        val state = LifecycleDispatcher(foreground, background)
        try {
            Fixture(mainOverride = state).use { f ->
                val busy = mutableListOf<Boolean>()
                f.onMain {
                    f.wb.onBusy = { busy += it }
                    f.wb.tools = { listOf(CodexTool("background_tool", "test", buildJsonObject { put("type", "object") }, false) {
                        CodexToolResult(true, "continued in background")
                    }) }
                }
                val c = f.startTurn()
                f.onMain { f.wb.send(c.id, "queued turn") }
                foreground.pause()
                state.pause()
                f.server.request(500, "item/tool/call", """{"threadId":"thread-1","turnId":"turn-1","tool":"background_tool","arguments":{}}""")
                assertEquals("true", f.server.takeResponse(500).obj("result")?.get("success")?.toString())
                f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}""")
                val queued = f.server.take("turn/start")
                f.server.reply(queued, """{"turn":{"id":"turn-2"}}""")
                f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-2"}}""")
                f.server.request(501, "item/commandExecution/requestApproval", """{"threadId":"thread-1","turnId":"turn-2","command":"test"}""")
                f.barrier()
                f.onMain {
                    assertEquals(CodexStatus.WAITING, c.status)
                    assertEquals(1, c.approvals.size)
                    assertTrue(c.queue.isEmpty())
                    assertEquals(listOf(true), busy) // 队列衔接不短暂关闭前台服务。
                }
                state.resume()
                foreground.resume()
                f.onMain { f.wb.answer(c.approvals.single().id, CodexAnswer.Accept) }
                assertEquals("accept", f.server.takeResponse(501).obj("result")?.str("decision"))
                f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-2","status":"completed","items":[]}}""")
                f.barrier()
                f.onMain {
                    assertEquals(CodexStatus.IDLE, c.status)
                    assertTrue(c.approvals.isEmpty())
                    assertEquals(listOf(true, false), busy)
                }
            }
        } finally {
            state.resume()
            foreground.resume()
            foreground.close()
            background.close()
        }
    }

    @Test
    fun busyCoversAllConversationsAndRestartReleasesIt() = runBlocking {
        Fixture().use { f ->
            val busy = mutableListOf<Boolean>()
            val first = f.newConversation()
            val second = f.newConversation()
            f.onMain {
                f.wb.onBusy = { busy += it }
                f.wb.send(first.id, "first")
                f.wb.send(second.id, "second")
                assertEquals(listOf(true), busy)
                f.wb.interrupt(first.id)
                assertEquals(listOf(true), busy)
                f.wb.restart()
                assertEquals(listOf(true, false), busy)
            }
        }
    }

    @Test
    fun deletionCallbackOnlyFiresAfterSuccessfulRemovalIncludingProjects() = runBlocking {
        Fixture().use { f ->
            val removed = mutableListOf<String>()
            val first = f.startTurn()
            val second = f.newConversation()
            f.onMain {
                f.wb.onConversationDeleted = { id ->
                    assertNull(f.wb.conversation(id))
                    removed += id
                }
                f.wb.deleteConversation(first.id)
                f.wb.deleteProject(first.projectId)
                assertTrue(removed.isEmpty())
                assertEquals(2, f.wb.conversations.size)
                assertTrue(f.wb.project(first.projectId) != null)
                f.wb.interrupt(first.id)
                f.wb.deleteConversation(first.id)
                f.wb.deleteConversation(first.id)
                assertEquals(listOf(first.id), removed)
                f.wb.deleteProject(second.projectId)
                assertEquals(listOf(first.id, second.id), removed)
                assertTrue(f.wb.conversations.isEmpty())
                assertTrue(f.wb.projects.isEmpty())
            }
        }
    }

    @Test
    fun stopBeforeThreadStartsDoesNotSendTurnStartOrCancelSharedStartup() = runBlocking {
        Fixture().use { f ->
            val first = f.newConversation()
            val second = f.newConversation()
            f.onMain { f.wb.send(first.id, "first") }
            val initialization = f.server.take("initialize")
            f.onMain {
                f.wb.send(second.id, "second")
                f.wb.interrupt(first.id)
                f.wb.interrupt(first.id)
            }
            f.server.reply(initialization)
            f.server.reply(f.server.take("model/list"), """{"data":[]}""")
            val thread = f.server.take("thread/start")
            f.server.reply(thread, """{"thread":{"id":"second-thread"}}""")
            val turn = f.server.take("turn/start")
            assertEquals("second-thread", turn.obj("params")?.str("threadId"))
            f.server.reply(turn, """{"turn":{"id":"second-turn"}}""")
            f.server.notify("turn/started", """{"threadId":"second-thread","turn":{"id":"second-turn"}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.INTERRUPTED, first.status)
                assertEquals(CodexStatus.RUNNING, second.status)
                assertNull(first.threadId)
                assertTrue(first.turns.isEmpty())
            }
            assertEquals(1, f.starts)
            assertEquals(1, f.server.count("thread/start"))
            assertEquals(1, f.server.count("turn/start"))
            assertTrue(f.server.isAlive)
        }
    }

    @Test
    fun stopWhileResumingThreadDoesNotStartTurn() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.send(c.id, "stop during thread setup") }
            f.initialize()
            val thread = f.server.take("thread/start")
            f.onMain { f.wb.interrupt(c.id) }
            f.server.reply(thread, """{"thread":{"id":"thread-1"}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.INTERRUPTED, c.status)
                assertNull(c.activeTurnId)
                assertNull(c.lastError)
            }
            assertEquals(0, f.server.count("turn/start"))
        }
    }

    @Test
    fun stopWhileTurnStartIsInFlightInterruptsItAndIgnoresLateCompletion() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.send(c.id, "old turn") }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            val oldStart = f.server.take("turn/start")
            f.onMain {
                f.wb.interrupt(c.id)
                f.wb.interrupt(c.id)
                f.wb.send(c.id, "replacement")
            }
            f.server.reply(oldStart, """{"turn":{"id":"old-turn"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"old-turn"}}""")
            val interrupt = f.server.take("turn/interrupt")
            assertEquals("old-turn", interrupt.obj("params")?.str("turnId"))
            f.server.reply(interrupt)
            val newStart = f.server.take("turn/start")
            f.server.reply(newStart, """{"turn":{"id":"new-turn"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"new-turn"}}""")
            f.barrier()
            f.onMain { f.wb.send(c.id, "queued after replacement") }
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"old-turn","status":"interrupted","items":[]}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.RUNNING, c.status)
                assertEquals("new-turn", c.activeTurnId)
                assertEquals(listOf("queued after replacement"), c.queue.map { it.text })
                assertTrue(c.turns.none { it.id == "old-turn" })
            }
            assertEquals(1, f.server.count("turn/interrupt"))
            assertEquals(2, f.server.count("turn/start"))
        }
    }

    @Test
    fun stoppingDynamicToolCancelsBeforeItsWriteAndRejectsLateCalls() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val output = f.dir.resolve("must-not-write.txt").toFile()
            f.onMain {
                f.wb.tools = { listOf(CodexTool("slow_write", "test", buildJsonObject { put("type", "object") }, false) {
                    entered.complete(Unit)
                    try {
                        finish.await()
                        output.writeText("write after stop")
                        CodexToolResult(true, "written")
                    } finally { cancelled.complete(Unit) }
                }) }
            }
            val c = f.startTurn()
            f.server.request(500, "item/tool/call", """{"threadId":"thread-1","turnId":"turn-1","tool":"slow_write","arguments":{}}""")
            withTimeout(5000) { entered.await() }
            f.onMain { f.wb.interrupt(c.id) }
            withTimeout(5000) { cancelled.await() }
            finish.complete(Unit)
            f.server.reply(f.server.take("turn/interrupt"))
            val result = f.server.takeResponse(500)
            assertEquals("false", result.obj("result")?.get("success")?.toString())
            f.server.request(501, "item/tool/call", """{"threadId":"thread-1","turnId":"turn-1","tool":"slow_write","arguments":{}}""")
            assertTrue(f.server.takeResponse(501).containsKey("error"))
            f.barrier()
            assertFalse(output.exists())
            f.onMain { assertEquals(CodexStatus.INTERRUPTED, c.status) }
        }
    }

    @Test
    fun stopAndImmediateSendWaitsForInterruptBeforeStartingReplacement() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.onMain {
                f.wb.interrupt(c.id)
                f.wb.interrupt(c.id)
                f.wb.send(c.id, "replacement")
            }
            val interrupted = f.server.take("turn/interrupt")
            f.barrier()
            assertEquals(1, f.server.count("turn/start"))
            f.server.reply(interrupted)
            val replacement = f.server.take("turn/start")
            f.server.reply(replacement, """{"turn":{"id":"turn-2"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-2"}}""")
            f.barrier()
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-1"}}""")
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"interrupted","items":[]}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.RUNNING, c.status)
                assertEquals("turn-2", c.activeTurnId)
                assertEquals("interrupted", c.turns.first().status)
            }
            assertEquals(1, f.server.count("turn/interrupt"))
        }
    }

    @Test
    fun restartDuringProcessCreationDisposesOldProcessWithoutHurtingNewConnection() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture().use { f ->
            val old = f.server
            f.beforeStart = { call ->
                if (call == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val first = f.newConversation()
            f.onMain { f.wb.send(first.id, "old") }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                f.onMain { f.wb.restart() }
                f.server = FakeServer()
                val second = f.newConversation()
                f.onMain { f.wb.send(second.id, "new") }
                f.initialize()
                f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-2"}}""")
                f.server.reply(f.server.take("turn/start"), """{"turn":{"id":"turn-2"}}""")
                f.server.notify("turn/started", """{"threadId":"thread-2","turn":{"id":"turn-2"}}""")
                f.barrier()
                release.countDown()
                withContext(Dispatchers.IO) { assertTrue(old.awaitDestroyed()) }
                f.barrier()
                f.onMain {
                    assertEquals(CodexStatus.INTERRUPTED, first.status)
                    assertEquals(CodexStatus.RUNNING, second.status)
                    assertEquals("turn-2", second.activeTurnId)
                    assertEquals("", f.wb.runtimeStatus)
                }
                assertEquals(0, old.count("initialize"))
                assertTrue(f.server.isAlive)
                assertEquals(2, f.starts)
            } finally { release.countDown() }
        }
    }

    @Test
    fun restartClearsWaitingApprovalsAndAllowsResumingConversation() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.request(500, "item/commandExecution/requestApproval", """{"threadId":"thread-1","turnId":"turn-1","command":"test"}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.WAITING, c.status)
                f.wb.restart()
                assertEquals(CodexStatus.INTERRUPTED, c.status)
                assertNull(c.activeTurnId)
                assertTrue(c.approvals.isEmpty())
                assertEquals("interrupted", c.turns.single().status)
            }
            assertFalse(f.server.isAlive)
            val next = FakeServer()
            f.server = next
            f.onMain { f.wb.send(c.id, "resume after restart") }
            f.initialize()
            val resumed = next.take("thread/resume")
            assertEquals("thread-1", resumed.obj("params")?.str("threadId"))
            next.reply(resumed, """{"thread":{"id":"thread-1"}}""")
            next.reply(next.take("turn/start"), """{"turn":{"id":"turn-2"}}""")
            next.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-2"}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.RUNNING, c.status)
                assertEquals("turn-2", c.activeTurnId)
                assertTrue(c.queue.isEmpty())
            }
            assertEquals(2, f.starts)
        }
    }

    @Test
    fun missingRolloutStartsNewThreadWithConversationText() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.notify("item/completed", """{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","id":"a1","text":"有，源码在 /opt/reference"}}""")
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}""")
            f.barrier()
            f.onMain { f.wb.restart() }
            val next = FakeServer()
            f.server = next
            f.onMain { f.wb.send(c.id, "继续") }
            f.initialize()
            // 换过虚拟环境后 Codex 找不到旧线程的记录
            next.fail(next.take("thread/resume"), "no rollout found for thread id thread-1")
            val started = next.take("thread/start")
            val instructions = started.obj("params")?.str("developerInstructions").orEmpty()
            assertTrue("有，源码在 /opt/reference" in instructions, instructions)
            next.reply(started, """{"thread":{"id":"thread-9"}}""")
            val turn = next.take("turn/start")
            assertEquals("thread-9", turn.obj("params")?.str("threadId"))
            next.reply(turn, """{"turn":{"id":"turn-2"}}""")
            f.barrier()
            f.onMain { assertEquals("thread-9", c.threadId) }
        }
    }

    @Test
    fun processExitCancelsLocalToolsAndClearsActiveState() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            f.onMain {
                f.wb.tools = { listOf(CodexTool("wait", "test", JsonObject(emptyMap()), false) {
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await(); CodexToolResult(true, "unused") }
                    finally { cancelled.complete(Unit) }
                }) }
            }
            val c = f.startTurn()
            f.server.request(500, "item/tool/call", """{"threadId":"thread-1","turnId":"turn-1","tool":"wait","arguments":{}}""")
            withTimeout(5000) { entered.await() }
            f.server.destroy()
            withTimeout(5000) { cancelled.await() }
            f.onMain {
                assertEquals(CodexStatus.INTERRUPTED, c.status)
                assertNull(c.activeTurnId)
                assertTrue(c.approvals.isEmpty())
            }
        }
    }

    @Test
    fun completedTurnStillStartsQueuedMessage() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.onMain { f.wb.send(c.id, "next") }
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}""")
            val next = f.server.take("turn/start")
            f.server.reply(next, """{"turn":{"id":"turn-2"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-2"}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.RUNNING, c.status)
                assertEquals("turn-2", c.activeTurnId)
                assertTrue(c.queue.isEmpty())
            }
        }
    }

    @Test
    fun turnStartTimeoutInterruptsKnownTurn() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            f.onMain {
                f.wb.tools = { listOf(CodexTool("wait", "test", JsonObject(emptyMap()), false) {
                    entered.complete(Unit)
                    try { CompletableDeferred<Unit>().await(); CodexToolResult(true, "unused") }
                    finally { cancelled.complete(Unit) }
                }) }
            }
            val c = f.newConversation()
            f.onMain { f.wb.turnStartTimeoutMillis = 300; f.wb.send(c.id, "start without reply") }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            f.server.take("turn/start")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-1"}}""")
            f.server.request(500, "item/tool/call", """{"threadId":"thread-1","turnId":"turn-1","tool":"wait","arguments":{}}""")
            withTimeout(5000) { entered.await() }
            val interrupt = f.server.take("turn/interrupt")
            assertEquals("turn-1", interrupt.obj("params")?.str("turnId"))
            // 远端尚未确认中断，本地工具就必须已经取消。
            withTimeout(5000) { cancelled.await() }
            f.server.reply(interrupt)
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.ERROR, c.status)
                assertNull(c.activeTurnId)
                assertTrue(c.lastError.orEmpty().contains("响应超时"))
            }
            assertTrue(f.server.isAlive)
        }
    }

    @Test
    fun turnStartTimeoutWithoutIdClosesUncontrollableProcess() = runBlocking {
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain { f.wb.turnStartTimeoutMillis = 30; f.wb.send(c.id, "start without any response") }
            f.initialize()
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            f.server.take("turn/start")
            withContext(Dispatchers.IO) { assertTrue(f.server.awaitDestroyed()) }
            f.onMain {
                assertEquals(CodexStatus.INTERRUPTED, c.status)
                assertNull(c.activeTurnId)
                assertTrue(c.lastError.orEmpty().contains("失去响应"))
            }
            assertEquals(0, f.server.count("turn/interrupt"))
        }
    }

    @Test
    fun finalUsageAfterCompletionIsAppliedAndPersisted() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}""")
            f.server.notify("thread/tokenUsage/updated", """{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":{"totalTokens":99},"last":{"totalTokens":12},"modelContextWindow":400000}}""")
            f.barrier()
            f.wb.flush()
            f.onMain {
                assertEquals(CodexStatus.IDLE, c.status)
                assertEquals(99L, c.totalTokens)
                assertEquals(12L, c.contextTokens)
            }
            val stored = CodexRpc.json.parseToJsonElement(f.dir.resolve("conversations/${c.id}.json").toFile().readText()).objOrNull()!!
            assertEquals(99L, stored.long("totalTokens"))
        }
    }

    @Test
    fun savesAndDeletionRemainOrderedEvenWhenIoIsDelayed() = runBlocking {
        val io = ManualDispatcher()
        Fixture(io = io).use { f ->
            val c = f.newConversation()
            f.onMain { repeat(100) { f.wb.renameConversation(c.id, "snapshot-$it") } }
            io.runNewestFirst()
            val file = f.dir.resolve("conversations/${c.id}.json").toFile()
            val stored = CodexRpc.json.parseToJsonElement(file.readText()).objOrNull()!!
            assertEquals("snapshot-99", stored.str("title"))
            f.onMain {
                f.wb.renameConversation(c.id, "save before delete")
                f.wb.deleteConversation(c.id)
            }
            io.runNewestFirst()
            assertFalse(file.exists())
            f.onMain {
                val restored = CodexWorkbench(f.dir.toFile(), CodexLauncher { error("unused") }, f.main, f.scope, io = io)
                assertTrue(restored.conversations.isEmpty())
            }
            io.runNewestFirst()
        }
    }

    @Test
    fun flushFailsPromptlyIfScopeIsCancelledBeforeWriterRuns() = runBlocking {
        val io = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val persistence = CodexPersistence(scope, io)
        val drained = async(start = CoroutineStart.UNDISPATCHED) { runCatching { persistence.flush() } }
        scope.cancel()
        io.runNewestFirst()
        assertTrue(withTimeout(5000) { drained.await() }.isFailure)
        assertFailsWith<IllegalStateException> { persistence.flush() }
        Unit
    }

    @Test
    fun failedTurnIsNotAutomaticallyReplayedAndManualRetryStillWorks() = runBlocking {
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"failed","error":{"message":"unexpected status 502"},"items":[]}}""")
            f.barrier()
            f.onMain {
                assertEquals(CodexStatus.ERROR, c.status)
                assertTrue(c.turns.single().error!!.contains("unexpected status 502"))
                assertTrue(c.turns.single().deletedRoles.isEmpty())
            }
            val before = f.server.count("turn/start")
            kotlinx.coroutines.delay(2500)
            assertEquals(before, f.server.count("turn/start"))
            f.onMain {
                f.wb.send(c.id, "换个问法")
            }
            val manual = f.server.take("turn/start")
            assertTrue(manual.toString().contains("换个问法"))
            f.server.reply(manual, """{"turn":{"id":"turn-3"}}""")
            f.server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-3"}}""")
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-3","status":"completed","items":[]}}""")
            f.barrier()
            val starts = f.server.count("turn/start")
            kotlinx.coroutines.delay(4500)
            assertEquals(starts, f.server.count("turn/start"))
        }
    }

    @Test
    fun warmUpStartsRuntimeOnceAndFirstSendReusesIt() = runBlocking {
        Fixture().use { f ->
            // 启动动画里预热：拉起进程、初始化、读模型列表，不发任何对话请求
            f.onMain { f.wb.warmUp() }
            f.initialize()
            f.barrier()
            assertEquals(1, f.server.count("initialize"))
            assertEquals(0, f.server.count("thread/start"))
            // 第一次发消息直接开线程，不再重新初始化
            val c = f.newConversation()
            f.onMain { f.wb.send(c.id, "hi") }
            f.server.reply(f.server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            f.server.reply(f.server.take("turn/start"), """{"turn":{"id":"turn-1"}}""")
            f.barrier()
            assertEquals(1, f.server.count("initialize"))
        }
    }

    @Test fun freeThreadUsesDedicatedProviderAndRevalidatesLoginBeforeEveryTurn() = runBlocking {
        Fixture().use { f ->
            var loggedIn = true
            val c = f.newConversation()
            f.onMain {
                assertTrue(f.wb.setModel(c.id, "workbench-free/deepseek-test", null))
                f.wb.prepareThread = {
                    check(loggedIn) { "请先登录 NewAPI" }
                    CodexThreadOptions("rustwright_free", buildJsonObject {
                        put("web_search", "disabled"); put("features.multi_agent", false)
                    })
                }
                f.wb.send(c.id, "free test")
                assertFalse(f.wb.setModel(c.id, "paid-test", null))
            }
            f.initialize()
            val thread = f.server.take("thread/start")
            val params = thread.obj("params")!!
            assertEquals("rustwright_free", params.str("modelProvider"))
            assertEquals(JsonPrimitive(false), params["allowProviderModelFallback"])
            assertEquals("disabled", params.obj("config")!!.str("web_search"))
            f.server.reply(thread, """{"thread":{"id":"free-thread"}}""")
            val turn = f.server.take("turn/start")
            assertEquals("workbench-free/deepseek-test", turn.obj("params")!!.str("model"))
            f.server.reply(turn, """{"turn":{"id":"free-turn"}}""")
            f.server.notify("turn/completed", """{"threadId":"free-thread","turn":{"id":"free-turn","status":"completed","items":[]}}""")
            f.barrier()
            val denied = CompletableDeferred<Unit>()
            f.onMain {
                loggedIn = false
                f.wb.onBusy = { if (!it) denied.complete(Unit) }
                f.wb.send(c.id, "must not send")
            }
            withTimeout(5000) { denied.await() }
            f.onMain { assertEquals(CodexStatus.ERROR, c.status); assertEquals("请先登录 NewAPI", c.lastError) }
            assertEquals(1, f.server.count("thread/start"))
            assertEquals(1, f.server.count("turn/start"))
        }
    }

    @Test fun switchingFreeToPaidStartsNewThreadCarryingTheConversation() = runBlocking {
        // Codex 不允许已有线程换线路（resume / turn/start 都报 ModelProviderMismatch），换线路就开新线程并带上对话文字
        Fixture().use { f ->
            val c = f.newConversation()
            f.onMain {
                assertTrue(f.wb.setModel(c.id, "workbench-free/deepseek-test", null))
                f.wb.prepareThread = { conv ->
                    if (CodexWorkbench.isFreeModel(conv.model)) CodexThreadOptions("rustwright_free", buildJsonObject { put("web_search", "disabled") })
                    else CodexThreadOptions("newapi")
                }
                f.wb.send(c.id, "free test")
            }
            f.initialize()
            val thread = f.server.take("thread/start")
            assertEquals("rustwright_free", thread.obj("params")!!.str("modelProvider"))
            f.server.reply(thread, """{"thread":{"id":"t1"}}""")
            val turn = f.server.take("turn/start")
            f.server.reply(turn, """{"turn":{"id":"turn1"}}""")
            f.server.notify("item/completed", """{"threadId":"t1","turnId":"turn1","item":{"type":"agentMessage","id":"a1","text":"预览坦克检查通过"}}""")
            f.server.notify("turn/completed", """{"threadId":"t1","turn":{"id":"turn1","status":"completed","items":[]}}""")
            f.barrier()
            f.onMain {
                assertEquals("rustwright_free", c.threadProvider)
                assertTrue(f.wb.setModel(c.id, "paid-test", null), "免费换付费不应再要求新建会话")
                f.wb.send(c.id, "paid now")
            }
            val unsubscribe = f.server.take("thread/unsubscribe")
            assertEquals("t1", unsubscribe.obj("params")!!.str("threadId"))
            f.server.reply(unsubscribe, """{"status":"unsubscribed"}""")
            val started = f.server.take("thread/start")
            val sp = started.obj("params")!!
            assertEquals("newapi", sp.str("modelProvider"))
            assertEquals("paid-test", sp.str("model"))
            assertTrue("预览坦克检查通过" in sp.str("developerInstructions").orEmpty(), "新线程要带上之前的对话")
            f.server.reply(started, """{"thread":{"id":"t2"}}""")
            val turn2 = f.server.take("turn/start")
            assertEquals("t2", turn2.obj("params")!!.str("threadId"))
            assertEquals(0, f.server.count("thread/resume"))
            f.onMain { assertEquals("t2", c.threadId); assertEquals("newapi", c.threadProvider) }
        }
    }

    @Test fun providerMismatchOnTurnStartRetriesOnNewThread() = runBlocking {
        // 老会话没记下线路：重启后 resume 成功，但 turn/start 才报换了线路 → 开新线程带上文字再发一次，不报错
        Fixture().use { f ->
            val c = f.startTurn()
            f.server.notify("item/completed", """{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","id":"a1","text":"旧线路上的回答"}}""")
            f.server.notify("turn/completed", """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}""")
            f.barrier()
            f.onMain { c.threadProvider = null; f.wb.restart() }
            val next = FakeServer()
            f.server = next
            f.onMain { f.wb.send(c.id, "继续") }
            f.initialize()
            next.reply(next.take("thread/resume"), """{"thread":{"id":"thread-1"}}""")
            next.fail(next.take("turn/start"), "This chat uses rustwright_free, but your current provider is newapi. Switch to rustwright_free to continue.")
            next.take("thread/unsubscribe").let { next.reply(it, """{"status":"unsubscribed"}""") }
            val started = next.take("thread/start")
            assertTrue("旧线路上的回答" in started.obj("params")?.str("developerInstructions").orEmpty())
            next.reply(started, """{"thread":{"id":"thread-7"}}""")
            val turn = next.take("turn/start")
            assertEquals("thread-7", turn.obj("params")?.str("threadId"))
            next.reply(turn, """{"turn":{"id":"turn-2"}}""")
            f.barrier()
            f.onMain { assertEquals("thread-7", c.threadId); assertTrue(c.status != CodexStatus.ERROR, "不应报错：${c.lastError}") }
        }
    }

    private class Fixture(val io: CoroutineDispatcher = Dispatchers.IO, mainOverride: CoroutineDispatcher? = null) : AutoCloseable {
        val dir = Files.createTempDirectory("codex-lifecycle")
        private val ownedMain = if (mainOverride == null) Executors.newSingleThreadExecutor().asCoroutineDispatcher() else null
        val main: CoroutineDispatcher = mainOverride ?: ownedMain!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile var starts = 0
        @Volatile var server = FakeServer()
        @Volatile var beforeStart: (Int) -> Unit = { }
        private val allServers = mutableListOf(server)
        val wb = CodexWorkbench(dir.toFile(), CodexLauncher {
            val selected = server
            val call = synchronized(allServers) {
                starts++
                if (selected !in allServers) allServers += selected
                starts
            }
            beforeStart(call)
            selected
        }, main, scope, io = io)

        suspend fun <T> onMain(block: () -> T) = withContext(main) { block() }

        suspend fun newConversation() = onMain {
            val project = wb.projects.firstOrNull() ?: wb.openProject("project", dir.resolve("project").toString())
            wb.newConversation(project.id, CodexMode.CODE, model = "test-model")
        }

        suspend fun initialize() {
            server.reply(server.take("initialize"))
            server.reply(server.take("model/list"), """{"data":[]}""")
        }

        suspend fun startTurn(): CodexConversation {
            val c = newConversation()
            onMain { wb.send(c.id, "start") }
            initialize()
            server.reply(server.take("thread/start"), """{"thread":{"id":"thread-1"}}""")
            server.reply(server.take("turn/start"), """{"turn":{"id":"turn-1"}}""")
            server.notify("turn/started", """{"threadId":"thread-1","turn":{"id":"turn-1"}}""")
            barrier()
            return c
        }

        /** 未知服务端请求在主线程被拒绝；读到响应即证明此前通知已处理，避免靠 sleep 猜时序。 */
        suspend fun barrier() {
            val id = server.nextBarrier++
            server.request(id, "test/barrier", "{}")
            server.takeResponse(id)
            onMain { }
        }

        override fun close() {
            runBlocking { onMain { wb.restart() } }
            if (io is ManualDispatcher) io.runNewestFirst()
            runBlocking {
                val drained = async(start = CoroutineStart.UNDISPATCHED) { wb.flush() }
                if (io is ManualDispatcher) io.runNewestFirst()
                withTimeout(5000) { drained.await() }
            }
            scope.cancel()
            if (io is ManualDispatcher) io.runNewestFirst()
            synchronized(allServers) { allServers.forEach { it.destroy() } }
            ownedMain?.close()
            dir.toFile().deleteRecursively()
        }
    }

    /** 模拟 GLSurfaceView 停帧：pause 返回前等待当前 UI 片段退出，此后投递仅入队。 */
    private class PausableDispatcher(private val delegate: kotlinx.coroutines.ExecutorCoroutineDispatcher) : CoroutineDispatcher(), AutoCloseable {
        private val gate = Any()
        private var paused = false
        private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, Runnable {
            synchronized(gate) {
                if (paused) pending.addLast(context to block) else block.run()
            }
        })
        fun pause() = synchronized(gate) { paused = true }
        fun resume() = synchronized(gate) {
            paused = false
            while (pending.isNotEmpty()) {
                val (context, block) = pending.removeFirst()
                dispatch(context, block)
            }
        }
        override fun close() { delegate.close() }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(tasks) { tasks.addLast(block) } }
        fun runNewestFirst() {
            while (true) {
                val next = synchronized(tasks) { tasks.pollLast() } ?: return
                next.run()
            }
        }
    }

    /** 仅输入端有阻塞读线程，客户端写入直接进入队列，由测试决定每条响应的时间。 */
    private class FakeServer : Process() {
        private val inputBytes = LinkedBlockingQueue<Int>()
        private val outgoing = LinkedBlockingQueue<JsonObject>()
        private val unmatched = mutableListOf<JsonObject>()
        private val history = mutableListOf<JsonObject>()
        private val exit = CountDownLatch(1)
        @Volatile private var alive = true
        var nextBarrier = 10_000L

        private val input = object : InputStream() {
            private var eof = false
            override fun read(): Int {
                if (eof) return -1
                return inputBytes.take().also { if (it == -1) eof = true }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val first = read()
                if (first < 0) return -1
                b[off] = first.toByte()
                var size = 1
                while (size < len) {
                    val next = inputBytes.poll() ?: break
                    if (next == -1) { eof = true; break }
                    b[off + size++] = next.toByte()
                }
                return size
            }
        }
        private val output = object : OutputStream() {
            private val line = ByteArrayOutputStream()
            @Synchronized override fun write(b: Int) {
                if (b == '\n'.code) {
                    val message = CodexRpc.json.parseToJsonElement(line.toString(Charsets.UTF_8)).objOrNull()!!
                    synchronized(history) { history += message }
                    outgoing.put(message)
                    line.reset()
                } else line.write(b)
            }
        }

        fun count(method: String) = synchronized(history) { history.count { it.str("method") == method } }
        suspend fun take(method: String) = takeMatching { it.str("method") == method }
        suspend fun takeResponse(id: Long) = takeMatching { it["id"] == JsonPrimitive(id) && it.str("method") == null }
        private suspend fun takeMatching(matches: (JsonObject) -> Boolean): JsonObject = withContext(Dispatchers.IO) {
            val buffered = unmatched.indexOfFirst(matches)
            if (buffered >= 0) return@withContext unmatched.removeAt(buffered)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val message = outgoing.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    ?: error("等待假进程消息超时；历史方法：${synchronized(history) { history.map { it.str("method") ?: "reply:${it["id"]}" } }}")
                if (matches(message)) return@withContext message
                unmatched += message
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        fun reply(request: JsonObject, result: String = "{}") = emit(buildJsonObject {
            put("id", request["id"]!!)
            put("result", CodexRpc.json.parseToJsonElement(result))
        })
        fun fail(request: JsonObject, message: String) = emit(buildJsonObject {
            put("id", request["id"]!!)
            put("error", buildJsonObject { put("code", -32600); put("message", message) })
        })
        fun notify(method: String, params: String) = emit(buildJsonObject {
            put("method", method); put("params", CodexRpc.json.parseToJsonElement(params))
        })
        fun request(id: Long, method: String, params: String) = emit(buildJsonObject {
            put("id", id); put("method", method); put("params", CodexRpc.json.parseToJsonElement(params))
        })
        @Synchronized private fun emit(message: JsonElement) {
            check(alive)
            (message.toString() + "\n").toByteArray().forEach { inputBytes.put(it.toInt() and 255) }
        }

        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { exit.await(); return 0 }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun isAlive() = alive
        fun awaitDestroyed(): Boolean = exit.await(5, TimeUnit.SECONDS)
        override fun destroy() { if (alive) { alive = false; inputBytes.put(-1); exit.countDown() } }
    }
}
