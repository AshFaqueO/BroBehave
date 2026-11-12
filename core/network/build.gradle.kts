import java.util.Properties

plugins {
    id("brobehave.android.library")
    id("brobehave.hilt")
    alias(libs.plugins.kotlin.serialization)
}

// The anon key is public by design (RLS protects the data) but still stays out of git:
// it comes from local.properties or CI environment variables. The service role key is
// never used by the app.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun secret(name: String, default: String): String =
    localProperties.getProperty(name) ?: providers.environmentVariable(name).orNull ?: default

android {
    namespace = "com.brobehave.core.network"
    buildFeatures.buildConfig = true
    defaultConfig {
        // 10.0.2.2 is the host machine as seen from the Android emulator (local Supabase).
        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL", "http://10.0.2.2:54321")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY", "missing-anon-key")}\"")
    }
}

dependencies {
    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.supabase.postgrest)
    api(libs.supabase.storage)
    api(libs.supabase.functions)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
