package rw.convention

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** 纯 Kotlin/JVM 库（core、modules 的 api/impl 等）。 */
class JvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")
        configureKotlin()
        configureJavaTarget()
        configureJUnit()
        enforceModuleBoundaries()
        // jar 名用完整路径（modules-pic-impl.jar），避免多个 impl.jar 在分发包里重名
        extensions.configure<org.gradle.api.plugins.BasePluginExtension> {
            archivesName.set(path.removePrefix(":").replace(':', '-'))
        }
    }
}

/** Android 库（platform/android 等只能在 Android 上存在的代码）。 */
class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<LibraryExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig.minSdk = MIN_SDK
            compileOptions {
                sourceCompatibility = BYTECODE
                targetCompatibility = BYTECODE
            }
            sourceSets.getByName("main").java.srcDirs("src/main/kotlin")
        }
        configureKotlin()
        enforceModuleBoundaries()
    }
}

/** Android 应用（app、原型、各模块 sample）。 */
class AndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<ApplicationExtension> {
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
                ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            compileOptions {
                sourceCompatibility = BYTECODE
                targetCompatibility = BYTECODE
            }
            packaging.jniLibs.useLegacyPackaging = true
            sourceSets.getByName("main").java.srcDirs("src/main/kotlin")
        }
        configureKotlin()
        enforceModuleBoundaries()
    }
}
