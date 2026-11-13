package com.brobehave.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

const val COMPILE_SDK = 37
const val TARGET_SDK = 37
const val MIN_SDK = 26

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Settings shared by the application and every Android library module. */
fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = COMPILE_SDK
        defaultConfig.minSdk = MIN_SDK
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.all { it.useJUnitPlatform() }
        lint.warningsAsErrors = false
        lint.abortOnError = true
    }
    configureUnitTestDependencies()
}

/** JUnit 5 (Jupiter) + MockK + Turbine + coroutines-test for every module. */
fun Project.configureUnitTestDependencies() {
    dependencies {
        add("testImplementation", platform(libs.lib("junit-bom")))
        add("testImplementation", libs.lib("junit-jupiter"))
        add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        add("testImplementation", libs.lib("mockk"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    }
    tasks.withType(Test::class.java).configureEach {
        useJUnitPlatform()
        // Placeholder modules have no tests yet; Gradle 9 fails such tasks by default.
        failOnNoDiscoveredTests.set(false)
        // The integration tests switch on with this variable; declare it as an input so a
        // cached "skipped" result is never reused once it is set.
        inputs.property("supabaseTestUrl", providers.environmentVariable("SUPABASE_TEST_URL").orElse(""))
    }
}
