package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session store: one file per game, holding what any player learned of it — the envelope every player shares (how
 * much was played, the model's own note, the last lines) and the payload the player that wrote it knows. Plus the one
 * thing a store owes the past: reading what the previous shape of the file held.
 */
class SessionStoreTest {

    private fun session(
        id: String = "fake-game",
        romSha256: String = "hash-of-this-rom",
        decisions: Int = 3,
        note: String? = null,
        history: List<String> = emptyList(),
        payload: JsonObject = JsonObject(emptyMap()),
    ) = StoredSession(id, romSha256, 123L, decisions, note, history, payload)

    @Test
    fun itKeepsOneFilePerGameUnderSessions() {
        val directory = Files.createTempDirectory("session-store")
        val file = SessionStore.write(session(), directory)
        assertEquals(directory.resolve("sessions").resolve("fake-game.json"), file)
        val read = assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"))
        assertEquals(3, read.decisions)
        assertNull(SessionStore.of("another-game", directory, "hash-of-this-rom"), "a game nobody played has none")
        SessionStore.delete("fake-game", directory)
        assertNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"))
        directory.toFile().deleteRecursively()
    }

    @Test
    fun itIsUsedForOneRevisionOfTheGameOnly() {
        val directory = Files.createTempDirectory("session-store")
        SessionStore.write(session(romSha256 = "hash-of-this-rom"), directory)
        assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"), "the same revision")
        assertNull(SessionStore.of("fake-game", directory, "another-rom"), "another revision lays memory out differently")
        assertNull(SessionStore.of("fake-game", directory, ""), "the app cannot say which revision it has: better safe")
        // A session written before the app checked ROMs is still used.
        SessionStore.write(session(romSha256 = ""), directory)
        assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"))
        directory.toFile().deleteRecursively()
    }

    @Test
    fun theEnvelopeCarriesWhatEveryPlayerHas() {
        val directory = Files.createTempDirectory("session-store")
        val payload = buildJsonObject { put("anything", JsonPrimitive("a player's own shape")) }
        SessionStore.write(session(note = "goal: reach the second town", history = listOf("#1 press_a → …"), payload = payload), directory)
        val read = assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"))
        assertEquals("goal: reach the second town", read.note)
        assertEquals(listOf("#1 press_a → …"), read.history)
        assertEquals("a player's own shape", read.payload["anything"]?.jsonPrimitive?.content)
        directory.toFile().deleteRecursively()
    }

    @Test
    fun anUnreadableFileIsIgnoredRatherThanFatal() {
        val directory = Files.createTempDirectory("session-store")
        val file = SessionStore.fileOf("fake-game", directory)
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ this is not json")
        assertNull(SessionStore.of("fake-game", directory, "hash"), "a corrupt file is a game nobody played yet")
        directory.toFile().deleteRecursively()
    }

    @Test
    fun aReaderMemoryAndATypedMemoryAreReadFromTheSameStore() {
        // The store does not care which player wrote it: the payload is theirs, and a reader can tell.
        val readerSession = session(
            payload = ReaderMemoryPayload.encode(
                listOf(ReaderMemoryPayload.ScreenVisit("boot (0)", 2)),
                listOf(
                    ReaderMemoryPayload.Outcome(
                        "boot (0)", "press_a", "screen: boot (0) → logos (129)", 1, mapOf("logos (129)" to 1),
                    ),
                ),
            ),
        )
        val map = ReaderAffordances.of(readerSession)
        assertEquals(listOf("boot (0)", "logos (129)"), map.screens.toList(), "where it was, and where it led")
        assertEquals("logos (129)", map.moves.single().to)
        // A typed player's payload is not a reader memory: nothing is read out of it, rather than something wrong.
        val typed = TypedMemoryPayload.encode(TypedMemoryPayload())
        assertTrue(ReaderAffordances.of(session(payload = typed)).moves.isEmpty())
        assertTrue(ReaderMemoryPayload.decode(typed).outcomes.isEmpty())
    }

    @Test
    fun itReadsWhatTheReaderMemoryUsedToHold() {
        val directory = Files.createTempDirectory("session-store")
        val readers = directory.resolve("readers")
        Files.createDirectories(readers)
        // The old shape, as the app wrote it before every player shared this store.
        Files.writeString(
            readers.resolve("fake-game.memory.json"),
            """
            {"id":"fake-game","romSha256":"hash-of-this-rom","updatedAt":9,"decisions":7,
             "note":"my note","history":["#1 press_a → screen: main menu (19) → single player menu (18)"],
             "screens":[{"screen":"main menu (19)","decisions":4}],
             "outcomes":[{"screen":"main menu (19)","action":"press_a","result":"screen: …","times":2,
                          "destinations":{"single player menu (18)":2}}]}
            """.trimIndent(),
        )
        val read = assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom"))
        assertEquals(7, read.decisions, "what it learned is not thrown away")
        assertEquals("my note", read.note)
        assertEquals(1, read.history.size)
        val payload = ReaderMemoryPayload.decode(read.payload)
        assertEquals(listOf("main menu (19)"), payload.screens.map { it.screen })
        assertEquals(2, payload.outcomes.single().destinations["single player menu (18)"])
        // Another revision of the game is still another revision, even in the old shape.
        assertNull(SessionStore.of("fake-game", directory, "another-rom"))
        // Once written in the new shape, the old file is not read any more.
        SessionStore.write(session(note = "written now"), directory)
        assertEquals("written now", assertNotNull(SessionStore.of("fake-game", directory, "hash-of-this-rom")).note)
        directory.toFile().deleteRecursively()
    }
}
