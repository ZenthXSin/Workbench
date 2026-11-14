package io.github.zenthxsin.rustwright.desktop

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import arc.Core
import io.github.zenthxsin.rustwright.core.runtime.Platform
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.shell.PtyCommand
import io.github.zenthxsin.rustwright.core.storage.SecretStore
import java.io.File
import java.util.Properties

/** 桌面平台（开发用）：数据放在 [home]，文件选择 / 导出用固定目录代替系统对话框。 */
class DesktopPlatform(private val home: File) : Platform {
    override val dataDir: File = File(home, "data").apply { mkdirs() }
    override val cacheDir: File = File(home, "cache").apply { mkdirs() }
    /** 版本号跟安卓同步（构建时写进 rw-desktop-version.properties）。 */
    private val version = Properties().apply { DesktopPlatform::class.java.classLoader.getResourceAsStream("rw-desktop-version.properties")?.use { load(it) } }
    override val appVersion: String = version.getProperty("version")?.let { "$it-desktop" } ?: "desktop-dev"
    override val appVersionCode: Int = version.getProperty("code")?.toIntOrNull() ?: 0
    private val exportDir = File(home, "export").apply { mkdirs() }

    override fun openUri(uri: String) { runCatching { Core.app.openURI(uri) } }
    override fun shareFile(file: File, mimeType: String) { println("[share] ${file.absolutePath}") }
    override fun clipboardText(): String? = runCatching { Core.app.clipboardText }.getOrNull()
    override fun setClipboardText(text: String) { runCatching { Core.app.clipboardText = text } }
    override fun toast(message: String) = println("[toast] $message")

    /** 取 home/import 下最新的文件。 */
    override suspend fun pickFile(mimeTypes: List<String>): File? =
        File(home, "import").listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    override suspend fun pickFiles(mimeTypes: List<String>): List<File> =
        File(home, "import").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    override suspend fun exportFile(file: File, suggestedName: String, mimeType: String): Boolean {
        file.copyTo(File(exportDir, suggestedName), overwrite = true)
        println("[export] ${File(exportDir, suggestedName).absolutePath}")
        return true
    }

    override fun installUpdate(apk: File, expectedVersionCode: Int) = throw UnsupportedOperationException("桌面版不支持自动更新")

    override fun openAsset(path: String) = javaClass.classLoader.getResourceAsStream("assets/$path")
    override fun listAssets(dir: String): List<String> = emptyList()
}

/** 开发用的明文密钥存储（home/secrets.properties）。 */
class FileSecretStore(private val file: File) : SecretStore {
    private val props = Properties().apply { if (file.isFile) file.inputStream().use { load(it) } }
    override fun get(key: String): String? = props.getProperty(key)
    override fun put(key: String, value: String) { props.setProperty(key, value); save() }
    override fun remove(key: String) { props.remove(key); save() }
    private fun save() = file.outputStream().use { props.store(it, "Workbench desktop (dev only, plaintext)") }
}

/**
 * 桌面上的「内置 Linux」：直接用宿主的 /bin/sh，工作区目录映射为 [LinuxEnvironment.WORKSPACE] 的实际路径。
 * PTY 终端用宿主 bash / sh（libtermux 的 Linux 版本随桌面资源打包）。
 */
class HostShellEnvironment(private val workspace: File) : LinuxEnvironment {
    override val installed: Boolean get() = posixShell() != null

    private val windows = System.getProperty("os.name").startsWith("Windows")

    /**
     * 跑命令用的 POSIX shell：Linux / macOS 用 /bin/sh；Windows 没有，找 Git for Windows 自带的 bash
     * （PATH 里的 bash.exe 可能是 WSL 的，路径体系不同，放最后）。
     */
    private fun posixShell(): String? {
        if (!windows) return "/bin/sh".takeIf { File(it).exists() }
        System.getProperty("rw.bash")?.let { if (File(it).isFile) return it }
        val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramW6432"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" })
        roots.flatMap { listOf("$it\\Git\\bin\\bash.exe", "$it\\Git\\usr\\bin\\bash.exe") }.firstOrNull { File(it).isFile }?.let { return it }
        return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { File(it, "bash.exe") }.firstOrNull { it.isFile && !it.path.contains("System32", true) }?.path
    }
    override val distro get() = if (windows) "Git Bash" else "宿主 shell"
    override val managed = false
    override fun toHost(guestPath: String): File =
        if (guestPath.startsWith(LinuxEnvironment.WORKSPACE)) File(workspace, guestPath.removePrefix(LinuxEnvironment.WORKSPACE)) else File(guestPath)
    // 桌面不做路径重映射：宿主路径即环境内路径
    override fun toGuest(host: File): String = host.absolutePath
    override fun install(log: (String) -> Unit) = log("桌面版直接使用宿主 shell，无需安装")
    override fun start(command: String, workDir: String, env: Map<String, String>, mergeStderr: Boolean): Process {
        workspace.mkdirs()
        val dir = if (workDir.startsWith(LinuxEnvironment.WORKSPACE)) File(workspace, workDir.removePrefix(LinuxEnvironment.WORKSPACE)) else workspace
        val sh = posixShell() ?: throw java.io.IOException("Windows 上需要先安装 Git for Windows（带 Git Bash），${AppBrand.identity.assistant}和命令行工具要用它来运行命令：https://git-scm.com/download/win")
        return ProcessBuilder(sh, "-lc", command).directory(dir.apply { mkdirs() }).redirectErrorStream(mergeStderr)
            .apply { environment().putAll(env) }.start()
    }
    override fun shellCommand(workDir: String): PtyCommand? {
        if (!System.getProperty("os.name").startsWith("Linux")) return null
        workspace.mkdirs()
        val shell = listOf("/bin/bash", "/bin/sh").first { File(it).exists() }
        val env = System.getenv() + mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "C.UTF-8")
        return PtyCommand(shell, listOf(shell, "-l"), env, workspace.absolutePath)
    }
}
