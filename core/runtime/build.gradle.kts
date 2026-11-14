plugins {
    alias(libs.plugins.rw.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Optional consuming-product integration fixtures; no signing material is included here.
tasks.withType<Test>().configureEach {
    listOf("rw.sealedApk", "rw.plainApk", "rw.certDer", "rw.resSecret").forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
}
