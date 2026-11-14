plugins {
    alias(libs.plugins.rw.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:runtime"))
    api(libs.kotlinx.serialization.json)
}
