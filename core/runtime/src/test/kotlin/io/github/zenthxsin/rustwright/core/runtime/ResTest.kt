package io.github.zenthxsin.rustwright.core.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResTest {
    @TempDir lateinit var dir: File
    private val original = Res.loader
    private val cert = "official-certificate".toByteArray()
    private val secret = ByteArray(32) { (it * 11 + 5).toByte() }
    private val key = Res.deriveKey(cert, secret)

    private val nonce = ByteArray(12) { (it * 7 + 3).toByte() }

    @AfterEach fun restore() { Res.loader = original }

    private fun put(name: String, data: ByteArray) = File(dir, name).apply { parentFile.mkdirs(); writeBytes(data) }
    private fun seal(path: String, plain: ByteArray) = put(Res.entryName(key, path), Res.encrypt(key, path, plain, nonce))
    private fun mount() { Res.loader = URLClassLoader(arrayOf(dir.toURI().toURL()), null) }

    @Test fun plainResourcesWorkWhenThePackageIsNotSealed() {
        put("assets/a.txt", "hello".toByteArray()); mount()
        assertEquals("hello", Res.bytes("/assets/a.txt")!!.toString(Charsets.UTF_8))
    }

    @Test fun sealedResourceRoundTripsAndHidesItsName() {
        val text = "bufferSize: 4200\n".repeat(200).toByteArray()
        val f = seal("assets/knowledge/x.yaml", text)
        put(Res.MARK, ByteArray(0)); mount(); Res.install(cert, secret)
        assertContentEquals(text, Res.bytes("assets/knowledge/x.yaml"))
        assertTrue(f.length() < text.size / 4, "可压缩的内容先压缩再加密")
        assertTrue("knowledge" !in f.path.removePrefix(dir.path) && "x.yaml" !in f.path)
        assertTrue("bufferSize" !in f.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun resignedPackageCannotReadAnything() {
        seal("skills/s/SKILL.md", "secret".toByteArray()); put(Res.MARK, ByteArray(0)); mount()
        Res.install("someone-elses-certificate".toByteArray(), secret)
        assertNull(Res.bytes("skills/s/SKILL.md"))
    }

    @Test fun flippedByteFailsAuthentication() {
        val f = seal("tiles/water.png", ByteArray(500) { it.toByte() }); put(Res.MARK, ByteArray(0)); mount(); Res.install(cert, secret)
        f.writeBytes(f.readBytes().also { it[40] = (it[40].toInt() xor 1).toByte() })
        assertNull(Res.bytes("tiles/water.png"))
    }

    @Test fun ciphertextMovedUnderAnotherNameIsRejected() {
        val a = seal("assets/prompts/a.md", "prompt A".toByteArray())
        put(Res.MARK, ByteArray(0)); mount(); Res.install(cert, secret)
        a.copyTo(File(dir, Res.entryName(key, "assets/prompts/b.md")))
        assertNull(Res.bytes("assets/prompts/b.md"))
    }

    @Test fun sealedPackageIgnoresPlainReplacements() {
        put("assets/prompts/a.md", "injected".toByteArray()); put(Res.MARK, ByteArray(0)); mount(); Res.install(cert, secret)
        assertNull(Res.bytes("assets/prompts/a.md"))
    }

    @Test fun unprotectedPathsStayPlainEvenWhenSealed() {
        put("licenses/x.txt", "MIT".toByteArray()); put(Res.MARK, ByteArray(0)); mount(); Res.install(cert, secret)
        assertEquals("MIT", Res.bytes("licenses/x.txt")!!.toString(Charsets.UTF_8))
    }

    @Test fun differentCertificatesDeriveDifferentKeysAndNames() {
        val other = Res.deriveKey("x".toByteArray(), secret)
        assertNotEquals(key.toList(), other.toList())
        assertNotEquals(Res.entryName(key, "assets/a"), Res.entryName(other, "assets/a"))
    }

}
