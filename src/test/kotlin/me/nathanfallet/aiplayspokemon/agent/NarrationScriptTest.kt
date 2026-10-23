package me.nathanfallet.aiplayspokemon.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [NarrationScript] as the stream's voice says one decision: spoken clean, short, and without markdown. */
class NarrationScriptTest {

    @Test
    fun saysTheDecisionItsConfidenceAndWhatItDid() {
        val line = NarrationScript.of(
            number = 42,
            confidence = 0.85,
            actions = listOf("step(north,3)"),
            thought = "The stairs are north past the table.",
        )
        assertEquals(
            "Decision 42. 85 percent confidence. step north 3. The stairs are north past the table.",
            line,
        )
    }

    @Test
    fun saysNoConfidenceWhenTheModelHasNone() {
        val line = NarrationScript.of(number = 7, confidence = null, actions = listOf("press(a)"), thought = null)
        assertEquals("Decision 7. press a.", line)
    }

    @Test
    fun countsTheFurtherStepsOfASequence() {
        val line = NarrationScript.of(
            number = 3,
            confidence = null,
            actions = listOf("interact(person:0)", "advance_dialogue", "advance_dialogue"),
            thought = null,
        )
        assertEquals("Decision 3. interact person:0, then 2 more steps.", line)
    }

    @Test
    fun saysWhenTheActionFailedOrTheSequenceStopped() {
        val line = NarrationScript.of(
            number = 9,
            confidence = 0.4,
            actions = listOf("go_to(exit:south)"),
            thought = "Going out.",
            problem = "human driving",
            stop = "nothing happened for 10 s",
        )
        assertEquals(
            "Decision 9. 40 percent confidence. go to exit:south. Going out. " +
                "But it didn't work: human driving. Then it stopped: nothing happened for 10 s.",
            line,
        )
    }

    @Test
    fun stripsMarkdownWhenSpeaking() {
        assertEquals(
            "I see a north door and exit 0.",
            NarrationScript.forSpeech("I see a **north** door and `exit_0`."),
        )
        assertEquals("", NarrationScript.forSpeech("```json\n```"))
    }

    @Test
    fun capsTheReasoningAtAWordBoundary() {
        val long = List(30) { "corridor" }.joinToString(" ")
        val said = NarrationScript.forSpeech(long)
        assertTrue(said.length <= 160, "too long: $said")
        assertTrue(said.endsWith("corridor"), "cut mid-word: $said")
        assertFalse(said.contains("  "), "leftover double space: $said")
    }
}