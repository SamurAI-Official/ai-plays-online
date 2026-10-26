package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ProgressSignal
import me.nathanfallet.aiplayspokemon.games.ReaderSpec

/**
 * Merges what a session just learned into the reader the app already had.
 *
 * A reader the app learned itself used to be frozen at its first draft: the learner stopped as soon as a reader
 * existed, so a game whose first session saw three screens kept a reader with one value for ever, and later sessions
 * could not add the screens they reached. This is the other half of "the app does not retrain every time": the draft
 * of a later session is folded into the reader, which grows instead of being replaced.
 *
 * What merging keeps, and what it never does:
 * - **One entry per address.** A value the reader already names keeps its name: a later draft only knows placeholders
 *   (`screen 3f…`), and its values are added for the screens it saw that the reader did not.
 * - **The names already known win**, and a placeholder that a walkthrough landmark can now name is renamed.
 * - **A reader somebody wrote is never touched** (see [ReaderSpec.isLearned]): the app only extends its own.
 * - **[MAX_OBSERVATIONS] is the cap**: a reader is a handful of values, not a memory dump.
 */
object ReaderMerge {

    /** Most observations a learned reader keeps: a screen id, a menu, a counter and a few flags. */
    const val MAX_OBSERVATIONS = 8

    /**
     * Two pictures within this many bits are the same screen. The same tolerance [ReaderCalibrator] uses when it names
     * a value: a landmark captured at a slightly different moment still names the screen it was taken on.
     */
    private const val SCREEN_TOLERANCE = 2

    /** The prefix a learned value carries when nothing knew what the screen was: `screen <16 hex digits>`. */
    private const val PLACEHOLDER_PREFIX = "screen "

    /**
     * [draft] folded into [existing] (null: the first draft, kept as it is). [names] maps a screen picture (its
     * [FrameHash]) to what something else calls it — a walkthrough chapter, for instance — so a placeholder the draft
     * could not name is renamed when a later session knows better. [progress] is what the app can claim from what it
     * saw this session (see `AppController.learnedProgress`).
     */
    fun merge(
        existing: ReaderSpec?,
        draft: ReaderSpec,
        names: Map<Long, String> = emptyMap(),
        progress: List<ProgressSignal> = emptyList(),
    ): ReaderSpec {
        if (existing == null || !existing.isLearned) return draft.copy(progress = progress)
        val merged = existing.observations.toMutableList()
        for (observation in draft.observations) {
            val index = merged.indexOfFirst { it.address == observation.address }
            when {
                index < 0 -> if (merged.size < MAX_OBSERVATIONS) merged += observation
                else -> {
                    val previous = merged[index]
                    merged[index] = previous.copy(
                        // The names already given win; the draft only adds the values it saw that the reader did not.
                        values = previous.values + observation.values.filterKeys { value -> value !in previous.values },
                        confidence = maxOf(previous.confidence, observation.confidence),
                    )
                }
            }
        }
        return existing.copy(
            observations = merged.map { observation -> observation.renamePlaceholders(names) }.uniqueNames().take(MAX_OBSERVATIONS),
            progress = mergeProgress(existing.progress, progress),
            notes = notes(existing, merged.size, progress.size),
        )
    }

    /**
     * Makes every observation's name unique, keeping the first one as it is.
     *
     * Each session's draft names its own values `screen`, `signal_2`, `signal_3`… so merging two sessions produces two
     * `screen` entries — and the panel and the prompt read the reader as a map by name, where the second would silently
     * replace the first. A later duplicate becomes `screen_2` (and so on), so both values stay readable.
     */
    private fun List<Observation>.uniqueNames(): List<Observation> {
        val used = mutableSetOf<String>()
        return map { observation ->
            var name = observation.name
            var suffix = 2
            while (!used.add(name)) {
                name = "${observation.name}_$suffix"
                suffix++
            }
            if (name == observation.name) observation else observation.copy(name = name)
        }
    }

    /** The signals already known win, and one is added per name (a chapter learned later does not replace an earlier one). */
    private fun mergeProgress(existing: List<ProgressSignal>, learned: List<ProgressSignal>): List<ProgressSignal> {
        val byName = existing.associateBy { it.name }
        return existing + learned.filter { it.name !in byName }
    }

    /**
     * The values that are still placeholders, renamed when [names] (a walkthrough landmark, the model's own reading)
     * now knows the screen they stand for. A name that is already a word is left alone.
     */
    private fun Observation.renamePlaceholders(names: Map<Long, String>): Observation {
        if (names.isEmpty()) return this
        val renamed = values.mapValues { (_, name) -> names.bestMatchFor(name) ?: name }
        return copy(values = renamed)
    }

    /** What [name] (a placeholder) stands for, when one of [names] is within [SCREEN_TOLERANCE] of its picture. */
    private fun Map<Long, String>.bestMatchFor(name: String): String? {
        val picture = placeholderPicture(name) ?: return null
        val closest = minByOrNull { (hash, _) -> FrameHash.distance(hash, picture) } ?: return null
        return closest.value.takeIf { FrameHash.distance(closest.key, picture) <= SCREEN_TOLERANCE }
    }

    /** The picture a `screen <hex>` placeholder names, or null when [name] is a word somebody chose. */
    private fun placeholderPicture(name: String): Long? {
        if (!name.startsWith(PLACEHOLDER_PREFIX)) return null
        return name.removePrefix(PLACEHOLDER_PREFIX).toULongOrNull(16)?.toLong()
    }

    private fun notes(existing: ReaderSpec, observations: Int, progress: Int): String {
        // The reader's own notes are kept (a user may have written what a value means); only the one line that counts
        // the extension is rewritten, so it does not pile up one line per write.
        val kept = existing.notes.lineSequence().filterNot { it.startsWith(EXTENDED_MARKER) }.joinToString("\n").trimEnd()
        val line = "$EXTENDED_MARKER $observations values" +
            (if (progress > 0) " and $progress progress signals" else "") + " so far."
        return if (kept.isEmpty()) line else "$kept\n$line"
    }

    private const val EXTENDED_MARKER = "Extended by later sessions:"
}
