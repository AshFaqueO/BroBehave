plugins {
    id("brobehave.android.feature")
}

android {
    namespace = "com.brobehave.feature.auth"
}

dependencies {
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.playServicesAuth)
    implementation(libs.googleid)
}
