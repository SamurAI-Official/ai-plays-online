package me.nathanfallet.aiplayspokemon.games

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The `<data>/games.json` shape: a list of profiles, merged over the built-ins by id. */
@Serializable
data class GamesFile(val games: List<GameProfile> = emptyList()) {
    companion object {
        /** Lenient on purpose: a user file only needs the fields it wants to change. */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
