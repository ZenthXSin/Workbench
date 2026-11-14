plugins { alias(libs.plugins.rw.jvm.library) }
dependencies { api(libs.kotlinx.coroutines.core); api(project(":ui:widgets")); api(project(":ui:kit")) }
