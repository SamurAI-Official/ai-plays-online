package me.nathanfallet.aiplayspokemon.games

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A game's walkthrough: the chapters of its story or its progression, in order.
 *
 * It is data, not code (see [WalkthroughCatalog]): shipped with the app for the games we know, overridable and
 * extendable in `<data>/walkthroughs/`, so a corrected route or a new game needs no rebuild.
 *
 * How a chapter is *reached* depends on the game:
 * - a game with a typed reader (Pokémon) has a story table whose steps are verified against RAM: the chapter lists
 *   the step ids it covers ([WalkthroughChapter.steps]) and progress is exact;
 * - a game we only see the screen of declares a [WalkthroughChapter.milestone] (a screen landmark), which the app
 *   can watch for, or the chapter is advanced by hand.
 */
@Serializable
data class Walkthrough(
    /** The profile id this walkthrough belongs to (e.g. `mario-kart-ds`). */
    val id: String,
    /**
     * Where the text comes from: `hand-written` (we wrote it), `community` (contributed), `imported` (a source the
     * user supplied). Never a copied guide: see docs/walkthroughs.md.
     */
    val provenance: String = PROVENANCE_HAND_WRITTEN,
    val chapters: List<WalkthroughChapter> = emptyList(),
) {
    fun chapter(id: String): WalkthroughChapter? = chapters.firstOrNull { it.id == id }

    /** The chapter a story-table step belongs to (typed games), when the data maps it. */
    fun chapterOfStep(stepId: String): WalkthroughChapter? =
        chapters.firstOrNull { chapter -> chapter.steps.any { it == stepId } }

    /** The chapter after [id], or null at the end. */
    fun next(id: String): WalkthroughChapter? {
        val index = chapters.indexOfFirst { it.id == id }
        return chapters.getOrNull(index + 1)
    }

    /** The chapter at or after [id] whose steps are not all done yet, for typed games. */
    fun currentFor(doneSteps: Set<String>): WalkthroughChapter? = chapters.firstOrNull { chapter ->
        chapter.steps.isNotEmpty() && !chapter.steps.all { it in doneSteps }
    } ?: chapters.lastOrNull()

    companion object {
        const val PROVENANCE_HAND_WRITTEN = "hand-written"
        const val PROVENANCE_COMMUNITY = "community"
        const val PROVENANCE_IMPORTED = "imported"
    }
}

/**
 * One step of a walkthrough: what to do, and how the app can tell it is done.
 *
 * @param goal what the player is trying to achieve here (goes into the prompt)
 * @param hints a few concrete pointers, shortest first; the prompt shows the next ones
 * @param steps story-table step ids this chapter covers (typed games only)
 * @param milestone a screen landmark the app can watch for (screen-only games)
 */
@Serializable
data class WalkthroughChapter(
    val id: String,
    val title: String,
    val goal: String,
    val hints: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val milestone: Milestone? = null,
)

/**
 * How the app recognises a chapter's landmark on screen.
 *
 * `frameHash` and `template` are cheap and offline: the frame is downscaled to a 16x16 grayscale
 * [dHash][me.nathanfallet.aiplayspokemon.emulator.dHash] and compared with the references (a small Hamming distance
 * tolerates animations). `vision` asks the multimodal provider instead, at most once every [everySeconds].
 */
@Serializable
data class Milestone(
    val kind: MilestoneKind,
    /** `dhash:…` references of the screen (one or more, for frames that vary). */
    val hashes: List<String> = emptyList(),
    /** `template` only: the region of the frame to compare, `[x, y, width, height]`. */
    val region: List<Int> = emptyList(),
    /** Maximum Hamming distance to still count as the same screen. */
    val threshold: Int = 6,
    /** How many consecutive matching checks (the app checks a few times per second) before it counts as reached. */
    val minStableChecks: Int = 3,
    /** `vision` only: what to ask about the frame. */
    val question: String = "",
    /** `vision` only: how often the question may be asked again. */
    val everySeconds: Int = 20,
)

@Serializable
enum class MilestoneKind {
    @SerialName("frameHash")
    FRAME_HASH,

    @SerialName("template")
    TEMPLATE,

    @SerialName("vision")
    VISION,
}
