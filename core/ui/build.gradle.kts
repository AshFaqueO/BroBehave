plugins {
    id("brobehave.android.library")
    id("brobehave.android.compose")
}

android {
    namespace = "com.brobehave.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
}
