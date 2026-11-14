package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.pic.impl.draw.PixelateImageTool
import io.github.zenthxsin.rustwright.pic.impl.rustpic.GenerateImageTool
import io.github.zenthxsin.rustwright.pic.impl.rustpic.ListImagesTool
import io.github.zenthxsin.rustwright.core.agent.ToolProject
import io.github.zenthxsin.rustwright.core.ai.AiChannel
import io.github.zenthxsin.rustwright.core.ai.image.ImageClient
import io.github.zenthxsin.rustwright.core.runtime.Events
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class FreePicServiceTest {
    @TempDir lateinit var temp: File
    private val decoder = ImageDecoder { bytes -> ImageIO.read(ByteArrayInputStream(bytes)).let { RgbaImage(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) } }
    private val png by lazy { Base64.getEncoder().encodeToString(PngEncoder.encode(RgbaImage(1024, 1024, IntArray(1024 * 1024) { -1 }))) }
    private val request = PicRequest("蓝色坦克", CreationMode.GENERAL, free = true, requestScope = "conversation:a")
    private var subject = "account-a"
    private var loggedIn = true
    private val relayOrigins = mutableListOf<String?>()
    private fun service(server: MockWebServer, suffix: String = "images") = PicServiceImpl(File(temp, suffix), Events(),
        { AiChannel(baseUrl = "http://127.0.0.1:1/v1", token = "paid-token", chatModel = "paid", imageModel = "paid-image") }, decoder,
        clientFactory = { channel, cancelled -> ImageClient(channel.token, channel.imageBaseUrl, cancelled, sleeper = {}, timeouts = ImageClient.Timeouts(connectMillis = 1000, readMillis = 2000)) },
        benefitReady = { loggedIn },
        benefitRelay = { origin ->
            relayOrigins += origin
            check(loggedIn)
            PicBenefitRelay(server.url("/v1").toString(), "login-token", subject, "workbench-free/gpt-image-2")
        })

    private fun success() = MockResponse().setBody("""{"data":[{"b64_json":"$png"}]}""")

    @Test fun freeConversationIgnoresGlobalPaidChannelAndSavesValidPng() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(success())
            val service = service(server)
            // 项目产物目录恰与图库相同，保存不能先删掉原图。
            val project = temp
            val tool = GenerateImageTool(service) { error("must not read current UI project") }
            val result = tool.executeInProject("u", buildJsonObject { put("prompt", "蓝色坦克"); put("mode", "general") }, ToolProject("p", project, "a", freeBenefits = true))
            assertFalse(result.isError, result.toAgent)
            val call = server.takeRequest()
            val body = Json.parseToJsonElement(call.body.readUtf8()).jsonObject
            assertEquals("Bearer login-token", call.getHeader("Authorization"))
            assertEquals("workbench-free/gpt-image-2", body["model"]!!.jsonPrimitive.content)
            assertEquals("1", body["n"]!!.jsonPrimitive.content)
            assertEquals("1024x1024", body["size"]!!.jsonPrimitive.content)
            assertEquals("medium", body["quality"]!!.jsonPrimitive.content)
            assertEquals("png", body["output_format"]!!.jsonPrimitive.content)
            assertNotNull(call.getHeader("Idempotency-Key"))
            val image = service.images.value.single()
            assertEquals(1024, ImageIO.read(image.file).width)
            assertTrue(File(project, "images/${image.file.name}").isFile)
            assertEquals(null, service.pendingFree("conversation:a"))
            assertEquals(image.id, service(server).images.value.single().id)
        }
    }

    @Test fun disconnectRestartAndUnknownStateReuseKeyWithoutChangingParameters() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(success().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val first = service(server)
            assertFails { first.generate(request) }
            val one = server.takeRequest()
            assertEquals(request, first.pendingFree(request.requestScope))
            val reopened = service(server)
            assertFails { reopened.generate(request.copy(prompt = "不同提示词")) }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"type":"rustwright_benefit_replay","state":"uncertain"}}"""))
            assertFails { reopened.retryFree(request.requestScope) }
            assertEquals(2, server.requestCount)
            val two = server.takeRequest()
            server.enqueue(success())
            val images = reopened.retryFree(request.requestScope)
            val three = server.takeRequest()
            assertEquals(one.getHeader("Idempotency-Key"), two.getHeader("Idempotency-Key"))
            assertEquals(one.getHeader("Idempotency-Key"), three.getHeader("Idempotency-Key"))
            assertEquals(one.body.readUtf8(), three.body.readUtf8())
            assertEquals(1, images.size)
            assertEquals(1, reopened.images.value.size)
            assertEquals(null, reopened.pendingFree(request.requestScope))
            assertTrue(relayOrigins.drop(1).all { it == "http://localhost:${server.port}" })
        }
    }

    @Test fun cancelledResponseRetainsKeyForReopenedService() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(success().setBodyDelay(500, TimeUnit.MILLISECONDS))
            val service = service(server)
            val job = launch(Dispatchers.Default) { service.generate(request) }
            val first = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }!!
            job.cancelAndJoin()
            assertTrue(service.images.value.isEmpty())
            assertEquals(request, service.pendingFree(request.requestScope))
            server.enqueue(success())
            service(server).retryFree(request.requestScope)
            assertEquals(first.getHeader("Idempotency-Key"), server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    @Test fun concurrentSameRequestSharesOneHttpCallAndOneGalleryImage() = runTest {
        MockWebServer().use { server ->
            server.start(); server.enqueue(success().setBodyDelay(300, TimeUnit.MILLISECONDS))
            val service = service(server)
            val one = async(start = CoroutineStart.UNDISPATCHED) { service.generate(request) }
            withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }!!
            val two = async(start = CoroutineStart.UNDISPATCHED) { service.generate(request) }
            assertFails { service.generate(request.copy(prompt = "不能插队的新图")) }
            assertEquals(one.await().single().id, two.await().single().id)
            assertEquals(1, server.requestCount)
            assertEquals(1, service.images.value.size)
        }
    }

    @Test fun loginRestrictionsAndAccountSwitchNeverUsePaidFallback() = runTest {
        MockWebServer().use { server ->
            server.start()
            val service = service(server)
            loggedIn = false
            assertFails { service.generate(request) }
            assertEquals(0, server.requestCount)
            loggedIn = true
            assertFails { service.generate(request.copy(count = 2)) }
            assertFails { service.generate(request.copy(references = listOf(File(temp, "ref.png")))) }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"type":"rustwright_benefit_replay","state":"reserved"}}"""))
            assertFails { service.generate(request) }
            subject = "account-b"
            assertFails { service.retryFree(request.requestScope) }
            assertEquals(1, server.requestCount)
            assertEquals(request, service.pendingFree(request.requestScope))
        }
    }

    @Test fun invalidPngKeepsPendingAndReleasedResponseAllowsNewRequest() = runTest {
        MockWebServer().use { server ->
            server.start()
            val service = service(server)
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"AAAA"}]}"""))
            assertFails { service.generate(request) }
            assertTrue(service.images.value.isEmpty())
            assertEquals(request, service.pendingFree(request.requestScope))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"type":"rustwright_benefit_replay","state":"released"}}"""))
            assertFails { service.retryFree(request.requestScope) }
            assertEquals(null, service.pendingFree(request.requestScope))
            server.enqueue(success())
            service.generate(request.copy(prompt = "已调整"))
            val first = server.takeRequest().getHeader("Idempotency-Key")
            assertEquals(first, server.takeRequest().getHeader("Idempotency-Key"))
            assertNotEquals(first, server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    @Test fun imageAlreadyCommittedBeforeCrashIsRecoveredLocallyWithoutRegenerating() = runTest {
        MockWebServer().use { server ->
            server.start()
            val service = service(server)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"type":"rustwright_benefit_replay","state":"uncertain"}}"""))
            assertFails { service.generate(request) }
            val record = File(temp, "images/.free-requests").listFiles()!!.single()
            val savedRecord = record.readText()
            assertFalse(savedRecord.contains("login-token"))
            assertFalse(savedRecord.contains("paid-token"))
            server.enqueue(success())
            val image = service.retryFree(request.requestScope).single()
            // 模拟元数据提交后、清 pending 前进程退出。
            record.writeText(savedRecord)
            val reopened = service(server)
            val recovered = reopened.retryFree(request.requestScope).single()
            assertEquals(image.id, recovered.id)
            assertEquals(2, server.requestCount)
            assertFalse(record.exists())
            assertEquals(1, reopened.images.value.size)
        }
    }
}
