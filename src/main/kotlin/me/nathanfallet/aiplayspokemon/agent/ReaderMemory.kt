package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.nathanfallet.aiplayspokemon.games.GamesFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * What the reader-based player remembers of a game, in the shape it writes to a session ([SessionStore]): the screens it
 * has been on and what it tried where. The rest of the session — the model's note, the decisions, the last lines — is
 * the envelope every player shares ([StoredSession]).
 *
 * The old file this used to be written to (`readers/<id>.memory.json`) is read by [SessionStore] as well, so nothing
 * learned before is lost.
 */
object ReaderMemoryPayload {

    /** The payload of a reader memory as the session file holds it. */
    @Serializable
    data class Data(
        /** Screens the player has been on, most time spent first. */
        val screens: List<ScreenVisit> = emptyList(),
        /** What one action did on one screen, and how often it was tried. */
        val outcomes: List<Outcome> = emptyList(),
    )

    /** One screen the player has seen, and how much of the run went into it. */
    @Serializable
    data class ScreenVisit(val screen: String, val decisions: Int = 1)

    /** One thing the player tried on one screen, and what it did there. */
    @Serializable
    data class Outcome(
        val screen: String,
        val action: String,
        val result: String,
        val times: Int = 1,
        /**
         * The screens the action led to, and how often each: kept as data rather than parsed out of [result], because it
         * is what makes the known map of the game exact ([ReaderAffordances]). More than one entry means the action went
         * somewhere else another time (the game was in a state the reader does not show), which the map says out loud
         * instead of pretending the first destination is the only one. Empty when the action never moved the screen.
         */
        val destinations: Map<String, Int> = emptyMap(),
    )

    /** The payload of [screens] and [outcomes], as a session holds it. */
    fun encode(screens: List<ScreenVisit>, outcomes: List<Outcome>): JsonObject =
        GamesFile.json.encodeToJsonElement(Data.serializer(), Data(screens = screens, outcomes = outcomes)).jsonObject

    /** What a session's payload says, or nothing when it is not a reader memory (another player wrote it). */
    fun decode(payload: JsonObject): Data =
        runCatching { GamesFile.json.decodeFromJsonElement(Data.serializer(), payload) }.getOrDefault(Data())
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
    private val outcomes = LinkedHashMap<String, ReaderMemoryPayload.Outcome>()

    private val history = ArrayDeque<String>()

    /** Every decision taken over every session that wrote this memory. */
    val totalDecisions: Int get() = earlierDecisions + decisions

    /** The screens seen so far, most time spent first. */
    val screensVisited: List<ReaderMemoryPayload.ScreenVisit>
        get() = screens.entries.sortedByDescending { it.value }.map { ReaderMemoryPayload.ScreenVisit(it.key, it.value) }

    /**
     * Records one **step** of a decision: what [action] did on the screen [before] shows, and where it left the game
     * ([after]). This is the evidence the map of the game is built from ([ReaderAffordances]), one edge per action — a
     * decision that ran a plan of three steps is three edges, not one.
     */
    fun recordStep(action: String, before: Map<String, String>, after: Map<String, String>, change: String) {
        val screen = screenOf(before)
        val key = "$screen|$action"
        val previous = outcomes.remove(key)
        // Where it led, as data: the map of the game is built from this, not from the text of the change.
        val landed = screenOf(after).takeIf { change != NOTHING_CHANGED }
        val destinations = previous?.destinations.orEmpty().let { known ->
            if (landed == null) known else known + (landed to (known[landed] ?: 0) + 1)
        }
        outcomes[key] = ReaderMemoryPayload.Outcome(
            screen = screen,
            action = action,
            result = change,
            times = (previous?.times ?: 0) + 1,
            destinations = destinations,
        )
        while (outcomes.size > maxOutcomes) outcomes.remove(outcomes.keys.first())
    }

    /**
     * Records the **decision** that took [action] on the screen [before] shows, and what the reader read afterwards
     * ([change] is how the player described it, [historyLine] the line the prompt's history shows). A repeat of the same
     * action on the same screen is grouped instead of piling up, and counted: "press_a here did this, three times" is
     * what tells a model whether a dead end is worth trying again.
     */
    fun record(action: String, before: Map<String, String>, after: Map<String, String>, change: String, historyLine: String) {
        decisions++
        screens.merge(screenOf(before), 1, Int::plus)
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
    private fun screenOf(values: Map<String, String>): String = ReaderAffordances.screenOf(values)

    /** What the player knows about the game's screens, distilled from what it tried where ([ReaderAffordances]). */
    fun known(): ReaderAffordances.Known = ReaderAffordances.Known(outcomes.values.toList())

    /** How much of the game this memory knows, in one line (the panel shows it). */
    val summary: String
        get() = buildString {
            append("$totalDecisions decisions over ${screens.size} screens")
            if (outcomes.isNotEmpty()) append(", ${outcomes.size} things tried")
            if (note != null) append(", with its own note")
        }

    /** This memory as a session to be written down ([SessionStore]): the envelope every player has, plus its payload. */
    fun toSession(id: String, romSha256: String): StoredSession = StoredSession(
        id = id,
        romSha256 = romSha256,
        updatedAt = System.currentTimeMillis(),
        decisions = totalDecisions,
        note = note,
        history = (earlierHistory + history).takeLast(maxHistory),
        payload = ReaderMemoryPayload.encode(screensVisited, outcomes.values.toList()),
    )

    companion object {

        /**
         * A memory picked up from what a previous session wrote: the note, the screens it has been on, what it tried
         * where, and its last decisions — so the next session starts knowing the game instead of exploring it again.
         */
        fun of(session: StoredSession): ReaderMemory = ReaderMemory().also { memory ->
            val payload = ReaderMemoryPayload.decode(session.payload)
            memory.note = session.note
            memory.earlierDecisions = session.decisions
            memory.earlierHistory = session.history
            payload.screens.forEach { visit -> memory.screens[visit.screen] = visit.decisions }
            payload.outcomes.forEach { outcome -> memory.outcomes["${outcome.screen}|${outcome.action}"] = outcome }
        }

        /** What [ReaderActions.describeChange] says when an action changed nothing. */
        private const val NOTHING_CHANGED = ReaderActions.NOTHING_CHANGED

        /** How many outcomes elsewhere are worth telling the model about. */
        private const val WORTH_REMEMBERING = 12
    }
}
