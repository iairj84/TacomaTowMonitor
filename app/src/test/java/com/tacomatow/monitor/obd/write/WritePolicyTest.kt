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
            WritePolicy.evaluate(bodyWrite.copy(header = "7C0", lid = 0x21), WriteConditions()),
        )
        assertEquals(
            WriteDecision.Allow,
            WritePolicy.evaluate(bodyWrite.copy(header = "7C4", lid = 0x01), WriteConditions()),
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
        )
        conditions.forEach { c ->
            val decision = WritePolicy.evaluate(bodyWrite, c) as WriteDecision.Refuse
            assertTrue("empty message for ${decision.rule}", decision.message.isNotBlank())
            assertTrue("empty detail for ${decision.rule}", decision.detail.isNotBlank())
        }
    }
}
