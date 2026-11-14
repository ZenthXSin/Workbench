plugins { alias(libs.plugins.rw.jvm.library) }

dependencies {
    api(project(":app-shared"))
    api(project(":arc:backends:backend-sdl"))
    api(project(":arc:extensions:freetype"))
    runtimeOnly(libs.org.json)
}
