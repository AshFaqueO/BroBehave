package com.brobehave.core.network

/** Build-time configuration that other modules may read. */
object NetworkConfig {
    /** OAuth web client ID for Google Sign-In; blank when Google Sign-In is not configured. */
    const val GOOGLE_WEB_CLIENT_ID: String = BuildConfig.GOOGLE_WEB_CLIENT_ID
}
