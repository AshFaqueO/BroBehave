package com.brobehave.core.model

/** Per-flavor switches. The implementation lives in `:app` and reads BuildConfig. */
interface FeatureFlags {
    /** Whether the optional on-device automatic detection (`:detection`) is offered at all. */
    val autoDetection: Boolean
}

/** Where a flagged day came from. Matches the Postgres `flag_source` enum. */
enum class FlagSource(val key: String) {
    AUTO("auto"),
    SELF("self"),
}
