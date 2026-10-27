package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the reader player remembers of a game, and what it tells the model about it: the model's own note, what each
 * action did on each screen, the screens it has been on — and all of that written down and picked up again, because
 * re-exploring the same menus on every launch is exactly what this is for.
 */
class ReaderMemoryTest {

    /** One decision on [screen], which left the game on [after] (the same screen unless the test says otherwise). */
    private fun ReaderMemory.decision(action: String, screen: String, change: String, after: String = screen) {
        // The player records each step (the map) and the decision (the history), in that order.
        recordStep(action, mapOf("screen" to screen), mapOf("screen" to after), change)
        record(
            action = action,
            before = mapOf("screen" to screen),
            after = mapOf("screen" to after),
            change = change,
            historyLine = "#1 $action → $change",
        )
    }

    @Test
    fun itGroupsWhatWasTriedWhereInsteadOfPilingItUp() {
        val memory = ReaderMemory()
        memory.decision("press_a", "main menu (19)", "screen: main menu (19) → single player menu (18)")
        memory.decision("press_b", "main menu (19)", "nothing changed")
        memory.decision("press_a", "main menu (19)", "screen: main menu (19) → single player menu (18)")
        val data = ReaderMemoryPayload.decode(memory.toSession("fake-game", "hash").payload)
        assertEquals(2, data.outcomes.size, "the same action on the same screen is one outcome, counted")
        val press = data.outcomes.first { it.action == "press_a" }
        assertEquals(2, press.times)
        assertEquals("main menu (19)", press.screen)
    }

    @Test
    fun itTellsTheModelWhatItTriedHereAndWhereItHasBeen() {
        val memory = ReaderMemory()
        memory.decision("press_a", "main menu (19)", "screen: main menu (19) → single player menu (18)")
        memory.decision("press_b", "main menu (19)", "nothing changed")
        memory.decision("press_a", "single player menu (18)", "screen: single player menu (18) → select campaign (64)")
        val described = memory.describe(mapOf("screen" to "main menu (19)"))
        val here = described["what_you_tried_here"]?.jsonArray.orEmpty().map { it.jsonObject }
        assertEquals(listOf("press_a", "press_b"), here.map { it["action"]?.jsonPrimitive?.content }, "most tried first")
        assertEquals(
            "nothing changed",
            here.first { it["action"]?.jsonPrimitive?.content == "press_b" }["what_it_did"]?.jsonPrimitive?.content,
        )
        val seen = described["screens_you_have_seen"]?.jsonArray.orEmpty().map { it.jsonObject }
        assertEquals(
            listOf("main menu (19)", "single player menu (18)"),
            seen.map { it["screen"]?.jsonPrimitive?.content },
        )
        assertEquals(2, seen.first()["decisions_spent"]?.jsonPrimitive?.content?.toInt())
        // What worked elsewhere is worth telling too: it is how a model gets past a screen it has not seen.
        val elsewhere = described["what_worked_elsewhere"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        assertTrue(elsewhere.any { it.startsWith("single player menu (18): press_a → ") }, elsewhere.toString())
    }

    @Test
    fun itCountsTheDecisionsThatChangedNothing() {
        val memory = ReaderMemory()
        memory.decision("wait", "boot (0)", "nothing changed")
        memory.decision("wait", "boot (0)", "nothing changed")
        val stuck = memory.describe(mapOf("screen" to "boot (0)"))["decisions_without_progress"]
        assertEquals(2, stuck?.jsonPrimitive?.content?.toInt())
        memory.decision("press_a", "boot (0)", "screen: boot (0) → main menu (19)")
        assertEquals(0, memory.decisionsWithoutProgress, "something changed: it is not stuck any more")
    }

    @Test
    fun theNoteIsTheModelsOwnAndIsKept() {
        val memory = ReaderMemory()
        memory.updateNote("goal: reach the mission briefing")
        memory.decision("press_a", "main menu (19)", "nothing changed")
        assertEquals("goal: reach the mission briefing", memory.toSession("fake-game", "hash").note)
        assertEquals(
            "goal: reach the mission briefing",
            memory.describe(emptyMap())["your_note"]?.jsonPrimitive?.content,
        )
        // An empty note never erases the last real one.
        memory.updateNote("   ")
        assertEquals("goal: reach the mission briefing", memory.note)
    }

    @Test
    fun aNewSessionStartsWithWhatTheLastOneLearned() {
        val first = ReaderMemory()
        first.decision("press_a", "main menu (19)", "screen: main menu (19) → single player menu (18)")
        first.updateNote("press A twice, then hold right")
        val resumed = ReaderMemory.of(first.toSession("fake-game", "hash"))
        assertEquals(1, resumed.totalDecisions, "the decisions of earlier sessions are still counted")
        assertEquals(0, resumed.decisions, "but none of them was taken in this session")
        val described = resumed.describe(mapOf("screen" to "main menu (19)"))
        assertEquals("press A twice, then hold right", described["your_note"]?.jsonPrimitive?.content)
        assertTrue(
            described["note_about_that"]?.jsonPrimitive?.content.orEmpty().contains("you played this game before"),
            "the model is told that this is what it knew, not what it just did",
        )
        val earlier = described["your_last_decisions_before_this_session"]?.jsonArray.orEmpty()
        assertEquals(1, earlier.size, "what it did last time is in the prompt")
        // What it tried here survives too: the second session does not explore it again.
        assertEquals(
            "press_a",
            described["what_you_tried_here"]?.jsonArray?.first()?.jsonObject?.get("action")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun itIsWrittenDownAndReadBack() {
        val directory = Files.createTempDirectory("reader-memory")
        val memory = ReaderMemory()
        memory.decision(
            "press_a",
            "main menu (19)",
            "screen: main menu (19) → single player menu (18)",
            after = "single player menu (18)",
        )
        memory.updateNote("note")
        // The session store owns the file (one per game, shared with every player); the reader memory owns the payload.
        val file = SessionStore.write(memory.toSession("fake-game", "hash-of-this-rom"), directory)
        assertEquals(directory.resolve("sessions").resolve("fake-game.json"), file)
        val read = assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"))
        assertEquals(1, read.decisions)
        assertEquals("note", read.note)
        // What it tried where comes back with it: the next session does not explore it again.
        val resumed = ReaderMemory.of(read)
        assertEquals(1, resumed.totalDecisions)
        assertEquals(listOf("main menu (19)"), resumed.screensVisited.map { it.screen })
        assertEquals(
            "single player menu (18)",
            resumed.known().moves.single().to,
            "and the map of the game is picked up too",
        )
        directory.toFile().deleteRecursively()
    }

    @Test
    fun aReaderWithoutAScreenValueStillRemembersWhereItWas() {
        val memory = ReaderMemory()
        memory.recordStep(
            action = "wait",
            before = mapOf("gold" to "120", "hp" to "18"),
            after = mapOf("gold" to "130", "hp" to "18"),
            change = "gold: 120 → 130",
        )
        memory.record(
            action = "wait",
            before = mapOf("gold" to "120", "hp" to "18"),
            after = mapOf("gold" to "130", "hp" to "18"),
            change = "gold: 120 → 130",
            historyLine = "#1 wait → gold: 120 → 130",
        )
        // The game has no `screen` value: every value stands for the screen, and it is told where that worked.
        val described = memory.describe(mapOf("gold" to "130", "hp" to "18"))
        assertEquals("gold 120, hp 18", memory.screensVisited.first().screen)
        assertTrue(described["what_you_tried_here"]?.jsonArray.orEmpty().isEmpty(), "another screen than the one tried")
        assertTrue(described["what_worked_elsewhere"]?.jsonArray.orEmpty().isNotEmpty())
    }

    @Test
    fun whatItRemembersStaysBounded() {
        val memory = ReaderMemory(maxScreens = 4, maxOutcomes = 6, maxNote = 20)
        repeat(12) { index ->
            memory.decision("press_a", "screen $index (0)", "screen $index (0) → screen ${index + 1} (1)")
        }
        val data = ReaderMemoryPayload.decode(memory.toSession("fake-game", "hash").payload)
        assertEquals(4, data.screens.size, "a game with a hundred menus must not grow the file without end")
        assertEquals(6, data.outcomes.size)
        memory.updateNote("x".repeat(100))
        assertEquals(20, memory.note?.length)
    }
}
