package io.github.zenthxsin.rustwright.core.codex

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.system.measureNanoTime
import kotlin.test.*

class BackendSecurityRegressionTest {
    @Test fun unauthenticatedRequestRejectsBeforeReadingBody() {
        val bridge = McpBridge({ emptyList() }) { _, _, _, _, _ -> error("must not execute") }
        bridge.start()
        try {
            Socket("127.0.0.1", bridge.port).use { s ->
                s.soTimeout = 300
                s.getOutputStream().write("POST /mcp/x HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\n\r\n".toByteArray())
                s.soTimeout = 3000
                assertTrue(s.getInputStream().bufferedReader().readLine().contains("401"))
            }
        } finally { bridge.stop() }
    }

    @Test fun directMcpCallRejectsMissingTurnId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("audit-mcp-").toFile()
        val wb = CodexWorkbench(dir, CodexLauncher { error("unused") }, Dispatchers.Unconfined, scope)
        try {
            val project = wb.openProject("audit", dir.path)
            val c = wb.newConversation(project.id, CodexMode.CODE).apply { threadId = "audit-thread" }
            // Seed an active turn; this is a unit fixture, not a way of obtaining real credentials.
            val runClass = Class.forName(CodexWorkbench::class.java.name + "\$TurnRun")
            val ctor = runClass.declaredConstructors.first { it.parameterCount == 1 }.apply { isAccessible = true }
            val run = ctor.newInstance(scope.coroutineContext[Job])
            runClass.getDeclaredField("turnId").apply { isAccessible = true }.set(run, "audit-turn")
            @Suppress("UNCHECKED_CAST")
            val runs = CodexWorkbench::class.java.getDeclaredField("runs").apply { isAccessible = true }.get(wb) as MutableMap<String, Any>
            runs[c.id] = run
            var executed = false
            val tool = CodexTool("audit_write", "no side effects", buildJsonObject {}, true) {
                executed = true; CodexToolResult(true, "fixture")
            }
            val r = wb.runMcpTool(tool, buildJsonObject {}, c.threadId, null)
            assertFalse(r.success)
            assertFalse(executed)
            assertTrue(c.approvals.isEmpty())
            val denied = async(start = CoroutineStart.UNDISPATCHED) { wb.runMcpTool(tool, buildJsonObject {}, c.threadId, "audit-turn") }
            assertFalse(denied.isCompleted)
            assertFalse(executed)
            wb.answer(c.approvals.single().id, CodexAnswer.Decline)
            assertFalse(denied.await().success)
            assertFalse(executed)
            val allowed = async(start = CoroutineStart.UNDISPATCHED) { wb.runMcpTool(tool, buildJsonObject {}, c.threadId, "audit-turn") }
            assertFalse(executed)
            wb.answer(c.approvals.single().id, CodexAnswer.Accept)
            assertTrue(allowed.await().success)
            assertTrue(executed)
            wb.flush()
        } finally { scope.cancel() }
    }

    @Test fun rpcRequestTimeoutBoundsPipeWrite() = runBlocking {
        val input = PipedInputStream()
        val keepOpen = PipedOutputStream(input)
        val output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) { Thread.sleep(350) }
        }
        val rpc = CodexRpc(input, output, { _, _, _ -> }, {})
        try {
            val elapsed = measureNanoTime {
                assertFailsWith<CodexException> { rpc.request("audit", buildJsonObject {}, 50) }
            } / 1_000_000
            assertTrue(elapsed < 300, "RPC timeout took $elapsed ms")
        } finally { rpc.close(); keepOpen.close(); input.close() }
    }

    @Test fun measureActualConversationLoadAndRenameSerialization() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("audit-history-").toFile()
        try {
            val wb = CodexWorkbench(dir, CodexLauncher { error("unused") }, Dispatchers.Unconfined, scope)
            val p = wb.openProject("audit", dir.path)
            val c = wb.newConversation(p.id)
            repeat(160) { n -> c.turns += CodexTurn("t$n", items = mutableListOf(CodexItem("i$n", "agentMessage", text = "x".repeat(100_000)))) }
            val samples = (1..5).map { n -> measureNanoTime { wb.renameConversation(c.id, "audit$n") } / 1_000_000.0 }
            wb.flush()
            var count = 0
            val loadMs = measureNanoTime { count = CodexWorkbench(dir, CodexLauncher { error("unused") }, Dispatchers.Unconfined, scope).conversations.size } / 1_000_000.0
            assertEquals(1, count)
            println("History benchmark (desktop JVM): rename ms=$samples; synchronous legacy load ms=$loadMs")
        } finally { scope.cancel() }
    }

    @Test fun backgroundRestoreMergesProjectsCreatedWhileLoading() = runBlocking {
        val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        fun drain() { while (true) (tasks.poll() ?: break).run() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("history-background-").toFile()
        val p = CodexProject("old-project", "existing", dir.path)
        File(dir, "projects.json").writeText(CodexRpc.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CodexProject.serializer()), listOf(p)))
        val old = CodexConversation("old-conversation", p.id, model = "fixture")
        File(dir, "conversations").mkdirs()
        File(dir, "conversations/${old.id}.json").writeText(CodexRpc.json.encodeToString(CodexConversation.serializer(), old))
        try {
            val wb = CodexWorkbench(dir, CodexLauncher { error("unused") }, Dispatchers.Unconfined, scope, io = io, loadInBackground = true)
            assertTrue(wb.projects.isEmpty())
            val reopened = wb.openProject("existing", dir.path)
            wb.newConversation(reopened.id)
            drain()
            assertEquals(1, wb.projects.size)
            assertEquals(2, wb.conversations.size)
            assertTrue(wb.conversations.all { it.projectId == reopened.id })
            val flushed = async(start = CoroutineStart.UNDISPATCHED) { wb.flush() }
            drain(); flushed.await()
            val restored = CodexWorkbench(dir, CodexLauncher { error("unused") }, Dispatchers.Unconfined, scope)
            assertEquals(2, restored.conversations.size)
        } finally { scope.cancel(); drain() }
    }
}
