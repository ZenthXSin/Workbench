plugins { alias(libs.plugins.rw.jvm.library) }

dependencies {
    api(project(":core:runtime"))
    testImplementation(libs.kotlinx.coroutines.test)
}
