package com.brobehave.core.model

import kotlinx.coroutines.flow.StateFlow

enum class DetectionStatus {
    /** The user has not turned detection on. */
    OFF,

    /** Running; a heartbeat is recorded once per local day. */
    ACTIVE,

    /** Another VPN took over (or the user disconnected it in system settings): nothing is checked. */
    REVOKED,
}

data class DetectionState(
    val status: DetectionStatus = DetectionStatus.OFF,
    /**
     * True when the phone's Private DNS setting names a provider: lookups are then
     * encrypted straight to that provider and detection cannot see them.
     */
    val privateDnsBypass: Boolean = false,
)

/**
 * What the rest of the app may know about and do with automatic detection. Implemented
 * by `:detection`; turning it ON goes through the disclosure screen and the system VPN
 * dialog, so there is deliberately no `start()` here.
 */
interface DetectionGateway {
    /** False when the feature flag switches detection off for this build. */
    val available: Boolean

    val state: StateFlow<DetectionState>

    fun stop()
}
