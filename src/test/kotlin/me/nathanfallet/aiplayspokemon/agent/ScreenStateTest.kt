package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.console.Frame
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.Screens
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

    private fun dsFrame(top: Int, bottom: Int) = Frame(256, 384, IntArray(256 * 384) { index ->
        if (index < 256 * 192) pattern(top)[index] else pattern(bottom)[index - 256 * 192]
    })

    /**
     * A screen-sized picture of the [kind] a test asks for: dHash compares neighbours, so two flat colours hash the
     * same — the patterns are what make screens differ.
     */
    private fun pattern(kind: Int) = IntArray(256 * 192) { index ->
        val x = index % 256
        val level = when (kind) {
            1 -> 255 - x
            2 -> if (x / 32 % 2 == 0) 0 else 255
            else -> 0
        }
        (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }

    /** The reports of an action that changed the bottom screen only, after a settled picture. */
    private fun bottomAnswered() = mapOf(
        Screens.Screen.TOP to ActionReport(changed = false, stableFrames = 90),
        Screens.Screen.BOTTOM to ActionReport(changed = true, stableFrames = 0),
    )

    @Test
    fun eachScreenIsDescribedOnItsOwn() {
        val state = ScreenState()
        val view = state.view(dsFrame(FLAT, RAMP), null, bottomAnswered())
        assertEquals(90, view.top.stableFrames)
        assertFalse(view.top.changedByAction)
        assertTrue(view.bottom.changedByAction)
        assertTrue(view.note!!.contains("bottom screen changed"), view.note!!)
        assertTrue(view.summary().startsWith("top "), view.summary())
    }

    @Test
    fun aStillScreenIsWhereTheGameWaitsEvenWhenTheOtherMoves() {
        val state = ScreenState()
        val view = state.view(dsFrame(FLAT, RAMP), null, bottomAnswered())
        assertTrue(view.note!!.contains("top screen has not changed"), view.note!!)
    }

    @Test
    fun theSummarySaysWhatTheModelIsLookingAt() {
        val state = ScreenState()
        val reports = mapOf(
            Screens.Screen.TOP to ActionReport(changed = true, stableFrames = 4),
            Screens.Screen.BOTTOM to ActionReport(changed = false, stableFrames = 0),
        )
        val view = state.view(dsFrame(FLAT, RAMP), null, reports)
        assertEquals("top not read · stable 4 · answered you | bottom not read · stable 0 · nothing moved", view.summary())
    }

    @Test
    fun aLandmarkIsAttachedToTheScreenItWasCapturedFrom() {
        val state = ScreenState()
        val frame = dsFrame(FLAT, RAMP)
        // AoE's menu landmark is the bottom screen: the chapter says so with its region.
        val chapter = WalkthroughChapter(
            id = "test:menu",
            title = "Boot to the front end",
            goal = "get to the menu",
            milestone = Milestone(
                kind = MilestoneKind.FRAME_HASH,
                hashes = listOf(FrameHash.format(Screens.hashOf(frame, Screens.Screen.BOTTOM))),
                region = listOf(0, 192, 256, 192),
            ),
        )
        val view = state.view(frame, chapter, emptyMap())
        assertEquals("Boot to the front end", view.bottom.landmark)
        assertNull(view.top.landmark)
        assertNull(view.landmark, "a region landmark is not a whole-frame one")
        assertTrue(view.note!!.contains("Boot to the front end"))
    }

    @Test
    fun aWholeFrameLandmarkStillWorks() {
        val state = ScreenState()
        val frame = dsFrame(FLAT, RAMP)
        val chapter = WalkthroughChapter(
            id = "test:whole",
            title = "Boot to the front end",
            goal = "get to the menu",
            milestone = Milestone(
                kind = MilestoneKind.FRAME_HASH,
                hashes = listOf(FrameHash.format(FrameHash.dHash(frame))),
            ),
        )
        val view = state.view(frame, chapter, emptyMap())
        assertEquals("Boot to the front end", view.landmark)
        assertNull(view.top.landmark, "a whole-frame landmark belongs to no single screen")
        assertNull(view.bottom.landmark)
    }

    @Test
    fun eachScreenIsReadOnItsOwnAndOnlyOnce() = runBlocking {
        val state = ScreenState()
        val model = ScriptedModel("dialogue", "menu")
        val frame = dsFrame(FLAT, RAMP)
        assertEquals("dialogue", state.read(model, frame, Screens.Screen.TOP))
        assertEquals("menu", state.read(model, frame, Screens.Screen.BOTTOM))
        assertEquals(2, model.calls, "one question per screen")
        // Neither screen changed: no new question.
        state.read(model, frame, Screens.Screen.TOP)
        state.read(model, frame, Screens.Screen.BOTTOM)
        assertEquals(2, model.calls)
        val view = state.view(frame, null, emptyMap())
        assertEquals("dialogue", view.top.looksLike)
        assertEquals("menu", view.bottom.looksLike)
    }

    @Test
    fun aModelWithoutVisionSaysSoInsteadOfInventingAScreen() = runBlocking {
        val state = ScreenState()
        val model = ScriptedModel("unknown")
        state.read(model, frame(FLAT), Screens.Screen.TOP)
        val view = state.view(frame(FLAT), null, emptyMap())
        assertTrue(view.top.blind)
        assertTrue(view.summary().contains("unknown"))
    }

    @Test
    fun anInventedAnswerCountsAsBlind() = runBlocking {
        val state = ScreenState()
        state.read(ScriptedModel("a lovely beach"), frame(FLAT), Screens.Screen.BOTTOM)
        assertTrue(state.view(frame(FLAT), null, emptyMap()).bottom.blind)
    }

    @Test
    fun nothingToReadWithoutAPicture() = runBlocking {
        val state = ScreenState()
        val model = ScriptedModel("dialogue")
        assertNull(state.read(model, null, Screens.Screen.TOP))
        assertEquals(0, model.calls)
        assertNull(state.view(null, null, emptyMap()).landmark)
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
