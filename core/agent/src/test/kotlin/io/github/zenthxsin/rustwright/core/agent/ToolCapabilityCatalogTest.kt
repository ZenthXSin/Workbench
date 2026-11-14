package io.github.zenthxsin.rustwright.core.agent

import io.github.zenthxsin.rustwright.core.runtime.Extensions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class ToolCapabilityCatalogTest {
    private class ProjectTool(override val name: String) : Tool(), ProjectAware {
        override val description = "edit pixels"
        override val parameters = buildJsonObject { put("type", "object") }
        override val risk = ToolRisk.WRITE
        override suspend fun execute(userId: String, args: JsonObject) = error("must not execute while browsing")
        override suspend fun executeInProject(userId: String, args: JsonObject, project: ToolProject) = error("must not execute while browsing")
    }
    @Test fun reflectsRegistrationScopeAndDisabledStateWithoutRunningTools() {
        val registry = AgentToolRegistry()
        val ext = Extensions()
        val catalog = ToolCapabilityCatalog(registry, ext)
        assertTrue(catalog.groups().isEmpty())
        ext.add(AgentPoints.TOOLSETS, Toolset("sample-image", "贴图", "像素工具"))
        registry.register(ProjectTool("edit_pixels"), AgentScene("image"), toolset = "sample-image")
        val skill = ext.add(AgentPoints.SKILLS, BundledSkill("pic-edit", "sample-image", files = { emptyMap() }))
        val g = catalog.groups(setOf("sample-image")).single()
        assertEquals(listOf("pic-edit"), g.skillIds)
        skill.remove()
        assertTrue(catalog.groups().single().skillIds.isEmpty())
        assertFalse(g.enabled)
        assertEquals("贴图", g.title)
        val t = g.tools.single()
        assertTrue(t.acceptsProjectSnapshot)
        assertEquals(ToolRisk.WRITE, t.risk)
        assertEquals(setOf(AgentScene("image")), t.scenes)
        assertTrue(catalog.groups(scene = AgentScene("layout")).isEmpty())
        assertTrue(catalog.groups(scene = AgentScene("image")).single().enabled)
        registry.unregister("edit_pixels")
        assertTrue(catalog.groups().isEmpty())
    }
}
