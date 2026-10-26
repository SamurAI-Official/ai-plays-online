package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ProgressSignal
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extending a reader the app learned itself: a later session's draft is folded into it instead of replacing it, and a
 * reader somebody wrote is never touched.
 */
class ReaderMergeTest {

    private fun learned(vararg observations: Observation, progress: List<ProgressSignal> = emptyList()) = ReaderSpec(
        id = "fake-game",
        provenance = ReaderSpec.PROVENANCE_LEARNED,
        observations = observations.toList(),
        progress = progress,
        notes = "Written automatically by ReaderCalibrator while the game was played.",
    )

    private fun screen(address: Long, values: Map<String, String>) =
        Observation("screen", address, ValueType.U8, values = values, confidence = 1.0)

    private fun draft(vararg observations: Observation, progress: List<ProgressSignal> = emptyList()) =
        ReaderSpec(id = "fake-game", provenance = ReaderSpec.PROVENANCE_LEARNED, observations = observations.toList(), progress = progress)

    @Test
    fun theFirstDraftIsKeptAsItIs() {
        val first = draft(screen(0x02000100, mapOf("19" to "main menu")))
        assertEquals(first, ReaderMerge.merge(null, first))
    }

    @Test
    fun aLaterDraftAddsItsValuesAndItsNewObservations() {
        val existing = learned(screen(0x02000100, mapOf("19" to "main menu")))
        val later = draft(
            screen(0x02000100, mapOf("19" to "screen 19", "84" to "screen 84")),
            Observation("menu", 0x02000200, ValueType.U8, values = mapOf("1" to "in mission")),
        )

        val merged = ReaderMerge.merge(existing, later)
        assertEquals(2, merged.observations.size, "the new address is added")
        val screen = merged.observations.first()
        // The name already given wins; the value the later session saw is added.
        assertEquals(mapOf("19" to "main menu", "84" to "screen 84"), screen.values)
        assertEquals("menu", merged.observations[1].name)
        assertEquals(ReaderSpec.PROVENANCE_LEARNED, merged.provenance, "merging never changes who wrote it")
        assertTrue(merged.notes.contains("Extended by later sessions: 2 values"), merged.notes)
    }

    @Test
    fun mergingTwiceDoesNotPileUpNotes() {
        val existing = learned(screen(0x02000100, mapOf("19" to "main menu")))
        val once = ReaderMerge.merge(existing, draft(screen(0x02000100, emptyMap())))
        val twice = ReaderMerge.merge(once, draft(screen(0x02000100, emptyMap())))
        assertEquals(once.notes, twice.notes, "the extension line is rewritten, not appended")
        assertEquals(1, twice.notes.lines().count { it.startsWith("Extended by later sessions:") })
    }

    @Test
    fun aPlaceholderIsRenamedWhenAWalkthroughLandmarkKnowsTheScreen() {
        // The calibrator could not name this screen (no walkthrough landmark matched then): it wrote its picture.
        val picture = 0x2362242C59CBC574L
        val existing = learned(screen(0x02000100, mapOf("19" to "screen " + picture.toULong().toString(16))))
        // A later session has a landmark for that screen, and the picture it captured is a few bits away.
        val names = mapOf((picture xor 0b11L) to "main menu")

        val merged = ReaderMerge.merge(existing, draft(), names)
        assertEquals(mapOf("19" to "main menu"), merged.observations.first().values)
    }

    @Test
    fun progressSignalsAreAddedOnceAndKept() {
        val existing = learned(screen(0x02000100, mapOf("84" to "in mission")))
        val learnedSignal = ProgressSignal("chapter:in-mission", 0x02000100, ValueType.U8, equals = 84)

        val merged = ReaderMerge.merge(existing, draft(), progress = listOf(learnedSignal))
        assertEquals(listOf("chapter:in-mission"), merged.progress.map { it.name })
        // Seen again next session: the same signal is not added twice.
        val again = ReaderMerge.merge(merged, draft(), progress = listOf(learnedSignal, ProgressSignal("chapter:menu", 0x02000100, ValueType.U8, equals = 19)))
        assertEquals(listOf("chapter:in-mission", "chapter:menu"), again.progress.map { it.name })
    }

    @Test
    fun aSecondSessionDoesNotHideTheFirstScreensValue() {
        // Two sessions each name their own values `screen`, `signal_2`…: the panel and the prompt read the reader as a
        // map by name, so the second `screen` would silently replace the first.
        val first = learned(
            Observation("screen", 0x02000100, ValueType.U8, values = mapOf("19" to "main menu")),
            Observation("signal_2", 0x02000101, ValueType.U8, values = mapOf("1" to "x")),
        )
        val second = draft(
            Observation("screen", 0x02200000, ValueType.U8, values = mapOf("7" to "battle")),
            Observation("signal_2", 0x02200001, ValueType.U8, values = mapOf("3" to "y")),
        )

        val names = ReaderMerge.merge(first, second).observations.map { it.name }
        assertEquals(listOf("screen", "signal_2", "screen_2", "signal_2_2"), names)
        assertEquals(names.size, names.toSet().size, "every value stays readable under its own name")
    }

    @Test
    fun aReaderSomebodyWroteIsNeverExtended() {
        val handWritten = ReaderSpec(
            id = "fake-game",
            provenance = "calibrated",
            observations = listOf(screen(0x02000100, mapOf("19" to "main menu"))),
            notes = "Calibrated by hand.",
        )
        val later = draft(Observation("menu", 0x02000200, ValueType.U8))
        assertEquals(later, ReaderMerge.merge(handWritten, later), "only a reader the app learned itself grows")
    }

    @Test
    fun theObservationsStayCapped() {
        val existing = learned(*(0 until ReaderMerge.MAX_OBSERVATIONS).map { index -> Observation("signal_$index", 0x02000100L + index) }.toTypedArray())
        val later = draft(Observation("one_more", 0x02000900, ValueType.U8))
        assertEquals(ReaderMerge.MAX_OBSERVATIONS, ReaderMerge.merge(existing, later).observations.size)
    }
}
