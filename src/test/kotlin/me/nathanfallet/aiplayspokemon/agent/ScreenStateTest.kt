package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.console.Frame
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.games.Milestone
import me.nathanfallet.aiplayspokemon.games.MilestoneKind
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenStateTest {

    /**
     * A frame whose picture is [kind]: 0 is flat (its hash is all zeros), 1 brightens left to right (all ones) and 2 is
     * striped (a mixed pattern) — three pictures far enough apart for the landmark test's threshold.
     */
    private fun frame(kind: Int) = Frame(
        256,
        384,
        IntArray(256 * 384) { index ->
            val x = index % 256
            val level = when (kind) {
                // dHash compares each sample with the one to its right: a picture that gets *darker* left to right
                // is all ones, one that gets brighter is all zeros.
                1 -> 255 - x
                2 -> if (x / 32 % 2 == 0) 0 else 255
                else -> 0
            }
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        },
    )

    /** A model that answers [answers] in order and counts how often it was asked. */
    private class ScriptedModel(private vararg val answers: String) : DecisionModel {
        var calls = 0
        override val name = "scripted"
        override val generative = true
        override suspend fun choose(request: ChoiceRequest): ChoiceResult {
            val answer = answers.getOrElse(calls) { answers.last() }
            calls++
            return ChoiceResult(answer, mapOf(answer to 1.0), 1.0, name, inputTokens = 0)
        }
    }

    private fun chapter(hash: Long) = WalkthroughChapter(
        id = "test:chapter",
        title = "Boot to the front end",
        goal = "get to the menu",
        milestone = Milestone(kind = MilestoneKind.FRAME_HASH, hashes = listOf(me.nathanfallet.aiplayspokemon.emulator.FrameHash.format(hash))),
    )

    @Test
    fun thePictureIsWatchedEvenWithoutAModel() {
        val state = ScreenState()
        // The picture's stability and whether it moved are measured while an action runs (the player does that);
        // what the app itself knows offline is which landmark it matches.
        val view = state.view(frame(FLAT), null, changedByAction = false, stableFrames = 90)
        assertTrue(view.note!!.contains("waiting for input"), "90 still frames: ${view.note}")
        val moved = state.view(frame(RAMP), null, changedByAction = true, stableFrames = 0)
        assertTrue(moved.note!!.contains("the game answered you"), moved.note!!)
        val nothing = state.view(frame(RAMP), null, changedByAction = false, stableFrames = 3)
        assertTrue(nothing.note!!.contains("did nothing visible"), nothing.note!!)
    }

    @Test
    fun theSummarySaysWhatTheModelIsLookingAt() {
        val view = state().view(frame(FLAT), null, changedByAction = true, stableFrames = 4)
        assertEquals("screen not read · stable 4 frames · answered you", view.summary())
    }

    private fun state() = ScreenState()

    @Test
    fun aPictureThatMatchesAChapterLandmarkIsNamed() {
        val state = ScreenState()
        val hash = me.nathanfallet.aiplayspokemon.emulator.FrameHash.dHash(frame(RAMP))
        val view = state.view(frame(RAMP), chapter(hash), changedByAction = true, stableFrames = 0)
        assertEquals("Boot to the front end", view.landmark)
        assertTrue(view.note!!.contains("Boot to the front end"))
        // A picture the chapter does not describe is not named.
        assertNull(state.view(frame(STRIPES), chapter(hash), changedByAction = true, stableFrames = 0).landmark)
    }

    @Test
    fun theModelIsAskedOncePerPicture() = runBlocking {
        val state = ScreenState()
        val model = ScriptedModel("dialogue")
        assertEquals("dialogue", state.read(model, frame(FLAT)))
        assertEquals(1, model.calls)
        // The same picture: no new question.
        state.read(model, frame(FLAT))
        assertEquals(1, model.calls)
        // A new picture: asked again.
        state.read(model, frame(STRIPES))
        assertEquals(2, model.calls)
        val view = state.view(frame(STRIPES), null, changedByAction = true, stableFrames = 0)
        assertEquals("dialogue", view.looksLike, "the reading belongs to the picture it was made on")
        assertFalse(view.blind)
    }

    @Test
    fun aModelWithoutVisionSaysSoInsteadOfInventingAScreen() = runBlocking {
        val state = ScreenState()
        val model = ScriptedModel("unknown")
        state.read(model, frame(FLAT))
        val view = state.view(frame(FLAT), null, changedByAction = true, stableFrames = 0)
        assertTrue(view.blind)
        assertTrue(view.summary().contains("unknown"))
    }

    @Test
    fun anInventedAnswerCountsAsBlind() = runBlocking {
        val state = ScreenState()
        state.read(ScriptedModel("a lovely beach"), frame(FLAT))
        assertTrue(state.view(frame(FLAT), null, changedByAction = false, stableFrames = 0).blind)
    }

    @Test
    fun nothingToReadWithoutAPicture() = runBlocking {
        val state = ScreenState()
        val model = ScriptedModel("dialogue")
        assertNull(state.read(model, null))
        assertEquals(0, model.calls)
        assertNull(state.view(null, null, changedByAction = false, stableFrames = 0).landmark)
    }

    private companion object {
        /** Flat picture: its hash is all zeros. */
        const val FLAT = 0

        /** Left-to-right brightening: its hash is all ones. */
        const val RAMP = 1

        /** Vertical stripes: a mixed pattern, far from both. */
        const val STRIPES = 2
    }
}
