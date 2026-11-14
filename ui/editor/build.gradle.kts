plugins { alias(libs.plugins.rw.jvm.library) }
// Compatibility assembly; public contracts and implementations are separate modules.
dependencies { api(project(":ui:editor:api")); api(project(":ui:editor:impl")) }
