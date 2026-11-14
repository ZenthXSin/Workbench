package io.github.zenthxsin.rustwright.pic.impl

import io.github.zenthxsin.rustwright.core.agent.*
import io.github.zenthxsin.rustwright.core.runtime.RwContext

internal fun registerPicSkills(ctx: RwContext) {
    val resources =
        mapOf(
            "pic-svg-design" to listOf("SKILL.md", "group.md"),
            "pic-icons-logos" to listOf("SKILL.md", "group.md"),
            "pic-illustration" to listOf("SKILL.md", "group.md"),
            "pic-composition" to listOf("SKILL.md", "group.md"),
            "pic-color-theory" to listOf("SKILL.md", "group.md"),
            "pic-topdown-characters" to listOf("SKILL.md"),
            "pic-creative-intent" to listOf("SKILL.md"),
            "pic-poster-design" to listOf("SKILL.md"),
            "pic-structural-design" to listOf("SKILL.md"),
            "pic-scripting" to listOf("SKILL.md", "group.md"),
            "pic-requirements" to listOf("SKILL.md", "group.md"),
        )
    resources.forEach { (id, files) ->
        ctx.extensions.add(
            AgentPoints.SKILLS,
            BundledSkill(id, "rustwright-pic") {
                files.associateWith { path ->
                    checkNotNull(
                            io.github.zenthxsin.rustwright.core.runtime.Res.open("skills/$id/$path")
                        ) {
                            "Missing bundled skill $id/$path"
                        }
                        .use { it.readBytes() }
                }
            },
        )
    }
    ctx.extensions.add(
        AgentPoints.INSTRUCTIONS,
        AgentInstruction(
            "pic.workspace",
            "rustwright-pic",
            "Image tools use explicit project-relative paths belonging to the calling conversation. " +
                "Use inspect_image/read_image_region for measured pixels, native model vision for semantic interpretation. " +
                "For layered editing: create/observe a .rwpixel or .mkpx document, apply_pixel_actions with observed version, then edit_image operation=export to a PNG. " +
                "generate_svg saves validated source; render_svg rasterizes to PNG; render_svg_frames validates ordered sources and publishes a PNG sheet atomically. Inspect actual output before claiming rendering was tested. " +
                "For storyboards, persist description/image_prompt/shots with generate_storyboard, then generate_storyboard_frame using the returned version; never silently rewrite a saved shot. " +
                "Legacy Makapix DSL and TypeScript Java-interop plugin ABI are not the native shell API. Use documented pixel actions or write a native script/skill explicitly; do not claim old scripts run unchanged. " +
                "Use generate_image for model-based image generation/editing with reference_ids; preserve user constraints and disclose actual validation.",
        ),
    )
    ctx.extensions.all(io.github.zenthxsin.rustwright.pic.api.PicPoints.DOMAINS)
        .filter { it.instructions.isNotBlank() }.forEach { domain ->
            ctx.extensions.add(AgentPoints.INSTRUCTIONS,
                AgentInstruction(domain.id, "rustwright-pic", domain.instructions))
        }
}
