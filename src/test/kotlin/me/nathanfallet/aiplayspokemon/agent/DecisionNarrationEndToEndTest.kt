package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole way a decision becomes speech: a real [Decision] (the one the player records), the line built from
 * it, and the macOS voice really saying it — the audio it produced is written to a file, so the run can be
 * checked afterwards.
 *
 * Only on macOS with `say` installed: the test skips itself elsewhere.
 */
class DecisionNarrationEndToEndTest {

    /** A state in the overworld: the decision's own (a decision carries the state it was made on). */
    private val state = GameState(
        frame = 0,
        screen = Screen.Overworld(awaiting = Awaiting.INPUT),
        player = null,
        party = emptyList(),
        bag = null,
        battle = null,
        field = FieldState(
            mapId = 1, mapName = MapName(1, map = "New Bark Town"), x = 5, y = 4,
            height = 0, facing = Direction.SOUTH, movement = MovementMode.WALK, moving = false,
        ),
    )

    private fun decision() = Decision(
        number = 7,
        state = state,
        actions = listOf("step(north,3)", "press(a)"),
        confidence = 0.82,
        probabilities = listOf("step(north,3)" to 0.82, "press(a)" to 0.11),
        latencyMillis = 1200,
        model = "test-model",
        thought = "The path north leads to the next house, so I walk there first.",
    )

    @Test
    fun aDecisionIsReallySaidByTheVoice() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        val directory = Files.createTempDirectory("narration")
        val file = directory.resolve("decision.aiff")
        val spoken = mutableListOf<Pair<String, String>>()
        val narrator = DecisionNarrator(
            voice = { "Whisper" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            speaker = DecisionNarrator.MacSay { file },
            onSpoken = { line, voice -> spoken += line to voice },
        )
        try {
            narrator.narrateDecision(decision())
            val until = System.currentTimeMillis() + 15_000
            while ((spoken.isEmpty() || !Files.exists(file)) && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(1, spoken.size, "the decision was not said: $spoken")
            assertEquals("Whisper", spoken.first().second, "said by another voice than the one picked")
            val line = spoken.first().first
            assertTrue("Decision 7." in line, line)
            assertTrue("82 percent confidence" in line, line)
            assertTrue("step north 3" in line, line)
            assertTrue("The path north leads to the next house" in line, line)
            assertTrue(Files.size(file) > 0, "the audio file is empty")
            println("said: $line (${Files.size(file)} bytes)")
        } finally {
            narrator.close()
        }
    }
}
