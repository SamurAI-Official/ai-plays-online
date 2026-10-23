package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Blocker
import dev.kotlinds.pokemonclient.state.BlockerCause
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StoryState
import dev.kotlinds.pokemonclient.state.StoryStep
import dev.kotlinds.pokemonclient.state.MapName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryGoalsTest {

    private val overworld = Screen.Overworld(awaiting = Awaiting.INPUT)

    private fun state(map: Int = 1, story: StoryState? = null): GameState = GameState(
        frame = 0, screen = overworld, player = null, party = emptyList(), bag = null, battle = null,
        field = FieldState(map, MapName(map, map = "Town $map"), 1, 1, height = 0, facing = Direction.SOUTH, movement = MovementMode.WALK, moving = false),
        story = story,
    )

    private fun step(id: String, place: Int? = null) = StoryStep(id, "do $id", place)

    @Test
    fun picksFirstOpenGoalInStoryOrder() {
        val story = StoryState(goal = step("a", 1), openGoals = listOf(step("a", 1), step("b", 2), step("c", 3)), completed = emptyList())
        val plan = StoryGoals.plan(state(map = 1, story = story))
        assertEquals("a", plan.goal?.id)
        assertEquals(listOf("b", "c"), plan.alternatives.map { it.id })
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun excludesGoalBlockedByActiveBlockerCauseOnItsMap() {
        val story = StoryState(
            goal = step("a", 1),
            openGoals = listOf(step("a", 1), step("b", 2)),
            blockers = listOf(Blocker("person:1", "a wild pokemon blocks the way", BlockerCause.WildPokemon(1))),
            completed = emptyList(),
        )
        val plan = StoryGoals.plan(state(map = 1, story = story))
        assertEquals("b", plan.goal?.id)
        assertEquals(1, plan.blocked.size)
        assertEquals("a", plan.blocked.first().goal.id)
    }

    @Test
    fun fallbackWhenAllOpenGoalsBlocked() {
        val story = StoryState(
            goal = step("a", 1),
            openGoals = listOf(step("a", 1)),
            blockers = listOf(Blocker("person:1", "password unknown", BlockerCause.PasswordDoor(emptyList(), false))),
            completed = emptyList(),
        )
        val plan = StoryGoals.plan(state(map = 1, story = story))
        assertNull(plan.goal)
        assertEquals(1, plan.blocked.size)
        assertEquals("a", plan.blocked.first().goal.id)
    }

    @Test
    fun noStoryYieldsNoGoal() {
        val plan = StoryGoals.plan(state(story = null))
        assertNull(plan.goal)
        assertTrue(plan.alternatives.isEmpty())
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun goalElsewhereNotBlockedByLocalBlocker() {
        val story = StoryState(
            goal = step("a", 2),
            openGoals = listOf(step("a", 2)),
            blockers = listOf(Blocker("person:1", "wild", BlockerCause.WildPokemon(1))),
            completed = emptyList(),
        )
        val plan = StoryGoals.plan(state(map = 1, story = story))
        assertEquals("a", plan.goal?.id)
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun completedStepsCountRetained() {
        val story = StoryState(goal = step("a", 1), openGoals = listOf(step("a", 1)), completed = listOf(step("x", 1), step("y", 2)))
        val plan = StoryGoals.plan(state(map = 1, story = story))
        assertEquals(2, plan.completedSteps)
    }
}