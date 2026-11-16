package com.tacomatow.monitor.obd.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePolicyTest {

    private val bodyWrite = WriteTarget(
        header = "750",
        lid = 0x14,
        byteIndex = 1,
        before = "26",
        after = "27",
        sub = WritePolicy.MAIN_BODY_SUB,
    )

    private fun rule(target: WriteTarget = bodyWrite, conditions: WriteConditions = WriteConditions()) =
        (WritePolicy.evaluate(target, conditions) as WriteDecision.Refuse).rule

    @Test
    fun `a verified single byte on the body ECU is allowed`() {
        assertEquals(WriteDecision.Allow, WritePolicy.evaluate(bodyWrite, WriteConditions()))
    }

    @Test
    fun `meter and AC blocks are writable`() {
        assertEquals(
            WriteDecision.Allow,
            WritePolicy.evaluate(bodyWrite.copy(header = "7C0", lid = 0x21, sub = null), WriteConditions()),
        )
        assertEquals(
            WriteDecision.Allow,
            WritePolicy.evaluate(bodyWrite.copy(header = "7C4", lid = 0x01, sub = null), WriteConditions()),
        )
    }

    @Test
    fun `powertrain block is refused`() {
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(header = "7E0")))
    }

    @Test
    fun `braking block is refused`() {
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(header = "7B0")))
    }

    @Test
    fun `immobiliser block is refused even though it is a body-family address`() {
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(header = "7C6")))
    }

    @Test
    fun `an address nobody has identified is refused`() {
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(header = "7FF")))
    }

    @Test
    fun `a LID outside the customization range is refused`() {
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(lid = 0x55)))
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(lid = 0x00)))
    }

    @Test
    fun `lowercase header is accepted`() {
        assertEquals(WriteDecision.Allow, WritePolicy.evaluate(bodyWrite.copy(header = "0x750"), WriteConditions()))
    }

    @Test
    fun `a gateway write without a sub-target is refused`() {
        // 750 covers every module behind the relay, so "body ECU" alone means
        // whichever one the gateway happens to pick.
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(sub = null)))
    }

    @Test
    fun `a gateway write to another sub-target is refused`() {
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(sub = 0x0F)))
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(sub = 0xEC)))
    }

    @Test
    fun `a directly addressed block takes no sub-target`() {
        assertEquals(
            RefusalRule.UNRECOGNISED_TARGET,
            rule(bodyWrite.copy(header = "7C0", lid = 0x21, sub = 0x40)),
        )
    }

    @Test
    fun `a value that is not a byte is refused`() {
        assertEquals(RefusalRule.MALFORMED_VALUE, rule(bodyWrite.copy(after = "270")))
        assertEquals(RefusalRule.MALFORMED_VALUE, rule(bodyWrite.copy(before = "ZZ")))
        assertEquals(RefusalRule.MALFORMED_VALUE, rule(bodyWrite.copy(after = "")))
    }

    @Test
    fun `writing two blocks or two bytes is refused`() {
        assertEquals(RefusalRule.BATCH, rule(conditions = WriteConditions(lidsSelected = 2)))
        assertEquals(RefusalRule.BATCH, rule(conditions = WriteConditions(bytesSelected = 2)))
    }

    @Test
    fun `writing the value it already holds is refused`() {
        assertEquals(RefusalRule.NO_CHANGE, rule(bodyWrite.copy(after = "26")))
    }

    @Test
    fun `an unverified entry is refused`() {
        assertEquals(
            RefusalRule.UNVERIFIED_ENTRY,
            rule(conditions = WriteConditions(verifiedForThisCalibration = false)),
        )
    }

    @Test
    fun `no backup is refused`() {
        assertEquals(RefusalRule.NO_BACKUP, rule(conditions = WriteConditions(backupPresent = false)))
    }

    @Test
    fun `an unparseable backup is refused`() {
        assertEquals(
            RefusalRule.BACKUP_UNPARSEABLE,
            rule(conditions = WriteConditions(backupParses = false)),
        )
    }

    @Test
    fun `a backup from an earlier session is refused`() {
        assertEquals(
            RefusalRule.STALE_BACKUP,
            rule(conditions = WriteConditions(backupTakenThisSession = false)),
        )
    }

    @Test
    fun `a block that changed since the backup is refused`() {
        assertEquals(
            RefusalRule.REREAD_MISMATCH,
            rule(conditions = WriteConditions(rereadMatchesBackup = false)),
        )
    }

    @Test
    fun `a refused read immediately before a write is refused`() {
        assertEquals(
            RefusalRule.READ_REFUSED,
            rule(conditions = WriteConditions(lastReadRefused = true)),
        )
    }

    @Test
    fun `engine running is refused`() {
        assertEquals(RefusalRule.ENGINE_RUNNING, rule(conditions = WriteConditions(engineRunning = true)))
    }

    @Test
    fun `any movement in the last samples is refused`() {
        assertEquals(
            RefusalRule.VEHICLE_MOVING,
            rule(conditions = WriteConditions(speedSamples = listOf(0, 0, 0, 3, 0))),
        )
    }

    @Test
    fun `too few speed samples is refused rather than assumed stationary`() {
        assertEquals(
            RefusalRule.VEHICLE_MOVING,
            rule(conditions = WriteConditions(speedSamples = listOf(0, 0))),
        )
    }

    @Test
    fun `low voltage is refused`() {
        assertEquals(RefusalRule.LOW_VOLTAGE, rule(conditions = WriteConditions(batteryVolts = 11.8f)))
    }

    @Test
    fun `an untrusted adapter is refused`() {
        assertEquals(
            RefusalRule.ADAPTER_UNTRUSTED,
            rule(conditions = WriteConditions(adapterTrusted = false)),
        )
    }

    @Test
    fun `a write to the same block inside the cooldown is refused`() {
        assertEquals(
            RefusalRule.COOLDOWN,
            rule(conditions = WriteConditions(lastWriteToSameLidMillisAgo = 12_000L)),
        )
    }

    @Test
    fun `a block that drifts on its own is never writable`() {
        // Idle scans saw this block move with nothing changed: it carries
        // live data, and a byte write would send back a stale copy of the
        // drifting bytes around the target.
        assertEquals(
            RefusalRule.VOLATILE_LIVE_DATA,
            rule(conditions = WriteConditions(isKnownVolatile = true)),
        )
    }

    @Test
    fun `a steady block stays writable`() {
        assertEquals(
            WriteDecision.Allow,
            WritePolicy.evaluate(bodyWrite, WriteConditions(isKnownVolatile = false)),
        )
    }

    @Test
    fun `a write to the same block after the cooldown is allowed`() {
        assertEquals(
            WriteDecision.Allow,
            WritePolicy.evaluate(
                bodyWrite,
                WriteConditions(lastWriteToSameLidMillisAgo = 61_000L),
            ),
        )
    }

    @Test
    fun `safety is checked before anything else`() {
        // Even with every other condition broken, the refusal names the target.
        val worst = WriteConditions(
            verifiedForThisCalibration = false,
            backupPresent = false,
            engineRunning = true,
            speedSamples = emptyList(),
            batteryVolts = 9f,
            adapterTrusted = false,
        )
        assertEquals(RefusalRule.UNRECOGNISED_TARGET, rule(bodyWrite.copy(header = "7E0"), worst))
    }

    @Test
    fun `every refusal carries a message the user can read and a rule for the log`() {
        val conditions = listOf(
            WriteConditions(verifiedForThisCalibration = false),
            WriteConditions(backupPresent = false),
            WriteConditions(backupParses = false),
            WriteConditions(backupTakenThisSession = false),
            WriteConditions(rereadMatchesBackup = false),
            WriteConditions(lastReadRefused = true),
            WriteConditions(engineRunning = true),
            WriteConditions(speedSamples = listOf(0, 5, 0, 0, 0)),
            WriteConditions(batteryVolts = 11f),
            WriteConditions(adapterTrusted = false),
            WriteConditions(lastWriteToSameLidMillisAgo = 1_000L),
            WriteConditions(lidsSelected = 3),
            WriteConditions(isKnownVolatile = true),
        )
        conditions.forEach { c ->
            val decision = WritePolicy.evaluate(bodyWrite, c) as WriteDecision.Refuse
            assertTrue("empty message for ${decision.rule}", decision.message.isNotBlank())
            assertTrue("empty detail for ${decision.rule}", decision.detail.isNotBlank())
        }
    }

    @Test
    fun `console refuses every truck-write service`() {
        listOf("04", "2E220101", "2F Foo", "31", "34", "35", "36", "37", "38", "3B1E", "3D")
            .forEach { cmd ->
                val refusal = WritePolicy.consoleRefusal("7C0", cmd)
                assertTrue("$cmd went out", refusal != null)
            }
    }

    @Test
    fun `console allows reads and adapter identity`() {
        listOf("0100", "0904", "2114", "221627", "ATI", "ATDPN", "ATRV", "3E80", "1902", "1A90")
            .forEach { cmd ->
                assertEquals(
                    "$cmd refused",
                    null,
                    WritePolicy.consoleRefusal(if (cmd.startsWith("AT")) null else "7C0", cmd),
                )
            }
    }

    @Test
    fun `console refuses adapter resets but not adapter reads`() {
        assertTrue(WritePolicy.consoleRefusal(null, "ATZ") != null)
        assertTrue(WritePolicy.consoleRefusal(null, "ATSP6") != null)
        assertTrue(WritePolicy.consoleRefusal(null, "ATD") != null)
        // ATDP reads the protocol: shares a prefix with ATD, must stay.
        assertEquals(null, WritePolicy.consoleRefusal(null, "ATDP"))
        assertEquals(null, WritePolicy.consoleRefusal(null, "ATDPN"))
    }

    @Test
    fun `console refuses a write behind a gateway sub-target`() {
        // First byte is the sub-target, not the service: reading it as a
        // service number is how this used to slip through.
        assertTrue(WritePolicy.consoleRefusal("750", "40053B142700") != null)
        assertEquals(null, WritePolicy.consoleRefusal("750", "40022114"))
        assertTrue(WritePolicy.consoleRefusal("750", "40022E1234") != null)
        assertTrue(WritePolicy.consoleRefusal("750", "4002850000") != null)
    }

    @Test
    fun `console refuses spaced AT commands like their compact forms`() {
        assertTrue(WritePolicy.consoleRefusal(null, "AT Z") != null)
        assertTrue(WritePolicy.consoleRefusal(null, "AT SP 0") != null)
        assertTrue(WritePolicy.consoleRefusal(null, "AT WS") != null)
        assertEquals(null, WritePolicy.consoleRefusal(null, "AT I"))
    }

    @Test
    fun `console refuses adapter-memory writes`() {
        assertTrue(WritePolicy.consoleRefusal(null, "AT PP 04 SV 15") != null)
        assertTrue(WritePolicy.consoleRefusal(null, "AT@3") != null)
    }

    @Test
    fun `console refuses state-changing services beyond the obvious one`() {
        listOf("14", "11", "27 05", "8502", "04").forEach { cmd ->
            assertTrue("$cmd went out", WritePolicy.consoleRefusal("7C0", cmd) != null)
        }
    }

    @Test
    fun `console refuses smuggled line breaks and bad headers`() {
        assertTrue(WritePolicy.consoleRefusal("7C0", "2114\r04") != null)
        assertTrue(WritePolicy.consoleRefusal("7C0; DROP", "2114") != null)
        assertTrue(WritePolicy.consoleRefusal("7C0", "") != null)
        assertEquals(null, WritePolicy.consoleRefusal("7C0", "2114"))
        assertEquals(null, WritePolicy.consoleRefusal(null, "2114"))
    }
}
