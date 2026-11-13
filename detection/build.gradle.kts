import java.util.Properties

plugins {
    // Same setup as a feature module: Compose (disclosure screen), Hilt, the data layer.
    id("brobehave.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.brobehave.detection"
    buildFeatures.buildConfig = true
    defaultConfig {
        // Public key (base64, X.509) that blocklist updates must be signed with. It is not a
        // secret, but it is per-deployment; while blank, only the bundled list is used.
        val publicKey = localProperties.getProperty("BLOCKLIST_PUBLIC_KEY")
            ?: providers.environmentVariable("BLOCKLIST_PUBLIC_KEY").orNull ?: ""
        buildConfigField("String", "BLOCKLIST_PUBLIC_KEY", "\"$publicKey\"")
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
}
