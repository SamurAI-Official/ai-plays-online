package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RamSignalsTest {

    private fun addressOf(offset: Int) = RamProbe.MAIN_RAM_START + offset

    /** A snapshot where [address] holds [value] as a byte. */
    private fun snapshot(address: Long, value: Int) =
        ByteArray(0x400000).also { it[(address - RamProbe.MAIN_RAM_START).toInt()] = value.toByte() }

    @Test
    fun theAddressThatFollowsTheScreenIsFound() {
        // 0x1000 says which screen is up (1 on the menu, 2 in mission), 0x2000 is noise. The two screens are far
        // apart, so they are two screens (see [similarScreensAreOneScreen]).
        val samples = listOf(
            RamSignals.Sample(snapshot(addressOf(0x1000), 1).also { it[0x2000] = 7 }, screen = 0x0000000000000000L),
            RamSignals.Sample(snapshot(addressOf(0x1000), 1).also { it[0x2000] = 9 }, screen = 0x0000000000000000L),
            RamSignals.Sample(snapshot(addressOf(0x1000), 2).also { it[0x2000] = 3 }, screen = 0xFFFFFFFFFFFFFFFFUL.toLong()),
        )
        val signals = RamSignals.screenSignals(samples, ValueType.U8)
        assertTrue(signals.isNotEmpty())
        assertEquals(addressOf(0x1000), signals.first().address)
        assertEquals(1.0, signals.first().consistency, "the value never disagrees with its screen")
        assertEquals(2, signals.first().distinct)
        assertEquals(1L, signals.first().valueOn(0x0000000000000000L))
        assertEquals(2L, signals.first().valueOn(0xFFFFFFFFFFFFFFFFUL.toLong()))
    }

    @Test
    fun similarScreensAreOneScreen() {
        // Two hashes 4 bits apart are the same screen (a menu with a blinking cursor), so an address that differs
        // between them is not a signal: this is what clustering is for.
        val samples = listOf(
            RamSignals.Sample(snapshot(addressOf(0x1000), 1), screen = 0x0000000000000000L),
            RamSignals.Sample(snapshot(addressOf(0x1000), 2), screen = 0x000000000000000FL),
        )
        assertEquals(listOf(0L, 0L), RamSignals.cluster(samples))
        assertTrue(RamSignals.screenSignals(samples, ValueType.U8).isEmpty(), "one screen, so nothing follows it")
    }

    @Test
    fun aConstantIsNotASignal() {
        val samples = List(3) { RamSignals.Sample(ByteArray(0x400000), screen = 0xAAAAL + it * 0x1111L) }
        assertTrue(RamSignals.screenSignals(samples, ValueType.U8).isEmpty())
    }

    @Test
    fun aCounterIsFoundAndNoiseIsNot() {
        val samples = listOf(0, 5, 10).map { step ->
            ByteArray(0x400000).also {
                // 0x1000 counts up by 5, 0x2000 jumps around.
                val value = step
                it[0x1000] = value.toByte()
                it[0x2000] = (if (step == 1) 200 else 3).toByte()
            }
        }
        val counters = RamSignals.counters(samples, ValueType.U8)
        assertTrue(counters.any { it.address == addressOf(0x1000) }, "the rising value is a counter")
        assertTrue(counters.none { it.address == addressOf(0x2000) }, "a value that jumps is not")
    }

    @Test
    fun anInputSignalIsWhatMovedEveryTimeAndNowhereElse() {
        fun snapshotWith(value: Int) = ByteArray(0x400000).also { it[0x1000] = value.toByte() }
        // The input moved 0x1000 every time; 0x2000 moved on its own once.
        val moments = listOf(snapshotWith(0) to snapshotWith(1), snapshotWith(1) to snapshotWith(2))
        val others = listOf(snapshotWith(2) to snapshotWith(2))
        val signals = RamSignals.inputSignals(moments, others, ValueType.U8)
        assertEquals(listOf(addressOf(0x1000)), signals.map { it.address })
    }
}
