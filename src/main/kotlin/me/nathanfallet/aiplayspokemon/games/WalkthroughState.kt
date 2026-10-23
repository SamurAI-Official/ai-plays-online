package me.nathanfallet.aiplayspokemon.games

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which walkthrough is in play and which chapter we are on: the one piece of walkthrough state the panel, the
 * prompts and the run statistics all read.
 *
 * The chapter is picked in two ways:
 * - **automatically** for a game with a typed reader: the story table says which steps are done, so the chapter is
 *   simply the first one whose steps are not all done (see [Walkthrough.currentFor]);
 * - **by landmark or by hand** otherwise: the screen watcher advances it when it sees a chapter's milestone, and the
 *   panel can pin any chapter (a human steering the run, or correcting a wrong guess).
 */
class WalkthroughState {

    private val _walkthrough = MutableStateFlow<Walkthrough?>(null)

    /** The walkthrough of the game being played, or null when it has none. */
    val walkthrough: StateFlow<Walkthrough?> = _walkthrough.asStateFlow()

    private val _chapterId = MutableStateFlow<String?>(null)

    /** The id of the chapter being played, or null when none is chosen yet. */
    val chapterId: StateFlow<String?> = _chapterId.asStateFlow()

    /** Whether the app may advance the chapter on its own (typed progress or a screen landmark). */
    var autoAdvance: Boolean = true

    /** Why the current chapter was chosen, for the panel and the run log. */
    var reason: String = ""

    val chapter: WalkthroughChapter? get() = _chapterId.value?.let { id -> _walkthrough.value?.chapter(id) }

    /** Switches to [walkthrough] (a new game), keeping the chapter when the data still has it. */
    fun load(walkthrough: Walkthrough?) {
        _walkthrough.value = walkthrough
        val current = _chapterId.value
        _chapterId.value = when {
            walkthrough == null -> null
            current != null && walkthrough.chapter(current) != null -> current
            else -> walkthrough.chapters.firstOrNull()?.id
        }
        if (_chapterId.value != null) reason = "the game's first chapter"
    }

    /** Pins a chapter (the panel's picker, or the model choosing to jump). */
    fun select(id: String?, why: String) {
        if (id != null && _walkthrough.value?.chapter(id) == null) return
        _chapterId.value = id
        reason = why
    }

    /** Advances to the next chapter after the current one, if any. Returns the chapter now current. */
    fun advance(why: String): WalkthroughChapter? {
        val walkthrough = _walkthrough.value ?: return null
        val current = _chapterId.value
        val next = if (current == null) walkthrough.chapters.firstOrNull() else walkthrough.next(current)
        if (next != null) {
            _chapterId.value = next.id
            reason = why
        }
        return next
    }

    /** Applies typed progress: the chapter is the first whose steps are not all done. */
    fun followProgress(doneSteps: Set<String>, why: String = "the game's own progress") {
        if (!autoAdvance) return
        val walkthrough = _walkthrough.value ?: return
        val current = walkthrough.currentFor(doneSteps) ?: return
        if (current.id != _chapterId.value) {
            _chapterId.value = current.id
            reason = why
        }
    }
}
