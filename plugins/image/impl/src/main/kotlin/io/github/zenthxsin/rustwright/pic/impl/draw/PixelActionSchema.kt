package io.github.zenthxsin.rustwright.pic.impl.draw

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

private fun integer(minimum: Int, maximum: Int) = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun choice(vararg values: String) = buildJsonObject {
    put("type", "string")
    put("enum", strings(*values))
}

private val pointSchema = buildJsonObject {
    put("type", "array")
    put("items", integer(0, 1023))
    put("minItems", 2)
    put("maxItems", 2)
    put(
        "description",
        "[x,y], integer pixel coordinates, origin at top-left; must be inside the observed canvas.",
    )
}

private val layerNameSchema = buildJsonObject {
    put("type", "string")
    put("minLength", 1)
    put("maxLength", 64)
}

private fun actionSchema(
    type: String,
    description: String,
    fields: JsonObject = JsonObject(emptyMap()),
    optional: Set<String> = emptySet(),
) = buildJsonObject {
    put("type", "object")
    put("description", description)
    put("properties", JsonObject(mapOf("type" to choice(type)) + fields))
    put(
        "required",
        JsonArray((listOf("type") + fields.keys.filterNot { it in optional }).map(::JsonPrimitive)),
    )
    put("additionalProperties", false)
}

internal val pixelActionSchemas: Map<String, JsonObject> =
    linkedMapOf(
            "set_color" to
                actionSchema(
                    "set_color",
                    "Set foreground color for following actions; RGBA, not ARGB.",
                    buildJsonObject {
                        put(
                            "color",
                            buildJsonObject {
                                put("type", "string")
                                put("pattern", "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
                            },
                        )
                    },
                ),
            "stroke" to
                actionSchema(
                    "stroke",
                    "Draw a connected stroke, or replace the rectangular selection using inclusive endpoints. Pencil is always 1 px.",
                    buildJsonObject {
                        put("tool", choice("pencil", "brush", "eraser", "rectangle_selection"))
                        put(
                            "points",
                            buildJsonObject {
                                put("type", "array")
                                put("items", pointSchema)
                                put("minItems", 1)
                                put("maxItems", 4096)
                            },
                        )
                        put("brushSize", integer(1, 64))
                    },
                    setOf("brushSize"),
                ),
            "shape" to
                actionSchema(
                    "shape",
                    "Draw on the active layer, clipped by any selection; start/end are inclusive.",
                    buildJsonObject {
                        put("tool", choice("line", "rectangle", "ellipse"))
                        put("start", pointSchema)
                        put("end", pointSchema)
                        put("filled", buildJsonObject { put("type", "boolean") })
                        put("lineWidth", integer(1, 64))
                    },
                    setOf("filled", "lineWidth"),
                ),
            "flood_fill" to
                actionSchema(
                    "flood_fill",
                    "Fill the connected color region at point with foreground color on the active layer, respecting the selection.",
                    buildJsonObject { put("point", pointSchema) },
                ),
            "select_all" to
                actionSchema(
                    "select_all",
                    "Select the entire canvas explicitly before a whole-layer edit.",
                ),
            "invert_selection" to
                actionSchema(
                    "invert_selection",
                    "Invert the selection mask inside the canvas; does not change pixels.",
                ),
            "clear_selection" to
                actionSchema("clear_selection", "Deselect only; does not erase pixels."),
            "fill_selection" to
                actionSchema(
                    "fill_selection",
                    "Fill selected pixels with foreground color. Requires nonempty selection and visible, unlocked active layer.",
                ),
            "clear_pixels" to
                actionSchema(
                    "clear_pixels",
                    "Erase selected pixels to transparent; requires nonempty selection and visible, unlocked active layer.",
                ),
            "flip_selection" to
                actionSchema(
                    "flip_selection",
                    "Mirror selected pixels within their bounds; requires nonempty selection and visible, unlocked active layer.",
                    buildJsonObject { put("axis", choice("horizontal", "vertical")) },
                ),
            "select_layer" to
                actionSchema(
                    "select_layer",
                    "Select a zero-based layer index returned by observation; never guess indices.",
                    buildJsonObject { put("index", integer(0, 63)) },
                ),
            "add_layer" to
                actionSchema(
                    "add_layer",
                    "Create and select a new layer, naming it by purpose. Observe the result for its index.",
                    buildJsonObject { put("name", layerNameSchema) },
                ),
            "duplicate_layer" to
                actionSchema(
                    "duplicate_layer",
                    "Duplicate a layer to preserve the original. Observe afterward to resolve the resulting active layer.",
                    buildJsonObject { put("index", integer(0, 63)) },
                ),
            "rename_layer" to
                actionSchema(
                    "rename_layer",
                    "Rename a layer without changing pixels.",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put("name", layerNameSchema)
                    },
                ),
            "set_layer_visible" to
                actionSchema(
                    "set_layer_visible",
                    "Show/hide a layer.",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put("enabled", buildJsonObject { put("type", "boolean") })
                    },
                ),
            "set_layer_locked" to
                actionSchema(
                    "set_layer_locked",
                    "Lock/unlock a layer.",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put("enabled", buildJsonObject { put("type", "boolean") })
                    },
                ),
            "delete_layer" to
                actionSchema(
                    "delete_layer",
                    "Remove a layer; at least one must remain.",
                    buildJsonObject { put("index", integer(0, 63)) },
                ),
            "move_layer" to
                actionSchema(
                    "move_layer",
                    "Move a layer to target index (bottom to top).",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put("target", integer(0, 63))
                    },
                ),
            "undo" to
                actionSchema(
                    "undo",
                    "Undo one document batch, not an entire batch; re-observe afterward.",
                ),
            "redo" to actionSchema("redo", "Redo one document batch; re-observe afterward."),
        )
        .apply {
            put(
                "polygon",
                actionSchema(
                    "polygon",
                    "Draw closed polygon, integer vertices",
                    buildJsonObject {
                        put(
                            "points",
                            buildJsonObject {
                                put("type", "array")
                                put("items", pointSchema)
                                put("minItems", 3)
                                put("maxItems", 4096)
                            },
                        )
                        put("filled", buildJsonObject { put("type", "boolean") })
                        put("lineWidth", integer(1, 64))
                    },
                    setOf("filled", "lineWidth"),
                ),
            )
            val indexed =
                setOf(
                    "select_frame",
                    "delete_frame",
                    "duplicate_frame",
                    "delete_palette",
                    "duplicate_palette",
                    "clear_palette",
                    "select_palette",
                    "palette_delete",
                    "palette_duplicate",
                    "merge_layer_down",
                )
            indexed.forEach { type ->
                put(
                    type,
                    actionSchema(type, type, buildJsonObject { put("index", integer(0, 255)) }),
                )
            }
            put(
                "add_frame",
                actionSchema(
                    "add_frame",
                    "Append blank frame",
                    buildJsonObject { put("duration_ms", integer(10, 60000)) },
                    setOf("duration_ms"),
                ),
            )
            put(
                "set_frame_duration",
                actionSchema(
                    "set_frame_duration",
                    "Set frame timing",
                    buildJsonObject {
                        put("index", integer(0, 255))
                        put("duration_ms", integer(10, 60000))
                    },
                ),
            )
            setOf("move_frame", "move_palette", "palette_swap").forEach { type ->
                put(
                    type,
                    actionSchema(
                        type,
                        type,
                        buildJsonObject {
                            put("index", integer(0, 255))
                            put("target", integer(0, 255))
                        },
                    ),
                )
            }
            setOf("add_palette", "rename_palette", "palette_name").forEach { type ->
                put(
                    type,
                    actionSchema(
                        type,
                        type,
                        buildJsonObject {
                            put("name", layerNameSchema)
                            if (type != "add_palette") put("index", integer(0, 255))
                        },
                    ),
                )
            }
            setOf("palette_add", "palette_edit").forEach { type ->
                put(
                    type,
                    actionSchema(
                        type,
                        type,
                        buildJsonObject {
                            put(
                                "color",
                                buildJsonObject {
                                    put("type", "string")
                                    put("pattern", "^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
                                },
                            )
                            if (type == "palette_edit") put("index", integer(0, 255))
                        },
                    ),
                )
            }
            put("palette_sort", actionSchema("palette_sort", "Sort active palette by luminance"))
            put(
                "set_layer_opacity",
                actionSchema(
                    "set_layer_opacity",
                    "Set layer opacity",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put("opacity", integer(0, 255))
                    },
                ),
            )
            put(
                "set_layer_blend",
                actionSchema(
                    "set_layer_blend",
                    "Set compositing blend mode",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put(
                            "blend",
                            choice(
                                *io.github.zenthxsin.rustwright.pic.imaging.PixelBlend.entries
                                    .map { it.name }
                                    .toTypedArray()
                            ),
                        )
                    },
                ),
            )
            put(
                "nudge_layer",
                actionSchema(
                    "nudge_layer",
                    "Translate pixels, clipping to canvas",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put("dx", integer(-1024, 1024))
                        put("dy", integer(-1024, 1024))
                    },
                ),
            )
            put(
                "duplicate_layer_to_frames",
                actionSchema(
                    "duplicate_layer_to_frames",
                    "Copy layer to listed frame indices",
                    buildJsonObject {
                        put("index", integer(0, 63))
                        put(
                            "targets",
                            buildJsonObject {
                                put("type", "array")
                                put("items", integer(0, 255))
                                put("minItems", 1)
                                put("maxItems", 256)
                            },
                        )
                    },
                ),
            )
        }
