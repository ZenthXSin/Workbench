package io.github.zenthxsin.rustwright.linux.impl

import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment

/** 内置 Linux 的状态：是否就绪、安装进度（0..1，未在安装为 null）、一句说明。 */
data class LinuxStatus(val ready: Boolean, val progress: Float?, val detail: String) {
    companion object {
        /** All managed-environment entry points use the installer's completion state. */
        fun of(linux: LinuxEnvironment?, provisioner: LinuxProvisioner?): LinuxStatus {
            val env = linux ?: return LinuxStatus(false, null, "这个平台没有内置 Linux")
            if (!env.managed) return LinuxStatus(true, null, "开发版 · 使用本机环境")
            provisioner?.takeIf { it.running }?.let {
                return LinuxStatus(false, it.progress, "安装中 ${(it.progress * 100).toInt()}%")
            }
            if (provisioner?.error != null) return LinuxStatus(false, null, "初始化失败，请在终端重试")
            if (!env.installed) return LinuxStatus(false, null, "未安装")
            // A leftover codex executable cannot prove setup completed. Its absolute symlink
            // also cannot be resolved using File.exists() outside the guest rootfs.
            val ready = provisioner?.ready ?: env.toHost(LinuxSetup.MARKER).isFile
            return LinuxStatus(ready, null, if (ready) "运行中" else "初始化未完成")
        }
    }
}
