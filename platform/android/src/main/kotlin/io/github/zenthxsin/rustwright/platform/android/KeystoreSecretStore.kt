package io.github.zenthxsin.rustwright.platform.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [SecretStore] 的 Android 实现：Keystore 中的 AES-GCM 密钥加密后存进 SharedPreferences（移植自 RustPic TokenRepository）。 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("rustwright_secrets", Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: String): String? {
        val iv = prefs.getString("$key.iv", null) ?: return null
        val value = prefs.getString("$key.value", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            // 密钥丢失（如系统重置 Keystore）或数据损坏：清除，按未登录处理
            remove(key)
            null
        }
    }

    @Synchronized
    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(
            prefs.edit()
                .putString("$key.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("$key.value", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit(),
        ) { "无法安全保存数据" }
    }

    @Synchronized
    override fun remove(key: String) {
        check(prefs.edit().remove("$key.iv").remove("$key.value").commit()) { "无法清除数据" }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rustwright_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
