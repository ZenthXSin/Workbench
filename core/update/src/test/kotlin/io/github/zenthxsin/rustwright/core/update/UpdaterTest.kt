package io.github.zenthxsin.rustwright.core.update

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdaterTest {
    private val sha = "a".repeat(64)

    @Test
    fun parsesAndValidatesManifest() {
        val info = UpdateInfo.parse("""{"versionCode":5,"versionName":"0.5.0","apkUrl":"https://x/a.apk","sha256":"$sha","minSupportedVersionCode":3,"extra":1}""")
        assertEquals(5, info.versionCode)
        assertTrue(info.isMandatoryFor(2))
        assertFailsWith<IllegalArgumentException> { UpdateInfo.parse("""{"versionCode":5,"versionName":"x","apkUrl":"http://x/a.apk","sha256":"$sha"}""") }
        assertFailsWith<IllegalArgumentException> { UpdateInfo.parse("""{"versionCode":5,"versionName":"x","apkUrl":"https://x/a.apk","sha256":"zz"}""") }
    }

    @Test
    fun checksAgainstCurrentVersion() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val body = """{"versionCode":5,"versionName":"0.5.0","apkUrl":"https://x/a.apk","sha256":"$sha","minSupportedVersionCode":3}"""
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body))
            // Interceptor substitutes transport only; production still validates the HTTPS URL.
            val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/api/stable.json")).build())
            }.build()
            val u = Updater("https://fixture.invalid/api/stable.json", Files.createTempDirectory("up").toFile(), client)
            val r = assertIs<UpdateCheckResult.Available>(u.check(2))
            assertTrue(r.mandatory)
            assertEquals(UpdateCheckResult.UpToDate, u.check(5))
        }
    }

    @Test
    fun rejectsNonHttpsDownloads() {
        val u = Updater("https://x/api.json", Files.createTempDirectory("up").toFile())
        val info = UpdateInfo(1, "1", "http://x/a.apk", sha)
        assertFailsWith<IllegalArgumentException> { u.download(info) }
    }

    @Test
    fun hashesFiles() {
        val f = Files.createTempFile("h", ".bin").toFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f))
    }
}
