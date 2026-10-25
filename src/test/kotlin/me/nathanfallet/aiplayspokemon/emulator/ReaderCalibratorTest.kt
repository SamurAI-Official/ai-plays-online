package me.nathanfallet.aiplayspokemon.emulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderCalibratorTest {

    private fun addressOf(offset: Int) = RamProbe.MAIN_RAM_START + offset

    /** One part of memory big enough to hold the whole fake RAM, so nothing rotates mid-test. */
    private fun calibrator() = ReaderCalibrator(chunkSize = 1 shl 23, chunkLooks = 10_000, maxChunkLooks = 20_000)

    /** Pictures far enough apart (more than the screen tolerance) to be four different screens. */
    private val screenA = 0x0000000000000000L
    private val screenB = 0x5555555555555555L
    private val screenC = 0x3333333333333333L
    private val screenD = 0x0F0F0F0F0F0F0F0FL

    /**
     * A fake game: [screenId] holds the current screen (address 0x100, or [idOffset] for the tests that need it
     * elsewhere in memory), [noisy] a value that changes on its own (address 0x200), and everything else stays put.
     */
    private fun ram(screenId: Int, noisy: Int, idOffset: Int = 0x100): ByteArray = ByteArray(0x400000).also { ram ->
        ram[idOffset] = screenId.toByte()
        ram[0x200] = noisy.toByte()
    }

    @Test
    fun oneLookIsNotEnoughAndNothingIsInvented() {
        val calibrator = calibrator()
        val progress = calibrator.observe(picture = screenA, ram = ram(1, 7))
        assertEquals(1, progress.samples)
        assertEquals(1, progress.screens)
        assertTrue(!progress.ready)
        assertNull(calibrator.draft("test"), "nothing to write from a single look")
    }

    @Test
    fun theScreenIdIsLearnedAndTheNoiseIsDropped() {
        val calibrator = calibrator()
        val screens = listOf(screenA to 1, screenB to 2, screenC to 3, screenD to 4)
        // Several looks per screen, with a noisy address changing every look.
        repeat(12) { round ->
            screens.forEachIndexed { index, (hash, id) ->
                calibrator.observe(hash, ram(screenId = id, noisy = round * 4 + index))
            }
        }
        assertTrue(calibrator.isReady(), "48 looks at four screens should be enough")
        val best = calibrator.candidates().first()
        assertEquals(addressOf(0x100), best.address, "the screen id comes first, the noisy address is dropped")
        assertEquals(4, best.distinct)
        assertEquals(mapOf(0 to 1L, 1 to 2L, 2 to 3L, 3 to 4L), best.byScreen)
        assertEquals(1.0, best.consistency, "it never once contradicted a screen")
        assertTrue(calibrator.candidates().none { it.address == addressOf(0x200) }, "noise is not a signal")
    }

    @Test
    fun aScreenThatAnimatesIsStillOneScreen() {
        val calibrator = calibrator()
        // The same menu, two pictures a couple of bits apart (a blinking cursor), on two values of the screen id.
        calibrator.observe(screenA, ram(screenId = 1, noisy = 0))
        calibrator.observe(screenA xor 0b11L, ram(screenId = 1, noisy = 0))
        calibrator.observe(screenB, ram(screenId = 2, noisy = 0))
        assertEquals(2, calibrator.progress().screens, "pictures within tolerance are one screen")
        assertEquals(2, calibrator.candidates().first().distinct)
    }

    @Test
    fun aVolatileAddressThatOnlyMostlyAgreesIsNotASignal() {
        val calibrator = calibrator()
        // A "screen id" that is right most of the time but changes on its own now and then: it is not one.
        repeat(30) { round ->
            calibrator.observe(screenA, ram(screenId = if (round % 3 == 0) 9 else 1, noisy = 0))
            calibrator.observe(screenB, ram(screenId = 2, noisy = 0))
        }
        assertTrue(
            calibrator.candidates().none { it.address == addressOf(0x100) },
            "an address that disagrees with the screen a third of the time is noise",
        )
    }

    @Test
    fun anAddressThatDisagreesWithItselfIsDropped() {
        val calibrator = calibrator()
        // The "screen id" takes a different value every look: dropped as soon as it shows it.
        repeat(10) { round -> calibrator.observe(screenA, ram(screenId = round, noisy = 0)) }
        calibrator.observe(screenB, ram(screenId = 2, noisy = 0))
        assertTrue(calibrator.candidates().none { it.address == addressOf(0x100) })
        assertEquals(0, calibrator.progress().candidates, "a volatile address is forgotten, not kept")
    }

    @Test
    fun theDraftNamesWhatItKnowsAndSaysHowItWasMade() {
        val calibrator = calibrator()
        listOf(screenA to 1, screenB to 2, screenC to 3).forEach { (hash, id) ->
            repeat(8) { calibrator.observe(hash, ram(id, noisy = it)) }
        }
        val draft = calibrator.draft("some-game", names = mapOf(screenB to "the main menu"))!!
        assertEquals("some-game", draft.id)
        assertEquals("learned while playing", draft.provenance)
        val screen = draft.observations.first()
        assertEquals("screen", screen.name)
        assertEquals(addressOf(0x100), screen.address)
        assertEquals("the main menu", screen.values.getValue("2"), "a name a walkthrough knows is used")
        assertTrue(screen.values.getValue("1").startsWith("screen "), "an unknown screen is named by its picture")
        assertTrue(draft.notes.contains("Written automatically"))
    }

    @Test
    fun aSingleScreenTeachesNothing() {
        val calibrator = calibrator()
        repeat(40) { calibrator.observe(screenA, ram(1, it)) }
        assertEquals(1, calibrator.progress().screens)
        assertTrue(!calibrator.isReady(), "one screen says nothing about which value means what")
    }

    @Test
    fun aClockThatMovesOncePerVisitIsNeverReportedAsAScreenSignal() {
        val calibrator = calibrator()
        var clock = 0
        // The clock at 0x200 moves once per screen *visit*, so within a visit it looks constant: it takes a later look
        // at the same screen (or a visit that gives it too many values) to tell it apart from a screen id.
        repeat(20) { round ->
            clock++
            calibrator.observe(screenA, ram(screenId = 1, noisy = clock))
            clock++
            calibrator.observe(screenB, ram(screenId = 2, noisy = clock))
        }
        assertTrue(
            calibrator.candidates(minConsistency = 0.0).none { it.address == addressOf(0x200) },
            "a clock is not a screen signal, however convincing its first few looks were",
        )
        val screenId = calibrator.candidates().firstOrNull { it.address == addressOf(0x100) }
        assertEquals(2, screenId?.distinct)
        assertTrue((screenId?.revisits ?: 0) > 0, "the screen id was seen again on a screen that came back")
    }

    @Test
    fun aSignalIsKeptWhenTheLearnerMovesOnToTheNextPartOfMemory() {
        // Two parts of 2 MiB each: the learner has to move on from the first, and what it found there must survive.
        val calibrator = ReaderCalibrator(chunkSize = 0x200000, chunkLooks = 3, maxChunkLooks = 6)
        repeat(12) { round ->
            calibrator.observe(screenA, ram(screenId = 1, noisy = round))
            calibrator.observe(screenB, ram(screenId = 2, noisy = round))
        }
        assertEquals(2, calibrator.progress().chunks, "2 MiB parts: there are two of them")
        val kept = calibrator.candidates().firstOrNull { it.address == addressOf(0x100) }
        assertEquals(2, kept?.distinct, "the signal from the part that was left behind is still reported")
    }

    @Test
    fun aSignalInALaterPartOfMemoryIsFoundAfterItMovesOn() {
        // The screen id lives past the first 2 MiB, so only the second part can find it.
        val calibrator = ReaderCalibrator(chunkSize = 0x200000, chunkLooks = 4, maxChunkLooks = 8)
        val idOffset = 0x200100
        repeat(20) { round ->
            calibrator.observe(screenA, ram(screenId = 1, noisy = 0, idOffset = idOffset))
            calibrator.observe(screenB, ram(screenId = 2, noisy = 0, idOffset = idOffset))
        }
        val best = calibrator.candidates().firstOrNull { it.address == addressOf(idOffset) }
        assertEquals(2, best?.distinct, "the signal in the second part of memory was still found")
    }

    @Test
    fun resetForgetsThePreviousGameIncludingVerificationAndRefutations() {
        val calibrator = calibrator()
        val pictures = listOf(screenA, screenB, screenC, screenD)

        fun play(values: List<Int>) {
            repeat(12) { round ->
                pictures.forEachIndexed { index, picture ->
                    calibrator.observe(picture, ram(screenId = values[index], noisy = round * 4 + index))
                }
            }
        }

        play(listOf(1, 2, 3, 4))
        assertTrue(calibrator.isReady())
        assertTrue(calibrator.candidates().first { it.address == addressOf(0x100) }.verified > 0)

        calibrator.reset()
        val empty = calibrator.progress()
        assertEquals(0, empty.samples)
        assertEquals(0, empty.screens)
        assertEquals(0, empty.candidates)
        assertTrue(calibrator.candidates().isEmpty())

        // The same address is a different screen id in the next game. None of the old game's values may refute it,
        // and its verification and revisit counts must describe only these 48 new looks.
        play(listOf(11, 22, 33, 44))
        val relearned = calibrator.candidates().firstOrNull { it.address == addressOf(0x100) }
        assertEquals(mapOf(0 to 11L, 1 to 22L, 2 to 33L, 3 to 44L), relearned?.byScreen)
        assertEquals(47, relearned?.verified, "watching starts on the second of the 48 looks")
        assertEquals(45, relearned?.revisits, "the first look at a new screen is not a revisit")
        assertTrue(calibrator.isReady())
    }

    @Test
    fun tooManyNewScreensDoesNotStallTheLearner() {
        val calibrator = ReaderCalibrator(
            chunkSize = 0x200000,
            chunkLooks = 3,
            maxChunkLooks = 3,
            maxScreens = 1,
        )
        calibrator.observe(screenA, ram(screenId = 1, noisy = 0))
        calibrator.observe(screenB, ram(screenId = 2, noisy = 0))
        val progress = calibrator.observe(screenC, ram(screenId = 3, noisy = 0))

        assertEquals(3, progress.samples, "an unnamed picture is still a look at the game")
        assertEquals(1, progress.chunk, "memory keeps rotating even when no more screens can be named")
        assertEquals(2, progress.chunks)
    }

}
