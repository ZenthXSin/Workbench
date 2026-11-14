plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

sourceSets.main {
    java.setSrcDirs(listOf("src"))
}

val unsafeJar = files("unsafe/unsafe.jar")

dependencies {
    // UnsafeBuffers 预编译产物。上游是 compileOnly + 打进 jar，但 Android 构建直接用 classes 目录不读 jar，
    // 会在运行时 NoClassDefFoundError（0.1.x 启动闪退的原因）；改为普通依赖，各平台都会打包。
    api(unsafeJar)
}
