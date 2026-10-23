package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Frame
import me.nathanfallet.aiplayspokemon.games.MilestoneKind
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter

/**
 * Watches the screen for a walkthrough chapter's landmark.
 *
 * A chapter of a game we have no reader for says what its screen looks like ([WalkthroughChapter.milestone]); this
 * hashes the frames the emulator produces and, when one matches for a few checks in a row, reports the chapter as
 * reached — so a screen-only game can advance its walkthrough on its own.
 *
 * It is deliberately cheap and offline: a 64-bit difference hash per check (see [FrameHash]), no model call. Checks
 * are driven by the app (a few per second), and [WalkthroughChapter.milestone]'s `vision` kind is not handled here:
 * it needs the multimodal provider and belongs to the screen agent.
 */
class ScreenWatcher {

    private var chapterId: String? = null
    private var stableChecks = 0
    private var lastHash: Long? = null

    /** The last frame's hash, for the panel and the authoring tool. */
    val lastFrameHash: Long? get() = lastHash

    /** Forgets any progress towards a landmark (a new game, or the chapter changed by hand). */
    fun reset() {
        chapterId = null
        stableChecks = 0
    }

    /**
     * Checks one frame against [chapter]'s landmark. Returns true exactly once per chapter, the moment the landmark
     * has been seen for enough consecutive checks.
     */
    fun check(frame: Frame?, chapter: WalkthroughChapter?): Boolean {
        if (frame == null || chapter == null) return false
        val milestone = chapter.milestone ?: return false
        if (milestone.kind == MilestoneKind.VISION) return false

        val hash = FrameHash.dHash(frame, milestone.region.takeIf { it.size == 4 })
        lastHash = hash

        // A different chapter (the app moved on) starts counting again.
        if (chapter.id != chapterId) {
            chapterId = chapter.id
            stableChecks = 0
        }
        if (!FrameHash.matches(hash, milestone.hashes, milestone.threshold)) {
            stableChecks = 0
            return false
        }
        stableChecks++
        if (stableChecks < milestone.minStableChecks) return false
        stableChecks = 0
        return true
    }
}
