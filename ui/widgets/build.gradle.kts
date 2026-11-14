plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(project(":core:editor:api"))
    testImplementation(project(":core:editor:impl"))
    api(project(":ui:designsystem"))
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.kotlinx.coroutines.core)
}
