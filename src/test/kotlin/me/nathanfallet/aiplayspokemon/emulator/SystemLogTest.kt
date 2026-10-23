package me.nathanfallet.aiplayspokemon.emulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SystemLog] as the UI's debug readout reads it: newest first, bounded, each entry stamped with its time — what
 * tells when a key got stuck and who drove the game when the agent stopped ([ConsoleHost.systemLog]).
 */
class SystemLogTest {

    @Test
    fun entriesAreNewestFirst() {
        val log = SystemLog(time = { "12:34:56.789" })
        log.add("keys: a")
        log.add("touch: down at 10,20")
        assertEquals(listOf("12:34:56.789  touch: down at 10,20", "12:34:56.789  keys: a"), log.entries.value)
    }

    @Test
    fun theOldestEntriesAreDroppedPastTheLimit() {
        val log = SystemLog(limit = 3, time = { "00:00:00.000" })
        repeat(5) { log.add("event $it") }
        assertEquals(3, log.entries.value.size)
        assertTrue(log.entries.value.first().endsWith("event 4"))
        assertTrue(log.entries.value.last().endsWith("event 2"))
    }

    @Test
    fun aRealClockStampsTheEntry() {
        val log = SystemLog()
        log.add("driver: you / free run")
        assertTrue(
            log.entries.value.single().matches(Regex("""\d{2}:\d{2}:\d{2}\.\d{3}  driver: you / free run""")),
            "not a timestamped line: ${log.entries.value.single()}",
        )
    }
}