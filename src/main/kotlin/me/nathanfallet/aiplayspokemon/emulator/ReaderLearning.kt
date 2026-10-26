package me.nathanfallet.aiplayspokemon.emulator

import kotlinx.serialization.Serializable
import me.nathanfallet.aiplayspokemon.games.GamesFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * What a [ReaderCalibrator] knows so far, written down so a later session **resumes** instead of starting over.
 *
 * The learner's evidence used to live only in memory: quitting before a reader was ready (40 looks, 4 screens, a
 * verified signal) threw all of it away, and the next launch began at the first byte of memory again — the app
 * "retrained" every time. This is that evidence, as data, in `<data>/readers/<id>.learning.json` (the `.learning`
 * suffix keeps it from ever being read as a reader: [me.nathanfallet.aiplayspokemon.games.ReaderCatalog] looks for
 * `<id>.json` exactly).
 *
 * What is kept is what stays true across launches: the screens seen (their pictures), the signals found and the
 * evidence behind them, and how far the sweep through memory got. What is **not** kept is the part of memory being
 * examined when the evidence was written: its tallies were built against a RAM baseline from that session, which a
 * new one does not have. It is re-baselined on the first look, so the work kept is everything from the chunks already
 * finished, never a guess about the one in progress.
 *
 * [romSha256] pins the evidence to one ROM: a different revision may lay its memory out differently, so its evidence
 * is discarded rather than mixed.
 */
@Serializable
data class ReaderLearning(
    /** The game's profile id, which is also the reader's file name. */
    val id: String,
    /** SHA-256 of the ROM the evidence came from (empty: written before the app checked). */
    val romSha256: String = "",
    val updatedAt: Long = 0,
    /** Looks at the game that went into this evidence. */
    val samples: Int = 0,
    /** The part of memory being examined, so a resumed run continues the sweep there. */
    val chunk: Int = 0,
    /** The screens the game has shown: the picture that stands for each one, and the looks it got. */
    val screens: List<ScreenEvidence> = emptyList(),
    /** The signals found in the parts of memory already examined. */
    val found: List<SignalEvidence> = emptyList(),
    /** The addresses checked against every later look, and the value each held per screen. */
    val watched: List<WatchedEvidence> = emptyList(),
    val verified: Map<Long, Int> = emptyMap(),
    val revisits: Map<Long, Int> = emptyMap(),
    /** Addresses that proved volatile (many values on one screen) or contradicted a screen: not worth looking at again. */
    val dropped: List<Long> = emptyList(),
    val refuted: List<Long> = emptyList(),
) {

    /** One screen, by index (the index [ReaderCalibrator.Signal.byScreen] refers to). */
    @Serializable
    data class ScreenEvidence(val index: Int, val picture: Long, val looks: Int)

    /** One signal found in a finished part of memory. */
    @Serializable
    data class SignalEvidence(
        val address: Long,
        val byScreen: Map<Int, Long>,
        val distinct: Int,
        val looks: Int,
        val consistency: Double,
    )

    /** One address being checked against every later look. */
    @Serializable
    data class WatchedEvidence(val address: Long, val byScreen: Map<Int, Long>)

    /** True when this evidence is about [sha256] (or was written before the app checked, so it is taken as is). */
    fun matches(sha256: String): Boolean = romSha256.isEmpty() || sha256.isEmpty() || romSha256 == sha256

    /** True when there is anything worth resuming from. */
    val usable: Boolean get() = samples > 0 && screens.isNotEmpty()
}

/** Reads and writes the learner's evidence, next to the readers it produces (`<data>/readers/`). */
object ReaderLearningStore {

    private const val DIRECTORY = "readers"
    private const val SUFFIX = ".learning.json"

    /** The file this evidence lives in. */
    fun fileOf(id: String, dataDirectory: Path): Path = dataDirectory.resolve(DIRECTORY).resolve("$id$SUFFIX")

    /**
     * The evidence of [id], or null when there is none (or it cannot be read: a corrupt file is ignored, never fatal —
     * the app simply learns from scratch, as it did before).
     */
    fun of(id: String, dataDirectory: Path?): ReaderLearning? {
        val file = dataDirectory?.let { fileOf(id, it) } ?: return null
        if (!file.isRegularFile()) return null
        return runCatching { GamesFile.json.decodeFromString<ReaderLearning>(Files.readString(file)) }.getOrNull()
    }

    /** Writes [learning] for [id], replacing the previous file. */
    fun write(learning: ReaderLearning, dataDirectory: Path): Path {
        val file = fileOf(learning.id, dataDirectory)
        Files.createDirectories(file.parent)
        Files.writeString(file, GamesFile.json.encodeToString(learning))
        return file
    }

    /** Forgets the evidence of [id] (a reader was written, or the user started over). */
    fun delete(id: String, dataDirectory: Path) {
        runCatching { Files.deleteIfExists(fileOf(id, dataDirectory)) }
    }
}
