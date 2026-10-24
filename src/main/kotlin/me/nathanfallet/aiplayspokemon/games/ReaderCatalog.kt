package me.nathanfallet.aiplayspokemon.games

import dev.kotlinds.pokemonclient.Memory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * The readers the app knows, as data: a game with a reader is a typed game.
 *
 * Two sources, in order (the first one that exists wins), like [WalkthroughCatalog]:
 *
 * 1. `<data>/readers/<id>.json` — the user's own, which is where a calibration writes its draft
 *    (`./gradlew devRun -PdevMain=…RamProbeToolKt -PdevArgs="game.nds|…|--write=…"`);
 * 2. the file shipped with the app, `readers/<id>.json` on the classpath (`src/main/resources`);
 * 3. nothing: the game stays screen-only (never an error).
 */
object ReaderCatalog {

    private const val RESOURCE_DIRECTORY = "readers"
    private const val USER_DIRECTORY = "readers"

    /** The reader of [profile], or null when the game has none (so it is not a typed game). */
    fun of(profile: GameProfile, dataDirectory: Path? = null): ReaderSpec? = of(profile.id, dataDirectory)

    /** The reader with [id], from the user's directory first, then the one shipped with the app. */
    fun of(id: String, dataDirectory: Path? = null): ReaderSpec? = user(id, dataDirectory) ?: shipped(id)

    /** The reader shipped with the app (`src/main/resources/readers/<id>.json`). */
    fun shipped(id: String): ReaderSpec? {
        val stream = ReaderCatalog::class.java.getResourceAsStream("/$RESOURCE_DIRECTORY/$id.json") ?: return null
        return stream.use { input -> decode(input.readBytes().decodeToString()) }
    }

    /** The user's own reader (`<data>/readers/<id>.json`). */
    fun user(id: String, dataDirectory: Path?): ReaderSpec? {
        val file = dataDirectory?.resolve(USER_DIRECTORY)?.resolve("$id.json") ?: return null
        if (!file.isRegularFile()) return null
        return runCatching { decode(Files.readString(file)) }.getOrNull()
    }

    /** Writes [spec] to `<data>/readers/<id>.json` (the calibration's output, and where a user edits one). */
    fun write(spec: ReaderSpec, dataDirectory: Path): Path {
        val directory = dataDirectory.resolve(USER_DIRECTORY)
        Files.createDirectories(directory)
        val file = directory.resolve("${spec.id}.json")
        Files.writeString(file, encode(spec))
        return file
    }

    /** The profile with what its reader changes: a game with a reader is typed. */
    fun apply(profile: GameProfile, dataDirectory: Path? = null): GameProfile {
        val reader = of(profile, dataDirectory) ?: return profile
        val typed = profile.copy(agent = AgentKind.TYPED)
        return if (reader.observations.isEmpty() && reader.progress.isEmpty()) typed else typed
    }

    /** Reads every observation of [spec] out of [memory], as the panel and the prompt show them. */
    fun observe(spec: ReaderSpec, memory: Memory): Map<String, String> =
        spec.observations.associate { it.name to it.format(memory) }

    /** [spec] as the JSON a `readers/<id>.json` holds (what [write] writes, and what a tool can print). */
    fun jsonOf(spec: ReaderSpec): String = encode(spec)

    private fun decode(text: String): ReaderSpec? =
        runCatching { GamesFile.json.decodeFromString<ReaderSpec>(text) }.getOrNull()

    private fun encode(spec: ReaderSpec): String = GamesFile.json.encodeToString(spec)
}
