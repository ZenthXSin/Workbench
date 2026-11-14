package io.github.zenthxsin.rustwright.core.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexRpcCancellationTest {
    @Test
    fun cancelledCallerDoesNotSendRpcBytes() = runBlocking {
        val input = WaitingInput()
        val output = ByteArrayOutputStream()
        val rpc = CodexRpc(input, output, { _, _, _ -> }, { })
        try {
            launch(Job().apply { cancel() }, start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<CancellationException> { rpc.request("must-not-send", JsonObject(emptyMap())) }
            }.join()
            assertEquals(0, output.size())
        } finally { rpc.close(); input.close() }
    }

    @Test
    fun callerTimeoutRemainsCancellationWhileRpcTimeoutIsReported() = runBlocking {
        val input = WaitingInput()
        val rpc = CodexRpc(input, ByteArrayOutputStream(), { _, _, _ -> }, { })
        try {
            assertFailsWith<CancellationException> {
                withTimeout(20) { rpc.request("caller-timeout", JsonObject(emptyMap()), 5_000) }
            }
            val error = assertFailsWith<CodexException> { rpc.request("rpc-timeout", JsonObject(emptyMap()), 20) }
            assertEquals("runtime_timeout", error.code)
        } finally { rpc.close(); input.close() }
    }

    private class WaitingInput : InputStream() {
        private val closed = CountDownLatch(1)
        override fun read(): Int { closed.await(); return -1 }
        override fun close() { closed.countDown() }
    }
}
