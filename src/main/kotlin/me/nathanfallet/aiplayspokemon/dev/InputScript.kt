package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint

/**
 * A timeline of inputs for the authoring tool: what is held on each frame of a run.
 *
 * ```
 * A@120-130;TOUCH:128,96@300-302;DOWN@400-420
 * ```
 *
 * Each step is `<input>@<first frame>[-<last frame>]`, where `<input>` is `NONE`, one or more buttons joined by `+`
 * (`A+DOWN`), and at most one touch point (`TOUCH:128,96`, in pixels of the touch screen). Steps are separated by
 * `;` (a `,` belongs to a touch point). Frame 0 is the first frame of the run, or the first frame after the
 * savestate `--state=` was loaded. Later steps win when they overlap, so a long `NONE@0-2000` at the front and
 * specific presses after it read as expected.
 *
 * It exists because a screen-only game's landmarks are often behind a menu: capturing them means driving the game
 * (tap Single Player, press A on the mission) while the tool records the screens.
 */
class InputScript private constructor(private val steps: List<Step>) {

    /** One step: [input] held from [firstFrame] to [lastFrame] (inclusive). */
    data class Step(val firstFrame: Int, val lastFrame: Int, val input: InputFrame)

    /** What is held on [frame]: the last step covering it, or nothing. */
    fun inputAt(frame: Int): InputFrame =
        steps.lastOrNull { frame >= it.firstFrame && frame <= it.lastFrame }?.input ?: InputFrame.NONE

    /** The frames this script touches, for the tool's report. */
    val touchedFrames: IntRange? get() = steps.minOfOrNull { it.firstFrame }?.let { first ->
        first..(steps.maxOf { it.lastFrame })
    }

    override fun toString(): String = steps.joinToString(";") { step ->
        val buttons = step.input.buttons.sortedBy { it.ordinal }.joinToString("+") { it.name }
        val touch = step.input.touch?.let { "TOUCH:${it.x},${it.y}" }
        val held = listOfNotNull(buttons.takeIf { it.isNotEmpty() }, touch).joinToString("+").ifEmpty { "NONE" }
        val range = if (step.firstFrame == step.lastFrame) "${step.firstFrame}" else "${step.firstFrame}-${step.lastFrame}"
        "$held@$range"
    }

    companion object {

        /** Nothing held, ever. */
        val NONE = InputScript(emptyList())

        /** Reads `A@10-20;TOUCH:12,34@5` (see the class documentation). Throws on anything unreadable. */
        fun parse(text: String?): InputScript {
            if (text.isNullOrBlank()) return NONE
            val steps = text.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::parseStep)
            return InputScript(steps)
        }

        private fun parseStep(step: String): Step {
            val at = step.lastIndexOf('@')
            require(at > 0) { "step '$step' is not <input>@<frame>[-<frame>]" }
            val input = parseInput(step.substring(0, at))
            val range = step.substring(at + 1)
            val dash = range.indexOf('-')
            val first = (if (dash < 0) range else range.substring(0, dash)).trim().toIntOrNull()
                ?: throw IllegalArgumentException("step '$step' has no first frame")
            val last = (if (dash < 0) range else range.substring(dash + 1)).trim().toIntOrNull()
                ?: throw IllegalArgumentException("step '$step' has no last frame")
            require(first >= 0 && last >= first) { "step '$step' has a backwards frame range" }
            return Step(first, last, input)
        }

        private fun parseInput(input: String): InputFrame {
            val buttons = mutableSetOf<Button>()
            var touch: TouchPoint? = null
            for (part in input.split('+').map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    part.equals("NONE", ignoreCase = true) -> Unit
                    part.startsWith("TOUCH:", ignoreCase = true) -> {
                        val coordinates = part.substringAfter(':').split(',')
                        val x = coordinates.getOrNull(0)?.trim()?.toIntOrNull()
                        val y = coordinates.getOrNull(1)?.trim()?.toIntOrNull()
                        require(x != null && y != null) { "'$part' is not TOUCH:x,y" }
                        touch = TouchPoint(x, y)
                    }
                    else -> buttons += Button.entries.firstOrNull { it.name.equals(part, ignoreCase = true) }
                        ?: throw IllegalArgumentException("'$part' is not a DS button")
                }
            }
            return InputFrame(buttons, touch)
        }
    }
}
