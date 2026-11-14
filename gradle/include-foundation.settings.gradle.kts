val workbenchRoot = extra["workbenchRoot"] as java.io.File
val modules = workbenchRoot.resolve("gradle/foundation-modules.txt").readLines().filter { it.startsWith(":") }
modules.forEach { path ->
    include(path)
    val parts = path.removePrefix(":").split(':')
    parts.indices.forEach { index ->
        val parent = ":" + parts.take(index + 1).joinToString(":")
        project(parent).projectDir = workbenchRoot.resolve(parts.take(index + 1).joinToString("/"))
    }
}
