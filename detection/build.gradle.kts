plugins {
    id("brobehave.android.library")
}

android {
    namespace = "com.brobehave.detection"
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(project(":core:testing"))
}
