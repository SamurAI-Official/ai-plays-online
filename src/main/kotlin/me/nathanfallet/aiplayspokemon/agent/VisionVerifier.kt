package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area

/**
 * Pre-commit vision: looks at the typed [GameState] of this turn (the screen it shows, plus the ROM's [Area] map,
 * when the game has one) and decides, before an action is committed, whether the intended movement can be walked
 * at all.
 *
 * Nothing collides first: [check] only reads. A refused step is skipped by the player loop (counted with a reason,
 * never narrated as a wall bump); its reason feeds the retry/next turn, so the model picks another action instead of
 * pressing into the same wall again.
 *
 * The screen decides first what a press even means:
 * - a message box ([Screen.Dialogue], [Screen.PressToContinue]) swallows the D-pad: [Verdict.Refused] — the open box
 *   is the reason, no tile is involved, so nothing is remembered as blocked;
 * - any screen but [Screen.Overworld] (menus, battle...) keeps the D-pad for its cursor: [Verdict.Allowed] — the
 *   overworld grid says nothing about it.
 *
 * On the overworld, two sources of truth, both already in the loop:
 * - the ROM [area] grid ([Area.tile]: `blocked` per tile) — static terrain, including walls;
 * - [FieldState.objects]: people, followers and hard obstacles standing on tiles (they move; the grid doesn't show them).
 */
object VisionVerifier {
    /** What the verifier says about one chosen action. */
    sealed interface Verdict {
        /** The action may be committed. */
        data object Allowed : Verdict

        /** Don't commit: the destination can't be walked into; pick another action instead. */
        data class Blocked(val destination: Point, val reason: String) : Verdict

        /** Don't commit: the screen itself refuses the walk (a message box is open) — no tile is involved. */
        data class Refused(val reason: String) : Verdict
    }

    /** A tile on the current map. */
    data class Point(val x: Int, val y: Int)

    /**
     * A refused choice, as the model is told and the history records it: the action, where it would have gone
     * ([destination] null when the screen itself refused: no tile is involved), why not.
     */
    data class BlockedAction(val action: String, val destination: Point?, val reason: String) {
        /** One line: `“press(up)” blocked by wall (Wall) at (1, 0)`; without a tile: `“press(up)” a message box is open: advance it with A`. */
        fun describe() = destination?.let { "“$action” $reason at (${it.x}, ${it.y})" } ?: "“$action” $reason"
    }

    /** Why a press made while a message box is open is refused — and what to do instead. */
    private const val MESSAGE_BOX = "a message box is open: advance it with A"

    /**
     * Whether [action] is safe to commit now ([screen]: what the game waits for, [field]: where the player stands,
     * [area]: the ROM map, null when the game has none). A press that isn't a walk at all (menus, battle, advancing
     * dialogue...) is always [Verdict.Allowed]; a message box swallowing a walk is a [Verdict.Refused].
     */
    fun check(action: GameAction, field: FieldState?, area: Area?, screen: Screen): Verdict {
        val movement = movementOf(action) ?: return Verdict.Allowed
        // The screen first: what the press means here. A message box swallows it (nothing walks while it is open:
        // the box, not the wall, is the reason — no tile is learned from it), and any screen but the overworld
        // keeps the D-pad for its cursor (menus, battle) — only an overworld press is a walk the grid can judge.
        if (screen is Screen.Dialogue || screen is Screen.PressToContinue) return Verdict.Refused(MESSAGE_BOX)
        if (screen !is Screen.Overworld) return Verdict.Allowed
        field ?: return Verdict.Allowed
        val destination = when (movement) {
            is Movement.Step -> Point(field.x + movement.direction.dx, field.y + movement.direction.dy)
            is Movement.GoTo -> Point(movement.x, movement.y)
        }
        // Static terrain first: the ROM grid knows walls, water, trees...
        val tile = area?.takeUnless { it.outside(destination.x, destination.y) }?.tile(destination.x, destination.y)
        if (tile != null && tile.blocked) return Verdict.Blocked(destination, "blocked by wall (${tile.kind})")
        // ...then who stands there now: people and hard obstacles block even a free tile.
        field.objects.firstOrNull { it.x == destination.x && it.y == destination.y && blocks(it) }?.let { blocker ->
            return Verdict.Blocked(destination, "blocked by ${blocker.label?.takeIf { it.isNotBlank() } ?: blocker.id}")
        }
        return Verdict.Allowed
    }

    /** A movement the model wants, distilled from the chosen action. Null: not a movement (always allowed). */
    private sealed interface Movement {
        data class Step(val direction: Direction) : Movement
        data class GoTo(val x: Int, val y: Int) : Movement
    }

    private fun movementOf(action: GameAction): Movement? = when (action) {
        is GameAction.Press -> directionOf(action.button)?.let { Movement.Step(it) }
        is GameAction.GoTo -> {
            val x = action.x
            val y = action.y
            if (x != null && y != null) Movement.GoTo(x, y) else null
        }
        else -> null
    }

    private fun directionOf(button: Button): Direction? = when (button) {
        Button.UP -> Direction.NORTH
        Button.DOWN -> Direction.SOUTH
        Button.LEFT -> Direction.WEST
        Button.RIGHT -> Direction.EAST
        else -> null
    }

    /** Whether a field object standing on the destination tile blocks walking into it. */
    private fun blocks(obj: FieldObject): Boolean = when (obj.kind) {
        FieldObjectKind.PERSON, FieldObjectKind.FOLLOWER -> true
        FieldObjectKind.OBSTACLE -> obj.obstacle in HARD_OBSTACLES
        else -> false
    }

    private val HARD_OBSTACLES = setOf(
        ObstacleKind.CUT_TREE,
        ObstacleKind.SMASH_ROCK,
        ObstacleKind.BOULDER,
        ObstacleKind.ICE_BLOCK,
    )
}

/** The ROM's map of where the player is in [state], when the game has one (shared with the explored map). */
internal fun areaOf(game: dev.kotlinds.pokemonclient.PokemonGame, state: GameState): Area? =
    state.field?.let { game.world?.areaOf(it.mapId) }

