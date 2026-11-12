plugins {
    id("brobehave.android.library")
    id("brobehave.android.room")
    id("brobehave.hilt")
    id("brobehave.android.robolectric")
}

android {
    namespace = "com.brobehave.core.database"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)
}
