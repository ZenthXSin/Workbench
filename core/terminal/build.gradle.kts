plugins {
    alias(libs.plugins.rw.jvm.library)
}

// Termux terminal-emulator（Apache-2.0，见 VENDOR.md）+ Rustwright 的 PTY 会话
dependencies {
    api(project(":core:shell"))
    api(libs.kotlinx.coroutines.core)
}
