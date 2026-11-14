package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.pic.api.*
import io.github.zenthxsin.rustwright.pic.imaging.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * Shared file backend. Documents are authoritative; PNG exports are explicit separate operations.
 */
class ProjectImageWorkspace(
    private val decoder: ImageDecoder = ArcImageDecoder,
    private val svgRasterizer: SvgRasterizer? = null,
) : ImageWorkspace {
    private val mutex = Mutex()

    private data class Cached(val version: String, val document: LayeredPixelDocument)

    private val documents = LinkedHashMap<String, Cached>()
    private val json = Json

    private fun resolve(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && '\u0000' !in path) {
            "Use a project-relative path"
        }
        val base = root.canonicalFile
        require(base.isDirectory)
        val file = File(base, path).canonicalFile
        require(file.toPath().startsWith(base.toPath()) && file != base) { "Path leaves project" }
        return file
    }

    private fun bytes(file: File): ByteArray {
        require(file.isFile && file.length() <= 32 * 1024 * 1024) {
            "Missing file or input exceeds 32 MiB"
        }
        return file.readBytes()
    }

    private fun version(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun writeBytes(root: File, path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = resolve(root, path)
        require(overwrite || !file.exists()) {
            "Output exists; choose another path or set overwrite=true"
        }
        file.parentFile.mkdirs()
        require(resolve(root, path) == file)
        val tmp = Files.createTempFile(file.parentFile.toPath(), ".rw-image-", ".tmp")
        try {
            Files.write(tmp, bytes)
            currentCoroutineContext().ensureActive()
            require(resolve(root, path) == file)
            if (overwrite)
                Files.move(
                    tmp,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            else Files.move(tmp, file.toPath())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun read(root: File, path: String): RgbaImage =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = resolve(root, path)
                if (file.extension.lowercase() in setOf("rwpixel", "mkpx"))
                    return@withLock load(file).document.composite()
                val data = bytes(file)
                val dimensions = ImageHeader.dimensions(data)
                RasterOperations.dimensions(dimensions.first, dimensions.second)
                decoder.decode(data).also { RasterOperations.dimensions(it.width, it.height) }
            }
        }

    override suspend fun write(root: File, path: String, image: RgbaImage, overwrite: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(path.endsWith(".png", true)) { "Raster output must be .png" }
                RasterOperations.dimensions(image.width, image.height)
                writeBytes(root, path, PngEncoder.encode(image), overwrite)
            }
        }

    override suspend fun saveSvg(root: File, path: String, svg: String, overwrite: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(path.endsWith(".svg", true))
                writeBytes(root, path, SvgSanitizer.sanitize(svg).encodeToByteArray(), overwrite)
            }
        }

    override suspend fun renderSvg(
        root: File,
        path: String,
        output: String,
        longSide: Int,
        overwrite: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(longSide in 1..2048 && output.endsWith(".png", true))
                val svg = SvgSanitizer.sanitize(bytes(resolve(root, path)).decodeToString())
                val image =
                    checkNotNull(svgRasterizer) { "SVG rasterizer unavailable on this platform" }
                        .render(svg, longSide)
                RasterOperations.dimensions(image.width, image.height)
                writeBytes(root, output, PngEncoder.encode(image), overwrite)
            }
        }

    override suspend fun renderSvgFrames(
        root: File,
        paths: List<String>,
        output: String,
        longSide: Int,
        columns: Int,
        overwrite: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            require(paths.size in 1..256 && longSide in 1..2048 && columns in 1..256)
            mutex.withLock {
                val renderer = checkNotNull(svgRasterizer) { "SVG rasterizer unavailable" }
                var pixels = 0L
                val frames =
                    paths.map { path ->
                        currentCoroutineContext().ensureActive()
                        require(path.endsWith(".svg", true))
                        val svg = SvgSanitizer.sanitize(bytes(resolve(root, path)).decodeToString())
                        val frame = renderer.render(svg, longSide)
                        RasterOperations.dimensions(frame.width, frame.height)
                        pixels += frame.pixels.size
                        require(pixels <= RasterOperations.MAX_PIXELS) {
                            "SVG frame set exceeds pixel budget"
                        }
                        frame
                    }
                val sheet = RasterOperations.join(frames, columns)
                currentCoroutineContext().ensureActive()
                require(output.endsWith(".png", true))
                writeBytes(root, output, PngEncoder.encode(sheet), overwrite)
            }
        }

    override suspend fun readPlan(root: File, path: String): SavedCreativePlan =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val data = bytes(resolve(root, path))
                val obj = Json.parseToJsonElement(data.decodeToString()).jsonObject
                require(obj["format"]?.jsonPrimitive?.content == "rustwright-creative-plan-v1")
                SavedCreativePlan(
                    CreativePlan(
                        obj.getValue("description").jsonPrimitive.content,
                        obj.getValue("image_prompt").jsonPrimitive.content,
                        obj.getValue("shots").jsonArray.map { it.jsonPrimitive.content },
                    ),
                    version(data),
                )
            }
        }

    override suspend fun savePlan(
        root: File,
        path: String,
        plan: CreativePlan,
        expectedVersion: String?,
    ): SavedCreativePlan =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(path.endsWith(".json", true))
                require(plan.description.isNotBlank() && plan.description.length <= 16000)
                require(plan.imagePrompt.isNotBlank() && plan.imagePrompt.length <= 32000)
                require(
                    plan.shots.size <= 24 &&
                        plan.shots.all { it.isNotBlank() && it.length <= 16000 }
                )
                val file = resolve(root, path)
                require(
                    if (file.exists())
                        expectedVersion != null && version(bytes(file)) == expectedVersion
                    else expectedVersion == null
                ) {
                    "Plan changed or exists; read and supply its version"
                }
                val data =
                    buildJsonObject {
                            put("format", "rustwright-creative-plan-v1")
                            put("description", plan.description)
                            put("image_prompt", plan.imagePrompt)
                            put("shots", JsonArray(plan.shots.map(::JsonPrimitive)))
                        }
                        .toString()
                        .encodeToByteArray()
                writeBytes(root, path, data, file.exists())
                SavedCreativePlan(plan, version(data))
            }
        }

    override suspend fun drawText(
        root: File,
        path: String,
        output: String,
        text: String,
        x: Int,
        y: Int,
        fontSize: Int,
        color: Int,
        overwrite: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            require(
                text.isNotEmpty() &&
                    text.length <= 5000 &&
                    fontSize in 1..512 &&
                    x in -8192..8192 &&
                    y in -8192..8192
            )
            val image = read(root, path)
            require(maxOf(image.width, image.height) <= 2048)
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val rgb = "#%06X".format(color and 0xffffff)
            val svg =
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${image.width} ${image.height}"><text x="$x" y="$y" font-size="$fontSize" font-family="sans-serif" fill="$rgb" fill-opacity="${Color.alpha(color)/255.0}">$escaped</text></svg>"""
            val rendered =
                checkNotNull(svgRasterizer) { "Text rasterizer unavailable" }
                    .render(SvgSanitizer.sanitize(svg), maxOf(image.width, image.height))
            val overlay =
                if (rendered.width == image.width && rendered.height == image.height) rendered
                else RgbaImage.scaledNearest(rendered, image.width, image.height)
            for (row in 0 until image.height) {
                currentCoroutineContext().ensureActive()
                for (column in 0 until image.width) {
                    val i = row * image.width + column
                    image.pixels[i] = RasterOperations.over(image.pixels[i], overlay.pixels[i])
                }
            }
            write(root, output, image, overwrite)
        }

    override suspend fun list(root: File): List<String> =
        withContext(Dispatchers.IO) {
            val base = root.canonicalFile
            require(base.isDirectory)
            val out = ArrayList<String>()
            Files.walk(base.toPath()).use { paths ->
                val it = paths.iterator()
                var visited = 0
                while (it.hasNext() && out.size < 500 && visited++ < 20_000) {
                    currentCoroutineContext().ensureActive()
                    val p = it.next()
                    if (
                        !Files.isSymbolicLink(p) &&
                            Files.isRegularFile(p) &&
                            p.toFile().extension.lowercase() in
                                setOf("png", "jpg", "jpeg", "webp", "svg", "rwpixel", "mkpx")
                    )
                        out += base.toPath().relativize(p).toString()
                }
            }
            out.sorted()
        }

    private suspend fun load(file: File): Cached {
        val data = bytes(file)
        val ver = version(data)
        documents[file.path]
            ?.takeIf { it.version == ver }
            ?.let {
                return it
            }
        val doc =
            if (file.extension.lowercase() == "mkpx") runInterruptible { MakapixCodec.decode(data) }
            else PixelDocumentCodec.decode(data)
        return Cached(ver, doc).also { cache(file, it) }
    }

    private fun cache(file: File, value: Cached) {
        documents.remove(file.path)
        documents[file.path] = value
        while (
            documents.size > 1 &&
                (documents.size > 4 ||
                    documents.values.sumOf { it.document.retainedPixels } > 12_582_912)
        ) documents.remove(documents.keys.first())
    }

    private suspend fun encode(doc: LayeredPixelDocument, path: String) =
        if (path.endsWith(".mkpx", true)) runInterruptible { MakapixCodec.encode(doc) }
        else PixelDocumentCodec.encode(doc)

    private fun info(c: Cached) =
        PixelDocumentInfo(
            c.document.width,
            c.document.height,
            c.version,
            c.document.activeLayer,
            c.document.layerNames,
            c.document.canUndo,
            c.document.canRedo,
            c.document.currentFrame,
            c.document.frameDurations,
            c.document.layerInfo,
            c.document.palettes,
            c.document.activePalette,
        )

    override suspend fun createDocument(
        root: File,
        path: String,
        width: Int,
        height: Int,
    ): PixelDocumentInfo =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(path.endsWith(".rwpixel", true) || path.endsWith(".mkpx", true))
                val doc = LayeredPixelDocument(width, height)
                val data = encode(doc, path)
                writeBytes(root, path, data, false)
                val c = Cached(version(data), doc)
                cache(resolve(root, path), c)
                info(c)
            }
        }

    override suspend fun importDocument(
        root: File,
        path: String,
        output: String,
    ): PixelDocumentInfo =
        withContext(Dispatchers.IO) {
            require(output.endsWith(".rwpixel", true) || output.endsWith(".mkpx", true))
            val raster =
                if (path.substringAfterLast('.').lowercase() !in setOf("rwpixel", "mkpx"))
                    read(root, path)
                else null
            mutex.withLock {
                val doc =
                    if (raster != null)
                        LayeredPixelDocument(raster.width, raster.height).apply {
                            restore(
                                LayeredPixelDocument.Snapshot(
                                    listOf(LayeredPixelDocument.Layer("Imported", raster.pixels)),
                                    0,
                                    0xff000000.toInt(),
                                    null,
                                )
                            )
                        }
                    else
                        load(resolve(root, path)).document.let { original ->
                            LayeredPixelDocument(original.width, original.height).apply {
                                restore(original.snapshot())
                            }
                        }
                val data = encode(doc, output)
                writeBytes(root, output, data, false)
                val cached = Cached(version(data), doc)
                cache(resolve(root, output), cached)
                info(cached)
            }
        }

    override suspend fun readAnimation(root: File, path: String): PixelAnimation =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cached = load(resolve(root, path))
                val doc = cached.document
                val frames =
                    List(doc.frameCount) { index ->
                        currentCoroutineContext().ensureActive()
                        doc.compositeFrame(index)
                    }
                PixelAnimation(frames, doc.frameDurations, cached.version)
            }
        }

    override suspend fun observeDocument(root: File, path: String): PixelDocumentInfo =
        withContext(Dispatchers.IO) { mutex.withLock { info(load(resolve(root, path))) } }

    override suspend fun apply(
        root: File,
        path: String,
        expectedVersion: String,
        actions: List<LayeredPixelDocument.Action>,
    ): PixelDocumentInfo =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = resolve(root, path)
                val original = load(file)
                require(original.version == expectedVersion) {
                    "Document changed; observe again before editing"
                }
                try {
                    val context = currentCoroutineContext()
                    original.document.apply(actions) { context.ensureActive() }
                    val data = encode(original.document, path)
                    currentCoroutineContext().ensureActive()
                    require(version(bytes(file)) == expectedVersion) {
                        "Document changed externally; observe again"
                    }
                    writeBytes(root, path, data, true)
                    val updated = Cached(version(data), original.document)
                    cache(file, updated)
                    info(updated)
                } catch (e: Exception) {
                    documents.remove(file.path)
                    throw e
                }
            }
        }
}
