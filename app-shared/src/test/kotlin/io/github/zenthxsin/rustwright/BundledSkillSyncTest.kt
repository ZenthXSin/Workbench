package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.agent.BundledSkill
import java.nio.file.Files
import kotlin.test.*

class BundledSkillSyncTest {
    private fun skill(body: String = "Read references/example.md", ref: String = "example") = BundledSkill("test", "test-tools") {
        mapOf("SKILL.md" to "---\nname: 中文名称\ndescription: >-\n  First line\n  second line\n---\n$body".toByteArray(), "references/example.md" to ref.toByteArray())
    }
    @Test fun installsReferencesAndValidCodexMetadataAndUpdatesIdempotently() {
        val root = Files.createTempDirectory("skill-sync").toFile()
        try {
            val sync = BundledSkillSync(root)
            assertEquals(1, sync.sync(listOf(skill()), emptySet()).installed)
            val dir = root.resolve("rw-builtin-test")
            assertTrue(dir.resolve("SKILL.md").readText().contains("name: rw-builtin-test"))
            assertTrue(dir.resolve("SKILL.md").readText().contains("First line second line"))
            assertEquals("example", dir.resolve("references/example.md").readText())
            assertEquals(0, sync.sync(listOf(skill()), emptySet()).installed)
            assertEquals(1, sync.sync(listOf(skill(ref = "updated")), emptySet()).installed)
            assertEquals("updated", dir.resolve("references/example.md").readText())
            assertEquals(1, sync.sync(listOf(skill()), setOf("test-tools")).removed)
            assertFalse(dir.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun retainsUserEditsExtraFilesAndUnmanagedNamesDuringUpdateAndDisable() {
        val root = Files.createTempDirectory("skill-sync").toFile()
        try {
            val sync = BundledSkillSync(root); sync.sync(listOf(skill()), emptySet())
            val dir = root.resolve("rw-builtin-test")
            dir.resolve("references/example.md").writeText("user changes")
            assertEquals(0, sync.sync(listOf(skill(ref = "overwrite")), emptySet()).installed)
            assertEquals(0, sync.sync(emptyList(), emptySet()).removed)
            assertEquals("user changes", dir.resolve("references/example.md").readText())
            val learned = root.resolve("rw-learned-custom").apply { mkdir() }
            learned.resolve("SKILL.md").writeText("keep")
            sync.sync(emptyList(), emptySet())
            assertEquals("keep", learned.resolve("SKILL.md").readText())
            dir.deleteRecursively(); dir.mkdir(); dir.resolve("SKILL.md").writeText("manual")
            sync.sync(listOf(skill()), emptySet())
            assertEquals("manual", dir.resolve("SKILL.md").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun preservesAdditionalUserResources() {
        val root = Files.createTempDirectory("skill-sync").toFile()
        try {
            val sync = BundledSkillSync(root)
            sync.sync(listOf(skill()), emptySet())
            val dir = root.resolve("rw-builtin-test")
            dir.resolve("notes.txt").writeText("my notes")
            assertEquals(0, sync.sync(listOf(skill("changed")), emptySet()).installed)
            assertEquals(0, sync.sync(emptyList(), emptySet()).removed)
            assertEquals("my notes", dir.resolve("notes.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsTraversalAndSymlinksAndDoesNotTouchExternalFiles() {
        val root = Files.createTempDirectory("skill-sync").toFile()
        val outside = Files.createTempDirectory("skill-outside").toFile()
        try {
            outside.resolve("keep").writeText("keep")
            val malicious = BundledSkill("bad", "test") { mapOf("SKILL.md" to "---\ndescription: test\n---\nx".toByteArray(), "../escape" to byteArrayOf(1)) }
            assertEquals(0, BundledSkillSync(root).sync(listOf(malicious), emptySet()).installed)
            Files.createSymbolicLink(root.resolve("rw-builtin-test").toPath(), outside.toPath())
            assertEquals(0, BundledSkillSync(root).sync(listOf(skill()), emptySet()).installed)
            assertEquals("keep", outside.resolve("keep").readText())
            Files.delete(root.resolve("rw-builtin-test").toPath())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun doesNotReplaceEditsMadeWhileLoadingNewResources() {
        val root = Files.createTempDirectory("skill-sync").toFile()
        try {
            val sync = BundledSkillSync(root); sync.sync(listOf(skill()), emptySet())
            val updating = BundledSkill("test", "test-tools") {
                root.resolve("rw-builtin-test/SKILL.md").appendText("\nuser edit")
                skill("new content").files()
            }
            assertEquals(0, sync.sync(listOf(updating), emptySet()).installed)
            assertTrue(root.resolve("rw-builtin-test/SKILL.md").readText().endsWith("user edit"))
        } finally { root.deleteRecursively() }
    }
    @Test fun androidStyleSystemAliasDoesNotBlockSyncUpdateOrRemoval() {
        val temp = Files.createTempDirectory("skill-android-alias").toFile()
        try {
            val actual = temp.resolve("data/user/0/app/files/linux/rootfs/root").apply { mkdirs() }
            val alias = temp.resolve("data/data").toPath()
            Files.createSymbolicLink(alias, temp.resolve("data/user/0").toPath())
            val home = alias.resolve("app/files/linux/rootfs/root").toFile()
            val sync = BundledSkillSync(home.resolve(".agents/skills"), home)
            assertEquals(1, sync.sync(listOf(skill()), emptySet()).installed)
            assertTrue(actual.resolve(".agents/skills/rw-builtin-test/SKILL.md").isFile)
            assertEquals(0, sync.sync(listOf(skill()), emptySet()).installed)
            assertEquals(1, sync.sync(listOf(skill(ref = "updated")), emptySet()).installed)
            assertEquals(1, sync.sync(emptyList(), emptySet()).removed)
            assertFalse(actual.resolve(".agents/skills/rw-builtin-test").exists())
        } finally { temp.deleteRecursively() }
    }

    @Test fun untrustedSkillPathLinksAndUnavailableDirectoryBecomeWarnings() {
        val home = Files.createTempDirectory("skill-home").toFile()
        val outside = Files.createTempDirectory("skill-outside").toFile()
        try {
            outside.resolve("keep.txt").writeText("keep")
            val agents = home.resolve(".agents")
            Files.createSymbolicLink(agents.toPath(), outside.toPath())
            val sync = BundledSkillSync(agents.resolve("skills"), home)
            val result = sync.sync(listOf(skill()), emptySet())
            assertEquals(0, result.installed)
            assertTrue(result.warnings.isNotEmpty())
            assertEquals(listOf("keep.txt"), outside.listFiles()!!.map { it.name })
            Files.delete(agents.toPath())
            agents.writeText("not a directory")
            assertTrue(sync.sync(listOf(skill()), emptySet()).warnings.isNotEmpty())
            assertEquals("not a directory", agents.readText())
        } finally { home.deleteRecursively(); outside.deleteRecursively() }
    }

}
