tasks.register("verifyWorkbenchIsolation") {
    group = "verification"
    doLast {
        val baseRoot = (rootProject.extra["workbenchRoot"] as java.io.File).canonicalFile
        baseRoot.resolve("gradle/foundation-modules.txt").readLines().filter { it.startsWith(":") }.forEach { path ->
            check(project(path).projectDir.canonicalFile == baseRoot.resolve(path.removePrefix(":").replace(':', '/')).canonicalFile) {
                "Foundation project $path must use its source from the foundation repository"
            }
        }
        allprojects.filter { it != rootProject && it.projectDir.canonicalFile.toPath().startsWith(baseRoot.toPath()) }.forEach { p ->
            p.configurations.forEach { configuration ->
                configuration.dependencies.withType<org.gradle.api.artifacts.ProjectDependency>().forEach { dep ->
                    val target = dep.dependencyProject
                    check(target.projectDir.canonicalFile.toPath().startsWith(baseRoot.toPath())) {
                        "${p.path}:${configuration.name} depends on consuming-product project ${target.path}"
                    }
                    if (p.path in setOf(":core:editor:api", ":ui:editor:api")) check(!target.path.endsWith(":impl")) { "${p.path} must not depend on implementation ${target.path}" }
                    if (p.path == ":app-shared" || listOf(":core:", ":ui:", ":modules:linux:").any { p.path.startsWith(it) }) {
                        check(!target.path.startsWith(":plugins:") && !target.path.startsWith(":products:")) { "${p.path} host must be independent of ${target.path}" }
                    }
                }
            }
        }
        logger.lifecycle("Workbench sources and dependencies are owned by the foundation repository")
    }
}
