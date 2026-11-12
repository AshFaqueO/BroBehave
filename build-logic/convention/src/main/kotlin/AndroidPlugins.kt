import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.brobehave.buildlogic.TARGET_SDK
import com.brobehave.buildlogic.configureAndroidCommon
import com.brobehave.buildlogic.configureUnitTestDependencies
import com.brobehave.buildlogic.lib
import com.brobehave.buildlogic.libs
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

// AGP 9 has built-in Kotlin support, so no kotlin-android plugin is applied anywhere.

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("brobehave.quality")
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = TARGET_SDK
        }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("brobehave.quality")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}

/** Compose compiler + the Compose BOM. Apply after an Android application/library plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        }
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        }
        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            add("implementation", bom)
            add("testImplementation", bom)
            add("implementation", libs.lib("androidx-compose-ui"))
            add("implementation", libs.lib("androidx-compose-foundation"))
            add("implementation", libs.lib("androidx-compose-material3"))
            add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        }
    }
}

/** A feature module: Android library + Compose + Hilt + the shared UI modules. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("brobehave.android.library")
        pluginManager.apply("brobehave.android.compose")
        pluginManager.apply("brobehave.hilt")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:data"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("testImplementation", project(":core:testing"))
        }
    }
}

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        dependencies {
            add("implementation", libs.lib("androidx-room-runtime"))
            add("implementation", libs.lib("androidx-room-ktx"))
            add("ksp", libs.lib("androidx-room-compiler"))
        }
    }
}

/** Robolectric (JUnit 4, run through the JUnit Vintage engine) for JVM tests that need Android. */
class AndroidRobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        dependencies {
            add("testImplementation", libs.lib("junit4"))
            add("testRuntimeOnly", libs.lib("junit-vintage-engine"))
            add("testImplementation", libs.lib("robolectric"))
            add("testImplementation", libs.lib("androidx-test-core"))
            add("testImplementation", libs.lib("androidx-test-ext-junit"))
        }
    }
}

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")
        dependencies {
            add("implementation", libs.lib("hilt-android"))
            add("ksp", libs.lib("hilt-compiler"))
        }
    }
}

/** Pure Kotlin/JVM module (no Android dependencies). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("brobehave.quality")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureUnitTestDependencies()
    }
}

/** ktlint + detekt with the shared config. */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        pluginManager.apply("io.gitlab.arturbosch.detekt")
        extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            source.setFrom("src/main/kotlin", "src/test/kotlin", "src/play/kotlin", "src/github/kotlin")
        }
    }
}
