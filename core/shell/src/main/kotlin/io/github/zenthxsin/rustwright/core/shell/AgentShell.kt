package io.github.zenthxsin.rustwright.core.shell

/** 一次命令执行的结果（输出已截断到 [AgentShell.MAX_OUTPUT]）。 */
data class CommandResult(val exitCode: Int?, val output: String, val timedOut: Boolean, val truncated: Boolean)

/**
 * 助手专用的终端会话：助手的命令和文件操作都在内置 Linux 里、在这个会话中可见地执行，
 * 用户在终端页能看到每一步，也可以按 Ctrl+C 打断。由 linux 模块提供。
 */
interface AgentShell {
    /** 内置 Linux 已安装、可以执行。 */
    val ready: Boolean

    /**
     * 执行一段 bash 脚本。[title] 是终端里显示的一行说明（默认为命令本身）；
     * [quiet] 时输出只回传给调用方，终端里只显示说明和行数（读文件等）。
     */
    suspend fun run(
        command: String,
        title: String = command,
        workDir: String = LinuxEnvironment.WORKSPACE,
        timeoutMillis: Long = 120_000,
        quiet: Boolean = false,
        /** 回传输出的上限（字节）；读文件时调大。 */
        outputLimit: Int = MAX_OUTPUT,
    ): CommandResult

    /** 在会话里打印一行说明（不执行命令）。 */
    fun note(text: String)

    companion object {
        const val MAX_OUTPUT = 60_000
    }
}
