package com.brobehave.core.model

/**
 * Discreet mode: swaps the launcher icon and name for a neutral one. Implemented in
 * `:app` with activity aliases.
 */
interface AppIconSwitcher {
    val isDiscreet: Boolean

    fun setDiscreet(enabled: Boolean)
}
