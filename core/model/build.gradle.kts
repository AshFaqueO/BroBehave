plugins {
    id("brobehave.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Model types double as the wire format for Supabase rows and RPC results.
    api(libs.kotlinx.serialization.json)
}
