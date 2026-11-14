package io.github.zenthxsin.rustwright.core.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 普通设置的键值存储：一个 JSON 文件，写入时原子替换。值用 kotlinx.serialization 编解码。
 * 线程安全；适合设置项这种小数据，不适合大批量记录。
 */
class KeyValueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val data: MutableMap<String, JsonElement> = load()

    private fun load(): MutableMap<String, JsonElement> =
        runCatching { (json.parseToJsonElement(file.readText()) as JsonObject).toMutableMap() }.getOrElse { LinkedHashMap() }

    @Synchronized
    fun <T> get(key: String, serializer: KSerializer<T>): T? =
        data[key]?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    @Synchronized
    fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        data[key] = json.encodeToJsonElement(serializer, value)
        flush()
    }

    @Synchronized
    fun remove(key: String) {
        if (data.remove(key) != null) flush()
    }

    @Synchronized
    fun contains(key: String) = key in data

    private fun flush() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(data)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "无法写入设置文件 $file" }
        }
    }
}
