plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(project(":ui:widgets"))
    api(project(":core:codex"))
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
}
