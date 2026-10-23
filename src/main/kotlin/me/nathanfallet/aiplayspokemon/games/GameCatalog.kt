package me.nathanfallet.aiplayspokemon.games

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/** A ROM found on disk, with the profile the app would use for it. */
data class DetectedRom(val path: Path, val profile: GameProfile) {
    /** What the game picker shows. */
    val label: String get() = profile.title
}

/**
 * The games the app knows, keyed by the ROM header so a user's own copy is recognized whatever the
 * file is called.
 *
 * A DS ROM starts with a 12-byte title (`0x00`) and a 4-byte game code (`0x0C`), both ASCII and both
 * padded with zeros. The code identifies the game; the title only names a ROM the catalog does not
 * know.
 *
 * Users can add or override profiles without rebuilding the app: `<data>/games.json` is merged over
 * the built-ins ([exportDefaults] writes the current ones out as a starting point).
 */
object GameCatalog {

    /** How any DS game behaves, for games without their own conventions. */
    const val DS_CONVENTIONS =
        "How the DS works: the D-pad moves you or a cursor; A confirms, talks and advances text; B cancels and " +
            "backs out; X and Y are game-specific; L and R are usually modifiers or shortcuts; START opens the pause " +
            "menu and SELECT is a secondary menu. A message box or a menu must be advanced or closed with A (B backs " +
            "out) before anything else reacts. Some screens use the touch screen, which can be tapped. Saving is " +
            "usually in the pause menu (START) and sometimes only at save points. Keep pressing forward: if nothing " +
            "changed after your last action, do something different."

    /** How the Pokémon games behave (the games with a typed RAM reader). */
    const val POKEMON_CONVENTIONS =
        "How the game works: a D-pad press turns you to face that direction if you weren't already, " +
            "otherwise it walks one tile. A talks to or examines what you are facing and advances text; " +
            "a message box must be advanced with A until it closes. B cancels and backs out of menus and screens. " +
            "Walking onto stairs, doors and ladders (exits) takes you to another place; for exit mats at the edge " +
            "of a room, stand on them and press towards the edge. People walk around and block the way. " +
            "If `previous_movement_failure` is set, your last move was refused with the reason given: don't do it that " +
            "way again (a wall means another direction, a message box means advance it with A). " +
            "If a way is blocked or you bump into the same wall twice, do not press that direction again: " +
            "re-read the exits and people around you in `game` and go to one with go_to or interact. " +
            "PCs, TVs and bookshelves are optional; the story moves forward by talking to people and going to new places."

    private fun pokemonObjective(game: String) =
        "Play $game like a good player: follow the story, talk to people to learn where to go, " +
            "explore new places, win battles, catch Pokémon and keep the team healthy."

    /** A game we know nothing about: it still runs, and the AI would only have the screen. */
    val generic = GameProfile(
        id = "unknown",
        title = "Unknown DS game",
        gameCode = "",
        agent = AgentKind.SCREEN,
        objective = "Play the game as well as you can: work out what the game wants, make progress, and avoid " +
            "losing progress (save when the game lets you).",
        conventions = DS_CONVENTIONS,
        touch = true,
    )

    /** The games we ship profiles for, with the prompts we tuned for them. */
    val builtIns: List<GameProfile> = listOf(
        GameProfile(
            id = "pokemon-heartgold",
            title = "Pokémon HeartGold",
            gameCode = "IPKE",
            romTitle = "POKEMON HG",
            agent = AgentKind.TYPED,
            objective = pokemonObjective("Pokémon HeartGold"),
            conventions = POKEMON_CONVENTIONS,
            preferredCore = "desmume",
            tested = "typed agent; 1,000-frame determinism, 300-frame two-process rollback",
        ),
        GameProfile(
            id = "pokemon-platinum",
            title = "Pokémon Platinum",
            gameCode = "CPUE",
            romTitle = "POKEMON PL",
            agent = AgentKind.TYPED,
            objective = pokemonObjective("Pokémon Platinum"),
            conventions = POKEMON_CONVENTIONS,
            preferredCore = "desmume",
            tested = "typed agent; boot check",
        ),
        GameProfile(
            id = "mario-kart-ds",
            title = "Mario Kart DS",
            gameCode = "AMCE",
            romTitle = "MARIOKARTDS",
            agent = AgentKind.SCREEN,
            objective = "Win races: pick a character and a kart, drive the tracks cleanly, place first to unlock " +
                "more cups, characters and karts, and race the Grand Prix cups in order.",
            conventions = DS_CONVENTIONS +
                " Mario Kart DS: A accelerates, B brakes (tap it to hop), the D-pad steers, X or Y looks behind, " +
                "L holds a second item, R drifts when you hold it into a turn. Items are used with X. A race ends " +
                "when you cross the finish line; the menus are D-pad + A, and the cup you race is chosen there.",
            touch = false,
            tested = "boot check; local wireless probe",
        ),

        GameProfile(
            id = "final-fantasy-iii",
            title = "Final Fantasy III",
            gameCode = "AFFE",
            romTitle = "FF3",
            agent = AgentKind.SCREEN,
            objective = "Follow the story of the four Warriors of Light: find the crystals the wind speaks of, " +
                "explore towns and dungeons, talk to everyone, fight the battles you meet, level up and keep the " +
                "party equipped, and save often.",
            conventions = DS_CONVENTIONS +
                " Final Fantasy III: battles are turn-based menus (Fight, Magic, Item, Run) where the D-pad picks " +
                "and A confirms; the touch screen can drive the menus too. Talk to villagers to learn where to go " +
                "next, buy and equip gear in shops, and save from the menu.",
            tested = "boot check",
        ),
        GameProfile(
            id = "final-fantasy-iv",
            title = "Final Fantasy IV",
            gameCode = "YF4E",
            romTitle = "FF4",
            agent = AgentKind.SCREEN,
            objective = "Follow the story of Cecil: explore towns and dungeons, talk to everyone, fight the battles " +
                "you meet, level up and keep the party equipped, and save often.",
            conventions = DS_CONVENTIONS +
                " Final Fantasy IV: battles are turn-based menus (Fight, Magic, Item, Run) where the D-pad picks " +
                "and A confirms; the touch screen can drive the menus too. Talk to villagers to learn where to go " +
                "next, buy and equip gear in shops, and save from the menu.",
            tested = "boot check",
        ),
        GameProfile(
            id = "phantasy-star-0",
            title = "Phantasy Star 0",
            gameCode = "C24E",
            romTitle = "PSZERO",
            agent = AgentKind.SCREEN,
            objective = "Follow the story: take the quests in town, explore the fields and dungeons, fight the " +
                "monsters you meet, keep your character equipped and healed, and save often.",
            conventions = DS_CONVENTIONS +
                " Phantasy Star 0: A attacks the target in front of you, the D-pad moves and selects palette " +
                "actions, START opens the menu (items, equipment, quests), and the touch screen shows the map and " +
                "a shortcut palette. Talk to the people in town to pick up quests.",
            tested = "boot check",
        ),
        GameProfile(
            id = "age-of-empires-aok",
            title = "Age of Empires: The Age of Kings",
            gameCode = "AEKE",
            romTitle = "AOK2",
            agent = AgentKind.SCREEN,
            objective = "Play the campaign: gather resources, build and defend a base, train an army and complete " +
                "the mission's objectives.",
            conventions = DS_CONVENTIONS +
                " Age of Empires: the D-pad pans the map and moves the cursor, A selects and confirms, B cancels, " +
                "L and R cycle between units and buildings, and the touch screen can select units too. Villagers " +
                "gather resources, buildings train units, and the mission objectives are shown in the pause menu.",
            tested = "boot check",
        ),
    )

    /** Built-ins plus the user's overrides, keyed by id (an override replaces a built-in). */
    fun all(dataDirectory: Path? = null): List<GameProfile> {
        val overrides = userProfiles(dataDirectory)
        if (overrides.isEmpty()) return builtIns
        val byId = builtIns.associateBy { it.id }.toMutableMap()
        for (profile in overrides) byId[profile.id] = profile
        return byId.values.toList()
    }

    /** The profile for a game code, or null when the catalog does not know it. */
    fun find(gameCode: String, dataDirectory: Path? = null): GameProfile? =
        all(dataDirectory).firstOrNull { it.gameCode.isNotEmpty() && it.gameCode.equals(gameCode, ignoreCase = true) }

    /** The profile to use for a ROM: its own, or [generic] when the catalog does not know it. */
    fun detect(romBytes: ByteArray, dataDirectory: Path? = null): GameProfile =
        detect(header(romBytes), dataDirectory)

    /** The profile to use for an already-read [header] (what [scan] uses: it never reads a whole ROM). */
    fun detect(header: RomHeader, dataDirectory: Path? = null): GameProfile =
        find(header.gameCode, dataDirectory) ?: generic

    /** A ROM's declared identity, used to match it against the catalog. */
    data class RomHeader(val title: String, val gameCode: String)

    /** Reads only the ROM header of [path] (16 bytes), not the whole game. */
    fun headerOf(path: Path): RomHeader = header(Files.newInputStream(path).use { it.readNBytes(HEADER_BYTES) })

    /** The title (`0x00`, 12 bytes) and game code (`0x0C`, 4 bytes) a ROM declares in its header. */
    fun header(romBytes: ByteArray): RomHeader {
        fun ascii(offset: Int, length: Int): String = buildString {
            for (index in offset until minOf(offset + length, romBytes.size)) {
                val value = romBytes[index].toInt() and 0xFF
                if (value == 0) break
                append(if (value in 32..126) value.toChar() else ' ')
            }
        }
        return RomHeader(title = ascii(0x00, 12).trim(), gameCode = ascii(0x0C, 4).trim())
    }

    /** The `.nds` files in [directory], each with the profile it would use. Empty when it is missing. */
    fun scan(directory: Path, dataDirectory: Path? = null): List<DetectedRom> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.equals("nds", ignoreCase = true) }
                .map { path -> DetectedRom(path, detect(headerOf(path), dataDirectory)) }
                .sorted(compareBy({ it.profile.title }, { it.path.fileName.toString() }))
                .toList()
        }
    }

    /** User profiles from `<data>/games.json`, ignoring the file when it is absent or malformed. */
    fun userProfiles(dataDirectory: Path?): List<GameProfile> {
        val file = dataDirectory?.resolve(GAMES_FILE) ?: return emptyList()
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching { GamesFile.json.decodeFromString<GamesFile>(Files.readString(file)).games }
            .getOrDefault(emptyList())
    }

    /** Writes the built-in profiles to `<data>/games.json`, to edit or to add games to. */
    fun exportDefaults(dataDirectory: Path): Path {
        val file = dataDirectory.resolve(GAMES_FILE)
        Files.createDirectories(dataDirectory)
        Files.writeString(file, GamesFile.json.encodeToString(GamesFile(builtIns + generic)))
        return file
    }

    private const val GAMES_FILE = "games.json"

    /** The header is 16 bytes: a 12-byte title at `0x00` and a 4-byte game code at `0x0C`. */
    private const val HEADER_BYTES = 16

}
