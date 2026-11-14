plugins { alias(libs.plugins.rw.jvm.library); application }
dependencies { implementation(project(":products:workbench")); implementation(project(":plugins:image:desktop")); implementation(project(":platform:desktop")); testImplementation(project(":ui:editor")) }
application {
    mainClass.set("io.github.zenthxsin.rustwright.workbench.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "--add-opens", "java.base/java.io=ALL-UNNAMED")
}

val prepareEditorHostNative by tasks.registering(Exec::class) {
    dependsOn(tasks.named("installDist"))
    onlyIf { providers.gradleProperty("editorHostNative").map(String::toBoolean).getOrElse(false) }
    commandLine("bash", project(":core:runtime").projectDir.parentFile.parentFile.resolve("tools/build-host-native.sh"))
}

tasks.register<JavaExec>("editorSmokeTest") {
    dependsOn(tasks.named("testClasses"), prepareEditorHostNative)
    classpath = sourceSets["test"].output + files(fileTree(layout.buildDirectory.dir("install/workbench/lib")) { include("*.jar") })
    mainClass.set("io.github.zenthxsin.rustwright.workbench.EditorSmokeKt")
    systemProperty("workbench.home", providers.gradleProperty("editorSmokeHome").getOrElse(layout.buildDirectory.dir("editor-smoke-home").get().asFile.absolutePath))
    systemProperty("rw.nosplash", "1")
}
