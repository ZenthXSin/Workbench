plugins {
    alias(libs.plugins.rw.android.library)
}

android {
    namespace = "io.github.zenthxsin.rustwright.platform.android"
    buildFeatures { buildConfig = true }
    // proot / talloc / shmem 预编译二进制（见 third_party/NOTICE-proot.md）
    sourceSets["main"].jniLibs.srcDirs("jniLibs")
}

dependencies {
    api(project(":arc:backends:backend-android"))
    api(project(":ui:designsystem"))
    api(project(":core:shell"))
    api(project(":core:storage"))
    api(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit4)
}
