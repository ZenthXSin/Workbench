plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(project(":ui:widgets"))
    api(project(":core:agent"))
    api(project(":core:account"))
}
