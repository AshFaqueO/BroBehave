plugins {
    id("brobehave.android.library")
    id("brobehave.android.compose")
    id("brobehave.android.robolectric")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.brobehave.core.designsystem"
}

// Golden images live in the repo so screenshot changes show up in review.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
