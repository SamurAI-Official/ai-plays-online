package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.games.GamesFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * What a player remembers of a game, written down so the next launch starts from it: **one file per game**, in
 * `<data>/sessions/<id>.json`.
 *
 * The envelope is what every player has: how much was played over the sessions that wrote the file, the model's own
 * note, and (for a player whose decisions are about screens) the last decisions as one line each. The [payload] is what
 * the player that wrote it knows, in its own shape — the reader player's screens and what it tried where
 * ([ReaderMemoryPayload]), the typed player's places, dialogues and explored map ([TypedMemoryPayload]) — so a new
 * player can be added without touching this file, and an old one can still read what it wrote.
 *
 * [romSha256] pins the session to one ROM: another revision lays its memory out differently (and shows other screens),
 * so its knowledge is discarded rather than mixed. A session written before the app checked ROMs (empty hash) is still
 * used, and one pinned to a revision is **not** used when the app cannot say which revision it has: better to learn the
 * game again than to play it with another revision's map.
 */
@Serializable
data class StoredSession(
    /** The game's profile id, which is also the file's name. */
    val id: String,
    val romSha256: String = "",
    val updatedAt: Long = 0,
    /** Decisions taken over the sessions that wrote this file. */
    val decisions: Int = 0,
    /** The model's own note (goal/plan), rewritten at will and picked up again. */
    val note: String? = null,
    /** The last decisions, as the line the prompt shows them (oldest first). */
    val history: List<String> = emptyList(),
    /** What the player that wrote this knows, in its own shape. */
    val payload: JsonObject = JsonObject(emptyMap()),
) {

    /**
     * True when this session can be used for the ROM [romSha256] the app is playing: its own hash is the same, or it was
     * written before the app checked ROMs (empty).
     */
    fun matches(romSha256: String): Boolean = this.romSha256.isEmpty() || this.romSha256 == romSha256
}
/**
 * Where a [StoredSession] lives: `<data>/sessions/<id>.json`.
 *
 * One store for every player: what a game taught the AI is about the game, not about the player that learned it, and a
 * player switching from the reader loop to the typed one should not have to learn the same game twice.
 */
object SessionStore {

    private const val DIRECTORY = "sessions"
    private const val SUFFIX = ".json"

    /** The file a game's session lives in. */
    fun fileOf(id: String, dataDirectory: Path): Path = dataDirectory.resolve(DIRECTORY).resolve("$id$SUFFIX")

    /**
     * The session of [id], or null when there is none — none yet, unreadable, or about another revision of the game than
     * the [romSha256] being played ([StoredSession.matches]).
     *
     * A memory a previous version of the app wrote next to the reader (`readers/<id>.memory.json`) is read as well, so
     * what a game taught the AI before this store existed is not thrown away ([legacy]).
     */
    fun of(id: String, dataDirectory: Path?, romSha256: String): StoredSession? {
        val directory = dataDirectory ?: return null
        val file = fileOf(id, directory)
        val session = if (file.isRegularFile()) {
            runCatching { GamesFile.json.decodeFromString<StoredSession>(Files.readString(file)) }.getOrNull()
        } else {
            legacy(id, directory)
        }
        return session?.takeIf { it.matches(romSha256) }
    }

    /** Writes [session] for its game, replacing the previous file. */
    fun write(session: StoredSession, dataDirectory: Path): Path {
        val file = fileOf(session.id, dataDirectory)
        Files.createDirectories(file.parent)
        Files.writeString(file, GamesFile.json.encodeToString(session))
        return file
    }

    /** Forgets a game's session (the user started over, or the reader it was played with is gone). */
    fun delete(id: String, dataDirectory: Path) {
        runCatching { Files.deleteIfExists(fileOf(id, dataDirectory)) }
        runCatching { Files.deleteIfExists(legacyFileOf(id, dataDirectory)) }
    }

    /** Where the reader player's own memory used to live, before every player shared this store. */
    private fun legacyFileOf(id: String, dataDirectory: Path): Path =
        dataDirectory.resolve("readers").resolve("$id.memory.json")

    /**
     * A session as an older version of the app wrote it: `readers/<id>.memory.json` held the reader player's memory with
     * its own id, note and decisions. It is read into the shape this store uses, and can go once no install can have the
     * old file.
     */
    private fun legacy(id: String, dataDirectory: Path): StoredSession? {
        val file = legacyFileOf(id, dataDirectory)
        if (!file.isRegularFile()) return null
        val old = runCatching { GamesFile.json.decodeFromString<LegacyReaderMemory>(Files.readString(file)) }
            .getOrNull() ?: return null
        return StoredSession(
            id = old.id,
            romSha256 = old.romSha256,
            updatedAt = old.updatedAt,
            decisions = old.decisions,
            note = old.note,
            history = old.history,
            payload = ReaderMemoryPayload.encode(old.screens, old.outcomes),
        )
    }

    /** The reader memory as an older version of the app wrote it (see [legacy]). */
    @Serializable
    private data class LegacyReaderMemory(
        val id: String,
        val romSha256: String = "",
        val updatedAt: Long = 0,
        val decisions: Int = 0,
        val note: String? = null,
        val history: List<String> = emptyList(),
        val screens: List<ReaderMemoryPayload.ScreenVisit> = emptyList(),
        val outcomes: List<ReaderMemoryPayload.Outcome> = emptyList(),
    )
}

