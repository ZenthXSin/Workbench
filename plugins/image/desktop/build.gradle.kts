plugins { alias(libs.plugins.rw.jvm.library) }

dependencies {
    api(project(":plugins:image:api"))
    implementation("org.apache.xmlgraphics:batik-transcoder:1.19")
}
