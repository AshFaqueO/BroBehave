plugins {
    id("brobehave.android.application")
    id("brobehave.android.compose")
    id("brobehave.hilt")
    id("brobehave.android.robolectric")
    alias(libs.plugins.kotlin.serialization)
}

// Firebase needs app/google-services.json (git-ignored). Without it the app still builds
// and runs; push and crash reporting are simply inactive.
if (file("google-services.json").exists()) {
    pluginManager.apply("com.google.gms.google-services")
    pluginManager.apply("com.google.firebase.crashlytics")
} else {
    logger.lifecycle("BroBehave: app/google-services.json not found — FCM and Crashlytics are inactive.")
}

android {
    namespace = "com.brobehave.app"

    defaultConfig {
        applicationId = "com.brobehave.app"
        versionCode = 1
        versionName = "0.2.0"
        // PLACEHOLDER: host of the invite App Links (https://<host>/u/<username>).
        manifestPlaceholders["inviteHost"] = "brobehave.app"
    }

    buildFeatures.buildConfig = true

    // Two distribution channels with the same features unless a flag turns one off.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "AUTO_DETECTION", "true")
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "AUTO_DETECTION", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing (from environment variables / GitHub Secrets) is added in Phase 5.
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:profile"))
    implementation(project(":detection"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager with Hilt-injected workers (offline sync).
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Firebase: FCM + Crashlytics only. No analytics, no other trackers.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
