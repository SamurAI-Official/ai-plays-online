package me.nathanfallet.aiplayspokemon.games

import dev.kotlinds.pokemonclient.Memory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a typed reader for a game is written down: as data, not as code.
 *
 * A reader says what to read from the game's main RAM and what to call it. The app then treats the game as a
 * [AgentKind.TYPED] game: the values are shown in the panel and given to the model with every decision, and a
 * [ProgressSignal] can verify a walkthrough chapter the way a Pokémon game's story flags do.
 *
 * It exists so that a game nobody wrote a reader for can still become typed: [me.nathanfallet.aiplayspokemon.dev.RamProbeTool]
 * calibrates these entries by driving the game and watching memory (`docs/readers.md`), and the result is this file.
 * Nothing here is guessed at runtime: every entry carries where it came from and how sure the calibration was
 * ([Observation.confidence], [ReaderSpec.provenance]).
 */
@Serializable
data class ReaderSpec(
    /** The game's profile id (`games/GameCatalog`), which is also the file's name. */
    val id: String,
    /** Where this file came from: `hand-written`, `calibrated` (see `docs/readers.md`) or `imported`. */
    val provenance: String,
    /** What the AI can see: one named value per entry, read every time the panel refreshes. */
    val observations: List<Observation> = emptyList(),
    /** What proves progress: read by a walkthrough chapter whose milestone is `kind: memory`. */
    val progress: List<ProgressSignal> = emptyList(),
    /** Free text for whoever calibrates next (what was tried, what is still wrong). */
    val notes: String = "",
) {
    /** True when the app may keep extending this reader: it learned it itself, nobody wrote it. */
    val isLearned: Boolean get() = provenance == PROVENANCE_LEARNED

    companion object {
        /** [provenance] of a reader the app learned by itself while the game was played (see [ReaderCalibrator]). */
        const val PROVENANCE_LEARNED = "learned while playing"
    }
}

/**
 * The address range a reader reads from: the console's main RAM, the only memory the app exposes. It is the reader
 * format's own range (the addresses in `readers/<id>.json` are these), so it lives here and the emulator's tools use it.
 */
const val READER_RAM_START = 0x02000000L

/** The last address of main RAM (4 MiB; the DS mirrors it above this, so there is nothing more to read). */
const val READER_RAM_END = 0x023FFFFFL

/** One value read from the game: a name, where it lives, and how to read it. */
@Serializable
data class Observation(
    /** The name the model sees, e.g. `cursor.x`, `resources.wood`, `mode`. */
    val name: String,
    /** ARM9 bus address in main RAM (`0x02000000`..`0x02FFFFFF`). */
    val address: Long,
    val type: ValueType = ValueType.U8,
    /** `BITS` only: which bits are the value (and how far they are shifted down). */
    val mask: Int? = null,
    val shift: Int = 0,
    /** Multiplier applied to the raw value before it is shown (a fixed-point position, for instance). */
    val scale: Double = 1.0,
    /** `ASCII` only: how many bytes the text occupies. */
    val length: Int = 8,
    /** Known values, for the model: `{"1": "main menu", "4": "in mission"}`. */
    val values: Map<String, String> = emptyMap(),
    /** What the calibration thinks of it, 0..1: 1 is hand-checked, low means a candidate (`docs/readers.md`). */
    val confidence: Double = 1.0,
    /** What this reading is, in one line, for the panel and the prompt. */
    val note: String = "",
    /** The second address of a value made of two: `PAIR` (x, y) or `EXPR` (its operand). */
    val address2: Long? = null,
    /** The type of the second address, or of what a `POINTER` points at. */
    val type2: ValueType = ValueType.S16,
    /** `EXPR` only: how the first address is read (the second is [type2]). */
    val type1: ValueType = ValueType.U8,
    /** Arrays only: where the list starts inside the array (`address + index * step`). */
    val index: Int = 0,
    /** `BITS` only: what each set bit means, e.g. `{"0": "paused", "3": "in battle"}`. */
    val bitNames: Map<String, String> = emptyMap(),
    /** `EXPR` only: how [address] and [address2] are combined into one value. */
    val op: ValueOp? = null,
    /** `EXPR` only: what [address2] is multiplied by before the operation (a byte of a 16-bit value, say). */
    val factor: Double = 1.0,
    /** `POINTER` only: added to the pointer before reading [type2] there. */
    val offset: Int = 0,
    /**
     * Read the value only while another address holds a value (a menu cursor is meaningless on the field): otherwise
     * the reading is `not now`. Null: always read.
     */
    val guardAddress: Long? = null,
    val guardEquals: Long = 1,
) {

    /** Reads the value at [address] out of [memory]. */
    fun read(memory: Memory): Long = when (type) {
        ValueType.U8 -> readAt(memory, address, ValueType.U8)
        ValueType.U16 -> readAt(memory, address, ValueType.U16)
        ValueType.U32 -> readAt(memory, address, ValueType.U32)
        ValueType.S8 -> readAt(memory, address, ValueType.S8)
        ValueType.S16 -> readAt(memory, address, ValueType.S16)
        ValueType.S32 -> readAt(memory, address, ValueType.S32)
        ValueType.BOOL -> readAt(memory, address, ValueType.BOOL)
        ValueType.BITS -> ((readAt(memory, address, ValueType.U32).toInt() and (mask ?: 0xFF)) ushr shift).toLong()
        ValueType.ASCII -> 0L // text: see [text]
        // A list has no single value (see [list]); a pair's first value is what a signal can compare.
        ValueType.U8_ARRAY, ValueType.U16_ARRAY -> 0L
        ValueType.PAIR -> readAt(memory, address, type2)
        ValueType.EXPR -> evaluate(memory)
        ValueType.POINTER -> pointer(memory)?.let { target -> readAt(memory, target + offset, type2) } ?: 0L
    }

    /** True when the value is worth reading right now (see [guardAddress]); a value with no guard always is. */
    fun applies(memory: Memory): Boolean =
        guardAddress == null || readAt(memory, guardAddress, ValueType.U8) == guardEquals

    /** The text of an `ASCII` reading (the other types show their number). */
    fun text(memory: Memory): String = String(memory.readBytes(address, length), Charsets.ISO_8859_1)
        .takeWhile { it.code in 32..126 }
        .trim()

    /** The entries of a `U8_ARRAY`/`U16_ARRAY` reading, from [index], [length] of them. */
    fun list(memory: Memory): List<Long> {
        val step = if (type == ValueType.U8_ARRAY) 1 else 2
        val entry = if (type == ValueType.U8_ARRAY) ValueType.U8 else ValueType.U16
        return (0 until length.coerceIn(1, MAX_LIST_LENGTH)).map { position ->
            readAt(memory, address + (index + position) * step, entry)
        }
    }

    /** How the value is shown: the named value when [values] knows it, the scaled number otherwise. */
    fun format(memory: Memory): String {
        if (!applies(memory)) return NOT_NOW
        return when (type) {
            ValueType.ASCII -> text(memory)
            ValueType.U8_ARRAY, ValueType.U16_ARRAY -> list(memory).joinToString(", ")
            ValueType.PAIR -> "(${readAt(memory, address, type2)}, ${scaled(readAt(memory, address2 ?: address, type2))})"
            ValueType.EXPR -> scaled(evaluate(memory))
            ValueType.POINTER -> pointer(memory)?.let { target -> scaled(readAt(memory, target + offset, type2)) } ?: OUT_OF_RANGE
            ValueType.BITS -> flags(memory) ?: scaled(read(memory))
            else -> {
                val raw = read(memory)
                values[raw.toString()]?.let { return "$it ($raw)" }
                scaled(raw)
            }
        }
    }

    /** The set bits of a `BITS` reading, named when [bitNames] says what they mean, or null when it says nothing. */
    private fun flags(memory: Memory): String? {
        if (bitNames.isEmpty()) return null
        val raw = (readAt(memory, address, ValueType.U32).toInt() and (mask ?: 0xFF)) ushr shift
        val set = bitNames.filterKeys { bit -> raw and (1 shl (bit.toIntOrNull() ?: 0)) != 0 }.values
        return if (set.isEmpty()) "none" else set.joinToString(", ")
    }

    /** `address op (address2 * factor)`: the small arithmetic a value spread over two addresses needs. */
    private fun evaluate(memory: Memory): Long {
        val left = readAt(memory, address, type1)
        val right = ((address2?.let { readAt(memory, it, type2) } ?: 0L) * factor).toLong()
        return when (op ?: ValueOp.ADD) {
            ValueOp.ADD -> left + right
            ValueOp.SUB -> left - right
            ValueOp.MUL -> left * right
            ValueOp.DIV -> if (right == 0L) 0L else left / right
            ValueOp.SHIFT_LEFT -> left shl right.toInt().coerceIn(0, 32)
            ValueOp.SHIFT_RIGHT -> left shr right.toInt().coerceIn(0, 32)
            ValueOp.AND -> left and right
            ValueOp.OR -> left or right
        }
    }

    /** Where a `POINTER` reading points, or null when it points outside main RAM (never followed). */
    private fun pointer(memory: Memory): Long? {
        val target = readAt(memory, address, ValueType.U32)
        if (target < READER_RAM_START || target > READER_RAM_END) return null
        return target
    }

    private fun scaled(value: Long): String {
        val scaled = value * scale
        return if (scale == 1.0) value.toString() else String.format("%.2f", scaled)
    }

    private fun readAt(memory: Memory, at: Long, asType: ValueType): Long = when (asType) {
        ValueType.U8, ValueType.U8_ARRAY -> memory.read8(at).toLong()
        ValueType.U16, ValueType.U16_ARRAY -> memory.read16(at).toLong()
        ValueType.U32, ValueType.POINTER -> memory.read32(at)
        ValueType.S8 -> memory.read8(at).toByte().toLong()
        ValueType.S16, ValueType.PAIR -> memory.read16(at).toShort().toLong()
        ValueType.S32 -> memory.read32(at).toInt().toLong()
        ValueType.BOOL -> if (memory.read8(at) != 0) 1L else 0L
        ValueType.BITS -> memory.read32(at)
        ValueType.ASCII, ValueType.EXPR -> 0L
    }

    private companion object {
        /** A reading that is not meaningful right now (see [guardAddress]). */
        const val NOT_NOW = "not now"

        /** A pointer that does not land in main RAM. */
        const val OUT_OF_RANGE = "out of range"

        /** Most entries an array reading shows: a reader is a handful of values, not a memory dump. */
        const val MAX_LIST_LENGTH = 32
    }
}

/** How an `EXPR` reading combines its two addresses. */
@Serializable
enum class ValueOp {
    @SerialName("add") ADD,
    @SerialName("sub") SUB,
    @SerialName("mul") MUL,
    @SerialName("div") DIV,
    @SerialName("shl") SHIFT_LEFT,
    @SerialName("shr") SHIFT_RIGHT,
    @SerialName("and") AND,
    @SerialName("or") OR,
}

/** How a value is encoded in memory. */
@Serializable
enum class ValueType {
    @SerialName("u8") U8,
    @SerialName("u16") U16,
    @SerialName("u32") U32,
    @SerialName("s8") S8,
    @SerialName("s16") S16,
    @SerialName("s32") S32,
    @SerialName("bool") BOOL,
    @SerialName("bits") BITS,
    @SerialName("ascii") ASCII,

    /** [Observation.length] entries from [Observation.index], shown as a list (a party, a menu, a bag). */
    @SerialName("u8_array") U8_ARRAY,
    @SerialName("u16_array") U16_ARRAY,

    /** Two values read as one, `(x, y)`: a position, a cursor, a pair of counters. */
    @SerialName("pair") PAIR,

    /** One value computed from two addresses (see [Observation.op]). */
    @SerialName("expr") EXPR,

    /** A `u32` address pointing at the value, read at `target + offset` when it lands in main RAM. */
    @SerialName("pointer") POINTER,
}

/** What the app can trust to tell whether the game reached a step. */
@Serializable
data class ProgressSignal(
    /** The name the walkthrough's milestone refers to. */
    val name: String,
    val address: Long,
    val type: ValueType = ValueType.U8,
    val mask: Int? = null,
    val shift: Int = 0,
    /** True when the signal is set at [equals] (the chapter is reached), false when it is set when different. */
    val equals: Long = 1,
    val note: String = "",
) {

    /** True when the game is at the step this signal is about. */
    fun reached(memory: Memory): Boolean = Observation(name, address, type, mask, shift = shift).read(memory) == equals
}
