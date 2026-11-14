package io.github.zenthxsin.rustwright.pic.api

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 创作模式（沿用 RustPic）。 */
enum class CreationMode { GENERAL, GAME_SPRITE }

/**
 * 一次生图请求。
 * @param spriteSize 贴图模式下的逻辑像素尺寸（如 64 → 64×64 像素图），通用模式忽略
 * @param references 参考图（改图 / 变体），为空则走纯文生图
 */
data class PicRequest(
    val prompt: String,
    val mode: CreationMode = CreationMode.GAME_SPRITE,
    val spriteSize: Int = 64,
    val count: Int = 1,
    val references: List<File> = emptyList(),
    /** 固定免费线路；不会因全局模型变化切换成付费。 */
    val free: Boolean = false,
    /** 每个会话与手动页分别保存未确认请求。 */
    val requestScope: String = "manual",
)

/** 图库里的一张图。贴图模式会同时产出原图和像素化后的 [pixel]。 */
data class PicImage(
    val id: String,
    val file: File,
    val prompt: String,
    val mode: CreationMode,
    val createdAt: Long,
    val pixel: File? = null,
)

/** 生图模块对外能力：助手、模组编辑都只依赖这个接口。 */
interface PicService {
    val images: StateFlow<List<PicImage>>

    /** 线路是否可用（已配置令牌与生图模型）。 */
    val ready: Boolean
    val freeReady: Boolean get() = false
    val selectedFree: Boolean get() = false
    /** 仅服务端确认可用时返回免费别名；不创建调用凭据。 */
    suspend fun freeOption(): String? = null
    fun pendingFree(scope: String = "manual"): PicRequest? = null
    suspend fun retryFree(scope: String = "manual"): List<PicImage> = error("没有待确认的免费图片请求")

    /** 发给生图服务前的最终提示词（含强制约束），供审批预览。 */
    fun finalPrompt(request: PicRequest): String

    suspend fun generate(request: PicRequest): List<PicImage>

    /** 把已有图片像素化成 [size]×[size] 的贴图。 */
    suspend fun pixelate(image: PicImage, size: Int): PicImage

    fun delete(image: PicImage)
}

/** 事件：生成了新图片。 */
data class ImagesGenerated(val images: List<PicImage>)

/** 事件：请求用像素编辑器打开一张 PNG（可来自图库或模组文件），保存时写回原文件。 */
data class EditPixelImage(val file: File)
