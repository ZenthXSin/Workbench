package io.github.zenthxsin.rustwright.core.shell

/**
 * 内置 Linux 环境（Android 上为 proot + 发行版 rootfs）。
 * 工作区挂载在 [WORKSPACE]，Agent 与终端看到的是同一份文件。
 */
interface LinuxEnvironment {
    /** rootfs 是否已就绪。 */
    val installed: Boolean

    /** 发行版名称（界面显示用），如 "Alpine Linux"。 */
    val distro: String get() = "Linux"

    /** 是否为 App 自己管理的 rootfs（可以装软件、做初始化）；桌面开发版直接用宿主 shell，为 false。 */
    val managed: Boolean get() = true

    /** 下载并解压 rootfs；[log] 可能在后台线程回调。 */
    fun install(log: (String) -> Unit)

    /** 以 root 身份在 rootfs 中执行 shell 命令；默认 stdout 与 stderr 合并（MCP stdio 等需要分开时传 false）。 */
    fun start(command: String, workDir: String = "/root", env: Map<String, String> = emptyMap(), mergeStderr: Boolean = true): Process

    /** 交互式 shell 的启动参数（给 PTY 终端用）；不支持时返回 null。 */
    fun shellCommand(workDir: String = WORKSPACE): PtyCommand? = null

    /** 环境内路径 → 宿主文件（如 /workspace/a → 应用数据目录下的 a）。 */
    fun toHost(guestPath: String): java.io.File

    /** 宿主文件 → 环境内路径；不在环境可见范围内时返回 null。 */
    fun toGuest(host: java.io.File): String?

    companion object {
        const val WORKSPACE = "/workspace"
    }
}

/** 在 PTY 里启动的程序：可执行文件路径、argv（含 argv[0]）、完整环境变量、工作目录。 */
data class PtyCommand(val executable: String, val args: List<String>, val env: Map<String, String>, val cwd: String)
