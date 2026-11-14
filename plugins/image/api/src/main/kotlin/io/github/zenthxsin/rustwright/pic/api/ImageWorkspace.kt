package io.github.zenthxsin.rustwright.pic.api

import io.github.zenthxsin.rustwright.pic.imaging.LayeredPixelDocument
import io.github.zenthxsin.rustwright.pic.imaging.RgbaImage
import java.io.File

/**
 * Explicit project roots make agent calls and future independent tool-mode pages share one backend.
 */
interface ImageWorkspace {
    suspend fun read(root: File, path: String): RgbaImage

    suspend fun write(root: File, path: String, image: RgbaImage, overwrite: Boolean = false)

    suspend fun saveSvg(root: File, path: String, svg: String, overwrite: Boolean = false)

    suspend fun renderSvg(
        root: File,
        path: String,
        output: String,
        longSide: Int,
        overwrite: Boolean = false,
    )

    suspend fun renderSvgFrames(
        root: File,
        paths: List<String>,
        output: String,
        longSide: Int,
        columns: Int,
        overwrite: Boolean = false,
    )

    suspend fun readPlan(root: File, path: String): SavedCreativePlan

    suspend fun savePlan(
        root: File,
        path: String,
        plan: CreativePlan,
        expectedVersion: String? = null,
    ): SavedCreativePlan

    suspend fun drawText(
        root: File,
        path: String,
        output: String,
        text: String,
        x: Int,
        y: Int,
        fontSize: Int,
        color: Int,
        overwrite: Boolean = false,
    )

    suspend fun list(root: File): List<String>

    suspend fun createDocument(root: File, path: String, width: Int, height: Int): PixelDocumentInfo

    suspend fun importDocument(root: File, path: String, output: String): PixelDocumentInfo

    suspend fun readAnimation(root: File, path: String): PixelAnimation

    suspend fun observeDocument(root: File, path: String): PixelDocumentInfo

    suspend fun apply(
        root: File,
        path: String,
        expectedVersion: String,
        actions: List<LayeredPixelDocument.Action>,
    ): PixelDocumentInfo
}

data class PixelDocumentInfo(
    val width: Int,
    val height: Int,
    val version: String,
    val activeLayer: Int,
    val layers: List<String>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val currentFrame: Int = 0,
    val frameDurations: List<Int> = listOf(100),
    val layerDetails: List<LayeredPixelDocument.LayerInfo> = emptyList(),
    val palettes: List<LayeredPixelDocument.Palette> = emptyList(),
    val activePalette: Int = 0,
)

data class PixelAnimation(
    val frames: List<RgbaImage>,
    val durationsMillis: List<Int>,
    val version: String,
)

/**
 * Authored creative requirements; no second planner rewrites saved prompts or frame descriptions.
 */
data class CreativePlan(
    val description: String,
    val imagePrompt: String,
    val shots: List<String> = emptyList(),
)

data class SavedCreativePlan(val plan: CreativePlan, val version: String)
