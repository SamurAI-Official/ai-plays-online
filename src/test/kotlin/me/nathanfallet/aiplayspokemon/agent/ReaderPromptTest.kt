package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter
import me.nathanfallet.aiplayspokemon.emulator.Screens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderPromptTest {

    private val profile = GameCatalog.builtIns.first { it.id == "age-of-empires-aok" }
    private val chapter = WalkthroughChapter(
        id = "aoe:cursor",
        title = "Learn the controls",
        goal = "Get comfortable moving the cursor.",
        hints = listOf("The D-pad pans the map."),
    )

    @Test
    fun theStateCarriesWhatTheReaderSeesAndWhatHappened() {
        val state = ReaderPrompt.state(
            profile = profile,
            objective = "Beat mission 1",
            chapter = chapter,
            values = mapOf("screen" to "84 (in mission)", "menu" to "0"),
            screen = ScreenState.View(
                top = ScreenState.ScreenView(
                    screen = Screens.Screen.TOP,
                    looksLike = "dialogue",
                    stableFrames = 12,
                    changedByAction = true,
                    landmark = "Start the campaign",
                ),
                bottom = ScreenState.ScreenView(
                    screen = Screens.Screen.BOTTOM,
                    looksLike = "menu",
                    stableFrames = 90,
                    changedByAction = false,
                ),
            ),
            lastAction = "press_a",
            lastChange = "screen: 83 → 84",
            history = listOf("#1 press_a → nothing changed (was: screen 19)"),
            stagnation = 0,
        )
        assertEquals(profile.title, state["game"]!!.jsonPrimitive.content)
        assertEquals("Beat mission 1", state["objective"]!!.jsonPrimitive.content)
        assertEquals("84 (in mission)", state["reader"]!!.jsonObject["screen"]!!.jsonPrimitive.content)
        val screen = state["screen"]!!.jsonObject
        assertEquals("dialogue", screen["top"]!!.jsonObject["looks_like"]!!.jsonPrimitive.content)
        assertEquals("Start the campaign", screen["top"]!!.jsonObject["landmark"]!!.jsonPrimitive.content)
        assertEquals("menu", screen["bottom"]!!.jsonObject["looks_like"]!!.jsonPrimitive.content)
        assertEquals("90", screen["bottom"]!!.jsonObject["stable_frames"]!!.jsonPrimitive.content)
        assertTrue(screen["note"]!!.jsonPrimitive.content.contains("bottom one is the touch screen"))
        assertTrue(screen["what_that_means"]!!.jsonPrimitive.content.contains("top screen changed"))
        assertEquals("Learn the controls", state["walkthrough"]!!.jsonObject["chapter"]!!.jsonPrimitive.content)
        assertEquals("The D-pad pans the map.", state["walkthrough"]!!.jsonObject["hints"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("press_a", state["last_action"]!!.jsonObject["did"]!!.jsonPrimitive.content)
        assertEquals("screen: 83 → 84", state["last_action"]!!.jsonObject["result"]!!.jsonPrimitive.content)
        assertTrue(state["history"]!!.jsonArray.isNotEmpty())
        assertTrue(state["stuck"] == null, "not stuck: nothing to say")
    }

    @Test
    fun aBlindModelIsToldWhyItSeesNothing() {
        val state = ReaderPrompt.state(
            profile = profile,
            objective = "objective",
            chapter = null,
            values = mapOf("screen" to "19 (main menu)"),
            screen = ScreenState.View(
                top = ScreenState.ScreenView(
                    screen = Screens.Screen.TOP,
                    looksLike = "unknown",
                    stableFrames = 3,
                    changedByAction = false,
                ),
                bottom = ScreenState.ScreenView(
                    screen = Screens.Screen.BOTTOM,
                    looksLike = "unknown",
                    stableFrames = 3,
                    changedByAction = false,
                ),
            ),
            lastAction = null,
            lastChange = null,
            history = emptyList(),
            stagnation = 0,
        )
        val screen = state["screen"]!!.jsonObject
        assertEquals("unknown", screen["top"]!!.jsonObject["looks_like"]!!.jsonPrimitive.content)
        assertTrue(screen["warning"]!!.jsonPrimitive.content.contains("cannot see the screenshots"))
    }

    @Test
    fun beingStuckIsSaidOutLoud() {
        val state = ReaderPrompt.state(
            profile, "objective", null, emptyMap(), null, null, null, emptyList(), stagnation = 4,
        )
        assertTrue(state["stuck"]!!.jsonPrimitive.content.startsWith("4 actions in a row changed nothing"))
        assertTrue(state["walkthrough"] == null, "no chapter, no walkthrough block")
        assertTrue(state["screen"] == null, "no picture knowledge, no screen block")
    }

    @Test
    fun theInstructionsSayHowToPlayAndWhatNotToTrust() {
        val instructions = ReaderPrompt.instructions(profile, chapter, generative = true)
        assertTrue(instructions.contains(profile.shortTitle))
        assertTrue(instructions.contains("`reader`"))
        assertTrue(instructions.contains("`screen`"), "the picture is part of what it is told")
        assertTrue(instructions.contains("dialogue box"), "and what a dialogue box wants")
        assertTrue(instructions.contains("can be wrong"), "the guide is not the game")
        assertTrue(instructions.contains("one of `options`"))
    }

    @Test
    fun aHistoryLineIsReadable() {
        assertEquals(
            "#3 press_a → screen: 19 → 18 (was: screen 19 · menu 70)",
            ReaderPrompt.historyLine(3, "press_a", "screen: 19 → 18", "screen 19 · menu 70"),
        )
    }
}
