package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.aiplayspokemon.decision.StepCondition
import me.nathanfallet.aiplayspokemon.games.ReaderSpec

/**
 * The map of the game the player has found out by playing it: from a screen, which action leads where, which ones do
 * nothing, and — when the reader knows a screen worth reaching — the way there.
 *
 * [ReaderMemory] records the raw evidence (`press_a` on `main menu (19)` led to `single player menu (18)`, 3 times);
 * this is that evidence distilled into what a model can *plan* with, and it is what makes a reader game navigable
 * instead of a sequence of surprises: the model is told the way through the menus it already knows, and where an action
 * is a dead end.
 *
 * It is evidence, never a promise: a transition that worked three times may fail the fourth (the game may be in a state
 * the reader does not show), so what the model is given says where it comes from (`times`) and to check
 * `last_action.not_done` instead of trusting it blindly.
 */
object ReaderAffordances {

    /** One known transition: from [from], [action] reached [to], [times] times. */
    data class Move(val from: String, val action: String, val to: String, val times: Int)

    /** What the player knows about one screen: where its actions lead, and which ones do nothing. */
    data class Screen(val screen: String, val moves: List<Move>, val deadEnds: List<Pair<String, Int>>)

    /**
     * The map, built from what the memory recorded. [moves] are the transitions between screens (an action that left the
     * screen where it was is not a move: it is what [Screen.deadEnds] or the reader's own change tells), and a path is a
     * breadth-first walk over them — the shortest known way, or nothing when there is none.
     */
    class Known(private val outcomes: List<ReaderMemoryData.Outcome>, private val maxSteps: Int = MAX_PATH) {

        /** Every known transition, most proven first: one entry per destination an action reached. */
        val moves: List<Move> = outcomes
            .flatMap { outcome ->
                outcome.destinations
                    .filterKeys { it != outcome.screen }
                    .map { (to, times) -> Move(outcome.screen, outcome.action, to, times) }
            }
            .sortedWith(compareByDescending<Move> { it.times }.thenBy { it.from }.thenBy { it.action })

        /** The screens the map knows about: the ones an action was tried on, and the ones an action led to. */
        val screens: Set<String> get() = (outcomes.map { it.screen } + moves.map { it.to }).toSet()

        /** What is known about [screen]: where its actions lead, and which ones changed nothing (tried [times]). */
        fun here(screen: String): Screen = Screen(
            screen = screen,
            moves = moves.filter { it.from == screen },
            deadEnds = outcomes
                .filter { it.screen == screen && it.result == ReaderActions.NOTHING_CHANGED }
                .sortedByDescending { it.times }
                .map { it.action to it.times },
        )

        /**
         * The shortest known way from [from] to [to], as the actions to take in order, or null when nothing is known
         * that leads there. [to] is matched the way a step condition matches a value, so a progress signal's number
         * (`84`) finds the screen the reader renders as `in mission (84)`.
         */
        fun path(from: String, to: String): List<String>? {
            if (StepCondition.names(from, to)) return emptyList()
            // Breadth first: the first time a screen is reached is by a shortest path, and a screen is never revisited
            // (a cycle would otherwise walk forever).
            var frontier = listOf(from to emptyList<String>())
            val seen = mutableSetOf(from)
            var steps = 0
            while (frontier.isNotEmpty() && steps < maxSteps) {
                val next = mutableListOf<Pair<String, List<String>>>()
                for ((screen, soFar) in frontier) {
                    for (move in moves.filter { it.from == screen }) {
                        val actions = soFar + move.action
                        if (StepCondition.names(move.to, to)) return actions
                        if (seen.add(move.to)) next += move.to to actions
                    }
                }
                frontier = next
                steps++
            }
            return null
        }
    }

    /** The map of what [memory] recorded. */
    fun of(memory: ReaderMemoryData): Known = Known(memory.outcomes)

    /**
     * The screen the reader's own progress signals aim at: a signal about the `screen` value (a `kind: memory` chapter
     * reaching it is what a walkthrough is about) names one. Given [known], the name is the screen key the map uses
     * (`in mission (84)`) once it has seen it, and the signal's own value (`84`) while it has not — both are things the
     * model sees in `reader.screen`. Null when the reader has no such signal.
     */
    fun goal(reader: ReaderSpec, known: Known? = null): String? {
        val screen = reader.observations.firstOrNull { it.name == SCREEN_VALUE } ?: return null
        val signal = reader.progress.firstOrNull { it.address == screen.address && it.type == screen.type } ?: return null
        val value = signal.equals.toString()
        return known?.screens?.firstOrNull { StepCondition.names(it, value) } ?: value
    }

    /**
     * What the model is told about the map, as the prompt's `affordances` block. Null when there is nothing to say
     * about [screen] and no known way to [goal]: a block of empty fields would be noise, and the absence of `from_here`
     * is what says the ground is new.
     */
    fun describe(known: Known, screen: String, goal: String?): JsonObject? {
        val here = known.here(screen)
        val path = goal?.let { known.path(screen, it) }
        if (here.moves.isEmpty() && here.deadEnds.isEmpty() && path == null) return null
        return buildJsonObject {
            put("screen", screen)
            if (here.moves.isNotEmpty()) {
                putJsonArray("from_here") {
                    here.moves.forEach { move ->
                        add(
                            buildJsonObject {
                                put("action", move.action)
                                put("goes_to", move.to)
                                put("times", move.times)
                            },
                        )
                    }
                }
            }
            if (here.deadEnds.isNotEmpty()) {
                putJsonArray("nothing_happens_here") {
                    here.deadEnds.forEach { (action, times) ->
                        add(
                            buildJsonObject {
                                put("action", action)
                                put("tried", times)
                            },
                        )
                    }
                }
            }
            path?.let { actions ->
                putJsonObject("to_reach") {
                    put("screen", goal.orEmpty())
                    if (actions.isEmpty()) {
                        put("you_are_there", true)
                    } else {
                        putJsonArray("actions") { actions.forEach { add(JsonPrimitive(it)) } }
                    }
                    put(
                        "how_much_to_trust_this",
                        "this is what worked before, not a promise: the game may be in a state the reader does not " +
                            "show, and a step can be refused (`last_action.not_done` says so when it is)",
                    )
                }
            }
        }
    }

    /**
     * The screen [values] shows, as this map is keyed by: the reader's own `screen` value, or every value when the
     * reader has none (a game whose reader does not name screens still has states worth telling apart).
     */
    fun screenOf(values: Map<String, String>): String = values[SCREEN_VALUE]
        ?: values.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { UNKNOWN_SCREEN }.take(MAX_SCREEN_KEY)

    /** The reader value that names the screen, which is what this map is keyed by. */
    const val SCREEN_VALUE = "screen"

    /** Longest path a known way may be: beyond this it is a walk, not a route through a menu. */
    const val MAX_PATH = 8

    private const val UNKNOWN_SCREEN = "unknown"

    private const val MAX_SCREEN_KEY = 80
}
