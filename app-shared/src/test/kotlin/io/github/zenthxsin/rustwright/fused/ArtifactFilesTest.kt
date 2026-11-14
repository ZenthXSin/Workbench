package io.github.zenthxsin.rustwright.fused

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactFilesTest {
    @TempDir lateinit var temp: File
    private fun artifact(root: File, id: String = "images/a.jpg", path: String? = File(root, id).path) =
        Artifact(id, Artifact.Kind.IMAGE, "a.jpg", "", "a", path = path)

    @Test fun resolvesTheOwnersFileEvenIfAnotherProjectHasTheSameFilename() {
        val a = File(temp, "a"); val b = File(temp, "b")
        val first = File(a, "images/a.jpg").apply { parentFile.mkdirs(); writeText("a") }
        val other = File(b, "images/a.jpg").apply { parentFile.mkdirs(); writeText("b") }
        val item = artifact(a)
        val resolved = ArtifactFiles.resolve(a, item)!!
        assertEquals(first.canonicalFile, resolved.canonicalFile)
        assertNull(ArtifactFiles.resolve(b, item))
        resolved.delete()
        assertTrue(other.isFile)
        assertNull(ArtifactFiles.resolve(a, item))
    }

    @Test fun rejectsTraversalMismatchedPathsAndLinksOutsideTheProject() {
        val root = File(temp, "project").apply { mkdirs() }
        val outside = File(temp, "outside.jpg").apply { writeText("private") }
        assertNull(ArtifactFiles.resolve(root, artifact(root, "../outside.jpg", outside.path)))
        val link = File(root, "linked.jpg")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(ArtifactFiles.resolve(root, artifact(root, "linked.jpg", link.path)))
        val local = File(root, "a.jpg").apply { writeText("a") }
        assertNull(ArtifactFiles.resolve(root, artifact(root, "a.jpg", outside.path)))
        assertTrue(local.isFile)
        assertTrue(outside.isFile)
    }
}
