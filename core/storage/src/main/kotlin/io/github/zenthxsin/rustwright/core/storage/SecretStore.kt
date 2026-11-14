package io.github.zenthxsin.rustwright.core.storage

/**
 * 敏感数据（OAuth 会话、API 令牌）的存放处。Android 实现用 Keystore AES-GCM 加密，
 * 读取失败（密钥丢失、数据损坏）时返回 null 并清除该项。
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** 仅用于测试 / 无安全存储的平台。 */
class InMemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String) { map[key] = value }
    @Synchronized override fun remove(key: String) { map.remove(key) }
}
