package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.RamMemory
import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.READER_RAM_START
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checking a reader against the game: the values that claim to name screens are held to the rule they were written
 * under, and everything else is reported for what it does. A synthetic reader and a synthetic game, so every verdict
 * is exercised (held, moves on its own, never changes, never readable).
 */
class ReaderVerifierTest {

    private fun addressOf(offset: Int) = READER_RAM_START + offset

    /** A reader with one of everything: a good screen id, an unstable one, a constant, a guarded one, a counter, text. */
    private fun reader() = ReaderSpec(
        id = "fake-game",
        provenance = "calibrated",
        observations = listOf(
            Observation("screen", addressOf(1), ValueType.U8, values = mapOf("0" to "menu", "1" to "field", "2" to "battle")),
            Observation("unstable", addressOf(3), ValueType.U8, values = mapOf("0" to "a")),
            Observation("constant", addressOf(5), ValueType.U8, values = mapOf("0" to "b")),
            Observation(
                "guarded", addressOf(7), ValueType.U8, values = mapOf("9" to "c"),
                guardAddress = addressOf(10), guardEquals = 1,
            ),
            Observation("counter", addressOf(12), ValueType.U16),
            Observation("text", addressOf(20), ValueType.ASCII, length = 8),
        ),
        notes = "Calibrated by hand.",
    )

    private class FakeGame {
        private var look = 0
        val ram = ByteArray(64)

        fun picture(screen: Int): Long = 0b11L shl (10 * screen)

        /** One look at [screen]: the screen id holds, the unstable value moves, the guard never holds. */
        fun look(screen: Int) {
            ram[1] = screen.toByte() // the screen id: one value per screen
            ram[3] = (look % 97).toByte() // moves within a screen
            ram[5] = 0 // never moves
            ram[7] = 9 // meaningful only behind the guard
            ram[10] = 0 // the guard: off, so `guarded` is never read
            ram[12] = look.toByte() // a counter
            ram[13] = (look ushr 8).toByte()
            val text = "HELLO " + (look % 10)
            text.forEachIndexed { index, character -> ram[20 + index] = character.code.toByte() }
            ram[20 + text.length] = 0
            look++
        }
    }

    /** Runs [looks] looks cycling through 3 screens (so every screen comes back). 90 by default: enough to be believed. */
    private fun verify(looks: Int = 90): Pair<ReaderVerifier, ReaderVerification> {
        val verifier = ReaderVerifier(reader())
        val game = FakeGame()
        var report = verifier.report()
        repeat(looks) { index ->
            val screen = index % 3
            game.look(screen)
            report = verifier.observe(game.picture(screen), RamMemory(game.ram))
        }
        return verifier to report
    }

    private fun ReaderVerification.verdictOf(name: String) = observations.first { it.name == name }.verdict

    @Test
    fun aScreenValueThatHoldsIsOk() {
        val (_, report) = verify()
        assertEquals(ReaderVerdict.OK, report.verdictOf("screen"))
        val screen = report.observations.first { it.name == "screen" }
        assertEquals(3, screen.screensSeen)
        assertEquals(3, screen.screensHeld)
        assertEquals(3, screen.distinct, "it tells the three screens apart")
        assertEquals(1.0, screen.consistency)
    }

    @Test
    fun aValueThatMovesWithinAScreenIsInconsistent() {
        val (_, report) = verify()
        assertEquals(ReaderVerdict.INCONSISTENT, report.verdictOf("unstable"))
        assertTrue(report.problems.any { it.name == "unstable" }, "it is what a user has to look at")
    }

    @Test
    fun aValueThatNeverChangesIsReportedAsConstant() {
        val (_, report) = verify()
        assertEquals(ReaderVerdict.CONSTANT, report.verdictOf("constant"))
        assertEquals(1, report.observations.first { it.name == "constant" }.distinct)
    }

    @Test
    fun aValueBehindAGuardThatNeverHoldsIsUnreadable() {
        val (_, report) = verify()
        val guarded = report.observations.first { it.name == "guarded" }
        assertEquals(ReaderVerdict.UNREADABLE, guarded.verdict)
        assertEquals(0, guarded.reads)
        assertEquals(90, guarded.unreadable)
        assertEquals("never read (guard off 90 times)", guarded.detail)
    }

    @Test
    fun aCounterAndTextAreReportedForWhatTheyDoNotForScreens() {
        val (_, report) = verify()
        assertEquals(ReaderVerdict.OK, report.verdictOf("counter"), "it moves: that is what it is for")
        assertEquals(ReaderVerdict.OK, report.verdictOf("text"))
        assertTrue(report.observations.first { it.name == "counter" }.detail.contains("moved"), "the range is the evidence")
        assertTrue(report.observations.first { it.name == "text" }.detail.startsWith("now: \"HELLO"), "what it says now")
    }

    @Test
    fun theSummaryCountsOnlyTheValuesThatClaimToNameScreens() {
        val (_, report) = verify()
        // screen (held), unstable (not), constant (not): the guarded one was never read, so it is not counted as seen.
        assertEquals(3, report.observations.count { it.screensSeen > 0 })
        assertTrue(report.summary.contains("1 of 3 screen values hold"), report.summary)
        assertTrue(report.summary.contains("2 to look at"), report.summary)
    }

    @Test
    fun theCheckFollowsTheReaderAsItGrows() {
        val (verifier, before) = verify()
        // The app learned more while playing: the same values, plus a new one.
        val grown = reader().let { spec ->
            spec.copy(observations = spec.observations + Observation("later", addressOf(30), ValueType.U8))
        }
        verifier.reread(grown)
        val after = verifier.report()
        assertEquals(before.observations.size + 1, after.observations.size)
        assertEquals(before.looks, after.looks, "the looks already taken are kept, not thrown away")
        assertEquals(3, after.observations.first { it.name == "screen" }.screensSeen)
        assertEquals(ReaderVerdict.OK, after.verdictOf("screen"))
        // A value that changed reading starts over: its tally was about the old reading.
        val rewritten = grown.copy(
            observations = grown.observations.map {
                if (it.name == "screen") it.copy(values = mapOf("0" to "renamed")) else it
            },
        )
        verifier.reread(rewritten)
        assertEquals(0, verifier.report().observations.first { it.name == "screen" }.screensSeen)
        // The confidence the check itself writes is not a change of reading: the looks stay.
        verifier.reread(rewritten.copy(observations = rewritten.observations.map { it.copy(confidence = 0.25) }))
        assertEquals(before.looks, verifier.report().looks)
    }

    @Test
    fun theReaderKeepsWhatTheCheckSaw() {
        val (verifier, report) = verify()
        val verified = verifier.verifiedReader(report)
        val byName = verified.observations.associateBy { it.name }
        assertEquals(1.0, byName.getValue("screen").confidence, "a claim that holds is not inflated")
        assertTrue(byName.getValue("unstable").confidence < 1.0, "a claim caught moving within a screen is downgraded")
        assertEquals(1.0, byName.getValue("constant").confidence, "a value the check could not exercise is not refuted")
        assertEquals(1.0, byName.getValue("guarded").confidence, "nor is one it never read")
        assertEquals(1.0, byName.getValue("counter").confidence, "a counter never claimed to name screens")
        assertTrue(verified.notes.contains("Checked while playing: 1 of 3 screen values hold"), verified.notes)
        assertTrue(verified.notes.contains("Calibrated by hand."), "the reader's own notes are kept")
        // Checked again: the line is rewritten, not piled up.
        val again = verifier.verifiedReader(report)
        assertEquals(1, again.notes.lines().count { it.startsWith("Checked while playing:") })
    }

    @Test
    fun aThinRunChangesNothing() {
        // Four looks per screen: every value looks constant, and nothing was ever re-visited. Not a verdict.
        val (verifier, report) = verify(looks = 12)
        assertFalse(report.isConclusive)
        assertTrue(report.problems.isEmpty(), "no problem is reported from a run that showed too little")
        assertTrue(report.summary.startsWith("checking…"), report.summary)
        assertEquals(reader(), verifier.verifiedReader(report), "the reader is left exactly as it was")
    }
}

