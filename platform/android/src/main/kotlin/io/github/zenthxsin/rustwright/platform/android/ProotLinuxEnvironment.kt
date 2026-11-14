package io.github.zenthxsin.rustwright.platform.android

import io.github.zenthxsin.rustwright.core.runtime.AppBrand
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.system.Os
import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.core.shell.PtyCommand
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 内置 Linux 环境：proot（打包在 nativeLibraryDir，可执行）+ 首次使用时下载的 Alpine Linux minirootfs。
 * 解压后先用系统自带的 sh 装上 bash，终端和初始化脚本都依赖它。已在 targetSdk 36 下验证可以 exec rootfs 里的程序。
 * 0.6.10 及以前用的是 Ubuntu 24.04；装好 Alpine 后旧目录会删掉（项目在 workspace，不受影响）。
 */
class ProotLinuxEnvironment(private val ctx: Context) : LinuxEnvironment {

    val base = File(ctx.filesDir, "linux")
    val rootfs = File(base, "alpine")
    /** 0.6.10 及以前的 Ubuntu rootfs；换成 Alpine 后删除。 */
    private val legacyRootfs = File(base, "ubuntu")
    val workspace = File(ctx.filesDir, "workspace")
    private val libDir = File(base, "lib")
    private val tmpDir = File(ctx.cacheDir, "proot-tmp")
    private val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private var dnsCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // A terminal can remain open while Wi-Fi, mobile data, or a VPN changes.
        // Keep its glibc resolver aligned with Android instead of pinning public DNS.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                refreshDns(properties)
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onSuccess { dnsCallback = callback }
    }

    internal fun close() {
        dnsCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        dnsCallback = null
    }

    override val installed: Boolean get() =
        File(rootfs, "sbin/apk").exists() && File(rootfs, "bin/bash").exists() && File(rootfs, ".rw-installed").exists()

    override val distro = "Alpine Linux"

    /** bash 装好之前（刚解压完）只能用 busybox 的 sh。 */
    private val shell: String get() = if (File(rootfs, "bin/bash").exists()) "/bin/bash" else "/bin/sh"

    override fun toHost(guestPath: String): File = when {
        guestPath == LinuxEnvironment.WORKSPACE || guestPath.startsWith(LinuxEnvironment.WORKSPACE + "/") ->
            File(workspace, guestPath.removePrefix(LinuxEnvironment.WORKSPACE))
        else -> File(rootfs, guestPath.removePrefix("/"))
    }

    override fun toGuest(host: File): String? {
        val path = host.canonicalFile.path
        val ws = workspace.canonicalFile.path
        val root = rootfs.canonicalFile.path
        return when {
            path == ws || path.startsWith("$ws/") -> LinuxEnvironment.WORKSPACE + path.removePrefix(ws)
            path == root || path.startsWith("$root/") -> path.removePrefix(root).ifEmpty { "/" }
            else -> null
        }
    }

    val targetSdk: Int get() = ctx.applicationInfo.targetSdkVersion

    private val alpineArch: String
        get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "armv7"
            "x86_64" -> "x86_64"
            else -> error("不支持的 ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        }

    /** 下载并解压 Alpine minirootfs（优先 BGP 40002 安装镜像），再装上 bash；[log] 在后台线程回调。 */
    override fun install(log: (String) -> Unit) {
        if (legacyRootfs.exists()) log("虚拟环境换成更轻的 Alpine Linux，旧的 Ubuntu 会在装好后删除；项目文件不受影响")
        // 网络不好时可以手动把 alpine-minirootfs-*.tar.gz 放到 Android/data/<包名>/files/ 下离线安装
        localArchive()?.let { file ->
            check(file.length() <= 64L * 1024 * 1024 && sha256(file) == trustedRootfsHash()) { "本地 rootfs 不是受信任的 Alpine $ROOTFS_VERSION" }
            log("使用本地 rootfs 包 ${file.name}")
            extract(file, log, deleteArchive = false)
            bootstrap(log)
            return
        }
        var lastError: Throwable? = null
        for (mirror in MIRRORS) {
            try {
                installFrom(mirror, log)
                bootstrap(log)
                return
            } catch (e: Throwable) {
                lastError = e
                log("从 $mirror 安装失败：${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载源")
    }

    private fun installFrom(mirror: String, log: (String) -> Unit) {
        val dir = "$mirror/alpine/$ALPINE/releases/$alpineArch"
        val file = "alpine-minirootfs-$ROOTFS_VERSION-$alpineArch.tar.gz"
        val sha = trustedRootfsHash()
        val archive = File(ctx.cacheDir, file)
        log("下载 $file")
        open("$dir/$file").use { input -> archive.outputStream().use { out -> copyWithProgress(input, out) { mb -> log("下载 $file · $mb MB") } } }
        val actual = sha256(archive)
        check(actual == sha) { "sha256 不匹配：$actual" }
        log("校验通过")
        extract(archive, log, deleteArchive = true)
        repositories = mirror
    }

    /** 刚解压的 rootfs 里用哪个镜像装 bash；本地包安装时为 null（按顺序试）。 */
    private var repositories: String? = null

    private fun localArchive(): File? = ctx.getExternalFilesDir(null)?.listFiles()
        ?.filter { it.isFile && it.name.startsWith("alpine-minirootfs-") && it.name.endsWith("-$alpineArch.tar.gz") }
        ?.maxByOrNull { it.name }

    private fun extract(archive: File, log: (String) -> Unit, deleteArchive: Boolean) {
        log("解压到 ${rootfs.path}")
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        val count = GZIPInputStream(BufferedInputStream(archive.inputStream())).use { untar(it, rootfs) }
        if (deleteArchive) archive.delete()
        configureRootfs()
        log("完成，共 $count 个条目")
    }

    /**
     * 用 busybox sh 装 bash（终端、助手的命令和初始化脚本都用 bash），装好才算安装完成。
     * apk 校验索引和软件包签名，按顺序换镜像。
     */
    private fun bootstrap(log: (String) -> Unit) {
        val mirrors = listOfNotNull(repositories) + MIRRORS.filter { it != repositories }
        val release = File(rootfs, "etc/alpine-release").readText().trim().split('.').take(2).joinToString(".")
        val script = mirrors.joinToString(" || ") { m ->
            "{ printf '%s/v$release/main\\n%s/v$release/community\\n' '$m/alpine' '$m/alpine' > /etc/apk/repositories " +
                "&& echo '软件源：$m' && timeout 120 apk update -q && apk add -q --no-cache bash; }"
        }
        log("安装 bash")
        val proc = start(script, "/", emptyMap(), mergeStderr = true)
        proc.inputStream.bufferedReader().forEachLine { if (it.isNotBlank()) log(it) }
        check(proc.waitFor() == 0 && File(rootfs, "bin/bash").exists()) { "bash 安装失败，请检查网络后重试" }
        File(rootfs, ".rw-installed").writeText(rootfs.list()?.size.toString())
        // 装好了再删旧的 Ubuntu，中途失败不会两边都没有。
        // Codex 的对话记录、配置在 /root/.codex，先搬过来，不然旧对话没法接着聊。
        if (legacyRootfs.exists()) {
            val legacyCodex = File(legacyRootfs, "root/.codex")
            if (legacyCodex.isDirectory) {
                log("迁移${AppBrand.identity.assistant}的对话记录")
                copyTree(legacyCodex, File(rootfs, "root/.codex"))
            }
            log("删除旧的 Ubuntu")
            legacyRootfs.deleteRecursively()
        }
    }

    /** 复制目录（不跟随软链接，已有的文件不覆盖）；单个文件失败不影响其余。 */
    private fun copyTree(from: File, to: File) {
        val src = from.toPath()
        java.nio.file.Files.walk(src).use { paths ->
            paths.forEach { path ->
                if (java.nio.file.Files.isSymbolicLink(path)) return@forEach
                val target = to.toPath().resolve(src.relativize(path).toString())
                runCatching {
                    if (java.nio.file.Files.isDirectory(path)) java.nio.file.Files.createDirectories(target)
                    else if (!java.nio.file.Files.exists(target)) {
                        java.nio.file.Files.createDirectories(target.parent)
                        java.nio.file.Files.copy(path, target)
                    }
                }
            }
        }
    }

    /** proot 下的最小配置：DNS、hosts、可写的 /tmp。 */
    private fun configureRootfs() {
        refreshDns()
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "tmp").apply { mkdirs(); setWritable(true, false) }
    }

    @Synchronized
    private fun refreshDns(properties: LinkProperties? = runCatching {
        connectivity?.activeNetwork?.let { connectivity.getLinkProperties(it) }
    }.getOrNull()) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) return
        runCatching {
            val file = File(etc, "resolv.conf")
            val addresses = properties?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.distinct().take(3)
            // During a brief disconnect keep the last working configuration.
            if (addresses.isEmpty() && file.isFile) return
            val servers = addresses.ifEmpty { listOf("223.5.5.5", "8.8.8.8") }
            val content = servers.joinToString("") { "nameserver $it\n" } + "options timeout:2 attempts:2\n"
            if (file.isFile && file.readText() == content) return
            // Some rootfs ship resolv.conf as a symlink to a resolver daemon, which proot does not run.
            // Rename a complete file so a concurrent apt/npm process never observes a partial write.
            val next = File(etc, ".rustwright-resolv.conf")
            next.writeText(content)
            check(next.renameTo(file)) { "无法更新 Linux DNS" }
        }.onFailure { io.github.zenthxsin.rustwright.core.runtime.RwLog.w("linux", "更新 Linux DNS 失败", it) }
    }

    private fun open(url: String): InputStream =
        (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }.let { c ->
            try {
                check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                object : java.io.FilterInputStream(c.inputStream) {
                    override fun close() { try { super.close() } finally { c.disconnect() } }
                }
            } catch (error: Throwable) { c.disconnect(); throw error }
        }

    private fun copyWithProgress(input: InputStream, out: java.io.OutputStream, report: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        var lastMb = -1L
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            check(total + n <= 64L * 1024 * 1024 && System.nanoTime() < deadline) { "rootfs 下载超过体积或时间限制" }
            out.write(buf, 0, n)
            total += n
            val mb = total / (1024 * 1024)
            if (mb != lastMb && mb % 2 == 0L) { report(mb); lastMb = mb }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun trustedRootfsHash(): String = when (alpineArch) {
        "aarch64" -> "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50"
        "x86_64" -> "f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407"
        "armv7" -> "dc1ddfaf03ede4be79ad7475490f6b7859dbcbbbbb85150aa1ee013b4bc2f0c1"
        else -> error("Unsupported rootfs architecture")
    }

    override fun start(command: String, workDir: String, env: Map<String, String>, mergeStderr: Boolean): Process {
        val args = prootArgs(workDir, env + ("TERM" to "dumb")) + listOf(shell, "-lc", command)
        return ProcessBuilder(args).redirectErrorStream(mergeStderr).apply { environment().putAll(hostEnv()) }.start()
    }

    /** 交互式登录 shell（PTY 终端）：TERM=xterm-256color，宿主环境变量整体替换为 proot 需要的几项。 */
    override fun shellCommand(workDir: String): PtyCommand {
        val args = prootArgs(workDir, mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor")) + listOf(shell, "-l")
        return PtyCommand(args.first(), args, hostEnv() + ("PATH" to (System.getenv("PATH") ?: "/system/bin")), base.path)
    }

    private fun prootArgs(workDir: String, env: Map<String, String>): List<String> {
        refreshDns()
        prepareLibs()
        prepareGroups()
        tmpDir.mkdirs()
        workspace.mkdirs()
        return listOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/urandom:/dev/random",
            "-b", "${workspace.path}:${LinuxEnvironment.WORKSPACE}",
            "-w", workDir,
            "/usr/bin/env", "-i",
            "HOME=/root", "LANG=C.UTF-8", "USER=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ) + env.map { (k, v) -> "$k=$v" }
    }

    private fun hostEnv(): Map<String, String> = buildMap {
        put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
        File(nativeDir, "libproot-loader32.so").takeIf { it.exists() }?.let { put("PROOT_LOADER_32", it.path) }
        put("PROOT_TMP_DIR", tmpDir.path)
        put("LD_LIBRARY_PATH", "${libDir.path}:${nativeDir.path}")
    }

    @Volatile private var groupsReady = false

    /** 把 Android 给 App 的附加组写进 rootfs 的 /etc/group，免得 bash 登录时报「cannot find name for group ID」。 */
    private fun prepareGroups() {
        if (groupsReady || !File(rootfs, "etc/group").isFile) return
        groupsReady = true
        runCatching {
            val gids = ProcessBuilder("/system/bin/id", "-G").start().inputStream.bufferedReader().readText()
                .trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            val file = File(rootfs, "etc/group")
            val known = file.readLines().mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
            val missing = gids.filter { it !in known }
            if (missing.isNotEmpty()) file.appendText(missing.joinToString("") { "aid_$it:x:$it:root\n" })
        }
    }

    /**
     * proot 依赖 libtalloc.so.2（SONAME），APK 只能装 lib*.so，这里补一个软链接。
     * 每次更新 App 后 nativeLibraryDir 会换目录，旧链接会悬空，所以目标不对就重建。
     */
    private fun prepareLibs() {
        libDir.mkdirs()
        val link = File(libDir, "libtalloc.so.2")
        val target = File(nativeDir, "libtalloc.so").path
        val current = runCatching { Os.readlink(link.path) }.getOrNull()
        if (current != target) {
            link.delete()
            runCatching { Os.symlink(target, link.path) }
        }
    }

    internal companion object {
        /** 跟着 Alpine 稳定版走，升级时改这里。 */
        const val ALPINE = "v3.24"
        // Verified against dl-cdn.alpinelinux.org release metadata on 2026-10-31.
        const val ROOTFS_VERSION = "3.24.3"

        /** 都按 `<镜像>/alpine/...` 组织；BGP 40002 是自己的缓存镜像。 */
        val MIRRORS = listOf(
            io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.mirror(),
            io.github.zenthxsin.rustwright.core.runtime.net.Endpoints.legacyMirror(),
            "https://mirrors.tuna.tsinghua.edu.cn",
            "https://mirrors.ustc.edu.cn",
            "https://mirrors.aliyun.com",
            "https://dl-cdn.alpinelinux.org",
        )

        /** 从 latest-releases.yaml 里找 minirootfs 的文件名和 sha256。 */
        fun minirootfs(yaml: String, arch: String): Pair<String, String>? {
            var file: String? = null
            var flavor: String? = null
            for (raw in yaml.lines()) {
                val line = raw.trim().removePrefix("- ").trim()
                when {
                    line.startsWith("flavor:") -> { flavor = line.substringAfter(':').trim(); file = null }
                    line.startsWith("file:") -> file = line.substringAfter(':').trim()
                    line.startsWith("sha256:") && flavor == "alpine-minirootfs" -> {
                        val f = file ?: continue
                        if (f.endsWith("-$arch.tar.gz")) return f to line.substringAfter(':').trim()
                    }
                }
            }
            return null
        }
    }

    private fun untar(input: InputStream, dest: File): Int =
        io.github.zenthxsin.rustwright.core.runtime.archive.TarArchive.extract(input, dest,
            symlink = { link, path -> Os.symlink(link, path) },
            chmod = { path, mode -> Os.chmod(path, mode) })
}
