package io.github.zenthxsin.rustwright.pic.impl.draw

import io.github.zenthxsin.rustwright.core.agent.*
import io.github.zenthxsin.rustwright.pic.api.*
import io.github.zenthxsin.rustwright.pic.imaging.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.number(key: String, default: Int? = null) =
    this[key]?.jsonPrimitive?.intOrNull ?: default ?: error("Missing integer $key")

private fun JsonObject.required(key: String) =
    string(key)?.takeIf { it.isNotBlank() } ?: error("Missing $key")

private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false

private fun info(d: PixelDocumentInfo) =
    buildJsonObject {
            put("width", d.width)
            put("height", d.height)
            put("version", d.version)
            put("active_layer", d.activeLayer)
            put("can_undo", d.canUndo)
            put("can_redo", d.canRedo)
            put("current_frame", d.currentFrame)
            put("active_palette", d.activePalette)
            put("frame_durations_ms", JsonArray(d.frameDurations.map(::JsonPrimitive)))
            putJsonArray("layer_details") {
                d.layerDetails.forEach { l ->
                    addJsonObject {
                        put("name", l.name)
                        put("visible", l.visible)
                        put("locked", l.locked)
                        put("opacity", l.opacity)
                        put("blend", l.blend.name)
                    }
                }
            }
            putJsonArray("palettes") {
                d.palettes.forEachIndexed { index, p ->
                    addJsonObject {
                        put("index", index)
                        put("name", p.name)
                        putJsonArray("colors") {
                            p.colors.forEach { c ->
                                addJsonObject {
                                    put("color", RasterOperations.hex(c.color))
                                    put("name", c.name?.let(::JsonPrimitive) ?: JsonNull)
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("layers") {
                d.layers.forEachIndexed { i, name ->
                    addJsonObject {
                        put("index", i)
                        put("name", name)
                    }
                }
            }
        }
        .toString()

/** Catalog uses explicit inputs/outputs. No UI selection or global 'active image' is consulted. */
fun projectRasterTools(workspace: ImageWorkspace): List<Tool> {
    fun tool(
        name: String,
        description: String,
        write: Boolean = false,
        required: List<String> = listOf("path"),
        body: suspend (ToolProject, JsonObject) -> String,
    ) =
        object : Tool(), ProjectAware, PathAware {
            override val name = name
            override val description = description
            override val risk = if (write) ToolRisk.WRITE else ToolRisk.READ
            override val parameters = buildJsonObject {
                put("type", "object")
                val allProperties = buildJsonObject {
                    for (key in
                        listOf(
                            "path",
                            "output",
                            "other",
                            "version",
                            "operation",
                            "color",
                            "svg",
                            "text",
                            "description",
                            "image_prompt",
                        )) putJsonObject(key) { put("type", "string") }
                    putJsonObject("amount") {
                        put("type", "number")
                        put(
                            "description",
                            "brightness/contrast/saturation -100..100; hue -180..180; rotation degrees; binarize 0..255; blur radius 0..32; sharpen 0..100; transparent tolerance 0..255",
                        )
                    }
                    for (key in
                        listOf(
                            "width",
                            "font_size",
                            "height",
                            "x",
                            "y",
                            "columns",
                            "rows",
                            "spacing",
                            "turns",
                            "limit",
                        )) putJsonObject(key) { put("type", "integer") }
                    putJsonObject("overwrite") {
                        put("type", "boolean")
                        put("description", "Default false; explicit true allows replacing output")
                    }
                    putJsonObject("shots") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("minItems", 1)
                        put("maxItems", 24)
                    }
                    putJsonObject("paths") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("maxItems", 256)
                    }
                    putJsonObject("palette") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("maxItems", 256)
                    }
                    putJsonObject("actions") {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 128)
                        putJsonObject("items") {
                            putJsonArray("oneOf") { pixelActionSchemas.values.forEach(::add) }
                        }
                    }
                }
                val optional =
                    when (name) {
                        "inspect_image" -> setOf("limit")
                        "draw_text_image" -> setOf("color", "overwrite")
                        "draw_image",
                        "generate_svg",
                        "render_svg",
                        "extract_image_frame" -> setOf("overwrite")
                        "update_effect_description",
                        "generate_storyboard" -> setOf("version")
                        "export_pixel_animation" -> setOf("columns", "overwrite")
                        "join_image_frames" -> setOf("spacing", "overwrite")
                        "render_svg_frames" -> setOf("overwrite")
                        "edit_image" ->
                            setOf(
                                "width",
                                "height",
                                "x",
                                "y",
                                "turns",
                                "amount",
                                "color",
                                "palette",
                                "overwrite",
                            )
                        else -> emptySet()
                    }
                put(
                    "properties",
                    JsonObject(allProperties.filterKeys { it in required || it in optional }),
                )
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                put("additionalProperties", false)
            }

            override fun touchedPaths(args: JsonObject) =
                if (!write) emptyList()
                else listOfNotNull(args.string("output") ?: args.string("path"))

            override suspend fun execute(userId: String, args: JsonObject) =
                ToolResult.failure("project_required", "A conversation project is required")

            override suspend fun executeInProject(
                userId: String,
                args: JsonObject,
                project: ToolProject,
            ): ToolResult =
                withContext(Dispatchers.IO) {
                    try {
                        currentCoroutineContext().ensureActive()
                        ToolResult(body(project, args))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolResult.failure(
                            "image_operation_failed",
                            e.message ?: "Image operation failed",
                        )
                    }
                }
        }
    return listOf(
        tool(
            "render_svg_frames",
            "Validate and rasterize ordered project SVG paths into one PNG animation sheet. width is per-frame long side; columns required. No partial output is saved if any frame fails. Use native model to author/revise SVG sources.",
            true,
            listOf("paths", "output", "width", "columns"),
        ) { p, a ->
            workspace.renderSvgFrames(
                p.directory,
                a.getValue("paths").jsonArray.map { it.jsonPrimitive.content },
                a.required("output"),
                a.number("width"),
                a.number("columns"),
                a.flag("overwrite"),
            )
            "Rendered ${a.getValue("paths").jsonArray.size} ordered SVG frames to ${a.required("output")}"
        },
        tool(
            "import_pixel_document",
            "Import raster as a layered document, or convert between .rwpixel and .mkpx preserving frames/layers/palettes. Output must not exist.",
            true,
            listOf("path", "output"),
        ) { p, a ->
            info(workspace.importDocument(p.directory, a.required("path"), a.required("output")))
        },
        tool(
            "draw_text_image",
            "Draw Unicode text using platform font rendering onto project image (long side <=2048), save PNG. x/y is text baseline in pixels, font_size 1..512, color #RRGGBB[AA].",
            true,
            listOf("path", "output", "text", "x", "y", "font_size"),
        ) { p, a ->
            workspace.drawText(
                p.directory,
                a.required("path"),
                a.required("output"),
                a.required("text"),
                a.number("x"),
                a.number("y"),
                a.number("font_size"),
                RasterOperations.color(a.string("color") ?: "#000000"),
                a.flag("overwrite"),
            )
            "Saved text overlay ${a.required("output")}"
        },
        tool(
            "draw_image",
            "Apply deterministic local pixel drawing actions onto a raster (up to 1024x1024), save PNG output. Uses same RGBA/selection/shape/polygon/brush/fill actions as apply_pixel_actions, with no image-model cost.",
            true,
            listOf("path", "output", "actions"),
        ) { p, a ->
            val source = workspace.read(p.directory, a.required("path"))
            val context = currentCoroutineContext()
            val output =
                RasterDrawing.apply(
                    source,
                    a.getValue("actions").jsonArray.map { parseAction(it.jsonObject) },
                ) {
                    context.ensureActive()
                }
            workspace.write(p.directory, a.required("output"), output, a.flag("overwrite"))
            "Saved ${a.required("output")}"
        },
        tool(
            "read_creative_plan",
            "Read saved creative description, exact image prompt, ordered storyboard shots and version from a project JSON plan.",
        ) { p, a ->
            planJson(workspace.readPlan(p.directory, a.required("path")))
        },
        tool(
            "update_effect_description",
            "Persist agreed description and complete image_prompt as project JSON. Saves a draft only, never generates images. Existing plan requires version and retains its storyboard shots.",
            true,
            listOf("path", "description", "image_prompt"),
        ) { p, a ->
            val previous =
                a.string("version")
                    ?.let { workspace.readPlan(p.directory, a.required("path")).plan.shots }
                    .orEmpty()
            planJson(
                workspace.savePlan(
                    p.directory,
                    a.required("path"),
                    CreativePlan(a.required("description"), a.required("image_prompt"), previous),
                    a.string("version"),
                )
            )
        },
        tool(
            "generate_storyboard",
            "Persist an authored storyboard plan: description, shared image_prompt and ordered shots (1-24). No image is generated. Read its version and use generate_storyboard_frame to generate selected frames without rewriting prompts.",
            true,
            listOf("path", "description", "image_prompt", "shots"),
        ) { p, a ->
            val shots = a.getValue("shots").jsonArray.map { it.jsonPrimitive.content }
            require(shots.isNotEmpty())
            planJson(
                workspace.savePlan(
                    p.directory,
                    a.required("path"),
                    CreativePlan(a.required("description"), a.required("image_prompt"), shots),
                    a.string("version"),
                )
            )
        },
        tool(
            "export_pixel_animation",
            "Export all .rwpixel frames as a PNG sprite sheet, preserving frame order. Returns per-frame durations; columns optional (default horizontal row).",
            true,
            listOf("path", "output"),
        ) { p, a ->
            val animation = workspace.readAnimation(p.directory, a.required("path"))
            val image =
                RasterOperations.join(animation.frames, a.number("columns", animation.frames.size))
            workspace.write(p.directory, a.required("output"), image, a.flag("overwrite"))
            buildJsonObject {
                    put("path", a.required("output"))
                    put("version", animation.version)
                    put("frames", animation.frames.size)
                    put("duration_ms", JsonArray(animation.durationsMillis.map(::JsonPrimitive)))
                }
                .toString()
        },
        tool(
            "render_svg",
            "Rasterize a validated project SVG into a PNG, preserving viewBox aspect. width is target long side (1-2048); output is project-relative PNG.",
            true,
            listOf("path", "output", "width"),
        ) { p, a ->
            workspace.renderSvg(
                p.directory,
                a.required("path"),
                a.required("output"),
                a.number("width"),
                a.flag("overwrite"),
            )
            "Rendered SVG to ${a.required("output")}"
        },
        tool(
            "generate_svg",
            "Validate and save complete SVG markup to a project .svg file. Removes scripts, events and external resources. Use render_svg to rasterize the saved vector into PNG for inspection.",
            true,
            listOf("path", "svg"),
        ) { p, a ->
            workspace.saveSvg(
                p.directory,
                a.required("path"),
                a.required("svg"),
                a.flag("overwrite"),
            )
            "Saved validated SVG ${a.required("path")}"
        },
        tool(
            "list_image_assets",
            "List up to 500 project image and .rwpixel files. All paths are relative to this conversation's project.",
            required = emptyList(),
        ) { p, _ ->
            JsonArray(workspace.list(p.directory).map(::JsonPrimitive)).toString()
        },
        tool(
            "inspect_image",
            "Read image width, height, transparency and frequent RGBA colors; path may be PNG/JPEG/WebP or .rwpixel. This is pixel analysis, not semantic vision.",
        ) { p, a ->
            val img = workspace.read(p.directory, a.required("path"))
            buildJsonObject {
                    put("width", img.width)
                    put("height", img.height)
                    put("transparent_pixels", img.pixels.count { Color.alpha(it) == 0 })
                    put("inspection", ImageInspection.analyze(img))
                    val quality = PixelArtQualityAnalyzer.analyze(img)
                    putJsonObject("quality") {
                        put("background_purity", quality.backgroundPurity)
                        put("subject_coverage", quality.subjectCoverage)
                        put("color_count", quality.colorCount)
                        put("dominant_color_ratio", quality.dominantColorRatio)
                    }
                    putJsonArray("palette") {
                        RasterOperations.palette(img, a.number("limit", 32)).forEach { (c, n) ->
                            addJsonObject {
                                put("color", RasterOperations.hex(c))
                                put("count", n)
                            }
                        }
                    }
                }
                .toString()
        },
        tool(
            "read_image_region",
            "Read exact RGBA pixels in a bounded rectangle, row-major. Requires x/y/width/height; maximum 4096 pixels.",
            required = listOf("path", "x", "y", "width", "height"),
        ) { p, a ->
            val w = a.number("width")
            val h = a.number("height")
            require(w.toLong() * h in 1..4096)
            val img =
                RasterOperations.crop(
                    workspace.read(p.directory, a.required("path")),
                    a.number("x"),
                    a.number("y"),
                    w,
                    h,
                )
            buildJsonObject {
                    put("width", w)
                    put("height", h)
                    putJsonArray("pixels") {
                        img.pixels.forEach { add(JsonPrimitive(RasterOperations.hex(it))) }
                    }
                }
                .toString()
        },
        tool(
            "compare_image_assets",
            "Compare equal-size project images: changed pixel count and absolute RGBA error; other is the second path.",
            required = listOf("path", "other"),
        ) { p, a ->
            val d =
                RasterOperations.compare(
                    workspace.read(p.directory, a.required("path")),
                    workspace.read(p.directory, a.required("other")),
                )
            buildJsonObject {
                    put("changed_pixels", d.changedPixels)
                    put("mean_absolute_channel_error", d.meanAbsoluteChannelError)
                    put("max_channel_error", d.maxChannelError)
                }
                .toString()
        },
        tool(
            "edit_image",
            "Local deterministic raster edit. operation: crop (x,y,width,height), resize/pixelate (width,height), rotate (clockwise quarter turns), flip_horizontal, flip_vertical, quantize (palette of #RRGGBB[AA]), brightness/contrast/saturation/hue/grayscale/binarize/blur/sharpen/transparent (amount parameter), or export (including .rwpixel to PNG). output must be .png; source preserved unless overwrite explicitly chosen.",
            true,
            listOf("path", "output", "operation"),
        ) { p, a ->
            val source = workspace.read(p.directory, a.required("path"))
            val context = currentCoroutineContext()
            val amount = a["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            require(amount.isFinite())
            val out =
                when (a.required("operation")) {
                    "crop" ->
                        RasterOperations.crop(
                            source,
                            a.number("x"),
                            a.number("y"),
                            a.number("width"),
                            a.number("height"),
                        )
                    "resize" -> {
                        val w = a.number("width")
                        val h = a.number("height")
                        RasterOperations.dimensions(w, h)
                        RgbaImage.scaledNearest(source, w, h)
                    }
                    "pixelate" -> {
                        val width = a.number("width")
                        val height = a.number("height")
                        require(width in 1..512 && height in 1..512)
                        // Bound the legacy multi-pass processor before invoking it.
                        require(source.pixels.size <= 1_048_576) {
                            "Pixelation input exceeds 1M pixels; resize first"
                        }
                        context.ensureActive()
                        PixelArtProcessor.process(
                                source,
                                PixelProcessingParams(outputWidth = width, outputHeight = height),
                            )
                            .also { context.ensureActive() }
                    }
                    "rotate" ->
                        if (a.containsKey("amount"))
                            RasterFilters.rotate(source, amount) { context.ensureActive() }
                        else RasterOperations.rotate(source, a.number("turns", 1))
                    "brightness",
                    "contrast",
                    "saturation",
                    "hue",
                    "grayscale",
                    "binarize" ->
                        RasterFilters.adjust(
                            source,
                            a.required("operation"),
                            if (a.required("operation") == "binarize" && !a.containsKey("amount"))
                                128.0
                            else amount,
                        ) {
                            context.ensureActive()
                        }
                    "blur" -> {
                        require(amount % 1 == 0.0)
                        RasterFilters.blur(source, amount.toInt()) { context.ensureActive() }
                    }
                    "sharpen" -> RasterFilters.sharpen(source, amount) { context.ensureActive() }
                    "transparent" ->
                        RasterFilters.transparent(
                            source,
                            RasterOperations.color(a.string("color") ?: "#FFFFFF"),
                            if (a.containsKey("amount")) amount.toInt() else 24,
                        ) {
                            context.ensureActive()
                        }
                    "flip_horizontal" -> RasterOperations.flip(source, true)
                    "flip_vertical" -> RasterOperations.flip(source, false)
                    "quantize" ->
                        RasterOperations.quantize(
                            source,
                            a.getValue("palette").jsonArray.map {
                                RasterOperations.color(it.jsonPrimitive.content)
                            },
                        ) {
                            context.ensureActive()
                        }
                    "export" -> source
                    else -> error("Unknown image operation")
                }
            workspace.write(p.directory, a.required("output"), out, a.flag("overwrite"))
            "Saved ${a.required("output")} (${out.width}x${out.height})"
        },
        tool(
            "join_image_frames",
            "Join project images in paths (ordered, max 256) into a PNG sprite sheet. columns required; optional transparent spacing in pixels. Unequal frames are top-left aligned in maximum-size cells.",
            true,
            listOf("paths", "output", "columns"),
        ) { p, a ->
            val paths = a.getValue("paths").jsonArray
            require(paths.size in 1..256)
            var totalPixels = 0L
            val frames =
                paths.map {
                    workspace.read(p.directory, it.jsonPrimitive.content).also { image ->
                        totalPixels += image.pixels.size
                        require(totalPixels <= RasterOperations.MAX_PIXELS)
                    }
                }
            require(frames.sumOf { it.pixels.size.toLong() } <= RasterOperations.MAX_PIXELS)
            val image = RasterOperations.join(frames, a.number("columns"), a.number("spacing", 0))
            workspace.write(p.directory, a.required("output"), image, a.flag("overwrite"))
            "Saved ${a.required("output")}, ${frames.size} frames"
        },
        tool(
            "extract_image_frame",
            "Extract a single row-major frame from a sprite sheet: columns/rows define grid, x/y are zero-based column/row. Sheet dimensions must divide exactly. output is PNG.",
            true,
            listOf("path", "output", "columns", "rows", "x", "y"),
        ) { p, a ->
            val img = workspace.read(p.directory, a.required("path"))
            val cols = a.number("columns")
            val rows = a.number("rows")
            require(
                cols in 1..256 && rows in 1..256 && img.width % cols == 0 && img.height % rows == 0
            )
            val x = a.number("x")
            val y = a.number("y")
            require(x in 0 until cols && y in 0 until rows)
            val out =
                RasterOperations.crop(
                    img,
                    x * (img.width / cols),
                    y * (img.height / rows),
                    img.width / cols,
                    img.height / rows,
                )
            workspace.write(p.directory, a.required("output"), out, a.flag("overwrite"))
            "Saved ${a.required("output")}"
        },
        tool(
            "create_pixel_document",
            "Create transparent layered .rwpixel or .mkpx document; width/height 1-1024. Output is never silently overwritten. Use apply_pixel_actions with returned version then edit_image export to PNG.",
            true,
            listOf("path", "width", "height"),
        ) { p, a ->
            info(
                workspace.createDocument(
                    p.directory,
                    a.required("path"),
                    a.number("width"),
                    a.number("height"),
                )
            )
        },
        tool(
            "observe_pixel_document",
            "Observe .rwpixel/.mkpx layers, active index and version before editing. Undo/redo survives only while document is cached in this process; file contents always persist.",
        ) { p, a ->
            info(workspace.observeDocument(p.directory, a.required("path")))
        },
        tool(
            "apply_pixel_actions",
            "Apply an atomic batch of up to 128 structured pixel actions to .rwpixel or .mkpx. Supply version from observation to reject stale edits. RGBA colors, inclusive integer coordinates. Undo/redo must be the only batch action. Layers, selection, drawing and flood fill supported.",
            true,
            listOf("path", "version", "actions"),
        ) { p, a ->
            val actions = a.getValue("actions").jsonArray.map { parseAction(it.jsonObject) }
            info(workspace.apply(p.directory, a.required("path"), a.required("version"), actions))
        },
    )
}

private fun parseAction(a: JsonObject): LayeredPixelDocument.Action {
    fun point(e: JsonElement): LayeredPixelDocument.Point {
        val p = e.jsonArray
        require(p.size == 2)
        return LayeredPixelDocument.Point(p[0].jsonPrimitive.int, p[1].jsonPrimitive.int)
    }
    val type = a.required("type")
    return when (type) {
        "stroke" -> {
            val tool = a.required("tool")
            require(tool in setOf("pencil", "brush", "eraser", "rectangle_selection"))
            LayeredPixelDocument.Action(
                tool,
                a.getValue("points").jsonArray.map(::point),
                size = if (tool == "pencil") 1 else a.number("brushSize", 1),
            )
        }
        "shape" -> {
            val tool = a.required("tool")
            require(tool in setOf("line", "rectangle", "ellipse"))
            LayeredPixelDocument.Action(
                tool,
                listOf(point(a.getValue("start")), point(a.getValue("end"))),
                size = a.number("lineWidth", 1),
                filled = a.flag("filled"),
            )
        }
        "polygon" ->
            LayeredPixelDocument.Action(
                type,
                a.getValue("points").jsonArray.map(::point),
                size = a.number("lineWidth", 1),
                filled = a.flag("filled"),
            )
        "flood_fill" -> LayeredPixelDocument.Action(type, listOf(point(a.getValue("point"))))
        "set_color" ->
            LayeredPixelDocument.Action(type, color = RasterOperations.color(a.required("color")))
        else -> {
            require(type in pixelActionSchemas)
            LayeredPixelDocument.Action(
                type,
                index = a.number("index", 0),
                name = a.string("name") ?: "Layer",
                axis = a.string("axis") ?: "horizontal",
                enabled = a["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                target = a.number("target", 0),
                color = a.string("color")?.let(RasterOperations::color) ?: 0xff000000.toInt(),
                opacity = a.number("opacity", 255),
                blend = a.string("blend")?.let(PixelBlend::valueOf) ?: PixelBlend.NORMAL,
                durationMillis = a.number("duration_ms", 100),
                dx = a.number("dx", 0),
                dy = a.number("dy", 0),
                targets = a["targets"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
            )
        }
    }
}

private fun planJson(saved: SavedCreativePlan) =
    buildJsonObject {
            put("version", saved.version)
            put("description", saved.plan.description)
            put("image_prompt", saved.plan.imagePrompt)
            put("shots", JsonArray(saved.plan.shots.map(::JsonPrimitive)))
        }
        .toString()
