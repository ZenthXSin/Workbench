package io.github.zenthxsin.rustwright.core.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 保存与删除由同一消费者按提交顺序执行，旧快照不能覆盖新快照或复活已删除的会话。 */
internal class CodexPersistence(scope: CoroutineScope, io: CoroutineDispatcher) {
    private class Operation(var execute: () -> Unit, val discarded: () -> Unit = {}, val key: String? = null)
    private val lock = Any()
    private val latest = HashMap<String, Operation>()
    private val operations = Channel<Operation>(128, onUndeliveredElement = { it.discarded() })
    private var failure: Throwable? = null

    init {
        scope.launch(io) {
            try {
                for (operation in operations) {
                    val execute = synchronized(lock) {
                        if (latest[operation.key] === operation) latest.remove(operation.key)
                        operation.execute
                    }
                    try { execute() } catch (e: Exception) { failure = e }
                }
            } finally {
                operations.cancel()
                synchronized(lock) { latest.clear() }
            }
        }.invokeOnCompletion { operations.cancel() }
    }

    fun write(file: File, text: String) = snapshot(file) { text }
    fun writeJson(file: File, value: kotlinx.serialization.json.JsonElement) = snapshot(file) { value.toString() }

    private fun snapshot(file: File, text: () -> String) = synchronized(lock) {
        val key = file.absolutePath
        val action = {
        file.parentFile.mkdirs()
        val tmp = File.createTempFile(".rw-save-", ".tmp", file.parentFile)
        try {
            tmp.writeText(text())
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        Unit
        }
        val pending = latest[key]
        if (pending != null) pending.execute = action
        else {
            val operation = Operation(action, key = key)
            check(operations.trySend(operation).isSuccess) { "Codex 存储繁忙或已关闭" }
            latest[key] = operation
        }
    }

    fun delete(file: File) = synchronized(lock) {
        latest.remove(file.absolutePath)
        enqueue { Files.deleteIfExists(file.toPath()); Unit }
    }

    /** 等待此前提交的操作全部落盘；也把磁盘错误交回调用方。 */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
        latest.clear()
        enqueue(discarded = { done.completeExceptionally(IllegalStateException("Codex 存储已关闭")) }) {
            val error = failure
            if (error == null) done.complete(Unit) else done.completeExceptionally(error)
        }
        }
        done.await()
    }

    private fun enqueue(discarded: () -> Unit = {}, operation: () -> Unit) {
        check(operations.trySend(Operation(operation, discarded)).isSuccess) { "Codex 存储已关闭" }
    }
}
