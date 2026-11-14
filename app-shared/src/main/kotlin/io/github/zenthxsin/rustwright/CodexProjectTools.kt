package io.github.zenthxsin.rustwright

import io.github.zenthxsin.rustwright.core.agent.ProjectAware
import io.github.zenthxsin.rustwright.core.agent.ToolProject
import io.github.zenthxsin.rustwright.core.codex.CodexToolCall
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.agent.ToolResult
import java.io.File

/** Codex 项目路径只在调用开始时解析一次；无法映射的项目明确失败，不能落到另一个已选项目。 */
suspend fun executeProjectTool(tool: ProjectAware, call: CodexToolCall, linux: LinuxEnvironment?): ToolResult {
    val project = call.project
        ?: return ToolResult.failure("project_unavailable", "The conversation's project is unavailable. Reopen the project before trying again.")
    val dir = runCatching {
        require(if (linux?.managed == true) project.path.startsWith('/') else File(project.path).isAbsolute) { "Project path must be absolute" }
        val directory = (if (linux?.managed == true) linux.toHost(project.path) else File(project.path)).canonicalFile
        require(directory.isDirectory) { "Project directory does not exist" }
        if (linux?.managed == true) {
            val mapped = linux.toGuest(directory) ?: error("Project directory is not accessible from Linux")
            require(File(mapped).normalize().path == File(project.path).normalize().path) { "Project directory could not be mapped consistently" }
        }
        directory
    }.getOrElse {
        return ToolResult.failure("project_unavailable", "Cannot use project '${project.name}': ${it.message}. Reopen the project before trying again.")
    }
    return tool.executeInProject("codex", call.arguments, ToolProject(project.id, dir, call.conversation.id,
        freeBenefits = call.model.startsWith(io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.benefitModelPrefix())))
}
