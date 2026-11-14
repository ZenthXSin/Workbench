package rw.convention

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** 全工程统一：JDK 21 编译，产物字节码 17（Android 可用）。 */
internal const val JDK = 21
internal val BYTECODE = JavaVersion.VERSION_17
internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26
internal const val TARGET_SDK = 36

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.configureKotlin() {
    extensions.getByType<KotlinProjectExtension>().jvmToolchain(JDK)
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

internal fun Project.configureJavaTarget() {
    extensions.findByType(JavaPluginExtension::class.java)?.apply {
        sourceCompatibility = BYTECODE
        targetCompatibility = BYTECODE
    }
}

internal fun Project.configureJUnit() {
    dependencies {
        add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
        add("testImplementation", libs.findLibrary("junit-jupiter").get())
        add("testImplementation", kotlin("test"))
        add("testRuntimeOnly", libs.findLibrary("junit-launcher").get())
    }
    // 测试用本机假服务器：放开服务地址白名单里的回环地址（只对测试进程）
    tasks.withType<Test>().configureEach { useJUnitPlatform(); systemProperty("rw.devRelay", "true") }
}

private fun org.gradle.api.artifacts.dsl.DependencyHandler.kotlin(module: String): String = "org.jetbrains.kotlin:kotlin-$module"

/**
 * 模块边界：`:modules:<a>:*` 不得依赖 `:modules:<b>:impl`（b ≠ a），模块之间只能通过 api 通信。
 * 在依赖解析前检查项目依赖，越界直接构建失败。
 */
internal fun Project.enforceModuleBoundaries() {
    val own = path.split(':').let { if (it.size > 2 && it[1] == "modules") it[2] else null }
    afterEvaluate {
        configurations.forEach { conf ->
            conf.dependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java).forEach { dep ->
                val target = dep.path.split(':')
                if (target.size > 3 && target[1] == "modules" && target[3] == "impl" && target[2] != own && !path.startsWith(":app")) {
                    throw org.gradle.api.GradleException("模块边界：$path 不能依赖 ${dep.path}，请改用 :modules:${target[2]}:api")
                }
            }
        }
    }
}
