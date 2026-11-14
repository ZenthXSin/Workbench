plugins { alias(libs.plugins.rw.android.application) }

android {
    namespace = "io.github.workbench"
    defaultConfig {
        applicationId = "io.github.workbench"
        versionCode = 3
        versionName = "0.3.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
}

dependencies {
    implementation(project(":platform:android"))
    implementation(project(":products:workbench"))
    implementation(project(":plugins:image:android"))
}
