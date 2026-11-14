package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.core.agent.*
import io.github.zenthxsin.rustwright.pic.imaging.*
import io.github.zenthxsin.rustwright.pic.impl.draw.projectRasterTools
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class ProjectImageWorkspaceTest {
    @Test
    fun svgFrameBatchPreservesOrderAndFailedBatchPublishesNothing() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-svg-frames").toFile()
        try {
            val seen = mutableListOf<Int>()
            val w =
                ProjectImageWorkspace(
                    svgRasterizer =
                        io.github.zenthxsin.rustwright.pic.api.SvgRasterizer { svg, _ ->
                            if ("fail" in svg) error("bad frame")
                            val color = if ("white" in svg) Color.WHITE else Color.BLACK
                            seen += color
                            RgbaImage(1, 1, intArrayOf(color))
                        }
                )
            w.saveSvg(root, "a.svg", "<svg viewBox=\"0 0 1 1\"><rect fill=\"white\"/></svg>")
            w.saveSvg(root, "b.svg", "<svg viewBox=\"0 0 1 1\"><rect fill=\"black\"/></svg>")
            w.renderSvgFrames(root, listOf("a.svg", "b.svg"), "ok.png", 1, 2)
            assertEquals(listOf(Color.WHITE, Color.BLACK), seen)
            assertEquals(2 to 1, ImageHeader.dimensions(root.resolve("ok.png").readBytes()))
            w.saveSvg(root, "bad.svg", "<svg viewBox=\"0 0 1 1\"><title>fail</title></svg>")
            assertFailsWith<IllegalStateException> {
                w.renderSvgFrames(root, listOf("a.svg", "bad.svg"), "bad.png", 1, 2)
            }
            assertFalse(root.resolve("bad.png").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mkpxWorkspaceConversionEditsAndStaleGuard() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-mkpx").toFile()
        try {
            val w = ProjectImageWorkspace()
            val initial = w.createDocument(root, "old.mkpx", 8, 8)
            val changed =
                w.apply(
                    root,
                    "old.mkpx",
                    initial.version,
                    listOf(
                        LayeredPixelDocument.Action("set_color", color = Color.WHITE),
                        LayeredPixelDocument.Action(
                            "pencil",
                            listOf(LayeredPixelDocument.Point(0, 0)),
                        ),
                    ),
                )
            assertEquals(Color.WHITE, ProjectImageWorkspace().read(root, "old.mkpx").pixels[0])
            assertFailsWith<IllegalArgumentException> {
                w.apply(
                    root,
                    "old.mkpx",
                    initial.version,
                    listOf(LayeredPixelDocument.Action("add_layer")),
                )
            }
            w.importDocument(root, "old.mkpx", "new.rwpixel")
            assertEquals(Color.WHITE, w.read(root, "new.rwpixel").pixels[0])
            w.apply(
                root,
                "new.rwpixel",
                w.observeDocument(root, "new.rwpixel").version,
                listOf(LayeredPixelDocument.Action("select_all"),LayeredPixelDocument.Action("clear_pixels")),
            )
            assertEquals(changed.version, w.observeDocument(root, "old.mkpx").version)
            assertEquals(Color.WHITE, w.read(root, "old.mkpx").pixels[0])
            assertFailsWith<IllegalArgumentException> {
                w.importDocument(root, "old.mkpx", "new.rwpixel")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storyboardPersistsAndUsesFixedPromptWithStaleVersionGuard() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-plan").toFile()
        try {
            val w = ProjectImageWorkspace()
            val plan =
                io.github.zenthxsin.rustwright.pic.api.CreativePlan(
                    "race",
                    "shared visual rules",
                    listOf("start", "finish"),
                )
            val saved = w.savePlan(root, "story.json", plan)
            assertEquals(plan, ProjectImageWorkspace().readPlan(root, "story.json").plan)
            assertFailsWith<IllegalArgumentException> { w.savePlan(root, "story.json", plan) }
            var captured: io.github.zenthxsin.rustwright.pic.api.PicRequest? = null
            val service =
                object : io.github.zenthxsin.rustwright.pic.api.PicService {
                    override val images =
                        kotlinx.coroutines.flow.MutableStateFlow(
                            emptyList<io.github.zenthxsin.rustwright.pic.api.PicImage>()
                        )
                    override val ready = true

                    override fun finalPrompt(
                        request: io.github.zenthxsin.rustwright.pic.api.PicRequest
                    ) = request.prompt

                    override suspend fun generate(
                        request: io.github.zenthxsin.rustwright.pic.api.PicRequest
                    ): List<io.github.zenthxsin.rustwright.pic.api.PicImage> {
                        captured = request
                        return emptyList()
                    }

                    override suspend fun pixelate(
                        image: io.github.zenthxsin.rustwright.pic.api.PicImage,
                        size: Int,
                    ) = error("unused")

                    override fun delete(image: io.github.zenthxsin.rustwright.pic.api.PicImage) {}
                }
            val tool =
                io.github.zenthxsin.rustwright.pic.impl.rustpic.StoryboardFrameTool(w, service)
            val args = buildJsonObject {
                put("path", "story.json")
                put("version", saved.version)
                put("frame", 1)
            }
            assertTrue(
                tool
                    .preview(ToolContext("u", "c", projectDir = root), args)
                    .contains("shared visual rules\n\nFrame 2/2: finish")
            )
            assertFalse(tool.executeInProject("u", args, ToolProject("p", root)).isError)
            assertEquals("shared visual rules\n\nFrame 2/2: finish", captured!!.prompt)
            w.savePlan(root, "story.json", plan.copy(description = "changed"), saved.version)
            captured = null
            assertTrue(tool.executeInProject("u", args, ToolProject("p", root)).isError)
            assertNull(captured)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun animationAndPalettePersistAndExportOrderedFrames() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-animation").toFile()
        try {
            val w = ProjectImageWorkspace()
            val d = w.createDocument(root, "timeline.rwpixel", 2, 2)
            val updated =
                w.apply(
                    root,
                    "timeline.rwpixel",
                    d.version,
                    listOf(
                        LayeredPixelDocument.Action("set_color", color = Color.WHITE),
                        LayeredPixelDocument.Action(
                            "pencil",
                            listOf(LayeredPixelDocument.Point(0, 0)),
                        ),
                        LayeredPixelDocument.Action("duplicate_frame", index = 0),
                        LayeredPixelDocument.Action(
                            "set_frame_duration",
                            index = 1,
                            durationMillis = 300,
                        ),
                        LayeredPixelDocument.Action("palette_add", color = Color.WHITE),
                    ),
                )
            val reopened = ProjectImageWorkspace()
            val info = reopened.observeDocument(root, "timeline.rwpixel")
            assertEquals(updated.version, info.version)
            assertEquals(listOf(100, 300), info.frameDurations)
            assertEquals(Color.WHITE, info.palettes[0].colors[0].color)
            val frames = reopened.readAnimation(root, "timeline.rwpixel")
            assertEquals(2, frames.frames.size)
            assertEquals(Color.WHITE, frames.frames[1].pixels[0])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rasterizerReceivesSanitizedSvgAndSavesPng() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-svg").toFile()
        try {
            var called = false
            val w =
                ProjectImageWorkspace(
                    svgRasterizer =
                        io.github.zenthxsin.rustwright.pic.api.SvgRasterizer { svg, side ->
                            called = true
                            assertFalse("<script" in svg)
                            assertTrue("xmlns=" in svg)
                            assertEquals(32, side)
                            RgbaImage(32, 16)
                        }
                )
            w.saveSvg(
                root,
                "unit.svg",
                """<svg viewBox="0 0 2 1"><script>bad()</script><rect width="2" height="1"/></svg>""",
            )
            w.renderSvg(root, "unit.svg", "unit.png", 32)
            assertTrue(called)
            assertEquals(32 to 16, ImageHeader.dimensions(root.resolve("unit.png").readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun documentPersistsAcrossBackendInstancesAndStaleEditsFail() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-doc").toFile()
        try {
            val w = ProjectImageWorkspace(ImageDecoder { error("native decoder not needed") })
            val first = w.createDocument(root, "image.rwpixel", 4, 4)
            val edited =
                w.apply(
                    root,
                    "image.rwpixel",
                    first.version,
                    listOf(
                        LayeredPixelDocument.Action("set_color", color = 0xff00ff00.toInt()),
                        LayeredPixelDocument.Action(
                            "pencil",
                            listOf(LayeredPixelDocument.Point(2, 1)),
                        ),
                    ),
                )
            assertNotEquals(first.version, edited.version)
            assertFailsWith<IllegalArgumentException> {
                w.apply(
                    root,
                    "image.rwpixel",
                    first.version,
                    listOf(LayeredPixelDocument.Action("add_layer")),
                )
            }
            val reopened = ProjectImageWorkspace()
            assertEquals(0xff00ff00.toInt(), reopened.read(root, "image.rwpixel").getPixel(2, 1))
            assertEquals(edited.version, reopened.observeDocument(root, "image.rwpixel").version)
            w.write(root, "export.png", w.read(root, "image.rwpixel"))
            assertContentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10),
                root.resolve("export.png").readBytes().take(8).toByteArray(),
            )
            assertFailsWith<IllegalArgumentException> {
                w.write(root, "export.png", RgbaImage(1, 1))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pathsCannotEscapeProjectAndFailedBatchDoesNotChangeDisk() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pic-project").toFile()
        val outside = Files.createTempDirectory("pic-outside").toFile()
        try {
            val w = ProjectImageWorkspace()
            assertFailsWith<IllegalArgumentException> {
                w.write(root, "../escaped.png", RgbaImage(1, 1))
            }
            Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> {
                w.write(root, "link/escaped.png", RgbaImage(1, 1))
            }
            val info = w.createDocument(root, "d.rwpixel", 2, 2)
            val before = root.resolve("d.rwpixel").readBytes()
            assertFailsWith<IllegalArgumentException> {
                w.apply(
                    root,
                    "d.rwpixel",
                    info.version,
                    listOf(
                        LayeredPixelDocument.Action("add_layer"),
                        LayeredPixelDocument.Action("select_layer", index = 99),
                    ),
                )
            }
            assertContentEquals(before, root.resolve("d.rwpixel").readBytes())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun toolsUseExplicitProjectAndRejectContextlessCalls() = runBlocking<Unit> {
        val a = Files.createTempDirectory("pic-a").toFile()
        val b = Files.createTempDirectory("pic-b").toFile()
        try {
            val tools = projectRasterTools(ProjectImageWorkspace())
            val create = tools.single { it.name == "create_pixel_document" }
            val args = buildJsonObject {
                put("path", "unit.rwpixel")
                put("width", 2)
                put("height", 3)
            }
            assertTrue(create.execute("u", args).isError)
            assertFalse(
                (create as ProjectAware).executeInProject("u", args, ToolProject("b", b)).isError
            )
            assertFalse(a.resolve("unit.rwpixel").exists())
            assertTrue(b.resolve("unit.rwpixel").isFile)
            assertEquals(ToolRisk.WRITE, create.risk)
            assertEquals(listOf("unit.rwpixel"), (create as PathAware).touchedPaths(args))
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }
}
