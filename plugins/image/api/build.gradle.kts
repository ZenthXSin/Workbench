plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(project(":core:runtime"))
    api(project(":plugins:image:imaging"))
}
