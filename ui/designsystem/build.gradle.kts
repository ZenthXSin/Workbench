plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(project(":arc:arc-core"))
    api(project(":arc:extensions:freetype"))
    api(project(":ui:dsl"))
    api(project(":core:runtime"))
}
