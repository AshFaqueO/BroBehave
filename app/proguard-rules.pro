# App-level R8 rules. Library-specific rules (Ktor, kotlinx.serialization, Hilt, Room,
# supabase-kt) are reviewed and completed in Phase 5; most ship as consumer rules already.

# Keep line numbers so Crashlytics stack traces are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Play In-App Review (play flavor) references a compile-time-only Google annotation that is
# not on the runtime classpath. It has no runtime effect, so the missing class is harmless.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
