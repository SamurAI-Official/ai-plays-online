package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.libretrorollback.SnapshotStrategy
import dev.kotlinds.pokemonclient.rollback.ConsoleRollbackCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetplayTest {

    private val settings = NetplaySettings(
        role = NetplayRole.HOST,
        peerHost = "127.0.0.1",
        peerPort = 7001,
        mode = Mode.ROLLBACK,
        inputDelay = 3,
        maxPredictionFrames = 4,
        snapshotStrategy = SnapshotStrategy.FULL,
    )

    @Test
    fun theHostIsPlayerOneAndTheJoinerPlayerTwo() {
        assertEquals(0, settings.localPlayer)
        assertEquals(1, settings.copy(role = NetplayRole.JOIN).localPlayer)
    }

    @Test
    fun theSessionUsesTheAdapterInputSizeAndTheChosenSettings() {
        val config = settings.toRollbackConfig()
        assertEquals(ConsoleRollbackCore.INPUT_SIZE, config.inputSize, "both peers must agree on the blob size")
        assertEquals(Mode.ROLLBACK, config.mode)
        assertEquals(3, config.inputDelay)
        assertEquals(4, config.maxPredictionFrames)
        assertEquals(SnapshotStrategy.FULL, config.snapshotStrategy)
        // This topology runs one console: its checksums are meaningful and must stay on.
        assertFalse(config.linkChecksums)
    }

    @Test
    fun theStatusSaysWhoPlaysWhereAndWhetherItIsSynchronized() {
        val status = NetplayStatus(
            role = NetplayRole.JOIN,
            peer = "10.0.0.5:7000",
            bindPort = 7001,
            mode = Mode.LOCKSTEP,
        )
        assertTrue(status.summary.contains("join"))
        assertTrue(status.summary.contains("10.0.0.5:7000"))
        assertTrue(status.summary.contains("lockstep"))
        assertTrue(status.summary.contains("connecting"))

        assertTrue(status.copy(synchronized = true).summary.contains("synchronized"))
        assertTrue(status.copy(stopped = "desync at frame 12").summary.contains("stopped: desync at frame 12"))
    }
}
