package com.brobehave.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.brobehave.core.model.AppIconSwitcher
import com.brobehave.core.model.FeatureFlags
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@HiltAndroidApp
class BroBehaveApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** WorkManager builds workers through Hilt so they can use repositories. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}

/** Feature flags backed by per-flavor BuildConfig fields (see app/build.gradle.kts). */
class BuildConfigFeatureFlags @Inject constructor() : FeatureFlags {
    override val autoDetection: Boolean = BuildConfig.AUTO_DETECTION
}

/**
 * Discreet mode: exactly one of two launcher aliases is enabled. Android may take a few
 * seconds to refresh the launcher, and may close the app while it does.
 */
class AliasAppIconSwitcher @Inject constructor(@ApplicationContext private val context: Context) : AppIconSwitcher {
    private val defaultAlias = ComponentName(context, "${context.packageName}.LauncherDefault")
    private val discreetAlias = ComponentName(context, "${context.packageName}.LauncherDiscreet")

    override val isDiscreet: Boolean
        get() = context.packageManager.getComponentEnabledSetting(discreetAlias) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    override fun setDiscreet(enabled: Boolean) {
        setEnabled(discreetAlias, enabled)
        setEnabled(defaultAlias, !enabled)
    }

    private fun setEnabled(component: ComponentName, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    @Binds
    fun bindFeatureFlags(impl: BuildConfigFeatureFlags): FeatureFlags

    @Binds
    fun bindAppIconSwitcher(impl: AliasAppIconSwitcher): AppIconSwitcher
}
