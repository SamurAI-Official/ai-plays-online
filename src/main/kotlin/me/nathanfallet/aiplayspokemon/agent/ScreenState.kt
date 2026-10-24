package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.console.Frame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.games.MilestoneKind
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter

/**
 * What the app can tell the model about the *picture*, which is what a reader alone cannot say.
 *
 * A reader reads numbers out of memory: it can say "screen 84 (in mission)" but not "a dialogue box is open and it
 * wants A". That gap is what makes an agent think it is stuck on a boot screen while the game is actually showing a
 * conversation. This closes it in two ways:
 *
 * - **offline**: the frame is hashed ([FrameHash]), so the app knows whether the picture changed since the last
 *   decision and how long it has been stable. A screen that has not changed for a second is a game *waiting for
 *   input*; one that changes every frame is loading or animating. It also knows whether the picture matches a
 *   walkthrough chapter's landmark, which names it outright.
 * - **with a model that can see**: the screenshot the app already attaches to every request is turned into one line,
 *   by asking the model which of a few kinds of screen it is looking at ([read]). The question is a normal
 *   [ChoiceRequest] with constrained options, so no new client code is needed, and `unknown` is one of them: a model
 *   that cannot see images says so instead of inventing a screen.
 */
class ScreenState {

    /** One decision's worth of screen knowledge. */
    data class View(
        /** How many frames the picture has looked the same, measured while the last action ran (0: it moved). */
        val stableFrames: Int,
        /** Whether the picture changed at all while the last action ran: did the game answer the press? */
        val changedByAction: Boolean,
        /** What the model said the screen shows, or null when it was never asked / could not see. */
        val looksLike: String?,
        /** The walkthrough chapter whose landmark this picture matches, when it matches one. */
        val landmark: String?,
    ) {
        /** True when the model was asked and answered that it cannot see the screen. */
        val blind: Boolean get() = looksLike == UNKNOWN

        /** A hint the model can act on, derived from the picture alone. */
        val note: String?
            get() = when {
                landmark != null -> "this is the screen of “$landmark”"
                changedByAction && stableFrames == 0 -> "the picture changed while you acted: the game answered you"
                stableFrames >= WAITING_FRAMES -> "the picture has not changed for $stableFrames frames: the game is " +
                    "waiting for input (press something, or move the selection)"
                else -> "the picture did not change while you acted: that action did nothing visible"
            }

        /** One line for the panel and the decision history. */
        fun summary(): String = buildString {
            append(looksLike ?: "screen not read")
            append(" · stable $stableFrames frames")
            append(if (changedByAction) " · answered you" else " · nothing moved")
            landmark?.let { append(" · $it") }
        }
    }

    private var readHash: Long? = null
    private var readKind: String? = null

    /** Forgets everything: a new game, or a chapter changed by hand. */
    fun reset() {
        readHash = null
        readKind = null
    }

    /** Updates the picture's landmark match and the reading, without asking any model. */
    fun view(frame: Frame?, chapter: WalkthroughChapter?, changedByAction: Boolean, stableFrames: Int): View {
        val hash = frame?.let(FrameHash::dHash)
        return View(
            stableFrames = stableFrames,
            changedByAction = changedByAction,
            looksLike = readKind?.takeIf { hash == readHash },
            landmark = chapter?.milestone?.let { milestone ->
                if (milestone.kind != MilestoneKind.FRAME_HASH) null
                else hash?.takeIf { FrameHash.matches(it, milestone.hashes, milestone.threshold) }?.let { chapter.title }
            },
        )
    }

    /**
     * Asks [model] what the picture shows, when the picture is new and nobody asked about it yet. Returns the reading
     * (`unknown` when the model cannot see images), or the previous reading when there is nothing new to ask.
     *
     * [onError] is told when the question itself failed (a model without image support, a timeout...), so the app can
     * say why nothing is being read instead of silently showing "not read".
     */
    suspend fun read(model: DecisionModel, frame: Frame?, onError: (String) -> Unit = {}): String? {
        val hash = frame?.let(FrameHash::dHash) ?: return null
        if (hash == readHash) return readKind
        val result = runCatching {
            model.choose(
                ChoiceRequest(
                    state = buildJsonObject {
                        put("question", "Look at the screenshot attached to this request: what is on the screen?")
                        put(
                            "why",
                            "Another agent plays this game through a memory reader. It cannot see the screen, and what " +
                                "the screen shows decides what to press (a dialogue box wants A, a menu wants the D-pad).",
                        )
                    },
                    instructions = "Answer with the option that matches the screen you can see. If no image is " +
                        "attached, or you cannot see it, answer `unknown` — never guess a screen you cannot see.",
                    options = SCREENS,
                    reasoning = false,
                ),
            )
        }.onFailure { failure ->
            onError(failure.message ?: failure::class.java.simpleName)
        }.getOrNull() ?: return readKind
        readHash = hash
        readKind = result.choice.takeIf { it in SCREENS.keys } ?: UNKNOWN
        return readKind
    }

    private companion object {
        /** The reading that means "this model cannot see the screenshot". */
        const val UNKNOWN = "unknown"

        /** A screen unchanged for this many frames is a game waiting for input (a second at 60 fps). */
        const val WAITING_FRAMES = 60

        /** The kinds of screen a DS game shows, in the words the agent needs to act on them. */
        val SCREENS = mapOf(
            "dialogue" to "a dialogue or text box is open: it wants A (or a tap) to go on",
            "menu" to "a menu, list or menu-like screen is up: move with the D-pad, confirm with A",
            "field" to "the game world is visible (a map, units, a board...): play it with the D-pad and the buttons",
            "loading" to "a loading or transition screen: nothing to press, wait",
            "intro" to "logos, a title screen or an intro/attract sequence: wait, or press START/A to skip it",
            "unknown" to "I cannot see the screen (no image, or a model without vision)",
        )
    }
}

