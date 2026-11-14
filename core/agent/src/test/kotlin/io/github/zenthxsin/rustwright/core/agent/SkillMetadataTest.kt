package io.github.zenthxsin.rustwright.core.agent

import kotlin.test.*

class SkillMetadataTest {
    @Test fun parsesLegacyFoldedDescriptionWithoutTreatingIndentedColonsAsMetadata() {
        val skill = Skills.parse("fallback", "---\nname: old-skill\ndescription: >-\n  Follow these rules:\n  preserve references.\n---\n# Body\nExample")!!
        assertEquals("old-skill", skill.name)
        assertEquals("Follow these rules: preserve references.", skill.description)
        assertEquals("# Body\nExample", skill.body)
    }
    @Test fun parsesQuotedEscapesAndLiteralBlocks() {
        assertEquals("say \"hello\"", Skills.parse("test", "---\ndescription: \"say \\\"hello\\\"\"\n---\nbody")!!.description)
        assertEquals("one\ntwo", Skills.parse("test", "---\ndescription: |\n  one\n  two\n---\nbody")!!.description)
    }
}
