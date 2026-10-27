package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import me.nathanfallet.aiplayspokemon.games.GameProfile
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter

/**
 * What a reader-based player asks its model, and what it tells it.
 *
 * The model cannot see the screen here: what it gets is the game's profile (objective, conventions), the chapter it
 * is on, the values the calibrated reader reads, and what its own last actions did. That is the whole point of the
 * reader: `screen 84 (in mission)` is something a model can reason about, a picture of a moving map is not.
 */
object ReaderPrompt {

    /** How many past actions the prompt carries, so the model can see what it already tried. */
    const val HISTORY_SIZE = 8

    /**
     * The state of the request: the game, the objective, the chapter, the reader's values, the last action with what
     * it changed, and the recent history. [stagnation] counts the actions that changed nothing, so the model is told
     * when it is stuck instead of repeating itself. [memory] is the player's own memory of this game
     * ([ReaderMemory.describe]): its note, what it already tried on this screen, and the screens it has been on —
     * including the ones from earlier sessions.
     */
    fun state(
        profile: GameProfile,
        objective: String,
        chapter: WalkthroughChapter?,
        values: Map<String, String>,
        screen: ScreenState.View?,
        lastAction: String?,
        lastChange: String?,
        history: List<String>,
        stagnation: Int,
        memory: JsonObject? = null,
        stop: ReaderChain.Stop? = null,
        affordances: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("game", profile.title)
        put("objective", objective)
        putJsonObject("reader") {
            for ((name, value) in values) put(name, value)
        }
        memory?.let { put("memory", it) }
        // What the player has found out about the game's screens: which action leads where from here, which ones do
        // nothing, and the way to the screen the reader aims at (agent/ReaderAffordances.kt).
        affordances?.let { put("affordances", it) }
        screen?.let { view ->
            putJsonObject("screen") {
                putJsonObject("top") { putScreenView(view.top) }
                putJsonObject("bottom") { putScreenView(view.bottom) }
                view.landmark?.let { put("landmark", it) }
                view.note?.let { put("what_that_means", it) }
                put(
                    "note",
                    "A Nintendo DS has two screens and games use both: the top one often shows the world or a " +
                        "dialogue box, the bottom one the menus — and the bottom one is the touch screen, so " +
                        "`touch_x_y` taps there.",
                )
                if (view.blind) {
                    put(
                        "warning",
                        "the model answered that it cannot see the screenshots: only `reader` is available, so " +
                            "dialogue boxes and menus cannot be read. Pick a model with vision to see the screens.",
                    )
                }
            }
        }
        chapter?.let { current ->
            putJsonObject("walkthrough") {
                put("chapter", current.title)
                put("goal", current.goal)
                putJsonArray("hints") { current.hints.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            }
        }
        lastAction?.let { action ->
            putJsonObject("last_action") {
                put("did", action)
                put("result", lastChange ?: "unknown")
                // A plan that stopped before its end says so, with the same field names the typed chains use
                // (`not_done`, `not_done_code`, `not_done_reason`): what was left, and why.
                stop?.let { reason ->
                    putJsonArray("not_done") { reason.notDone.forEach { add(JsonPrimitive(it)) } }
                    put("not_done_code", reason.code)
                    put("not_done_reason", reason.message)
                }
            }
        }
        if (history.isNotEmpty()) {
            putJsonArray("history") { history.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        if (stagnation > 0) {
            put(
                "stuck",
                "$stagnation actions in a row changed nothing in `reader`: do something different (another button, " +
                    "a wait, a different part of the screen). If `screen.what_that_means` says the game is waiting " +
                    "for input, that is the answer: press what it waits for.",
            )
        }
    }

    /** What the model is told to do, on top of its own objective and the game's conventions. */
    fun instructions(profile: GameProfile, chapter: WalkthroughChapter?, generative: Boolean): String = buildString {
        append("You are playing ${profile.shortTitle} on a Nintendo DS. ")
        append("`reader` is what a calibrated memory reader sees (its `screen` value is which screen the game is on). ")
        append("`memory` is your own memory of this game, kept between sessions: `your_note` is what you wrote for ")
        append("yourself, `what_you_tried_here` is what each action already did on this screen, and ")
        append("`screens_you_have_seen` is where you have been. Read it before deciding: it says what you already ")
        append("know, so you do not explore the same menus again. ")
        append("You may add steps after your choice (`then`): a step is an option id, or ")
        append("`{\"action\": \"<option id>\", \"when\": {\"is\": \"<what the reader must show>\"}}` to run it only while ")
        append("the game still shows that (`is_not` for the opposite). A step without `when` is only run while the game ")
        append("is still moving: if your action changed nothing, the plan stops there and `last_action` says what was ")
        append("left as `not_done` with `not_done_code` and `not_done_reason`. Use `when` when you know what the game ")
        append("should show next — it is how a plan survives a menu that has one entry less than you thought. ")
        append("`affordances` is the map of the game you have found out by playing: `from_here` says where each action ")
        append("led from this screen (`goes_to`, and `times` for how often), `nothing_happens_here` says which ones did ")
        append("nothing, and `to_reach.actions` is the shortest way you know to the screen the reader aims at — follow ")
        append("it when it is there. When the block is missing, or has no `from_here`, nothing has been tried here ")
        append("yet: this is new ground, explore it. ")
        append("`screen` is what the picture shows and how it is moving: use it — a dialogue box wants A, a menu wants ")
        append("the D-pad, a stable picture means the game is waiting for you, and a picture that keeps changing is ")
        append("loading or animating. `screen.looks_like` is a reading of the picture, and like the walkthrough it can ")
        append("be wrong: the facts are `stable_frames`, `changed_by_your_action` and the reader. ")
        append(profile.conventions)
        if (chapter != null) {
            append(" Follow the walkthrough: its `goal` is what to achieve now, its `hints` are how. ")
            append("It is a human-written guide and can be wrong: the game itself wins. ")
        }
        append("Choose one of `options`. ")
        if (generative) {
            append("Think about the reader, the picture and what your last action changed, then answer. ")
            append("Do not repeat an action that just did nothing unless the game is clearly waiting for it.")
        }
    }

    /** One line per past decision, oldest first, as [state]'s `history`. */
    fun historyLine(number: Int, action: String, change: String, summary: String): String =
        "#$number $action → $change (was: $summary)"

    /** One screen of the `screen` block: what it shows, and what the last action did to it. */
    private fun JsonObjectBuilder.putScreenView(view: ScreenState.ScreenView) {
        put("looks_like", view.looksLike ?: "not read")
        put("stable_frames", view.stableFrames)
        put("changed_by_your_action", view.changedByAction)
        view.landmark?.let { put("landmark", it) }
    }
}
