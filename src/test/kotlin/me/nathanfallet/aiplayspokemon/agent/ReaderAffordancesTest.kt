package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ProgressSignal
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.ValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The map of the game the player found out by playing: where each action leads from a screen, what does nothing, and the
 * shortest known way to the screen the reader aims at — with what it rests on, so a model can tell a proven route from
 * a lucky one.
 */
class ReaderAffordancesTest {

    private fun outcome(
        screen: String,
        action: String,
        vararg destinations: String,
        times: Int = 1,
        result: String = "screen: $screen → ${destinations.firstOrNull() ?: screen}",
    ) = ReaderMemoryData.Outcome(
        screen = screen,
        action = action,
        result = result,
        times = times,
        destinations = if (destinations.size == 1) mapOf(destinations[0] to times) else destinations.groupingBy { it }.eachCount(),
    )

    /** The AoE front end, as a player would find it: boot → logos → main menu → single player → campaign → mission. */
    private val map = ReaderAffordances.Known(
        listOf(
            outcome("boot (0)", "wait", "publisher logos (129)", times = 3),
            outcome("publisher logos (129)", "wait", "main menu (19)", times = 3),
            outcome("main menu (19)", "press_a", "single player menu (18)", times = 2),
            outcome("main menu (19)", "press_b", "main menu (19)", result = ReaderActions.NOTHING_CHANGED),
            outcome("single player menu (18)", "press_a", "select campaign (64)", times = 2),
            outcome("select campaign (64)", "press_a", "select mission (231)"),
            outcome("select mission (231)", "press_a", "mission briefing (83)"),
            outcome("mission briefing (83)", "press_a", "in mission (84)"),
        ),
    )

    @Test
    fun itKnowsWhereEachActionLeadsFromAScreen() {
        val here = map.here("main menu (19)")
        assertEquals(listOf("press_a"), here.moves.map { it.action })
        assertEquals("single player menu (18)", here.moves.single().to)
        assertEquals(2, here.moves.single().times)
        assertEquals(listOf("press_b" to 1), here.deadEnds, "an action that changed nothing is a dead end here")
        // A screen nobody has tried anything on is new ground.
        assertEquals(emptyList(), map.here("loading the mission (29)").moves)
    }

    @Test
    fun itSaysWhenAnActionWentSomewhereElseAnotherTime() {
        val uncertain = ReaderAffordances.Known(
            listOf(outcome("main menu (19)", "press_a", "single player menu (18)", "options (21)")),
        )
        assertEquals(
            listOf("options (21)", "single player menu (18)"),
            uncertain.moves.map { it.to }.sorted(),
            "both destinations are kept: the map does not pretend one is the only one",
        )
        assertEquals(listOf(1, 1), uncertain.moves.map { it.times })
    }

    @Test
    fun itFindsTheShortestKnownWay() {
        assertEquals(
            listOf("press_a", "press_a", "press_a", "press_a", "press_a"),
            map.path("main menu (19)", "in mission (84)"),
        )
        assertEquals(emptyList(), map.path("in mission (84)", "in mission (84)"), "already there")
        // A progress signal names a screen by its number: the same loose matching a step condition uses finds it.
        assertEquals(listOf("press_a"), map.path("select campaign (64)", "231"))
        assertEquals(listOf("wait", "wait"), map.path("boot (0)", "main menu (19)"))
        // Nothing known leads there.
        assertNull(map.path("in mission (84)", "boot (0)"))
        assertNull(ReaderAffordances.Known(listOf(outcome("a", "press_a", "b"))).path("a", "never seen"))
    }

    @Test
    fun itStopsWalkingOnACycle() {
        // Two screens that lead to each other, with a way out: the walk must not loop forever.
        val cyclic = ReaderAffordances.Known(
            listOf(
                outcome("menu (1)", "press_a", "sub (2)"),
                outcome("sub (2)", "press_b", "menu (1)"),
                outcome("sub (2)", "press_a", "deep (3)"),
            ),
        )
        assertEquals(listOf("press_a", "press_a"), cyclic.path("menu (1)", "deep (3)"))
        assertNull(cyclic.path("menu (1)", "somewhere else"))
    }

    @Test
    fun theReaderSaysWhichScreenItAimsAt() {
        val reader = ReaderSpec(
            id = "fake-game",
            provenance = "calibrated",
            observations = listOf(Observation("screen", 0x2000000, ValueType.U8)),
            progress = listOf(ProgressSignal("in-mission", 0x2000000, ValueType.U8, equals = 84)),
        )
        assertEquals("84", ReaderAffordances.goal(reader), "no map yet: the signal's own value")
        assertEquals("in mission (84)", ReaderAffordances.goal(reader, map), "the map has seen it: the screen it knows")
        // A signal about another value is not a screen to reach, and a reader with no signal has no goal.
        assertEquals(
            null,
            ReaderAffordances.goal(reader.copy(progress = listOf(ProgressSignal("gold", 0x2000004, ValueType.U16)))),
        )
        assertEquals(null, ReaderAffordances.goal(reader.copy(progress = emptyList())))
        assertEquals(null, ReaderAffordances.goal(reader.copy(observations = emptyList())))
    }

    @Test
    fun itTellsTheModelWhatItKnowsHereAndTheWayToTheGoal() {
        val described = ReaderAffordances.describe(map, "main menu (19)", "in mission (84)")
        assertEquals("main menu (19)", described?.get("screen")?.jsonPrimitive?.content)
        val from = described?.get("from_here")?.jsonArray.orEmpty().map { it.jsonObject }
        assertEquals(listOf("press_a"), from.map { it["action"]?.jsonPrimitive?.content })
        assertEquals("single player menu (18)", from.single()["goes_to"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("press_b"),
            described?.get("nothing_happens_here")?.jsonArray.orEmpty()
                .map { it.jsonObject["action"]?.jsonPrimitive?.content },
        )
        val toReach = described?.get("to_reach")?.jsonObject
        assertEquals("in mission (84)", toReach?.get("screen")?.jsonPrimitive?.content)
        assertEquals(
            listOf("press_a", "press_a", "press_a", "press_a", "press_a"),
            toReach?.get("actions")?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
        )
        assertTrue(
            toReach?.get("how_much_to_trust_this")?.jsonPrimitive?.content.orEmpty().contains("not a promise"),
            "the route is what worked before, and the model is told so",
        )
        // Nothing to say about a screen no action was tried on, and no known way from it: no block at all (the absence
        // of `from_here` is what says the ground is new), while standing on the goal is worth saying.
        assertNull(ReaderAffordances.describe(map, "loading the mission (29)", "in mission (84)"))
        assertTrue(
            ReaderAffordances.describe(map, "in mission (84)", "in mission (84)")
                ?.get("to_reach")?.jsonObject?.get("you_are_there")?.jsonPrimitive?.content == "true",
            "standing on the screen the reader aims at is worth a line",
        )
        assertNull(ReaderAffordances.describe(ReaderAffordances.Known(emptyList()), "boot (0)", "84"))
    }
}
