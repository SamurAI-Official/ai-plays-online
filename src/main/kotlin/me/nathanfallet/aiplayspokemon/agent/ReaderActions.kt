package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint

/**
 * The actions a reader-based player can take, and what each one means on the console.
 *
 * A game with a calibrated reader has no typed action vocabulary (no `go_to`, no `battle`): what the AI can do is what
 * a human with the console can do — press a button, hold a direction, tap the touch screen, wait. Each option is one
 * of those, with the frames it is held and the frames the game is given afterwards to react, so an option is one
 * "move" the model chooses between.
 *
 * The vocabulary is deliberately small and named after the *intent* (confirm, cancel, move up, wait) rather than the
 * button, because the model reasons about the game, not the console.
 */
object ReaderActions {

    /** What one option does: what is held, for how long, and how long the game gets afterwards. */
    data class Plan(
        val buttons: Set<Button> = emptySet(),
        val touch: TouchPoint? = null,
        val heldFrames: Int = PRESS_FRAMES,
        val settleFrames: Int = SETTLE_FRAMES,
    ) {
        /** The input held while the action runs. */
        fun input(): InputFrame = InputFrame(buttons, touch)

        /** Frames this action emulates in total. */
        val totalFrames: Int get() = heldFrames + settleFrames
    }

    /** Pressed long enough that the game reads it, short enough that its key repeat never triggers. */
    const val PRESS_FRAMES = 4

    /** Held long enough to walk a few tiles / scroll a list. */
    const val HOLD_FRAMES = 30

    /** Frames given to the game after the input, so the next observation sees its answer. */
    const val SETTLE_FRAMES = 30

    /** How long a `wait` action waits. */
    const val WAIT_FRAMES = 60

    /** The touch grid: the nine points a model can aim at without seeing the screen (thirds of the touch screen). */
    private val TOUCH_COLUMNS = listOf(64, 128, 192)
    private val TOUCH_ROWS = listOf(32, 96, 160)

    /**
     * Every option, as the model sees it: `id → description`. Presses first (they are what most games need), then the
     * holds, then waiting, then the touch grid.
     */
    fun options(): Map<String, String> = buildMap {
        for ((id, button) in PRESSES) put(id, "press ${button.name} (${PRESS_MEANINGS.getValue(id)})")
        for (button in listOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)) {
            put("hold_${button.name.lowercase()}", "hold ${button.name} for half a second (walk / scroll)")
        }
        put("wait", "do nothing for a second (let the game talk, animate, load)")
        put("wait_long", "do nothing for two seconds (a cutscene, a long load)")
        for (row in TOUCH_ROWS.indices) {
            for (column in TOUCH_COLUMNS.indices) {
                val x = TOUCH_COLUMNS[column]
                val y = TOUCH_ROWS[row]
                put("touch_${x}_$y", "tap the touch screen at $x,$y (${POSITIONS[row * 3 + column]})")
            }
        }
    }

    /** The plan of [option], or null when the option is not one of ours (a model inventing ids). */
    fun plan(option: String): Plan? {
        PRESSES.firstOrNull { it.first == option }?.let { (_, button) -> return Plan(buttons = setOf(button)) }
        if (option == "wait") return Plan(heldFrames = 0, settleFrames = WAIT_FRAMES)
        if (option == "wait_long") return Plan(heldFrames = 0, settleFrames = WAIT_FRAMES * 2)
        if (option.startsWith("hold_")) {
            val button = Button.entries.firstOrNull { it.name.equals(option.removePrefix("hold_"), ignoreCase = true) }
            return button?.let { Plan(buttons = setOf(it), heldFrames = HOLD_FRAMES) }
        }
        if (option.startsWith("touch_")) {
            val coordinates = option.removePrefix("touch_").split('_').mapNotNull { it.toIntOrNull() }
            if (coordinates.size == 2) return Plan(touch = TouchPoint(coordinates[0], coordinates[1]))
        }
        return null
    }


    /** One-line description of what changed between two observations, for the decision history. */
    fun describeChange(before: Map<String, String>, after: Map<String, String>): String {
        val changes = before.keys.union(after.keys)
            .filter { before[it] != after[it] }
            .map { name -> "$name: ${before[name] ?: "?"} → ${after[name] ?: "?"}" }
        return if (changes.isEmpty()) NOTHING_CHANGED else changes.joinToString(", ")
    }

    /** What [describeChange] answers when an action changed nothing the reader can see. */
    const val NOTHING_CHANGED = "nothing changed"

    /** A one-line summary of the observations, for the panel's history and the prompt. */
    fun summary(values: Map<String, String>): String =
        values.entries.joinToString(" · ") { (name, value) -> "$name $value" }

    /** Presses the model can ask for: the button, and what it means on a DS game. */
    private val PRESSES = listOf(
        "press_a" to Button.A,
        "press_b" to Button.B,
        "press_x" to Button.X,
        "press_y" to Button.Y,
        "press_l" to Button.L,
        "press_r" to Button.R,
        "press_start" to Button.START,
        "press_select" to Button.SELECT,
        "press_up" to Button.UP,
        "press_down" to Button.DOWN,
        "press_left" to Button.LEFT,
        "press_right" to Button.RIGHT,
    )

    private val PRESS_MEANINGS = mapOf(
        "press_a" to "confirm, select, talk, attack",
        "press_b" to "cancel, back",
        "press_x" to "the game's X action",
        "press_y" to "the game's Y action",
        "press_l" to "left shoulder",
        "press_r" to "right shoulder",
        "press_start" to "pause / menu",
        "press_select" to "select / map",
        "press_up" to "up (menu cursor, or a step)",
        "press_down" to "down (menu cursor, or a step)",
        "press_left" to "left",
        "press_right" to "right",
    )

    private val POSITIONS = listOf(
        "top left", "top centre", "top right",
        "middle left", "centre", "middle right",
        "bottom left", "bottom centre", "bottom right",
    )
}
