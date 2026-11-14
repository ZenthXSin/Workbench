plugins { alias(libs.plugins.rw.jvm.library) }
dependencies { api(project(":core:editor:impl")); api(project(":ui:editor:api")); implementation(libs.kotlinx.coroutines.core) }
