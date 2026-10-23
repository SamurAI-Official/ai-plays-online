package me.nathanfallet.aiplayspokemon.games

import kotlinx.serialization.Serializable

/** What an AI gets to play a game: the state read from RAM, or the screen. */
enum class AgentKind {
    /** A typed reader exists (pokemon-client): the AI gets the game's state, actions and menus. */
    TYPED,

    /** No reader yet: the AI only has the screen and the controller. */
    SCREEN,
}

/**
 * Everything the app needs to treat a ROM as a game: how to name it, what to tell the AI, and what
 * that AI can even see.
 *
 * Profiles are matched by the ROM's own header (see [GameCatalog.detect]), so a user's copy of a
 * known game is recognized whatever the file is called, and an unknown game falls back to
 * [GameCatalog.generic].
 *
 * @param id stable key, also used by user overrides in `<data>/games.json`
 * @param title how the app names the game (game picker, narration, run logs)
 * @param gameCode the four ASCII characters at ROM offset `0x0C`
 * @param romTitle the twelve ASCII characters at ROM offset `0x00` (informational)
 * @param agent what the AI sees: a typed RAM reader, or the screen
 * @param objective the per-game goal put in the prompt (what "playing well" means here)
 * @param conventions the per-game prompt section describing how the game behaves: controls, menus,
 *   saving, what moves the game forward
 * @param touch whether the stylus matters for this game
 * @param preferredCore `LibretroCoreSpec` name to start with, or null for the app default
 * @param tested what we verified ourselves, shown in the game picker and the README
 * @param walkthroughId walkthrough to use (`walkthroughs/<id>.json`), or null to use [id]
 */
@Serializable
data class GameProfile(
    val id: String,
    val title: String,
    val gameCode: String,
    val romTitle: String = "",
    val agent: AgentKind,
    val objective: String,
    val conventions: String,
    val touch: Boolean = true,
    val preferredCore: String? = null,
    val tested: String = "",
    val walkthroughId: String? = null,
) {
    /** The game's name without the region/revision suffix, for prompts. */
    val shortTitle: String get() = title.substringBefore(" (").substringBefore(" -")
}
