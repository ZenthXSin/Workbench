plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(project(":core:runtime"))
    api(project(":core:storage"))
    implementation(libs.okhttp)
    // Android 框架自带 org.json；JVM（测试、将来的桌面/CLI）需要自带实现
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
