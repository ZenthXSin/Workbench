plugins {
    alias(libs.plugins.rw.jvm.library)
}

// 平台无关的应用外壳与组合根（Android 与桌面启动器共用）
dependencies {
    api(project(":ui:designsystem"))
    api(project(":core:shell"))
    api(project(":core:storage"))
    implementation(project(":core:update"))
    api(project(":core:account"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:agent"))
    implementation(project(":ui:codex"))
    api(project(":ui:kit"))
    api(project(":core:codex"))
    api(project(":core:agent"))
    api(project(":core:workspace"))
    implementation(project(":ui:editor"))
    implementation(project(":modules:linux:impl"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
