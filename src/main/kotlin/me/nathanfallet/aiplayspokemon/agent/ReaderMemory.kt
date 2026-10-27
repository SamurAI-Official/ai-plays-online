package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.aiplayspokemon.games.GamesFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * What the reader-based player remembers of a game, written down so the next session starts from it.
 *
 * [ReaderPlayer] used to keep everything in memory only: its note was dropped the moment the game was closed, the
 * decisions it had made were gone, and a new launch re-explored the same menus. This is that memory as data, in
 * `<data>/readers/<id>.memory.json` (the `.memory` suffix keeps it from ever being read as a reader: [ReaderCatalog]
 * looks for `<id>.json` exactly).
 *
 * What is kept is what stays true across launches: the model's own note, what each action did on each screen (the
 * evidence behind "try this here"), the screens the player has been on with how many decisions it spent there, and the
 * last decisions as one line each. [romSha256] pins it to one ROM: a different revision shows different screens, so its
 * memory is discarded rather than mixed.
 */
@Serializable
data class ReaderMemoryData(
    /** The game's profile id, which is also the reader's file name. */
    val id: String,
    /** SHA-256 of the ROM this memory came from (empty: written before the app checked). */
    val romSha256: String = "",
    val updatedAt: Long = 0,
    /** Decisions taken over every session that wrote this memory. */
    val decisions: Int = 0,
    /** The model's own note (goal/plan), rewritten at will. */
    val note: String? = null,
    /** The last decisions, as the history line the prompt uses, oldest first. */
    val history: List<String> = emptyList(),
    /** Screens the player has been on, most time spent first. */
    val screens: List<ScreenVisit> = emptyList(),
    /** What one action did on one screen, and how often it was tried. */
    val outcomes: List<Outcome> = emptyList(),
) {

    /** One screen the player has seen, and how much of the run went into it. */
    @Serializable
    data class ScreenVisit(val screen: String, val decisions: Int = 1)

    /** One thing the player tried on one screen, and what it did there. */
    @Serializable
    data class Outcome(val screen: String, val action: String, val result: String, val times: Int = 1)

    /**
     * True when this memory can be used for the ROM [romSha256] the app is playing: its own hash is the same, or it was
     * written before the app checked ROMs (empty). A memory pinned to one revision is **not** used when the app does not
     * know which revision it has (an empty [romSha256] asked about): better to learn the game again than to play it with
     * another revision's screens.
     */
    fun matches(romSha256: String): Boolean = this.romSha256.isEmpty() || this.romSha256 == romSha256
}

/**
 * The memory of a running [ReaderPlayer]: what it has tried where, the screens it has been on, the model's note.
 *
 * Like [AgentMemory] it keeps **facts and the model's own words, never advice**: the note is the model's, the outcomes
 * are what the reader showed after an action, and interpreting them is the model's job. Unlike [AgentMemory] it is
 * meant to be written down and picked up again ([toData] / [resume]), because a reader game is explored over many
 * sessions and re-exploring the same menus every launch is exactly what this fixes.
 */
class ReaderMemory(
    private val maxScreens: Int = 40,
    private val maxOutcomes: Int = 120,
    private val maxHistory: Int = ReaderPrompt.HISTORY_SIZE * 4,
    private val maxNote: Int = 600,
) {
    /** The model's own note (goal/plan): written by the model, kept across sessions. */
    var note: String? = null
        private set

    /** Decisions taken in this session (the total, including earlier sessions, is [totalDecisions]). */
    var decisions: Int = 0
        private set

    /** Decisions since the reader showed something new. */
    var decisionsWithoutProgress: Int = 0
        private set

    /** Decisions taken before this session (read back from the file). */
    var earlierDecisions: Int = 0
        private set

    /** The decisions of earlier sessions, oldest first, as the prompt's history lines. */
    private var earlierHistory: List<String> = emptyList()

    /** Screens the player has been on, in visit order: screen → decisions spent. */
    private val screens = LinkedHashMap<String, Int>()

    /** What was tried where: `screen|action` → the outcome, most recently seen last. */
    private val outcomes = LinkedHashMap<String, ReaderMemoryData.Outcome>()

    private val history = ArrayDeque<String>()

    /** Every decision taken over every session that wrote this memory. */
    val totalDecisions: Int get() = earlierDecisions + decisions

    /** The screens seen so far, most time spent first. */
    val screensVisited: List<ReaderMemoryData.ScreenVisit>
        get() = screens.entries.sortedByDescending { it.value }.map { ReaderMemoryData.ScreenVisit(it.key, it.value) }

    /**
     * Records the decision that took [action] on the screen [before] shows, and what the reader read afterwards
     * ([change] is how the player described it, [historyLine] the line the prompt's history shows). A repeat of the same
     * action on the same screen is grouped instead of piling up, and counted: "press_a here did this, three times" is
     * what tells a model whether a dead end is worth trying again.
     */
    fun record(action: String, before: Map<String, String>, change: String, historyLine: String) {
        decisions++
        val screen = screenOf(before)
        screens.merge(screen, 1, Int::plus)
        val key = "$screen|$action"
        val previous = outcomes.remove(key)
        outcomes[key] = ReaderMemoryData.Outcome(
            screen = screen,
            action = action,
            result = change,
            times = (previous?.times ?: 0) + 1,
        )
        while (outcomes.size > maxOutcomes) outcomes.remove(outcomes.keys.first())
        history.addLast(historyLine)
        while (history.size > maxHistory) history.removeFirst()
        decisionsWithoutProgress = if (change == NOTHING_CHANGED) decisionsWithoutProgress + 1 else 0
        // The screens are bounded too: a game with a hundred menus must not grow the file without end.
        while (screens.size > maxScreens) screens.remove(screens.keys.first())
    }

    /** Remembers the model's own note (its goal/plan), which the next session picks up again. */
    fun updateNote(value: String?) {
        if (!value.isNullOrBlank()) note = value.take(maxNote)
    }

    /** What the player tells the model about its own memory, as the prompt's `memory` block. */
    fun describe(current: Map<String, String>): JsonObject {
        val here = screenOf(current)
        val triedHere = outcomes.values.filter { it.screen == here }
        return buildJsonObject {
            note?.let { put("your_note", it) }
            put("decisions_taken_so_far", totalDecisions)
            if (earlierDecisions > 0) {
                put(
                    "note_about_that",
                    "you played this game before, in earlier sessions: $earlierDecisions of those decisions were not " +
                        "in this one, so what is below is what you already know, not what you just did.",
                )
            }
            if (decisionsWithoutProgress > 0) put("decisions_without_progress", decisionsWithoutProgress)
            if (screens.size > 1) {
                putJsonArray("screens_you_have_seen") {
                    screensVisited.forEach { visit ->
                        add(
                            buildJsonObject {
                                put("screen", visit.screen)
                                put("decisions_spent", visit.decisions)
                            },
                        )
                    }
                }
            }
            if (triedHere.isNotEmpty()) {
                putJsonArray("what_you_tried_here") {
                    triedHere.sortedByDescending { it.times }.forEach { outcome ->
                        add(
                            buildJsonObject {
                                put("action", outcome.action)
                                put("what_it_did", outcome.result)
                                if (outcome.times > 1) put("times_tried", outcome.times)
                            },
                        )
                    }
                }
            }
            val elsewhere = outcomes.values
                .filter { it.screen != here && it.result != NOTHING_CHANGED }
                .takeLast(WORTH_REMEMBERING)
            if (elsewhere.isNotEmpty()) {
                putJsonArray("what_worked_elsewhere") {
                    elsewhere.forEach { outcome ->
                        add(JsonPrimitive("${outcome.screen}: ${outcome.action} → ${outcome.result}"))
                    }
                }
            }
            val earlier = earlierHistory.takeLast(ReaderPrompt.HISTORY_SIZE)
            if (earlier.isNotEmpty()) {
                putJsonArray("your_last_decisions_before_this_session") {
                    earlier.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    }

    /** The screen [values] shows, as a key: the reader's own `screen` value, or every value when there is none. */
    private fun screenOf(values: Map<String, String>): String = values["screen"]
        ?: values.entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { UNKNOWN_SCREEN }.take(MAX_SCREEN_KEY)

    /** This memory as data, to be written down ([ReaderMemoryStore]). */
    fun toData(id: String, romSha256: String): ReaderMemoryData = ReaderMemoryData(
        id = id,
        romSha256 = romSha256,
        updatedAt = System.currentTimeMillis(),
        decisions = totalDecisions,
        note = note,
        history = (earlierHistory + history).takeLast(maxHistory),
        screens = screensVisited,
        outcomes = outcomes.values.toList(),
    )

    companion object {

        /**
         * A memory picked up from what a previous session wrote: the note, the screens it has been on, what it tried
         * where, and its last decisions — so the next session starts knowing the game instead of exploring it again.
         */
        fun resume(data: ReaderMemoryData): ReaderMemory = ReaderMemory().also { memory ->
            memory.note = data.note
            memory.earlierDecisions = data.decisions
            memory.earlierHistory = data.history
            data.screens.forEach { visit -> memory.screens[visit.screen] = visit.decisions }
            data.outcomes.forEach { outcome -> memory.outcomes["${outcome.screen}|${outcome.action}"] = outcome }
        }

        /** What [ReaderActions.describeChange] says when an action changed nothing. */
        private const val NOTHING_CHANGED = ReaderActions.NOTHING_CHANGED

        /** How many outcomes elsewhere are worth telling the model about. */
        private const val WORTH_REMEMBERING = 12

        /** Longest screen key (a reader with no `screen` value: every value stands for the screen). */
        private const val MAX_SCREEN_KEY = 80

        private const val UNKNOWN_SCREEN = "unknown"
    }
}

/**
 * Where a [ReaderMemory] lives: `<data>/readers/<id>.memory.json`.
 *
 * The `.memory` suffix keeps it from ever being read as a reader ([ReaderCatalog] looks for `<id>.json` exactly), and
 * the file sits next to the reader it was played with — the reader says what the game *is*, the memory says what the
 * player has *found out* about it, and a reader is far more useful with both.
 */
object ReaderMemoryStore {

    private const val DIRECTORY = "readers"
    private const val SUFFIX = ".memory.json"

    /** The file a game's memory lives in. */
    fun fileOf(id: String, dataDirectory: Path): Path = dataDirectory.resolve(DIRECTORY).resolve("$id$SUFFIX")

    /**
     * The memory of [id], or null when there is none — none yet, unreadable, or about another revision of the game than
     * the [romSha256] being played ([ReaderMemoryData.matches]: a different revision shows different screens, so its
     * memory would be about a game we are not playing).
     */
    fun of(id: String, dataDirectory: Path?, romSha256: String): ReaderMemoryData? {
        val file = dataDirectory?.let { fileOf(id, it) } ?: return null
        if (!file.isRegularFile()) return null
        return runCatching { GamesFile.json.decodeFromString<ReaderMemoryData>(Files.readString(file)) }
            .getOrNull()
            ?.takeIf { it.matches(romSha256) }
    }

    /** Writes [memory] for its game, replacing the previous file. */
    fun write(memory: ReaderMemoryData, dataDirectory: Path): Path {
        val file = fileOf(memory.id, dataDirectory)
        Files.createDirectories(file.parent)
        Files.writeString(file, GamesFile.json.encodeToString(memory))
        return file
    }

    /** Forgets a game's memory (the user started over, or the reader it was played with is gone). */
    fun delete(id: String, dataDirectory: Path) {
        runCatching { Files.deleteIfExists(fileOf(id, dataDirectory)) }
    }
}
