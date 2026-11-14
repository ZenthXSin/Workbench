plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "arc.backend.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].apply {
        java.setSrcDirs(listOf("src"))
        // Arc 预编译 natives（libarc、libarc-freetype）
        jniLibs.setSrcDirs(listOf("../../natives/android"))
    }
}

dependencies {
    api(project(":arc:arc-core"))
}
