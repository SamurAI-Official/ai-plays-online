package me.nathanfallet.aiplayspokemon.emulator

import java.nio.file.Files
import java.nio.file.Path
import me.nathanfallet.aiplayspokemon.games.GamesFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The learner's evidence, and what resuming from it does: a session that ends before a reader is ready must not start
 * over. A synthetic game stands in for a real one: a byte that says which screen is up (twice, at two addresses), a
 * counter that moves on its own (it must be refuted, not read), and the rest of memory untouched.
 */
class ReaderLearningTest {

    /** A fake game of [chunks] chunks of [chunkSize] bytes, with [screens] screens shown in a cycle. */
    private class FakeGame(
        private val chunkSize: Int = 8,
        private val chunks: Int = 4,
        private val screens: Int = 5,
    ) {
        val ram = ByteArray(chunkSize * chunks)
        private var look = 0

        /** The picture of screen [index]: two bits far apart, so no two screens are ever mistaken for one another. */
        fun picture(index: Int): Long = 0b11L shl (10 * index)

        /** Feeds [count] more looks to [calibrator], continuing the same game (and the same counter). */
        fun looks(count: Int, calibrator: ReaderCalibrator) {
            repeat(count) {
                val screen = look % screens
                ram[1] = screen.toByte() // the game's screen id
                ram[3] = (look and 0xFF).toByte() // a frame counter: moves on its own, never a screen signal
                ram[7] = (screen * 2).toByte() // a second value that follows the screen
                calibrator.observe(picture(screen), ram)
                look++
            }
        }
    }

    private fun calibrator() = ReaderCalibrator(
        chunkSize = 8,
        chunkLooks = 4,
        maxChunkLooks = 8,
        maxScreens = 16,
        minSamples = 20,
        minScreens = 3,
        minDistinct = 3,
        minConsistency = 0.9,
        minVerified = 4,
        minRevisits = 1,
    )

    /** What a draft says, without the prose that counts looks: the claim itself. */
    private fun claim(calibrator: ReaderCalibrator): List<Pair<Long, Map<String, String>>> =
        assertNotNull(calibrator.draft("fake-game"), "no draft was written").observations
            .map { observation -> observation.address to observation.values }

    @Test
    fun evidenceSurvivesTheFileItIsWrittenTo() {
        val calibrator = calibrator()
        val game = FakeGame()
        game.looks(6, calibrator)
        val evidence = calibrator.toEvidence("fake-game", "a-rom-hash")

        val directory = Files.createTempDirectory("reader-learning")
        val file = ReaderLearningStore.write(evidence, directory)
        assertEquals(directory.resolve("readers").resolve("fake-game.learning.json"), file)
        assertTrue(file.fileName.toString().endsWith(".learning.json"), "the evidence must not look like a reader")

        assertEquals(evidence, ReaderLearningStore.of("fake-game", directory), "the evidence must round-trip")
        assertTrue(evidence.matches("a-rom-hash"))
        assertTrue(evidence.matches("another-hash") == false, "evidence from another ROM must not be resumed")
        assertTrue(evidence.matches(""), "evidence written before the app checked is taken as is")
    }

    @Test
    fun aMissingOrCorruptFileIsSimplyNotResumed() {
        val directory = Files.createTempDirectory("reader-learning")
        assertNull(ReaderLearningStore.of("nothing-here", directory))
        Files.createDirectories(directory.resolve("readers"))
        Files.writeString(directory.resolve("readers").resolve("broken.learning.json"), "{ not json")
        assertNull(ReaderLearningStore.of("broken", directory), "a corrupt file must be ignored, not fatal")
    }

    @Test
    fun aRestoredCalibratorKeepsWhatWasLearned() {
        val game = FakeGame()
        val first = calibrator()
        game.looks(6, first)
        val evidence = first.toEvidence("fake-game", "hash")

        val resumed = calibrator()
        resumed.restore(evidence)
        // Nothing has been examined yet in this session: the sweep continues where the evidence says, on the first look.
        assertEquals(evidence.samples, resumed.progress().samples)
        assertEquals(evidence.screens.size, resumed.progress().screens)
        game.looks(1, resumed)
        assertEquals(evidence.chunk, resumed.progress().chunk, "the sweep must continue at the part it stopped at")
    }

    @Test
    fun theEvidenceStaysBoundedHoweverMuchMemoryMovesWithTheScreen() {
        // A pathological game: every byte of memory is a function of the screen, so every address is a perfect signal
        // (a real 71-screen run folded 705,824 of them and wrote a 222 MB file). Only the best handful are ever read.
        val chunkSize = 4096
        val calibrator = ReaderCalibrator(chunkSize = chunkSize, chunkLooks = 4, maxChunkLooks = 8)
        val ram = ByteArray(chunkSize * 4)
        repeat(24) { look ->
            val screen = look % 5
            for (index in ram.indices) ram[index] = (screen * 7 + index).toByte()
            calibrator.observe(0b11L shl (10 * screen), ram)
        }

        val evidence = calibrator.toEvidence("noisy-game", "hash")
        // Bounded, and not empty: the game really did offer thousands of signals, of which only the best are kept.
        assertTrue(evidence.found.size in 32..64, "the found signals must stay bounded, were ${evidence.found.size}")
        assertTrue(evidence.dropped.size <= 4096, "the forgotten addresses must stay bounded, were ${evidence.dropped.size}")
        val size = GamesFile.json.encodeToString(evidence).length
        assertTrue(size < 200_000, "the evidence file must stay small, was $size bytes")
    }

    @Test
    fun resumingReachesAReaderInFewerLooksThanStartingOver() {
        // How many looks a reader takes from scratch, on this fake game.
        val scratch = calibrator()
        val firstGame = FakeGame()
        var fromScratch = 0
        while (!scratch.isReady() && fromScratch < 500) {
            firstGame.looks(1, scratch)
            fromScratch++
        }
        assertTrue(scratch.isReady(), "the fake game should teach a reader in a reasonable number of looks")

        // The same game, stopped half way and picked up by a new session: it must need the looks that were left, not
        // a whole run (the evidence kept the screens, the signals and the checks; only the part in progress restarts).
        val game = FakeGame()
        val first = calibrator()
        game.looks(fromScratch / 2, first)
        val resumed = calibrator()
        resumed.restore(first.toEvidence("fake-game", "hash"))
        var secondSession = 0
        while (!resumed.isReady() && secondSession < 500) {
            game.looks(1, resumed)
            secondSession++
        }
        assertTrue(resumed.isReady(), "the resumed session should reach a reader")
        assertTrue(
            secondSession <= fromScratch / 2 + 4,
            "resuming took $secondSession looks where a whole run takes $fromScratch: the evidence was not used",
        )
    }

    @Test
    fun aResumedRunLearnsTheSameReaderAsAnUninterruptedOne() {
        val uninterrupted = calibrator()
        FakeGame().looks(60, uninterrupted)
        val expected = claim(uninterrupted)
        assertTrue(expected.isNotEmpty(), "the uninterrupted run should have found the screen id")

        // The same game, interrupted after 6 looks and picked up by a new calibrator (as a new session would).
        val game = FakeGame()
        val first = calibrator()
        game.looks(6, first)
        val resumed = calibrator()
        resumed.restore(first.toEvidence("fake-game", "hash"))
        game.looks(54, resumed)

        assertEquals(expected, claim(resumed), "resuming must not lose the signals the first session found")
    }
}
