package com.brobehave.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brobehave.app.navigation.BroBehaveNavShell
import com.brobehave.core.designsystem.theme.BroBehaveTheme
import dagger.hilt.android.AndroidEntryPoint

/** The single activity; everything else is Compose. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Username from an invite link waiting to be handled. */
    private var inviteUsername by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) inviteUsername = intent.inviteUsername()
        setContent {
            BroBehaveTheme {
                BroBehaveNavShell(
                    inviteUsername = inviteUsername,
                    onInviteHandled = { inviteUsername = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.inviteUsername()?.let { inviteUsername = it }
    }

    /** Both invite link forms carry the username as `?u=<username>`. */
    private fun Intent.inviteUsername(): String? {
        val link = data?.takeIf { action == Intent.ACTION_VIEW && it.isHierarchical } ?: return null
        return link.getQueryParameter("u")?.lowercase()?.takeIf(USERNAME::matches)
    }

    private companion object {
        val USERNAME = Regex("^[a-z0-9_]{3,20}$")
    }
}
