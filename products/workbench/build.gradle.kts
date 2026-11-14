plugins { alias(libs.plugins.rw.jvm.library) }
dependencies {
    api(project(":app-shared"))
    api(project(":plugins:image:integration"))
    testImplementation(project(":core:agent"))
    testImplementation(project(":core:storage"))
}
