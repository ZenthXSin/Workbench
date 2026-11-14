package io.github.zenthxsin.rustwright

import arc.scene.Element
import io.github.zenthxsin.rustwright.core.codex.CodexProject
import io.github.zenthxsin.rustwright.core.codex.CodexTurn
import io.github.zenthxsin.rustwright.core.runtime.Contribution
import io.github.zenthxsin.rustwright.core.runtime.ExtensionPoint
import io.github.zenthxsin.rustwright.fused.Artifact
import io.github.zenthxsin.rustwright.fused.FusedShell
import io.github.zenthxsin.rustwright.fused.TurnCheck
import java.io.File

class ProjectAction(
    override val id: String,
    val title: String,
    val icon: String,
    val available: (File) -> Boolean = { true },
    val run: suspend (CodexProject, File) -> Unit,
    override val order: Int = 100,
) : Contribution

class FilePresentation(
    override val id: String,
    val accepts: (File) -> Boolean,
    val kind: Artifact.Kind,
    val detail: (File) -> String? = { null },
    val preview: ((File) -> Element?)? = null,
    val open: ((File) -> Element?)? = null,
    val card: ((Artifact, (Artifact) -> Unit) -> Element?)? = null,
    val referencedFiles: (File) -> Set<String> = { emptySet() },
    override val order: Int = 100,
) : Contribution

class ProjectInspector(
    override val id: String,
    val accepts: (File) -> Boolean,
    val check: suspend (File, CodexTurn?) -> TurnCheck?,
    val stats: (File) -> String = { "" },
    override val order: Int = 100,
) : Contribution

class WorkbenchHome(override val id: String, val build: (FusedShell) -> Element, override val order: Int = 100) : Contribution
class FileOpener(override val id: String, val open: (File) -> Boolean, override val order: Int = 100) : Contribution
class NavigationAlias(override val id: String, val target: String, override val order: Int = 100) : Contribution
class ToolCommand(override val id: String, val execute: (File?) -> Boolean, override val order: Int = 100) : Contribution

class ProjectStarter(override val id: String, val title: String, val icon: String, val prompt: String,
    val available: (File) -> Boolean = { true }, override val order: Int = 100) : Contribution

object ShellPoints {
    val COMMANDS = ExtensionPoint<ToolCommand>("shell.tool.commands")
    val STARTERS = ExtensionPoint<ProjectStarter>("shell.project.starters")
    val PROJECT_ACTIONS = ExtensionPoint<ProjectAction>("shell.project.actions")
    val FILES = ExtensionPoint<FilePresentation>("shell.files")
    val OPENERS = ExtensionPoint<FileOpener>("shell.file.openers")
    val INSPECTORS = ExtensionPoint<ProjectInspector>("shell.project.inspectors")
    val HOME = ExtensionPoint<WorkbenchHome>("shell.home")
    val NAVIGATION = ExtensionPoint<NavigationAlias>("shell.navigation.aliases")
}
