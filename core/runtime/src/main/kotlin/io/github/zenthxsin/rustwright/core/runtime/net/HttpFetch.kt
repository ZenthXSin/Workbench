package io.github.zenthxsin.rustwright.core.runtime.net

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small credential-free HTTPS downloads; cancellation closes the actual socket. */
object HttpFetch {
    private val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16),
        { task -> Thread(task, "rw-http-fetch").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

    suspend fun get(url: String, limit: Int, timeoutMs: Int = 15000, statusOnly: Boolean = false): ByteArray = suspendCancellableCoroutine { continuation ->
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        continuation.invokeOnCancellation { connection.disconnect() }
        try { workers.execute {
            val result = runCatching {
                check(continuation.isActive)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                if (statusOnly) ByteArray(0) else connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        check(continuation.isActive && System.nanoTime() < deadline) { "HTTP request cancelled or timed out" }
                        val n = input.read(buffer); if (n < 0) break
                        require(output.size() + n <= limit) { "HTTP response too large" }
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            }
            connection.disconnect()
            continuation.resumeWith(result)
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
            connection.disconnect(); continuation.resumeWith(Result.failure(error))
        }
    }
}
