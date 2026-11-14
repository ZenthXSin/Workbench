package io.github.zenthxsin.rustwright.core.ai

import io.github.zenthxsin.rustwright.core.ai.image.ImageApiException
import io.github.zenthxsin.rustwright.core.ai.image.ImageClient
import io.github.zenthxsin.rustwright.core.ai.image.ImageOutput
import io.github.zenthxsin.rustwright.core.ai.image.ImageRequest
import io.github.zenthxsin.rustwright.core.ai.model.ModelCatalog
import io.github.zenthxsin.rustwright.core.ai.model.ModelCatalogClient
import io.github.zenthxsin.rustwright.core.ai.model.ModelCatalogException
import io.github.zenthxsin.rustwright.core.ai.model.ModelKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreAiTest {

    private val models = """
        {"data":[
          {"id":"gpt-6-astra","model_ratio":"1.5","completion_ratio":4,"context_length":200000,"supported_endpoint_types":["openai"]},
          {"id":"claude-sonnet-5","model_ratio":2,"supported_endpoint_types":["anthropic","openai"],"architecture":{"input_modalities":["text","image"]}},
          {"id":"gpt-image-2.5-sunburst","quota_type":1,"model_price":0.2,"supported_endpoint_types":["image-generation"]},
          {"id":"text-embedding-3","supported_endpoint_types":["embeddings"]},
          {"id":"deepseek-v4"},
          {"id":""}
        ]}
    """.trimIndent()

    @Test
    fun parsesAndClassifiesCatalog() {
        val c = ModelCatalog.parse(models)
        assertEquals(listOf("gpt-6-astra", "claude-sonnet-5", "deepseek-v4"), c.chat.map { it.id })
        assertEquals(listOf("gpt-image-2.5-sunburst"), c.image.map { it.id })
        assertEquals(ModelKind.EMBEDDING, c["text-embedding-3"]!!.kind)
        val astra = c["GPT-6-ASTRA"]!!
        assertEquals(1.5, astra.ratio)
        assertEquals(4.0, astra.outputRatio)
        assertEquals(200000, astra.contextWindow)
        assertEquals(ChatProtocol.ANTHROPIC, c["claude-sonnet-5"]!!.protocol)
        assertTrue(c["claude-sonnet-5"]!!.multimodal)
        assertEquals(false, c["deepseek-v4"]!!.multimodal)
        assertTrue(c["gpt-image-2.5-sunburst"]!!.pricedPerCall)
        assertEquals("claude-sonnet-5", c.preferredChat("claude-sonnet-5")!!.id)
        assertEquals("gpt-6-astra", c.preferredChat("gpt-image-2.5-sunburst")!!.id)
    }

    @Test
    fun clientReportsHttpErrorsAndPicksFastest() = runTest {
        MockWebServer().use { ok ->
            MockWebServer().use { bad ->
                ok.enqueue(MockResponse().setBody(models))
                bad.enqueue(MockResponse().setResponseCode(401))
                val client = ModelCatalogClient()
                val sel = client.selectFastest("sk-test", listOf(bad.url("/v1").toString(), ok.url("/v1").toString()))
                assertEquals(ok.url("/v1").toString().trimEnd('/'), sel.baseUrl)
                assertEquals("Bearer sk-test", ok.takeRequest().getHeader("Authorization"))
                bad.enqueue(MockResponse().setResponseCode(401))
                val e = assertFailsWith<ModelCatalogException> { client.fetch("sk-test", bad.url("/v1").toString()) }
                assertEquals(401, e.httpStatus)
                assertTrue(e.message!!.startsWith("令牌无效"))
            }
        }
    }

    @Test
    fun imageClientRetriesAndParses() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"busy Bearer sk-abcdefghijk"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"AAAA"},{"url":"https://x/y.png"}]}"""))
            val sleeps = mutableListOf<Long>()
            val client = ImageClient("sk-secret", server.url("/v1").toString(), sleeper = { sleeps += it })
            val out = client.generate(ImageRequest("坦克", "gpt-image", count = 2), idempotencyKey = "fixture-idempotency-key")
            assertEquals(listOf(ImageOutput.Inline("AAAA"), ImageOutput.Remote("https://x/y.png")), out)
            assertEquals(15_000L, sleeps.sum())
            val first = server.takeRequest()
            assertTrue(first.body.readUtf8().contains("\"prompt\":\"坦克\""))

            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"bad size for sk-secret"}}"""))
            val e = assertFailsWith<ImageApiException> { client.generate(ImageRequest("x", "m")) }
            assertEquals(400, e.statusCode)
            assertTrue("sk-secret" !in e.message!!, e.message)
        }
    }

    @Test
    fun paidImageWithoutIdempotencyKeyIsNotReplayed() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("busy")) }
            val sleeps = mutableListOf<Long>()
            val client = ImageClient("test", server.url("/v1").toString(), sleeper = { sleeps += it })
            assertFailsWith<ImageApiException> { client.generate(ImageRequest("p", "m")) }
            assertEquals(1, server.requestCount)
            assertTrue(sleeps.isEmpty())
        }
    }

    @Test
    fun editSendsMultipart() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""["https://x/1.png"]"""))
            ImageClient("t", server.url("/v1").toString()).edit(ImageRequest("改", "m"), listOf(byteArrayOf(1, 2, 3)), maskPng = byteArrayOf(9))
            val req = server.takeRequest()
            assertEquals("/v1/images/edits", req.path)
            val body = req.body.readUtf8()
            assertTrue("filename=\"reference-1.png\"" in body && "name=\"mask\"" in body && "name=\"prompt\"" in body)
        }
    }

    @Test
    fun idempotentImageRetriesKeepKeyAndBenefitStateStopsRetrying() {
        MockWebServer().use { server ->
            val key = "test-image-request-0001"
            val client = ImageClient("token", server.url("/v1").toString(), sleeper = {})
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"message":"temporary"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"AAAA"}]}"""))
            client.generate(ImageRequest("p", "m", quality = "medium", outputFormat = "png"), idempotencyKey = key)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals(key, first.getHeader("Idempotency-Key"))
            assertEquals(key, second.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            for (state in listOf("reserved", "uncertain", "released")) {
                server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"type":"rustwright_benefit_replay","state":"$state"}}"""))
                val e = assertFailsWith<ImageApiException> { client.generate(ImageRequest("p", "m"), idempotencyKey = key) }
                assertEquals("rustwright_benefit_replay", e.type)
                assertEquals(state, e.state)
            }
            assertEquals(5, server.requestCount)
        }
    }
}
