plugins {
    `kotlin-dsl`
}

group = "com.brobehave.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "brobehave.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "brobehave.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "brobehave.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "brobehave.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidRoom") {
            id = "brobehave.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("androidRobolectric") {
            id = "brobehave.android.robolectric"
            implementationClass = "AndroidRobolectricConventionPlugin"
        }
        register("hilt") {
            id = "brobehave.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "brobehave.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "brobehave.quality"
            implementationClass = "QualityConventionPlugin"
        }
    }
}
