plugins {
    alias(libs.plugins.rw.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":plugins:image:api"))
    implementation(project(":plugins:image:imaging"))
    implementation(project(":core:ai"))
    implementation(project(":core:account"))
    implementation(project(":core:agent"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:kit"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
