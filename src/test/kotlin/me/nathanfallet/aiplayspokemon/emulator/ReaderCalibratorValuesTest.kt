package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the learner finds besides "which screen is up": values that count (a score, money, a timer) and text the game
 * writes. A synthetic game stands in for a real one — a screen id, a slowly rising "gold", a frame counter that rises
 * every look, a dialogue buffer the game rewrites, and binary noise that must never be mistaken for text.
 */
class ReaderCalibratorValuesTest {

    private class FakeGame {
        val ram = ByteArray(64)
        private var look = 0

        /** The picture of screen [index]: two bits far apart, so no two screens are mistaken for one another. */
        fun picture(index: Int): Long = 0b11L shl (10 * index)

        fun looks(count: Int, calibrator: ReaderCalibrator) {
            repeat(count) {
                val screen = look % 5
                ram[1] = screen.toByte() // which screen the game is on
                ram[8] = (100 + look / 20).toByte() // "gold": counts up, slowly (twice in 50 looks)
                val frame = look + 1 // a frame counter: rises on every single look
                ram[12] = frame.toByte()
                ram[13] = (frame ushr 8).toByte()
                ram[14] = (frame ushr 16).toByte()
                ram[15] = (frame ushr 24).toByte()
                val text = "HELLO WORLD" + (look / 10) // the game's dialogue buffer, rewritten every 10 looks
                text.forEachIndexed { index, character -> ram[20 + index] = character.code.toByte() }
                ram[20 + text.length] = 0
                for (index in 40 until ram.size) ram[index] = (0x80 + (look % 16)).toByte() // binary, never printable
                calibrator.observe(picture(screen), ram)
                look++
            }
        }
    }

    /** One part of memory (the whole fake RAM), long enough for "gold" to move twice before the part is left. */
    private fun calibrator() = ReaderCalibrator(
        chunkSize = 64,
        chunkLooks = 50,
        maxChunkLooks = 60,
        maxScreens = 16,
        minSamples = 20,
        minScreens = 3,
        minDistinct = 3,
        minConsistency = 0.9,
        minVerified = 4,
        minRevisits = 1,
    )

    private fun observations(): List<Observation> {
        val game = FakeGame()
        val calibrator = calibrator()
        game.looks(55, calibrator)
        return calibrator.draft("fake-game")?.observations.orEmpty()
    }

    private fun Observation.at(offset: Int) = address == RamProbe.MAIN_RAM_START + offset

    @Test
    fun thePanelReadyFlagAgreesWithWhatWouldBeWritten() {
        // A run can end with unverified addresses separating more screens than the proven ones: the panel must not say
        // "not ready" while a reader is written, nor "ready" when nothing would be.
        val game = FakeGame()
        val calibrator = calibrator()
        repeat(60) {
            game.looks(1, calibrator)
            assertEquals(calibrator.isReady(), calibrator.progress().ready, "after ${it + 1} looks")
        }
    }

    @Test
    fun aValueThatOnlyGoesOneWayIsProposedAsACounter() {
        val counters = observations().filter { it.type == ValueType.U16 || it.type == ValueType.U32 }
        val gold = counters.firstOrNull { it.at(8) }
        assertTrue(gold != null, "the rising value should be proposed: ${counters.map { it.name to it.address }}")
        assertEquals(ValueType.U16, gold.type, "it is a halfword, the narrowest reading of it")
        assertTrue(gold.note.contains("only ever went one way"), gold.note)
        // The dialogue buffer's own bytes are text, not a counter that happens to rise.
        assertTrue(counters.none { it.address in (RamProbe.MAIN_RAM_START + 20)..(RamProbe.MAIN_RAM_START + 39) })
    }

    @Test
    fun aValueThatMovesOnEveryLookIsNotACounter() {
        // The frame counter rises every look: proposing it would drown the model in a number that says nothing.
        val frame = observations().filter { it.at(12) || it.at(13) || it.at(14) || it.at(15) }
        assertEquals(emptyList(), frame, "a clock is not a counter")
    }

    @Test
    fun textTheGameRewritesIsProposedAsText() {
        val text = observations().firstOrNull { it.type == ValueType.ASCII }
        assertTrue(text != null, "the dialogue buffer should be proposed: ${observations().map { it.name to it.type }}")
        assertTrue(text.at(20), "it is the buffer at offset 20")
        assertTrue(text.length >= "HELLO WORLD".length, "its length is what the game wrote (${text.length})")
    }

    @Test
    fun binaryNoiseIsNeverMistakenForText() {
        // Nothing in the noisy half of memory (offsets 40..63) may be proposed as a value of any kind.
        val noise = observations().filter { it.address >= RamProbe.MAIN_RAM_START + 40 }
        assertEquals(emptyList(), noise, "printable-looking binary is not text")
    }
}
