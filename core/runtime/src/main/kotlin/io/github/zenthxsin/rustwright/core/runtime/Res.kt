package io.github.zenthxsin.rustwright.core.runtime

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 随包资源的统一入口（assets / skills / icons / fonts）。
 *
 * 开发和调试包：资源就是 classpath 里的明文文件。
 * 正式包：打包时每个文件被换成 `r/<名字的 HMAC>`，内容是 AES-256-GCM 密文（先压缩再加密，路径作附加认证数据）：
 * - 文件名和目录结构看不出来；
 * - 密钥 = SHA-256(安装包签名证书 ‖ 内置密钥材料)：重新签名的包派生出的密钥不同，一个资源都解不开；
 * - 改动任何一个字节、把一个文件的密文挪到另一个名字下，GCM 校验都会失败，读取返回 null。
 * 只要包里有加密资源（[MARK]），受保护的目录就不再回退到明文，防止删掉密文塞明文进来替换。
 *
 * 这是抬高门槛而不是绝对安全：密钥材料和算法都在安装包里，能调试进程的人最终能拿到明文。
 */
object Res {
    /** 受保护的顶层目录。 */
    val PROTECTED: List<String> get() = (listOf("assets/", "skills/", "tiles/", "rw-icons/", "rw-fonts/", "brand/", "rustwright/") +
        io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.configured("resources.protected", "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinct()
    const val DIR = "r/"
    /** 标记文件：存在就说明这个包的资源是加密的。 */
    const val MARK = "r/0"
    private const val NAME_HEX = 40

    @Volatile private var key: ByteArray? = null
    @Volatile internal var loader: ClassLoader = Res::class.java.classLoader!!
    private val sealed: Boolean by lazy { loader.getResource(MARK) != null }

    /**
     * 启动时由平台调用一次：[certificate] 是安装包签名证书的 DER 字节，[secret] 是 32 字节密钥材料
     * （由使用此库的产品提供；本源码包不附带任何资源密钥）。
     * 密钥 = SHA-256(证书 ‖ 密钥材料)：换签名就解不开，且密钥材料不在 dex 里。
     */
    fun install(certificate: ByteArray, secret: ByteArray) { key = deriveKey(certificate, secret) }

    fun deriveKey(certificate: ByteArray, secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { update(certificate); update(secret); digest() }

    /** 加密后的条目名（打包任务用同一个算法）。 */
    fun entryName(key: ByteArray, path: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return DIR + mac.doFinal(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(NAME_HEX)
    }

    fun open(path: String): InputStream? = bytes(path)?.let(::ByteArrayInputStream)

    fun bytes(path: String): ByteArray? {
        val p = path.trimStart('/')
        val guarded = PROTECTED.any { p.startsWith(it) }
        val k = key
        if (guarded && k != null) {
            loader.getResourceAsStream(entryName(k, p))?.use { it.readBytes() }?.let { return decrypt(k, p, it) }
        }
        // 加密包里受保护的资源只认密文
        if (guarded && isSealed()) return null
        return loader.getResourceAsStream(p)?.use { it.readBytes() }
    }

    internal fun isSealed(): Boolean = if (loader === Res::class.java.classLoader) sealed else loader.getResource(MARK) != null

    /** 密文格式：12 字节随机数 ‖ GCM(标志位 ‖ 内容)；标志位 1 表示内容是 deflate 压缩的。 */
    fun decrypt(key: ByteArray, path: String, blob: ByteArray): ByteArray? = runCatching {
        if (blob.size < 12 + 16 + 1) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(path.toByteArray(Charsets.UTF_8))
        val plain = c.doFinal(blob, 12, blob.size - 12)
        if (plain[0].toInt() != 1) return plain.copyOfRange(1, plain.size)
        val inf = Inflater(true)
        inf.setInput(plain, 1, plain.size - 1)
        val out = java.io.ByteArrayOutputStream(plain.size * 3)
        val buf = ByteArray(16384)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    /** 加密一个文件（打包任务和测试用；运行时不会调用）。 */
    fun encrypt(key: ByteArray, path: String, plain: ByteArray, nonce: ByteArray): ByteArray {
        val def = java.util.zip.Deflater(9, true)
        def.setInput(plain); def.finish()
        val packed = java.io.ByteArrayOutputStream(plain.size / 2 + 64)
        val buf = ByteArray(16384)
        while (!def.finished()) packed.write(buf, 0, def.deflate(buf))
        def.end()
        val small = packed.size() < plain.size
        val body = ByteArray(1 + if (small) packed.size() else plain.size)
        body[0] = if (small) 1 else 0
        System.arraycopy(if (small) packed.toByteArray() else plain, 0, body, 1, body.size - 1)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(path.toByteArray(Charsets.UTF_8))
        return nonce + c.doFinal(body)
    }
}
