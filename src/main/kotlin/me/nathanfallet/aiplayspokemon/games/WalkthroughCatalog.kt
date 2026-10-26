package me.nathanfallet.aiplayspokemon.games

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * The walkthroughs the app knows, as data.
 *
 * Three sources, in order (the first one that exists wins):
 *
 * 1. `<data>/walkthroughs/<id>.json` — the user's own: a corrected route, a language, or a game we don't ship;
 * 2. the file shipped with the app, `walkthroughs/<id>.json` on the classpath (`src/main/resources`);
 * 3. nothing: the game then plays with its profile prompt only (never an error).
 *
 * [export] copies a shipped walkthrough into the data directory to edit, and [provenanceNote] says where the text
 * of a walkthrough came from, so an imported guide is never passed off as ours.
 */
object WalkthroughCatalog {

    private const val RESOURCE_DIRECTORY = "walkthroughs"
    private const val USER_DIRECTORY = "walkthroughs"

    /** The walkthrough of [profile], or null when there is none. */
    fun of(profile: GameProfile, dataDirectory: Path? = null): Walkthrough? =
        of(profile.walkthroughId ?: profile.id, dataDirectory)

    /** The walkthrough with [id], from the user's directory first, then the one shipped with the app. */
    fun of(id: String, dataDirectory: Path? = null): Walkthrough? =
        user(id, dataDirectory) ?: shipped(id)

    /** The walkthrough shipped with the app (`src/main/resources/walkthroughs/<id>.json`). */
    fun shipped(id: String): Walkthrough? {
        val stream = WalkthroughCatalog::class.java.getResourceAsStream("/$RESOURCE_DIRECTORY/$id.json")
            ?: return null
        return stream.use { input ->
            runCatching { GamesFile.json.decodeFromString<Walkthrough>(input.readBytes().decodeToString()) }
                .getOrNull()
        }
    }

    /** The user's own walkthrough (`<data>/walkthroughs/<id>.json`). */
    fun user(id: String, dataDirectory: Path?): Walkthrough? {
        val file = dataDirectory?.resolve(USER_DIRECTORY)?.resolve("$id.json") ?: return null
        if (!Files.isRegularFile(file)) return null
        return runCatching { GamesFile.json.decodeFromString<Walkthrough>(Files.readString(file)) }.getOrNull()
    }

    /** Every walkthrough the app can offer, shipped ones first and the user's overrides replacing them. */
    fun all(profiles: List<GameProfile> = GameCatalog.builtIns, dataDirectory: Path? = null): List<Walkthrough> =
        profiles.mapNotNull { of(it, dataDirectory) }

    /** Copies the shipped walkthrough of [id] into `<data>/walkthroughs/`, to edit it. Returns the file, or null. */
    fun export(id: String, dataDirectory: Path): Path? {
        val walkthrough = shipped(id) ?: return null
        val directory = dataDirectory.resolve(USER_DIRECTORY)
        Files.createDirectories(directory)
        val file = directory.resolve("$id.json")
        Files.writeString(file, GamesFile.json.encodeToString(walkthrough))
        return file
    }

    /**
     * Generates (and caches) a walkthrough for [profile] from an online source, when none is already available.
     * Returns the cached/generated walkthrough, or null when the source has nothing (or the network fails).
     */
    fun generate(profile: GameProfile, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(profile.walkthroughId ?: profile.id, profile.title, dataDirectory, source)

    /** Generates (and caches) a walkthrough with [id] from an online source. */
    fun generate(id: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(id, id, dataDirectory, source)

    /** Generates (and caches) a walkthrough with [id]/[title] from an online source. */
    fun generate(id: String, title: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? {
        // A user's or shipped walkthrough always wins; never overwrite it.
        of(id, dataDirectory)?.let { return it }
        val walkthrough = safeFetch(source, id, title) ?: return null
        if (walkthrough.chapters.isEmpty()) return null
        val cached = walkthrough.copy(id = id)
        if (dataDirectory != null) {
            runCatching {
                val directory = dataDirectory.resolve(USER_DIRECTORY)
                Files.createDirectories(directory)
                Files.writeString(directory.resolve("$id.json"), GamesFile.json.encodeToString(cached))
            }
        }
        return cached
    }

    /** Never throws: fetch failures become null. */
    private fun safeFetch(source: WalkthroughSource, id: String, title: String): Walkthrough? =
        runCatching { source.fetch(id, title) }.getOrNull()

    /** Generates (and caches) a walkthrough for [profile] from [source]. */
    fun generate(profile: GameProfile, source: WalkthroughSource): Walkthrough? =
        generate(profile, null, source)


    /** Generates (and caches) a walkthrough with [id] from [source]. */
    fun generate(id: String, source: WalkthroughSource): Walkthrough? =
        generate(id, null, source)


    /** Generates (and caches) a walkthrough with [id]/[title] from [source]. */
    fun generate(id: String, title: String, source: WalkthroughSource): Walkthrough? =
        generate(id, title, null, source)


    /** Alias of [generate]. */
    fun fromOnline(id: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(id, dataDirectory, source)

    /** Alias of [generate]. */
    fun fetch(profile: GameProfile, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(profile, dataDirectory, source)

    /** Alias of [generate]. */
    fun fetch(id: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(id, dataDirectory, source)

    /** Alias of [generate]. */
    fun online(profile: GameProfile, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(profile, dataDirectory, source)

    /** Alias of [generate]. */
    fun online(id: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(id, dataDirectory, source)

    /** Alias of [generate]. */
    fun generateFromOnline(profile: GameProfile, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(profile, dataDirectory, source)

    /** Alias of [generate]. */
    fun generateFromOnline(id: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(id, dataDirectory, source)

    /** Alias of [generate]. */
    fun generateWalkthrough(profile: GameProfile, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(profile, dataDirectory, source)

    /** Alias of [generate]. */
    fun generateWalkthrough(id: String, dataDirectory: Path? = null, source: WalkthroughSource = OnlineWalkthroughSource()): Walkthrough? =
        generate(id, dataDirectory, source)
    fun provenanceNote(walkthrough: Walkthrough): String = when (walkthrough.provenance) {
        Walkthrough.PROVENANCE_HAND_WRITTEN -> "written for this project"
        Walkthrough.PROVENANCE_COMMUNITY -> "contributed by the community"
        Walkthrough.PROVENANCE_IMPORTED -> "imported by you (not checked by us)"
        Walkthrough.PROVENANCE_ONLINE -> "fetched from an online source"
        Walkthrough.PROVENANCE_GENERATED -> "generated from an online source"
        else -> walkthrough.provenance
    }
}
