package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RamProbeTest {

    /** 4 MB of main RAM with [values] written at [address] (an ARM9 bus address). */
    private fun ram(vararg values: Pair<Long, ByteArray>): ByteArray {
        val memory = ByteArray(0x400000)
        values.forEach { (address, bytes) ->
            bytes.copyInto(memory, (address - RamProbe.MAIN_RAM_START).toInt())
        }
        return memory
    }

    private fun addressOf(offset: Int) = RamProbe.MAIN_RAM_START + offset

    @Test
    fun aByteIsFoundWhereItWasWritten() {
        val memory = ram(addressOf(0x1000) to byteArrayOf(42))
        assertEquals(listOf(addressOf(0x1000)), RamProbe.find(memory, 42, ValueType.U8))
    }

    @Test
    fun aHalfwordIsOnlyReadAtAlignedAddresses() {
        // 0x1234 at an even address: found once, not once per byte.
        val memory = ram(addressOf(0x2000) to byteArrayOf(0x34, 0x12))
        assertEquals(listOf(addressOf(0x2000)), RamProbe.find(memory, 0x1234, ValueType.U16))
    }

    @Test
    fun signedAndUnsignedReadTheSameBytesDifferently() {
        val memory = ram(addressOf(0x10) to byteArrayOf(0xFF.toByte()))
        assertEquals(255, RamProbe.read(memory, addressOf(0x10), ValueType.U8))
        assertEquals(-1, RamProbe.read(memory, addressOf(0x10), ValueType.S8))
    }

    @Test
    fun aDiffReportsOnlyWhatMoved() {
        val before = ram(addressOf(0x300) to byteArrayOf(1, 2, 3))
        val after = ram(addressOf(0x300) to byteArrayOf(1, 9, 3))
        val changes = RamProbe.diff(before, after, ValueType.U8)
        assertEquals(1, changes.size)
        assertEquals(addressOf(0x301), changes.single().address)
        assertEquals(2, changes.single().before)
        assertEquals(9, changes.single().after)
    }

    @Test
    fun addressesRoundTripThroughTheirText() {
        val address = RamProbe.parseAddress(RamProbe.address(addressOf(0x1234)))
        assertEquals(addressOf(0x1234), address)
        // A plain number is read as decimal, so it has to be an address of main RAM to count.
        assertEquals(addressOf(0), RamProbe.parseAddress("33554432"))
        assertNull(RamProbe.parseAddress("32"), "not an address of main RAM")
        assertNull(RamProbe.parseAddress("0x01000000"), "outside main RAM")
        assertNull(RamProbe.parseAddress("not an address"))
    }

    @Test
    fun theStepFollowsTheType() {
        assertEquals(1, RamProbe.step(ValueType.U8))
        assertEquals(2, RamProbe.step(ValueType.S16))
        assertEquals(4, RamProbe.step(ValueType.U32))
        assertTrue(RamProbe.find(ram(), 0, ValueType.U32, limit = 4).size == 4)
    }
}
