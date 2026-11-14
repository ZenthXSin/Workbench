plugins { alias(libs.plugins.rw.android.library) }
android { namespace = "io.github.zenthxsin.rustwright.image.android" }
dependencies {
    api(project(":plugins:image:api"))
    implementation("com.caverock:androidsvg-aar:1.4")
}
