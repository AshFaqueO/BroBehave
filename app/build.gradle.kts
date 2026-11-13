plugins {
    id("brobehave.android.application")
    id("brobehave.android.compose")
    id("brobehave.hilt")
    id("brobehave.android.robolectric")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// Firebase needs app/google-services.json (git-ignored). Without it the app still builds
// and runs; push and crash reporting are simply inactive.
if (file("google-services.json").exists()) {
    pluginManager.apply("com.google.gms.google-services")
    pluginManager.apply("com.google.firebase.crashlytics")
} else {
    logger.lifecycle("BroBehave: app/google-services.json not found — FCM and Crashlytics are inactive.")
}

val webBaseUrl = providers.gradleProperty("brobehave.webBaseUrl").get()
val webUri = uri(webBaseUrl)

android {
    namespace = "com.brobehave.app"

    // Release signing comes from environment variables (CI secrets) and is never committed.
    // Without them, release builds are simply unsigned.
    val keystoreFile = providers.environmentVariable("BROBEHAVE_KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("BROBEHAVE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("BROBEHAVE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("BROBEHAVE_KEY_PASSWORD").orNull
            }
        }
    }

    defaultConfig {
        applicationId = "com.brobehave.app"
        versionCode = 1
        versionName = "0.5.0"
        // Invite links: https://<host><path>/invite/?u=<username>
        manifestPlaceholders["inviteHost"] = webUri.host
        manifestPlaceholders["invitePath"] = webUri.path.trimEnd('/') + "/invite"
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
            val repo = providers.gradleProperty("brobehave.githubRepo").get()
            buildConfigField("String", "GITHUB_REPO", "\"$repo\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // The release code (R8-shrunk) pointed at the local Supabase stack, for testing what
        // actually ships. Debug-signed and allowed plain http; never distributed.
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            versionNameSuffix = "-staging"
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

    // Distribution channel specifics.
    "playImplementation"(libs.play.app.update)
    "playImplementation"(libs.play.review)

    // Lets the baseline profile shipped in the APK be installed on first run.
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    // Firebase: FCM + Crashlytics only. No analytics, no other trackers.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)

    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
