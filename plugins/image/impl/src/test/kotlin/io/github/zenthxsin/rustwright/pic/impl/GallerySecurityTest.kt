package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.core.runtime.Events
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GallerySecurityTest {
    @Test fun metadataCannotDeleteOutsideGallery() {
        val root = Files.createTempDirectory("audit-gallery-").toFile()
        val gallery = File(root, "gallery").apply { mkdirs() }
        val victim = File(root, "outside-fixture.txt").apply { writeText("private fixture") }
        File(gallery, "img_fixture.png").writeText("unused image fixture")
        File(gallery, "img_fixture.json").writeText("""{"prompt":"fixture","mode":"GENERAL","createdAt":1,"pixel":"../outside-fixture.txt"}""")
        val service = PicServiceImpl(gallery, Events(), { null }, ImageDecoder { error("not decoding") })
        val item = service.images.value.single()
        assertNull(item.pixel)
        service.delete(item)
        assertTrue(victim.exists())
    }
}
