package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.state.Blocker
import dev.kotlinds.pokemonclient.state.BlockerCause
import dev.kotlinds.pokemonclient.state.StoryStep
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentMemoryStoryTest {

    @Test
    fun `retains the selected goal and blocked goals in memory`() {
        val memory = AgentMemory()
        val plan = StoryGoalPlan(
            goal = StoryStep("johto:badge_plain", "Challenge Whitney at the Goldenrod Gym", 76),
            alternatives = listOf(StoryStep("johto:badge_fog", "Challenge Morty", 90)),
            blocked = listOf(BlockedGoal(StoryStep("johto:badge_plain", "Challenge Whitney", 76), "the woman blocks the door", BlockerCause.PasswordDoor(emptyList(), false))),
            blockers = listOf(Blocker("person:1", "the woman blocks the door", BlockerCause.PasswordDoor(emptyList(), false))),
            completedSteps = 5,
        )
        memory.observeStory(plan)
        val story = memory.describe(false, null)["story"]!!.jsonObject
        assertEquals("Challenge Whitney at the Goldenrod Gym", story["goal"]!!.jsonPrimitive.content)
        assertEquals(1, story["other_open_goals"]!!.jsonArray.size)
        assertEquals(1, story["blocked_goals"]!!.jsonArray.size)
        assertTrue(story["blocked_goals"]!!.jsonArray[0].jsonPrimitive.content.contains("the woman blocks the door"))
        assertEquals(5, story["steps_done"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `memory retains story across describe calls`() {
        val memory = AgentMemory()
        memory.observeStory(StoryGoalPlan(null, emptyList(), emptyList(), emptyList(), 3))
        // no goal -> no story object? Actually storyStepsDone>0 still emits story with steps_done
        val story = memory.describe(false, null)["story"]!!.jsonObject
        assertEquals(3, story["steps_done"]!!.jsonPrimitive.content.toInt())
    }
}