plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    implementation(project(":core:shell"))
    implementation(project(":core:terminal"))
    implementation(project(":core:agent"))
    implementation(project(":ui:widgets"))
    implementation(project(":ui:kit"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// 终端集成回归使用宿主编译的 JNI，避免随包桌面库的 glibc 版本影响测试。
val testPtyDir = layout.buildDirectory.dir("test-native")
val testPtySource = project(":platform:android").layout.projectDirectory.file("native/termux/termux.c")
val compileTestPty by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") in setOf("amd64", "x86_64") }
    inputs.file(testPtySource)
    outputs.file(testPtyDir.map { it.file("libtermux.so") })
    doFirst { testPtyDir.get().asFile.mkdirs() }
    commandLine(
        "cc", "-D_GNU_SOURCE", "-shared", "-fPIC", "-O2",
        "-I${System.getProperty("java.home")}/include", "-I${System.getProperty("java.home")}/include/linux",
        testPtySource.asFile.absolutePath, "-o", testPtyDir.get().file("libtermux.so").asFile.absolutePath,
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(compileTestPty)
    jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
    systemProperty("java.library.path", testPtyDir.get().asFile.absolutePath)
}
