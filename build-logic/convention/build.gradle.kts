plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "rw.jvm.library"
            implementationClass = "rw.convention.JvmLibraryPlugin"
        }
        register("androidLibrary") {
            id = "rw.android.library"
            implementationClass = "rw.convention.AndroidLibraryPlugin"
        }
        register("androidApplication") {
            id = "rw.android.application"
            implementationClass = "rw.convention.AndroidApplicationPlugin"
        }
    }
}
