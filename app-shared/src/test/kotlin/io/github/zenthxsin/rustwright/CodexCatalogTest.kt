package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.linux.impl.LinuxSetup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexCatalogTest {
    @TempDir lateinit var temp: File
    private val bundled = """{"models":[{"slug":"gpt-6.1-sol","tool_mode":"code_mode_only","use_responses_lite":true,"supports_search_tool":true}]}"""

    private fun prepare(hostBody: String? = "exit 0", version: String = LinuxSetup.CODEX_VERSION, exportFails: Boolean = false): File {
        val bash = System.getenv("BASH_BIN") ?: if (System.getProperty("os.name").startsWith("Windows"))
            "C:/Program Files/Git/bin/bash.exe" else "/bin/bash"
        assumeTrue(File(bash).isFile, "Shell integration test requires bash")
        // Spaces and apostrophes must stay literal, including in the generated shell arguments.
        val root = File(temp, "user's tools").apply { mkdirs() }
        fun executable(name: String, content: String) = File(root, name).apply {
            writeText("#!/bin/sh\n$content\n")
            setExecutable(true)
        }
        val bin = executable("codex", "printf '%s\\n' ${shellQuote(bundled)}; exit ${if (exportFails) 1 else 0}")
        val host = File(root, "codex-code-mode-host")
        hostBody?.let { executable(host.name, it) }
        val versionFile = File(root, "code-mode-host.version").apply { writeText(version) }
        val catalog = File(root, "models.json").apply { writeText("stale catalog") }
        val command = codexCatalogPreparation(bin.invariantSeparatorsPath, catalog.invariantSeparatorsPath,
            host.invariantSeparatorsPath, versionFile.invariantSeparatorsPath)
        val output = File(temp, "shell.log")
        // Windows command-line re-quoting must not alter the script before bash parses it.
        val process = ProcessBuilder(bash, "-s").redirectErrorStream(true).redirectOutput(output).start()
        try {
            process.outputStream.bufferedWriter().use { it.write("export PATH=/usr/bin:/bin:\$PATH\n$command\n") }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Catalog preparation timed out")
            assertEquals(0, process.exitValue(), output.readText())
        } finally { process.destroyForcibly() }
        assertFalse(File(root, "models.json.tmp").exists())
        assertFalse(File(root, "models.json.raw").exists())
        return catalog
    }

    private fun assertMode(catalog: File, codeMode: Boolean) {
        val model = Json.parseToJsonElement(catalog.readText()).jsonObject.getValue("models").jsonArray.single().jsonObject
        assertEquals(if (codeMode) "code_mode_only" else "null", model.getValue("tool_mode").jsonPrimitive.content)
        assertEquals("false", model.getValue("use_responses_lite").jsonPrimitive.content)
        assertEquals("false", model.getValue("supports_search_tool").jsonPrimitive.content)
    }

    @Test fun workingHostKeepsCodeModeByDefault() = assertMode(prepare(), true)
    @Test fun missingHostFallsBackToDirectTools() = assertMode(prepare(hostBody = null), false)
    @Test fun mismatchedHostVersionFallsBackToDirectTools() = assertMode(prepare(version = "0.160.0"), false)
    @Test fun hostStartupFailureFallsBackToDirectTools() = assertMode(prepare(hostBody = "exit 1"), false)
    @Test fun hangingHostCannotBlockStartup() = assertMode(prepare(hostBody = "sleep 30"), false)
    @Test fun failedExportRemovesStaleCatalogEvenIfItPrintedJson() {
        assertFalse(prepare(exportFails = true).exists())
    }
}
