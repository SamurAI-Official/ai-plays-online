package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.actions.ChainLimits
import dev.kotlinds.pokemonclient.actions.ChainRunner
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.games.AgentKind
import me.nathanfallet.aiplayspokemon.games.GameProfile
import me.nathanfallet.aiplayspokemon.games.Walkthrough
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter

/** How many hints of the current chapter the prompt carries. */
private const val MAX_HINTS = 6

/**
 * Turns "what the player can perceive and remember" into a decision request.
 *
 * The state is what a human would get from the screen (read from RAM for the games we have a reader
 * for) plus their memory, and what any player of that game knows (how the controls behave, from the
 * game's [GameProfile]). It never says which option is right: with the story goal assist off, the AI
 * has to work out where to go by itself.
 */
object DecisionPrompt {

    /**
     * How a sequence (the options after the choice: `then`) runs: the same chain as the MCP agents' (`ChainRunner.forAgent`,
     * its stop rules and [ChainLimits.AGENT]), told so the model knows why its sequence may end early and where to read
     * why (`not_done_code`, `dropped_code` in the next state).
     */
    private val SEQUENCE_RULES =
        "Options you add after your choice run one after the other, each on the screen the previous one led to (at most " +
            "${ChainRunner.MAX_THEN}). The sequence stops early, the options left not done (the next state says which and why: " +
            "`not_done`, `not_done_code`), when: an opponent is replaced or faints, or one of your battling Pokémon faints; an " +
            "escape fails; an option isn't offered on the screen reached; nothing happened in the game for " +
            "${ChainLimits.AGENT.idle.inWholeSeconds} s, or after ${ChainLimits.AGENT.total.inWholeSeconds} s in all. When the battle " +
            "ends, its battle options left are dropped (`dropped`, `dropped_code`) and the others go on."

    private fun instructions(
        settings: PlayerSettings,
        planner: Boolean,
        profile: GameProfile,
        walkthrough: Boolean,
        sequences: Boolean,
    ): String = buildString {
        append("You are playing ${profile.shortTitle} on a Nintendo DS. ")
        append(
            when (settings.mode) {
                ControlMode.PURE -> "You hold the controller: each option is a button to press (or waiting). "
                else -> "Each option is either an action your hands carry out for you (walking to a place, talking " +
                    "to someone, choosing a menu option, exploring in a direction) or a single button press. "
            }
        )
        append("`game` describes what is on screen right now, and `memory` is what you remember: your recent ")
        append("actions and exactly what changed after each one, where you have been, what you read")
        if (settings.exploredMap) append(", the map of what you have explored")
        append(". ")
        append(profile.conventions)
        append(" Choose the next option that makes the most progress towards `objective`")
        if (profile.agent == AgentKind.TYPED && settings.knowledge.allows(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)) {
            append(" and `game.story_goals` (when there are several open goals, any of them)")
            append("; your current goal and the goals blocked right now are in `memory.story` (clear the blockers before the blocked goals)")
        }
        append(". If your recent actions changed nothing, do something different.")
        if (walkthrough) append(
            " `walkthrough` is a human-written guide to this game's progression: follow its `goal` and `hints` " +
                "while they match what you see, and ignore them when the game is somewhere else (a guide can be " +
                "wrong about where you are).",
        )
        if (sequences) append(" ").append(SEQUENCE_RULES)
        if (planner) append(" You are the planner: also write in `note` the goal and plan the fast decision model should follow next.")
    }

    fun build(
        objective: String,
        turn: AgentSession.Turn,
        memory: AgentMemory,
        settings: PlayerSettings,
        generative: Boolean,
        plannerGoal: String? = null,
        planner: Boolean = false,
        /** The game being played: names it in the prompt and carries its per-game conventions. */
        profile: GameProfile,
        /** The walkthrough of this game, when it has one (see [WalkthroughCatalog]). */
        walkthrough: Walkthrough? = null,
        /** The chapter being played, when one is chosen. */
        chapter: WalkthroughChapter? = null,
        /** Why the last movement step failed (blocked by a wall, a person...): shown once, then cleared. */
        previousMovementFailure: String? = null,
    ) = ChoiceRequest(
        state = buildJsonObject {
            put("objective", objective)
            plannerGoal?.let { put("goal_from_planner", it) }
            previousMovementFailure?.let { put("previous_movement_failure", it) }
            chapter?.let { current ->
                putJsonObject("walkthrough") {
                    put("chapter", current.title)
                    put("goal", current.goal)
                    if (current.hints.isNotEmpty()) {
                        putJsonArray("hints") { current.hints.take(MAX_HINTS).forEach { add(JsonPrimitive(it)) } }
                    }
                    val index = walkthrough?.chapters?.indexOfFirst { it.id == current.id } ?: -1
                    if (index >= 0) put("chapter_number", index + 1)
                    if (index >= 0) put("chapters_total", walkthrough?.chapters?.size ?: 0)
                }
            }
            put("game", turn.state)
            put("memory", memory.describe(settings.exploredMap, turn.gameState.field))
        },
        instructions = instructions(
            settings,
            planner,
            profile,
            walkthrough = walkthrough != null,
            sequences = generative && settings.allowSequences,
        ),
        options = turn.options.associate { it.key to it.description },
        allowSequence = generative && settings.allowSequences,
        reasoning = settings.reasoning,
        allowNote = generative && (settings.modelNotes || planner),
    )
}
