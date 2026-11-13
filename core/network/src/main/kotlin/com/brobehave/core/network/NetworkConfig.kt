package com.brobehave.core.network

/** Build-time configuration that other modules may read. */
object NetworkConfig {
    /** OAuth web client ID for Google Sign-In; blank when Google Sign-In is not configured. */
    const val GOOGLE_WEB_CLIENT_ID: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    const val SUPABASE_URL: String = BuildConfig.SUPABASE_URL

    /** The public anon key (never the service role key). */
    const val SUPABASE_ANON_KEY: String = BuildConfig.SUPABASE_ANON_KEY
}
