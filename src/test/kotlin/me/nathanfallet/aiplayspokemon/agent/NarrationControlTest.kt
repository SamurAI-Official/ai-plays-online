package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NarrationControl] against a fake voice: the dropdown's choice decides whether anything is said, the voice
 * used is the one picked (read per line, so a change applies to the next line), and only the lines the voice
 * really said show up in the readout.
 */
class NarrationControlTest {

    /** A voice that records what it was asked to say. */
    private class FakeSpeaker : DecisionNarrator.Speaker {
        val spoken = CopyOnWriteArrayList<Pair<String, String>>() // text, voice
        override fun speak(text: String, voice: String, rate: Int) {
            spoken += text to voice
        }

        override fun cut() = Unit
    }

    private fun control(
        speaker: FakeSpeaker,
        voice: () -> String = { "Whisper" },
        log: MutableList<String> = mutableListOf(),
    ) = NarrationControl(
        voice = voice,
        rate = { 185 },
        scope = CoroutineScope(Dispatchers.IO),
        speaker = { speaker },
        log = { line -> log += line },
    )

    @Test
    fun nothingIsSaidWhileNarrationIsOff() {
        val speaker = FakeSpeaker()
        val control = control(speaker)
        assertNull(control.narrator(), "no narrator while narration is off")
        Thread.sleep(50)
        assertEquals(emptyList(), speaker.spoken)
        assertEquals(emptyList(), control.spoken.value)
    }

    @Test
    fun theVoicePickedIsTheOneUsedAndTheLineIsReadOut() {
        val speaker = FakeSpeaker()
        val control = control(speaker)
        control.setVoice("Whisper")
        val narrator = assertNotNull(control.narrator(), "a narrator once a voice is picked")
        narrator.narrateMilestone("Left the house")
        await { speaker.spoken.isNotEmpty() }
        assertEquals(listOf("Milestone: Left the house" to "Whisper"), speaker.spoken.toList())
        // The readout shows the line that was really said, with the voice that said it.
        assertEquals(listOf("[Whisper] Milestone: Left the house"), control.spoken.value)
    }

    @Test
    fun aNewVoiceAppliesToTheNextLineWithoutANewNarrator() {
        val speaker = FakeSpeaker()
        var voice = "Whisper"
        val control = control(speaker, voice = { voice })
        control.setVoice("Whisper")
        val narrator = assertNotNull(control.narrator())
        narrator.narrateMilestone("First")
        await { speaker.spoken.size == 1 }
        assertEquals("Whisper", speaker.spoken.first().second, "the voice is read per line")
        voice = "Samantha" // changed after the first line was said
        narrator.narrateMilestone("Second")
        await { speaker.spoken.size == 2 }
        assertEquals("Samantha", speaker.spoken.last().second, "the new voice applies to the next line")
    }

    @Test
    fun turningNarrationOffStopsTheVoiceAndKeepsWhatWasSaid() {
        val speaker = FakeSpeaker()
        val control = control(speaker)
        control.setVoice("Whisper")
        val narrator = assertNotNull(control.narrator())
        narrator.narrateMilestone("Said before the switch")
        await { speaker.spoken.isNotEmpty() }
        control.setVoice(null)
        assertNull(control.narrator(), "no narrator once narration is off")
        narrator.narrateMilestone("Not said")
        Thread.sleep(100)
        assertEquals(listOf("Milestone: Said before the switch"), speaker.spoken.map { it.first })
        // What was really said stays in the readout.
        assertEquals(listOf("[Whisper] Milestone: Said before the switch"), control.spoken.value)
    }

    @Test
    fun aLineTheVoiceRefusesIsNotShownAsSpoken() {
        val logged = mutableListOf<String>()
        val control = NarrationControl(
            voice = { "Whisper" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            speaker = {
                object : DecisionNarrator.Speaker {
                    override fun speak(text: String, voice: String, rate: Int) =
                        throw IllegalStateException("say exited with 1 for voice \"$voice\"")

                    override fun cut() = Unit
                }
            },
            log = { line -> logged += line },
        )
        control.setVoice("Whisper")
        assertNotNull(control.narrator()).narrateMilestone("Never said")
        await { logged.any { it.startsWith("narration failed:") } }
        assertTrue(control.spoken.value.isEmpty(), "a line that failed is not shown as spoken")
    }

    @Test
    fun onlyTheNewestLinesAreKept() {
        val speaker = FakeSpeaker()
        val control = NarrationControl(
            voice = { "Whisper" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            speaker = { speaker },
            limit = 3,
        )
        control.setVoice("Whisper")
        val narrator = assertNotNull(control.narrator())
        // One at a time: the narrator's queue drops the oldest waiting line when it is behind.
        repeat(5) {
            narrator.narrateMilestone("Line $it")
            await { speaker.spoken.size == it + 1 }
        }
        assertEquals(
            listOf("[Whisper] Milestone: Line 4", "[Whisper] Milestone: Line 3", "[Whisper] Milestone: Line 2"),
            control.spoken.value,
        )
    }

    @Test
    fun theExplicitToggleTurnsNarrationOnAndOffIndependentlyOfTheVoice() {
        val speaker = FakeSpeaker()
        val control = control(speaker)
        // The toggle is independent of the voice: narration can be switched on and off without touching the voice.
        control.setEnabled(true)
        assertTrue(control.isEnabled)
        assertNotNull(control.narrator(), "the narrator follows the explicit toggle on")
        control.narrator()!!.narrateMilestone("First said")
        await { speaker.spoken.isNotEmpty() }
        control.setEnabled(false)
        assertNull(control.narrator(), "no narrator once the explicit toggle is off")
        // Calling narrateMilestone with the narrator stopped must not throw and must not record a line.
        assertNull(control.narrator())
        Thread.sleep(100)
        assertEquals(listOf("Milestone: First said"), speaker.spoken.map { it.first })
        // Restoring the toggle leaves the voice intact and reopens the narrator.
        control.setEnabled(true)
        assertTrue(control.isEnabled)
        assertNotNull(control.narrator(), "the narrator comes back on the explicit toggle")
    }

    @Test
    fun theControlFollowsTheAppsSetting() {
        val speaker = FakeSpeaker()
        val control = control(speaker)
        control.setEnabled(true)
        assertTrue(control.isEnabled)
        assertNotNull(control.narrator(), "the narrator follows the setting on")
        control.setEnabled(false)
        assertNull(control.narrator(), "the narrator follows the setting off")
    }

    /** Waits (briefly) for [condition]: lines are spoken on the narrator's own coroutine. */
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 2_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < until, "condition not met within 2 s")
            Thread.sleep(10)
        }
    }
}
