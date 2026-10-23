package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.state.Blocker
import dev.kotlinds.pokemonclient.state.BlockerCause
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.StoryStep

data class StoryGoalPlan(
    val goal: StoryStep?,
    val alternatives: List<StoryStep>,
    val blocked: List<BlockedGoal>,
    val blockers: List<Blocker>,
    val completedSteps: Int,
)

data class BlockedGoal(val goal: StoryStep, val reason: String, val cause: BlockerCause?)

object StoryGoals {
    fun plan(state: GameState): StoryGoalPlan {
        val story = state.story ?: return StoryGoalPlan(null, emptyList(), emptyList(), emptyList(), 0)
        val mapId = state.field?.mapId
        val active = story.blockers.filter { it.cause != null }
        val alternatives = mutableListOf<StoryStep>()
        val blocked = mutableListOf<BlockedGoal>()
        var chosen: StoryStep? = null
        for (candidate in story.openGoals) {
            val blocker = if (candidate.place != null && candidate.place == mapId) active.firstOrNull() else null
            if (blocker == null) {
                if (chosen == null) chosen = candidate else alternatives.add(candidate)
            } else {
                blocked.add(BlockedGoal(candidate, blocker.reason, blocker.cause))
            }
        }
        return StoryGoalPlan(chosen, alternatives, blocked, story.blockers, story.completed.size)
    }
}