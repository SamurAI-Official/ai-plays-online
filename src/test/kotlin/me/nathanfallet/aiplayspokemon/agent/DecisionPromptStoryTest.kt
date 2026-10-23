package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StoryState
import dev.kotlinds.pokemonclient.state.StoryStep
import dev.kotlinds.pokemonclient.state.MapName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import kotlin.test.Test
import kotlin.test.assertTrue

class DecisionPromptStoryTest {

    /** The typed profile the story-goal assist belongs to. */
    private val heartGold = GameCatalog.builtIns.first { it.id == "pokemon-heartgold" }

    private val overworld = Screen.Overworld(awaiting = Awaiting.INPUT)

    private fun gameState(story: StoryState?): GameState = GameState(
        frame = 0, screen = overworld, player = null, party = emptyList(), bag = null, battle = null,
        field = FieldState(1, MapName(1, map = "Town 1"), 1, 1, height = 0, facing = Direction.SOUTH, movement = MovementMode.WALK, moving = false),
        story = story,
    )

    @Test
    fun promptIncludesSelectedStoryGoalInMemoryWhenWalkthroughKnowledge() {
        val story = StoryState(goal = StoryStep("a", "do a", 1), openGoals = listOf(StoryStep("a", "do a", 1)), completed = emptyList())
        val gs = gameState(story)
        val memory = AgentMemory()
        memory.observeStory(StoryGoals.plan(gs))
        val turn = AgentSession.Turn(gs, buildJsonObject {}, emptyList())
        val settings = PlayerSettings(knowledge = KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)
        val req = DecisionPrompt.build("obj", turn, memory, settings, generative = false, profile = heartGold)
        val mem = req.state["memory"]!!.jsonObject
        assertTrue(mem["story"]!!.jsonObject["goal"]!!.jsonPrimitive.content == "do a")
        assertTrue(req.instructions.contains("memory.story"))
    }

    @Test
    fun promptOmitsStoryGoalWhenNotWalkthroughKnowledge() {
        val story = StoryState(goal = StoryStep("a", "do a", 1), openGoals = listOf(StoryStep("a", "do a", 1)), completed = emptyList())
        val gs = gameState(story)
        val memory = AgentMemory()
        val turn = AgentSession.Turn(gs, buildJsonObject {}, emptyList())
        val settings = PlayerSettings(knowledge = KnowledgeLevel.POKEDEX)
        val req = DecisionPrompt.build("obj", turn, memory, settings, generative = false, profile = heartGold)
        val mem = req.state["memory"]!!.jsonObject
        assertTrue(mem["story"] == null)
        assertTrue(!req.instructions.contains("memory.story"))
    }
}