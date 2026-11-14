package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.pic.impl.draw.PixelateImageTool
import io.github.zenthxsin.rustwright.pic.impl.rustpic.GenerateImageTool
import io.github.zenthxsin.rustwright.pic.impl.rustpic.ListImagesTool
import io.github.zenthxsin.rustwright.core.ai.AiChannel
import io.github.zenthxsin.rustwright.core.runtime.Events
import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicRequest
import io.github.zenthxsin.rustwright.pic.imaging.PngEncoder
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PicServiceTest {

    private val decoder = ImageDecoder { bytes ->
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        RgbaImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    private fun spritePng(): String {
        val img = RgbaImage(128, 128)
        for (y in 0 until 128) for (x in 0 until 128) img.setPixel(x, y, if (x in 40..88 && y in 40..88) 0xFF3050C0.toInt() else -1)
        return Base64.getEncoder().encodeToString(PngEncoder.encode(img))
    }

    @Test
    fun spritePromptCarriesConstraints() {
        val p = SpritePrompt.build(PicRequest("一个手持步枪朝右的士兵", CreationMode.GAME_SPRITE, 64))
        assertTrue("strict 64x64 logical pixel grid" in p)
        assertTrue("16x16 real pixels" in p)
        assertTrue("toward the RIGHT edge" in p)
        assertTrue("held weapon" in p)
        assertEquals("纯文本", SpritePrompt.build(PicRequest("纯文本", CreationMode.GENERAL)))
    }

    @Test
    fun generatesSavesPixelatesAndRestores() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${spritePng()}"}]}"""))
            val dir = Files.createTempDirectory("pic").toFile()
            val channel = AiChannel(baseUrl = server.url("/v1").toString(), token = "sk-x", chatModel = "c", imageModel = "gpt-image")
            val service = PicServiceImpl(dir, Events(), { channel }, decoder)
            val imgs = service.generate(PicRequest("坦克", CreationMode.GAME_SPRITE, 32))
            assertEquals(1, imgs.size)
            val px = imgs.single().pixel!!
            val decoded = ImageIO.read(px)
            assertEquals(32, decoded.width)
            assertEquals(0, decoded.getRGB(0, 0) ushr 24)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"model\":\"gpt-image\""))

            // 重新打开后能从磁盘恢复图库
            val again = PicServiceImpl(dir, Events(), { channel }, decoder)
            assertEquals(imgs.single().id, again.images.value.single().id)
            assertEquals(px.name, again.images.value.single().pixel?.name)

            val listed = ListImagesTool(again).execute("u", buildJsonObject {})
            assertTrue(imgs.single().id in listed.toAgent)
            val pix = PixelateImageTool(again).execute("u", buildJsonObject { put("id", imgs.single().id); put("size", 16) })
            assertTrue(!pix.isError, pix.toAgent)
            assertEquals(16, ImageIO.read(again.images.value.single().pixel).width)
        }
    }

    @Test
    fun toolRefusesWithoutImageModel() = runTest {
        val service = PicServiceImpl(Files.createTempDirectory("pic").toFile(), Events(), { AiChannel(token = "t", chatModel = "c") }, decoder)
        val r = GenerateImageTool(service).execute("u", buildJsonObject { put("prompt", "x") })
        assertEquals("not_configured", r.error?.type)
    }
}
