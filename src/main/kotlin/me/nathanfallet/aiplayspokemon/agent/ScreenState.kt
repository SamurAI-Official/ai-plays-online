package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.console.Frame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.Screens
import me.nathanfallet.aiplayspokemon.games.MilestoneKind
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter

/**
 * What the app can tell the model about the *picture*, which is what a reader alone cannot say.
 *
 * A reader reads numbers out of memory: it can say "screen 84 (in mission)" but not "a dialogue box is open and it
 * wants A". That gap is what makes an agent think it is stuck on a boot screen while the game is actually showing a
 * conversation. This closes it in two ways, **per screen**, because a DS game uses both (a map above a touch menu, an
 * illustration above a dialogue box):
 *
 * - **offline**: each screen is hashed ([FrameHash]), so the app knows whether *that* screen changed while the action
 *   ran and how long it has been stable — an animated HUD on one screen no longer hides the other being still. A
 *   screen that has not changed for a second is a game *waiting for input*. Each screen is also matched against the
 *   walkthrough chapter's landmark (which names a region, or the whole frame).
 * - **with a model that can see**: the two screens are attached to every request as two pictures, and the model is
 *   asked what each one shows ([read]), one line per screen, cached per screen and picture. `unknown` is one of the
 *   options, so a model without vision says so instead of inventing a screen.
 */
class ScreenState {

    /** What each screen is doing. */
    data class ScreenView(
        val screen: Screens.Screen,
        /** What the model said this screen shows, or null when it was never asked / could not see. */
        val looksLike: String?,
        /** How many frames this screen has looked the same, measured while the last action ran. */
        val stableFrames: Int,
        /** Whether this screen changed at all while the last action ran: did the game answer the press there? */
        val changedByAction: Boolean,
        /** The walkthrough chapter whose landmark this screen matches, when it matches one. */
        val landmark: String? = null,
    ) {
        /** True when the model was asked and answered that it cannot see this screen. */
        val blind: Boolean get() = looksLike == UNKNOWN

        /** One line for the panel and the decision history. */
        fun summary(): String = buildString {
            append(looksLike ?: "not read")
            append(" · stable $stableFrames")
            append(if (changedByAction) " · answered you" else " · nothing moved")
            landmark?.let { append(" · $it") }
        }
    }

    /** One decision's worth of screen knowledge: what each screen shows, and what that means. */
    data class View(
        val top: ScreenView,
        val bottom: ScreenView,
        /** A landmark that covers the whole frame (a game whose walkthrough captured no region), when one matches. */
        val landmark: String? = null,
    ) {
        /** The screens, in the order the pictures are attached. */
        val screens: List<ScreenView> get() = listOf(top, bottom)

        /** True when the model answered that it cannot see the screens at all. */
        val blind: Boolean get() = top.blind && bottom.blind

        /** A hint the model can act on, derived from the pictures alone: every fact there is, not just one. */
        val note: String?
            get() = buildList {
                landmark?.let { add("this is the screen of “$it”") }
                screens.mapNotNull { it.landmark }.forEach { add("a screen is the screen of “$it”") }
                answered?.let { add("the ${it.screen.label} changed while you acted: the game answered you there") }
                waiting?.let {
                    add("the ${it.screen.label} has not changed for ${it.stableFrames} frames: that is where the " +
                        "game is waiting for input")
                }
            }.joinToString(" · ").ifEmpty { "neither screen changed while you acted: that action did nothing visible" }

        /** The screen the game answered on, when exactly one moved. */
        private val answered: ScreenView? get() = screens.singleOrNull { it.changedByAction }

        /** The screen that has been still long enough to be the one waiting for input. */
        private val waiting: ScreenView? get() = screens.filter { it.stableFrames >= WAITING_FRAMES }.maxByOrNull { it.stableFrames }

        /** One line for the panel and the decision history. */
        fun summary(): String = "top ${top.summary()} | bottom ${bottom.summary()}"
    }

    /** What the model answered about each screen, and which picture each answer was made on. */
    private val readHashes = mutableMapOf<Screens.Screen, Long>()
    private val readKinds = mutableMapOf<Screens.Screen, String>()

    /** Forgets everything: a new game, or a chapter changed by hand. */
    fun reset() {
        readHashes.clear()
        readKinds.clear()
    }

    /**
     * What each screen is doing right now, without asking any model: the landmark each one matches (a chapter's
     * landmark names a region, or the whole frame), and what the last action did to each of them ([reports], measured
     * by the player frame by frame while the action ran).
     */
    fun view(frame: Frame?, chapter: WalkthroughChapter?, reports: Map<Screens.Screen, ActionReport>): View {
        val milestone = chapter?.milestone?.takeIf { it.kind == MilestoneKind.FRAME_HASH }
        val region = milestone?.region?.takeIf { it.size == 4 }
        val wholeFrameLandmark = milestone
            ?.takeIf { region == null }
            ?.let { candidate -> frame?.let { FrameHash.dHash(it) }?.takeIf { FrameHash.matches(it, candidate.hashes, candidate.threshold) } }
            ?.let { chapter.title }

        fun screenView(screen: Screens.Screen): ScreenView {
            val report = reports[screen] ?: ActionReport()
            val hash = frame?.let { Screens.hashOf(it, screen) }
            val matchesThisScreen = milestone != null && region != null && regionMatches(region, frame, screen) &&
                hash != null && FrameHash.matches(hash, milestone.hashes, milestone.threshold)
            return ScreenView(
                screen = screen,
                looksLike = readKinds[screen]?.takeIf { hash != null && readHashes[screen] == hash },
                stableFrames = report.stableFrames,
                changedByAction = report.changed,
                landmark = chapter?.title?.takeIf { matchesThisScreen },
            )
        }

        return View(top = screenView(Screens.Screen.TOP), bottom = screenView(Screens.Screen.BOTTOM), landmark = wholeFrameLandmark)
    }

    /** True when a milestone's region is (mostly) this screen, so its landmark belongs to it. */
    private fun regionMatches(region: List<Int>, frame: Frame?, screen: Screens.Screen): Boolean {
        val frameHeight = frame?.height ?: return false
        val middle = frameHeight / 2
        val top = region[1]
        val bottom = region[1] + region[3]
        return if (screen == Screens.Screen.TOP) top < middle else bottom > middle
    }

    /**
     * Asks [model] what [screen] shows, when that screen is new and nobody asked about it yet. Returns the reading
     * (`unknown` when the model cannot see images), or the previous reading when there is nothing new to ask.
     *
     * [onError] is told when the question itself failed (a model without image support, a timeout...), so the app can
     * say why nothing is being read instead of silently showing "not read".
     */
    suspend fun read(
        model: DecisionModel,
        frame: Frame?,
        screen: Screens.Screen,
        onError: (String) -> Unit = {},
    ): String? {
        val hash = frame?.let { Screens.hashOf(it, screen) } ?: return null
        if (readHashes[screen] == hash) return readKinds[screen]
        val result = runCatching {
            model.choose(
                ChoiceRequest(
                    state = buildJsonObject {
                        put("question", "What is the ${screen.label} of this Nintendo DS showing right now?")
                        put("which_picture", "The picture to look at is the one labelled “${screen.label}”.")
                        put(
                            "why",
                            "Another agent plays this game through a memory reader that cannot see the screens: these " +
                                "readings are its only view of them, and it decides what to press from them.",
                        )
                    },
                    instructions = "You are looking at the ${screen.label} of a Nintendo DS: one 256x192 screen, sent " +
                        "as its own picture (the other screen is a separate picture). Answer with the option that best " +
                        "describes what this screen shows and what the player would respond to. If no image is " +
                        "attached, or you cannot see one, answer `unknown` — never guess a screen you cannot see.",
                    options = SCREENS,
                    // Reading a picture is a vision task, not a formality: the model is allowed to think about it.
                    reasoning = true,
                ),
            )
        }.onFailure { failure ->
            onError(failure.message ?: failure::class.java.simpleName)
        }.getOrNull() ?: return readKinds[screen]
        readHashes[screen] = hash
        val kind = result.choice.takeIf { it in SCREENS.keys } ?: UNKNOWN
        readKinds[screen] = kind
        return kind
    }

    /** What the model says a screen shows, and what the app can derive from it. */
    private companion object {
        /** The reading that means "this model cannot see the screenshot". */
        const val UNKNOWN = "unknown"

        /** A screen unchanged for this many frames is a game waiting for input (a second at 60 fps). */
        const val WAITING_FRAMES = 60

        /** The kinds of screen a DS game shows, in the words the agent needs to act on them. */
        val SCREENS = mapOf(
            "dialogue" to "a text or dialogue box is waiting to be advanced (press A or tap it)",
            "menu" to "a menu, list or selection is on screen, waiting for a choice (move with the D-pad, confirm with A)",
            "field" to "the game itself is being played (a map, units, a board): nothing is waiting to be dismissed",
            "loading" to "a loading or transition screen: nothing to press, wait",
            "intro" to "logos, a title screen or an intro/attract sequence: wait, or press START/A to skip it",
            "unknown" to "I cannot see the screen (no image, or a model without vision)",
        )
    }
}

/** What one action did to one screen, measured by the player while the action ran. */
data class ActionReport(val changed: Boolean = false, val stableFrames: Int = 0)

