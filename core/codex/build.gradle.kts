plugins {
    alias(libs.plugins.rw.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:runtime"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":core:runtime"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// The real app-server smoke test is optional; switching CODEX_BIN must invalidate
// a previous run that returned early without executing the external binary.
tasks.withType<Test>().configureEach {
    inputs.property("codexBinary", providers.environmentVariable("CODEX_BIN").orElse(""))
}
