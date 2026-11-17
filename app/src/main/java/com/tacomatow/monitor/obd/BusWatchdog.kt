package com.tacomatow.monitor.obd

/**
 * Decides when a live link has stopped being a live *bus*.
 *
 * This is a different failure from a dropped connection. The socket stays up,
 * `AT RV`/`AT I` keep answering, and every vehicle request returns NO DATA
 * until the dongle is physically unplugged — the cheap-clone wedge the adapter
 * check already describes ("it never emits a refusal, it just goes quiet").
 * The old advice was to walk back to the OBD port, which is exactly what this
 * app exists to avoid.
 *
 * Pure on purpose: the policy is worth testing, and the recovery itself (AT WS
 * then AT Z) lives in [Elm327Client.recoverBus], where it can be replayed
 * against a scripted adapter.
 */
object BusWatchdog {

    /** No vehicle data for this long is abnormal once the engine is running. */
    const val SILENCE_MS = 20_000L

    /** Between resets. A dongle that is recovering should not be reset again at once. */
    const val BACKOFF_MS = 90_000L

    /** Warm start, then cold. Both are the software version of an unplug. */
    const val MAX_RESETS = 2

    /**
     * Below charging voltage the engine is off and silence is *expected* — the
     * ECUs go to sleep a minute or so after the key comes out. A reset there
     * would be noise at every stop, so the ladder waits instead.
     */
    const val ENGINE_OFF_MAX_V = 12.9f

    enum class Action {
        /** Nothing to do. */
        NONE,

        /** Silent, but the adapter says the engine is off. Expect it; do not reset. */
        WAIT_ENGINE_OFF,

        /** Warm start (AT WS): drops the ELM's command state and its CAN controller. */
        RESET_WARM,

        /** Cold start (AT Z): stronger, and the last thing software can do. */
        RESET_COLD,

        /** Both resets were spent. Say so once, then stop. */
        GIVE_UP,

        /**
         * The adapter stopped answering AT commands during a reset, so no
         * further reset is possible — a different fact from spent resets, and
         * it must not be reported as one.
         */
        ADAPTER_GONE,
    }

    /**
     * [lastDataAt] is when a gauge last had a value (or the session opened, for
     * a dongle that has never delivered one). [volts] is the adapter's own
     * reading — it answers even when the CAN side is wedged, which is what
     * makes it usable as the engine-off discriminator. [adapterSilent] means
     * the last reset found the adapter not answering AT commands either.
     */
    fun decide(
        now: Long,
        lastDataAt: Long,
        lastAttemptAt: Long,
        volts: Float?,
        resets: Int,
        adapterSilent: Boolean,
        giveUpHandled: Boolean,
        paused: Boolean,
        handoffArmed: Boolean,
    ): Action {
        // Another screen owns the dongle, or the app handed it to another
        // app: resetting it here would fight whoever is using it.
        if (paused || handoffArmed) return Action.NONE
        if (giveUpHandled) return Action.NONE
        if (now - lastDataAt < SILENCE_MS) return Action.NONE
        if (adapterSilent) return Action.ADAPTER_GONE
        if (resets >= MAX_RESETS) return Action.GIVE_UP
        if (resets > 0 && now - lastAttemptAt < BACKOFF_MS) return Action.NONE
        if (volts != null && volts < ENGINE_OFF_MAX_V) return Action.WAIT_ENGINE_OFF
        return if (resets == 0) Action.RESET_WARM else Action.RESET_COLD
    }
}
