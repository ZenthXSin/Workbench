package io.github.zenthxsin.rustwright.core.runtime

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端到端验证打包封装：使用 Res 封装格式的 APK，必须能被运行时 Res 用「证书 ‖ resSecret」派生的密钥读回，
 * 且内容和未封装的原文逐字节一致。缺少 -D 参数时跳过（只有封装流水线跑完才传）。
 */
class SealedApkInteropTest {
    private val original = Res.loader
    @AfterEach fun restore() { Res.loader = original }

    private fun hex(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[it*2],16) shl 4) or Character.digit(s[it*2+1],16)).toByte() }

    @Test fun sealedApkReadsBackThroughRes() {
        val sealed = System.getProperty("rw.sealedApk")?.let(::File)?.takeIf { it.isFile }
        val plain = System.getProperty("rw.plainApk")?.let(::File)?.takeIf { it.isFile }
        val cert = System.getProperty("rw.certDer")?.let(::File)?.takeIf { it.isFile }?.readBytes()
        val secret = System.getProperty("rw.resSecret")?.let(::hex)
        assumeTrue(sealed != null && plain != null && cert != null && secret != null, "封装产物未提供，跳过")

        Res.loader = URLClassLoader(arrayOf(sealed!!.toURI().toURL()), null)
        Res.install(cert!!, secret!!)

        // 封装包里看不到任何明文受保护路径
        ZipFile(sealed).use { z -> assertTrue(z.entries().asSequence().none { e -> Res.PROTECTED.any { e.name.startsWith(it) } }, "封装包不应残留明文资源") }

        // 抽查几个资源：Res 读回的内容和未封装原文完全一致
        val samples = ZipFile(plain!!).use { z -> z.entries().asSequence().filter { e -> !e.isDirectory && Res.PROTECTED.any { e.name.startsWith(it) } }.map { it.name }.take(3).toList() }
        ZipFile(plain!!).use { pz ->
            var checked = 0
            for (path in samples) {
                val want = pz.getEntry(path)?.let { pz.getInputStream(it).readBytes() } ?: continue
                assertContentEquals(want, Res.bytes(path), "封装后读回的 $path 与原文不一致")
                checked++
            }
            assertTrue(checked > 0, "没有抽到任何样本资源")
        }

        // 换一张证书：一个资源都读不出
        Res.install("wrong-cert".toByteArray(), secret)
        assertNull(Res.bytes(samples.first()), "换签名后不应能解密")
    }
}
