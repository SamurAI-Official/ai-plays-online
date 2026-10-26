package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.RamMemory
import me.nathanfallet.aiplayspokemon.games.READER_RAM_END
import me.nathanfallet.aiplayspokemon.games.READER_RAM_START
import me.nathanfallet.aiplayspokemon.games.ValueType

/**
 * Looks through the game's main RAM for the values a typed reader needs: this is what makes a reader for a new game
 * something the app can find instead of something a human has to hunt down with a debugger.
 *
 * Everything here works on snapshots ([snapshot] of what the emulator returned), so it is plain, testable code: no
 * emulator, no timing, no model. The recipes that drive a game and call these live in `dev/RamProbeTool`, and what
 * they produce is a `readers/<id>.json` (see `docs/readers.md`). The analysis built on top is in [RamSignals].
 *
 * A snapshot is the 4 MB of main RAM; addresses are ARM9 bus addresses (`0x02000000`..`0x023FFFFF`), and a value can
 * be read as a byte, a halfword or a word ([ValueType]).
 */
object RamProbe {

    /** Main RAM's first address: what [Memory] takes, and what a `readers/<id>.json` stores. */
    const val MAIN_RAM_START = READER_RAM_START

    /** The last address of main RAM (4 MB; the DS mirrors it above this, so there is nothing more to find). */
    const val MAIN_RAM_END = READER_RAM_END

    /** A snapshot of [ram] (the bytes the emulator returned) as the [Memory] the readers read. */
    fun snapshot(ram: ByteArray): Memory = RamMemory(ram)

    /** The value at [address] in [ram]. */
    fun read(ram: ByteArray, address: Long, type: ValueType): Long = read(snapshot(ram), address, type)

    /** The value at [address] in [memory]. */
    fun read(memory: Memory, address: Long, type: ValueType): Long = when (type) {
        ValueType.U8 -> memory.read8(address).toLong()
        ValueType.U16 -> memory.read16(address).toLong()
        ValueType.U32 -> memory.read32(address)
        ValueType.S8 -> memory.read8(address).toByte().toLong()
        ValueType.S16 -> memory.read16(address).toShort().toLong()
        ValueType.S32 -> memory.read32(address).toInt().toLong()
        ValueType.BOOL -> if (memory.read8(address) != 0) 1L else 0L
        ValueType.BITS -> memory.read32(address)
        ValueType.ASCII -> 0L
        // The composite types need the rest of an [Observation] (its length, op, second address): a plain (address, type)
        // read has nothing to combine, so it reads what it can and lets the reader do the rest.
        ValueType.U8_ARRAY, ValueType.U16_ARRAY, ValueType.EXPR -> 0L
        ValueType.PAIR -> memory.read16(address).toShort().toLong()
        ValueType.POINTER -> memory.read32(address)
    }

    /**
     * Every address in [range] (main RAM by default) holding [value], read as [type]. Alignment follows the type: a
     * `u16` is only read at even addresses, a `u32` only every four bytes.
     */
    fun find(
        ram: ByteArray,
        value: Long,
        type: ValueType = ValueType.U8,
        range: LongRange = MAIN_RAM_START..MAIN_RAM_END,
        limit: Int = 64,
    ): List<Long> {
        val addresses = mutableListOf<Long>()
        var address = range.first
        while (address <= range.last && addresses.size < limit) {
            if (address - MAIN_RAM_START in 0 until ram.size && read(ram, address, type) == value) addresses += address
            address += step(type)
        }
        return addresses
    }

    /** One address whose value differs between two snapshots. */
    data class Change(val address: Long, val before: Long, val after: Long)

    /**
     * The addresses whose value changed between [before] and [after], read as [type]. This is the primitive behind
     * every recipe: press something, snapshot, and what moved is what the game keeps about it.
     */
    fun diff(before: ByteArray, after: ByteArray, type: ValueType = ValueType.U8, limit: Int = 4096): List<Change> {
        val size = minOf(before.size, after.size)
        val changes = mutableListOf<Change>()
        var offset = 0
        val stride = step(type)
        while (offset + stride <= size && changes.size < limit) {
            val address = MAIN_RAM_START + offset
            val was = read(before, address, type)
            val now = read(after, address, type)
            if (was != now) changes += Change(address, was, now)
            offset += stride
        }
        return changes
    }

    /** Bytes a [type] occupies (what the address step and the alignment follow). */
    fun step(type: ValueType): Int = when (type) {
        ValueType.U8, ValueType.S8, ValueType.BOOL, ValueType.BITS, ValueType.ASCII, ValueType.U8_ARRAY -> 1
        ValueType.U16, ValueType.S16, ValueType.U16_ARRAY, ValueType.PAIR -> 2
        ValueType.U32, ValueType.S32, ValueType.EXPR, ValueType.POINTER -> 4
    }

    /** `0x02000000` form, the one `readers/<id>.json` and every other tool uses. */
    fun address(address: Long): String = "0x" + address.toString(16).padStart(8, '0')

    /** Reads an address written as `0x02000000` (or a plain decimal), for the tools' command lines. */
    fun parseAddress(text: String): Long? {
        val value = text.trim().removePrefix("0x").removePrefix("0X")
        val parsed = if (text.startsWith("0x", ignoreCase = true)) value.toLongOrNull(16) else value.toLongOrNull()
        return parsed?.takeIf { it in MAIN_RAM_START..(MAIN_RAM_START + 0x400000) }
    }

    /** A short report of [changes], for the tools' output. */
    fun summarise(changes: List<Change>, limit: Int = 12): String =
        changes.take(limit).joinToString("\n") { change ->
            "  ${address(change.address)}  ${change.before} -> ${change.after}"
        } + if (changes.size > limit) "\n  … and ${changes.size - limit} more" else ""
}
