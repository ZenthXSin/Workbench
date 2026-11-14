package io.github.zenthxsin.rustwright.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueStoreTest {
    @Serializable
    data class Prefs(val model: String, val steps: Int = 8)

    @Test
    fun persistsAcrossInstances() {
        val f = Files.createTempDirectory("kv").resolve("settings.json").toFile()
        KeyValueStore(f).apply {
            put("prefs", Prefs.serializer(), Prefs("gpt-6-astra"))
            put("name", String.serializer(), "锈匠")
        }
        val again = KeyValueStore(f)
        assertEquals(Prefs("gpt-6-astra", 8), again.get("prefs", Prefs.serializer()))
        assertEquals("锈匠", again.get("name", String.serializer()))
        again.remove("name")
        assertNull(KeyValueStore(f).get("name", String.serializer()))
        // 类型不符时返回 null 而不是抛错
        assertNull(again.get("prefs", Int.serializer()))
    }
}
