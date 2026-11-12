package com.brobehave.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * The single Supabase client. It is created with the anon key only; every table is
 * protected by Row Level Security, and the service role key never ships in the app.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    /** Lenient about new server columns so an older app keeps working after a migration. */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideJson(): Json = json

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        defaultSerializer = KotlinXSerializer(json)
        install(Auth)
        install(Postgrest)
        install(Storage)
        install(Functions)
    }
}
