plugins {
    id("brobehave.android.library")
    id("brobehave.hilt")
    id("brobehave.android.robolectric")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.brobehave.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(project(":core:testing"))
    // The integration test builds an in-memory Room database.
    testImplementation(libs.androidx.room.runtime)
}
