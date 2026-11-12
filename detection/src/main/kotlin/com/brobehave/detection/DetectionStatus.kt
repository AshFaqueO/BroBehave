package com.brobehave.detection

/**
 * State of the optional on-device automatic detection. The engine itself (a DNS-only
 * VpnService) is built in Phase 4; the whole module is gated behind
 * `FeatureFlags.autoDetection` and is OFF by default.
 */
enum class DetectionStatus {
    /** The user has not turned detection on. */
    OFF,

    /** Running; a heartbeat is recorded once per local day. */
    ACTIVE,

    /** Another VPN took over the tunnel, so nothing is being checked. */
    REVOKED,
}
