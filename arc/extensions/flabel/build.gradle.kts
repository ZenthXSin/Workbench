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

dependencies {
    api(project(":arc:arc-core"))
}
