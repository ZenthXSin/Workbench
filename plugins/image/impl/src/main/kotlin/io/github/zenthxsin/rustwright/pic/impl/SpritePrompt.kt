package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.pic.api.CreationMode
import io.github.zenthxsin.rustwright.pic.api.PicRequest

/**
 * 在用户确认的提示词上追加强制约束（移植自 RustPic GenerationPromptBuilder）：
 * 贴图模式要求正上方俯视、纯色白底、严格逻辑像素网格与留白；人物朝向 / 持物另加约束；改图要求真正修改。
 * 只做确定性的文本拼接，不再调用第二个模型改写。
 */
object SpritePrompt {
    const val CANVAS = 1024

    data class Grid(val output: Int, val block: Int, val crop: Int, val margin: Int, val canvas: Int)

    fun grid(output: Int): Grid {
        val canvas = maxOf(CANVAS, output)
        val block = (canvas / output).coerceAtLeast(1)
        return Grid(output, block, block * output, block * 3, canvas)
    }

    fun build(request: PicRequest): String {
        val clean = request.prompt.trim()
        val constraints = buildList {
            if (request.mode == CreationMode.GAME_SPRITE) {
                add(spriteConstraints(grid(request.spriteSize)))
                characterConstraints(clean)?.let(::add)
            }
            if (request.references.isNotEmpty()) add(EDIT_CONSTRAINT)
        }
        if (constraints.isEmpty()) return clean
        return "$clean\n\nMandatory generation constraints, which override any conflicting wording: ${constraints.joinToString(" ")}"
    }

    /** 请求尺寸：贴图固定 1024 方形画布；通用模式也用 1024 方形（后续可按比例选）。 */
    fun requestSize(request: PicRequest): String = "${CANVAS}x$CANVAS"

    private fun spriteConstraints(g: Grid) = """
        Render the planned subject as a 2D game sprite readable at normal RTS gameplay zoom. Preserve the reviewed design, art style, palette and functional relationships; do not add weapons, movement systems, team markings or mechanical parts absent from the plan. For directional assets, point the default forward or firing direction toward the top edge unless the user's written request specifies another direction.
        One complete game asset centered on an opaque solid background, defaulting to pure white #FFFFFF. Background must be a single opaque solid RGB color only; never use transparent, alpha, checkerboard, see-through, cutout, or multi-color backgrounds.
        Unless the user explicitly requests a different camera angle or perspective, use a strict top-down orthographic view: place the camera directly overhead at a true 90-degree nadir angle, looking perfectly straight down with no horizontal component. Under this default view, do not use a three-quarter view, elevated perspective, high-angle view, isometric view, or semi-top-down view, and never show the front, face, chest, vertical sides, horizon-facing surfaces, or eye-level features of the subject. Upward-facing slopes and bevels visible from directly above may show stepped shading without tilting the camera. This default camera rule applies especially to people, humanoids, creatures, and characters: depict only the forms visible from directly above, such as the top of the head, shoulders, and upward-facing surfaces; never tilt the character toward the viewer or use a conventional character-illustration pose. A reference image alone does not count as an explicit request for another perspective. Follow another perspective only when the user's written request clearly asks for it.
        The ${g.canvas}x${g.canvas} canvas is a strict ${g.output}x${g.output} logical pixel grid: every logical pixel occupies exactly ${g.block}x${g.block} real pixels and all ${g.block * g.block} pixels in that cell use one identical solid RGB color. Keep hard cell boundaries: no anti-aliasing, blur, feathering, color bleed, subpixel texture or within-cell gradients. Across different logical cells, allow deliberate stepped color ramps and coherent pixel clusters for the planned lighting, volume and material texture. Avoid random noise and dithering unless the reviewed style explicitly calls for them.
        Leave at least three logical pixels of empty margin on every side (${g.margin} real pixels). Render only the requested asset and its requested components; no unrequested text, labels, UI, watermarks, scenery, extra subjects, effects or decoration. Keep the subject inside the centered ${g.crop}x${g.crop} crop that will be compressed to ${g.output}x${g.output}.
    """.trimIndent().replace('\n', ' ')

    private fun characterConstraints(text: String): String? {
        if (!CHARACTER.containsMatchIn(text)) return null
        val facing = FACING.firstNotNullOfOrNull { (desc, re) -> desc.takeIf { re.containsMatchIn(text) } }
        val holds = HELD.containsMatchIn(text)
        if (facing == null && !holds) return null
        return buildString {
            facing?.let { append("Preserve the character's explicitly requested facing direction exactly: ").append(it).append(" This is the character's forward vector in canvas coordinates, independent of the camera direction. ") }
            if (holds) append("For every held weapon or tool, show one unambiguous, anatomically coherent top-down grip: the object must visibly connect to the gripping hand, and that hand and forearm must extend toward and remain aligned with the object. Never draw a detached, floating, wrong-hand, or disconnected held object.")
        }.trim()
    }

    private const val EDIT_CONSTRAINT = "The attached image is the EDIT target: visibly execute every change explicitly requested by the user, never return the input unchanged, change only what the user requested, and preserve all unspecified content, identity, composition, and details."

    private val CHARACTER = Regex("(?i)(人物|角色|士兵|战士|骑士|工兵|弓手|法师|刺客|人形(?:单位)?|\\b(?:npc|human|humanoid|character|soldier|warrior|knight|engineer|archer|mage|assassin)\\b)")
    private val HELD = Regex("(?i)(手持|持有|持(?:剑|刀|枪|弓|盾|矛|斧|锤|杖|扳手|工具|武器)|握(?:住|着)?|拿(?:着|住)?|抓(?:住|着)?|双手(?:握|持|拿)|\\b(?:holding|holds?|gripping|wielding)\\b|\\barmed\\s+with\\b)")
    private val FACING = listOf(
        "front/朝前 means the forward vector points toward the TOP edge of the canvas" to Regex("(?i)(?:面朝|朝向|朝)前(?:方)?|(?:facing|faces?|face)\\s+(?:toward\\s+)?(?:the\\s+)?front(?:ward)?"),
        "back/朝后 means the forward vector points toward the BOTTOM edge of the canvas" to Regex("(?i)(?:面朝|朝向|朝)后(?:方)?|(?:facing|faces?|face)\\s+(?:toward\\s+)?(?:the\\s+)?back(?:ward)?"),
        "left/朝左 means the forward vector points toward the LEFT edge of the canvas" to Regex("(?i)(?:面朝|朝向|朝)左(?:方)?|(?:facing|faces?|face)\\s+(?:toward\\s+)?(?:the\\s+)?left"),
        "right/朝右 means the forward vector points toward the RIGHT edge of the canvas" to Regex("(?i)(?:面朝|朝向|朝)右(?:方)?|(?:facing|faces?|face)\\s+(?:toward\\s+)?(?:the\\s+)?right"),
    )
}
