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
    // SDL 后端 natives（sdl-arc、SDL2）+ Arc 桌面 natives（arc、arc-freetype），SharedLibraryLoader 从 classpath 根目录解压
    resources.setSrcDirs(listOf("libs/linux64", "libs/windows64", "../../natives/desktop"))
}

dependencies {
    api(project(":arc:arc-core"))
}
