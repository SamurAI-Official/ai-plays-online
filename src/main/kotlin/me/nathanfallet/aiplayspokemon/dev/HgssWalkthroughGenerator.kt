package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.games.hgss.HgssStoryTable
import me.nathanfallet.aiplayspokemon.games.GamesFile
import me.nathanfallet.aiplayspokemon.games.Walkthrough
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates `src/main/resources/walkthroughs/pokemon-heartgold.json` from pokemon-client's HeartGold story table.
 *
 * The story table already holds the whole main story, each step with a description and a condition checked against
 * the game's flags and vars: the walkthrough is that table, grouped into chapters at its checkpoints (badges, the
 * Hall of Fame, arriving in Kanto). Generating it keeps the text and the step ids in sync with the table the app
 * verifies progress against — the walkthrough is data, but it is not written by hand.
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.HgssWalkthroughGeneratorKt
 * ```
 */
fun main(args: Array<String>) {
    val output = Path.of(args.firstOrNull() ?: "src/main/resources/walkthroughs/pokemon-heartgold.json")
    val steps = HgssStoryTable.steps
    require(steps.isNotEmpty()) { "the story table is empty" }

    // One chapter per checkpoint: the steps that lead to it, then the checkpoint itself.
    val chapters = mutableListOf<WalkthroughChapter>()
    var pending = mutableListOf<String>()
    var pendingHints = mutableListOf<String>()
    for (step in steps) {
        pending += step.id
        if (!step.checkpoint) {
            pendingHints += step.description
            continue
        }
        chapters += WalkthroughChapter(
            id = chapterId(step.id),
            title = title(step.description),
            goal = step.description,
            hints = pendingHints.takeLast(MAX_HINTS).map { trim(it) },
            steps = pending.toList(),
        )
        pending = mutableListOf()
        pendingHints = mutableListOf()
    }
    // Steps after the last checkpoint (the story table's tail), if any.
    if (pending.isNotEmpty()) {
        chapters += WalkthroughChapter(
            id = chapterId(steps.last().id),
            title = title(steps.last().description),
            goal = steps.last().description,
            hints = pendingHints.takeLast(MAX_HINTS).map { trim(it) },
            steps = pending.toList(),
        )
    }

    val walkthrough = Walkthrough(
        id = "pokemon-heartgold",
        provenance = Walkthrough.PROVENANCE_HAND_WRITTEN,
        chapters = chapters,
    )
    Files.createDirectories(output.parent)
    Files.writeString(output, GamesFile.json.encodeToString(walkthrough))
    println("wrote ${output.toAbsolutePath()} (${chapters.size} chapters, ${steps.size} steps)")
}

/** `johto:badge_zephyr` → `hgss:badge_zephyr` (stable across the story table's regions). */
private fun chapterId(stepId: String): String = "hgss:" + stepId.substringAfter(':')

/** A short chapter name from the checkpoint's own sentence. */
private fun title(description: String): String = trim(
    description.substringBefore(" (").removePrefix("Challenge ").removePrefix("Get off the ship at "),
)

private fun trim(text: String): String =
    if (text.length <= MAX_HINT_LENGTH) text else text.take(MAX_HINT_LENGTH - 1).trimEnd() + "…"

private const val MAX_HINTS = 8
private const val MAX_HINT_LENGTH = 140
