package com.yourname.womensafety.data

/**
 * Shared in-memory SOS state that bridges the UI layer ([SosViewModel]) and
 * the background [IotWearableManager].
 *
 * Because the ESP32 foreground service has no reference to any ViewModel,
 * this singleton lets the hardware button handler know:
 *  • whether an SOS is currently active (countdown or recently dispatched)
 *  • the active alert's ID (needed to call cancel / mark-safe)
 *  • whether a hardware-triggered SOS dispatch cooldown is in effect
 *
 * Thread-safety: all fields are @Volatile (single-writer, multi-reader).
 * Updates are always made on the coroutine that owns the result, so no
 * additional synchronisation is required.
 */
object IotSosTracker {

    /** The currently active alert ID, or null when no SOS is in progress. */
    @Volatile var activeAlertId: String? = null
        private set

    /** True while the 10-second countdown is running (triggered, not yet dispatched). */
    @Volatile var isCountdownActive: Boolean = false
        private set

    /** True once the alert has been dispatched to contacts. */
    @Volatile var isDispatched: Boolean = false
        private set

    /** Epoch-millis timestamp when the alert was dispatched (0 if not dispatched). */
    @Volatile var dispatchedAt: Long = 0L
        private set

    /**
     * True if the current (or most recently tracked) alert was triggered by the
     * hardware wearable button.  Used to enforce the 10-minute hardware cooldown.
     */
    @Volatile var isHardwareTriggered: Boolean = false
        private set

    // ── Writers (called by IotWearableManager) ───────────────────────────

    /**
     * Record that the hardware button just created a new SOS alert.
     * Clears any previous dispatch state and starts the countdown phase.
     */
    fun onHardwareAlertCreated(alertId: String) {
        activeAlertId      = alertId
        isCountdownActive  = true
        isDispatched       = false
        dispatchedAt       = 0L
        isHardwareTriggered = true
    }

    // ── Writers (called by SosViewModel) ─────────────────────────────────

    /**
     * Record that the UI (manual tap or Auto SOS) created a new SOS alert.
     * Only updates the tracker when the alertId is genuinely new — prevents
     * overwriting a hardware-triggered entry when the SOS screen opens with
     * the same alertId.
     */
    fun onUiAlertCreated(alertId: String) {
        if (activeAlertId == alertId) return   // already tracked (e.g. by hardware)
        activeAlertId      = alertId
        isCountdownActive  = true
        isDispatched       = false
        dispatchedAt       = 0L
        isHardwareTriggered = false
    }

    /**
     * Record that the alert was dispatched to contacts
     * (sendNow / countdown auto-fired).
     */
    fun onAlertDispatched(alertId: String) {
        // Guard: only update if this is the alert we're tracking
        if (activeAlertId == alertId || activeAlertId == null) {
            activeAlertId     = alertId
            isCountdownActive = false
            isDispatched      = true
            dispatchedAt      = System.currentTimeMillis()
        }
    }

    /** Record that the alert was cancelled or the user marked themselves safe. */
    fun onAlertResolved() {
        activeAlertId      = null
        isCountdownActive  = false
        isDispatched       = false
        dispatchedAt       = 0L
        // NOTE: isHardwareTriggered stays true so the cooldown check still works
        // until isInHardwareCooldown() returns false naturally after 10 minutes.
    }

    // ── Queries (called by IotWearableManager) ───────────────────────────

    /**
     * Returns true when a hardware double-tap should trigger "I'm Safe" / cancel.
     *
     * Covers two cases:
     *  • SOS is in the countdown phase (not yet dispatched)
     *  • SOS was dispatched in the last 60 seconds
     */
    fun isActiveOrRecentlyDispatched(): Boolean {
        if (activeAlertId == null) return false
        if (isCountdownActive) return true
        if (!isDispatched) return false
        return (System.currentTimeMillis() - dispatchedAt) < 60_000L
    }

    /**
     * Returns true during the 10-minute window after a **hardware-triggered**
     * SOS was dispatched, AND immediately when a hardware SOS is in its countdown
     * phase (not yet dispatched).  While true, new hardware single-taps are suppressed.
     *
     * Covering the countdown phase prevents the first press of an intended
     * cancel double-tap (gap > 1.5 s from the original trigger press) from being
     * mis-classified as a single-tap that spawns a second SOS alert.
     */
    fun isInHardwareCooldown(): Boolean {
        if (!isHardwareTriggered) return false
        if (isCountdownActive) return true          // block new HW triggers during countdown
        if (!isDispatched) return false
        return (System.currentTimeMillis() - dispatchedAt) < 600_000L  // 10 min
    }
}
