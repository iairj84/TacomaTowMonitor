package com.tacomatow.monitor.obd

import com.tacomatow.monitor.obd.BusWatchdog.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The point of these is the cases that must NOT reset: a parked truck, a
 * handed-off dongle, and a reset that is still cooling down. A watchdog that
 * fires there is worse than none — it resets a healthy adapter at every stop
 * and logs noise the owner cannot act on.
 */
class BusWatchdogTest {

    private fun decide(
        now: Long = 1_000_000L,
        lastDataAt: Long = 1_000_000L,
        lastAttemptAt: Long = 0L,
        volts: Float? = 14.1f,
        resets: Int = 0,
        adapterSilent: Boolean = false,
        giveUpHandled: Boolean = false,
        paused: Boolean = false,
        handoffArmed: Boolean = false,
    ): Action = BusWatchdog.decide(
        now, lastDataAt, lastAttemptAt, volts, resets, adapterSilent,
        giveUpHandled, paused, handoffArmed,
    )

    @Test
    fun `data still flowing is left alone`() {
        assertEquals(Action.NONE, decide(lastDataAt = 999_000L))
    }

    @Test
    fun `a running engine with a silent bus gets a warm start first`() {
        assertEquals(
            Action.RESET_WARM,
            decide(lastDataAt = 1_000_000L - BusWatchdog.SILENCE_MS, volts = 14.2f),
        )
    }

    @Test
    fun `a second reset escalates to a cold start`() {
        assertEquals(
            Action.RESET_COLD,
            decide(
                lastDataAt = 1_000_000L - 60_000L,
                lastAttemptAt = 1_000_000L - BusWatchdog.BACKOFF_MS,
                resets = 1,
            ),
        )
    }

    @Test
    fun `resets are spaced out`() {
        assertEquals(
            Action.NONE,
            decide(lastAttemptAt = 1_000_000L - 5_000L, resets = 1),
        )
    }

    @Test
    fun `engine off is silence, not a fault`() {
        // 12.4 V key-off: the ECUs are asleep and a reset would only be noise.
        assertEquals(
            Action.WAIT_ENGINE_OFF,
            decide(lastDataAt = 1_000_000L - 60_000L, volts = 12.4f),
        )
    }

    @Test
    fun `an unknown voltage still tries`() {
        assertEquals(
            Action.RESET_WARM,
            decide(lastDataAt = 1_000_000L - 60_000L, volts = null),
        )
    }

    @Test
    fun `a handed-off dongle is never touched`() {
        assertEquals(
            Action.NONE,
            decide(lastDataAt = 1_000_000L - 60_000L, handoffArmed = true),
        )
    }

    @Test
    fun `another screen owning the adapter suppresses the reset`() {
        assertEquals(
            Action.NONE,
            decide(lastDataAt = 1_000_000L - 60_000L, paused = true),
        )
    }

    @Test
    fun `two spent resets are the end of it`() {
        assertEquals(
            Action.GIVE_UP,
            decide(lastDataAt = 1_000_000L - 60_000L, lastAttemptAt = 1L, resets = BusWatchdog.MAX_RESETS),
        )
    }

    @Test
    fun `an adapter that stopped talking AT gets no more resets`() {
        assertEquals(
            Action.GIVE_UP,
            decide(lastDataAt = 1_000_000L - 60_000L, adapterSilent = true),
        )
    }

    @Test
    fun `giving up is reported once`() {
        assertEquals(
            Action.NONE,
            decide(lastDataAt = 1_000_000L - 60_000L, resets = 2, giveUpHandled = true),
        )
    }
}
