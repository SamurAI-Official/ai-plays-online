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
)

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
) {

    /** Reads the value at [address] out of [memory]. */
    fun read(memory: Memory): Long = when (type) {
        ValueType.U8 -> memory.read8(address).toLong()
        ValueType.U16 -> memory.read16(address).toLong()
        ValueType.U32 -> memory.read32(address)
        ValueType.S8 -> memory.read8(address).toByte().toLong()
        ValueType.S16 -> memory.read16(address).toShort().toLong()
        ValueType.S32 -> memory.read32(address).toInt().toLong()
        ValueType.BOOL -> if (memory.read8(address) != 0) 1L else 0L
        ValueType.BITS -> ((memory.read32(address).toInt() and (mask ?: 0xFF)) ushr shift).toLong()
        ValueType.ASCII -> 0L // text: see [text]
    }

    /** The text of an `ASCII` reading (the other types show their number). */
    fun text(memory: Memory): String = String(memory.readBytes(address, length), Charsets.ISO_8859_1)
        .takeWhile { it.code in 32..126 }
        .trim()

    /** How the value is shown: the named value when [values] knows it, the scaled number otherwise. */
    fun format(memory: Memory): String {
        if (type == ValueType.ASCII) return text(memory)
        val raw = read(memory)
        values[raw.toString()]?.let { return "$it ($raw)" }
        val scaled = raw * scale
        return if (scale == 1.0) raw.toString() else String.format("%.2f", scaled)
    }
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
