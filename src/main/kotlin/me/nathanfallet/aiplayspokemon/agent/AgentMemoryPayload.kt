package me.nathanfallet.aiplayspokemon.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.nathanfallet.aiplayspokemon.games.GamesFile

/**
 * What the typed player remembers of a game, in the shape it writes to a session ([SessionStore]): where it has been,
 * what it read, the map it has explored, and where the story stood. The model's own note and the decisions taken are the
 * session's envelope ([StoredSession]), shared with every other player.
 *
 * What is **not** kept: the recent actions and how often each was tried in a situation (they are about the situation the
 * player was in, which is read live at the next launch) and the model's last thoughts (momentary). What is kept is what
 * stays true across launches — and the map is the part that makes resuming worth it: an agent that already knows the
 * walls it hit does not hit them again.
 */
@Serializable
data class TypedMemoryPayload(
    val places: List<Place> = emptyList(),
    val dialogues: List<String> = emptyList(),
    val maps: List<MapKnowledge> = emptyList(),
    val story: StoryKnowledge? = null,
) {

    /** One place visited, and how much of the run went into it. */
    @Serializable
    data class Place(val name: String, val decisions: Int = 1)

    /** One map as the player knows it: the terrain seen, the tiles walked, and the tiles a step was refused at. */
    @Serializable
    data class MapKnowledge(
        val id: Int,
        val location: String? = null,
        val map: String? = null,
        /** The terrain seen, as `"x,y"` → the symbol the map view shows ([MapView.terrain]). */
        val explored: Map<String, String> = emptyMap(),
        /** The tiles walked on. */
        val walked: List<String> = emptyList(),
        /** Tiles a step was refused at, and why (the blocked heatmap). */
        val blocked: Map<String, String> = emptyMap(),
    )

    /** Where the story stood when the session was written. */
    @Serializable
    data class StoryKnowledge(
        val goal: String? = null,
        val stepsDone: Int = 0,
        val alternatives: List<String> = emptyList(),
        val blocked: List<String> = emptyList(),
    )

    companion object {

        /** The payload of a typed memory, as a session holds it. */
        fun encode(payload: TypedMemoryPayload): JsonObject =
            GamesFile.json.encodeToJsonElement(TypedMemoryPayload.serializer(), payload).jsonObject

        /** What a session's payload says, or nothing when it is not a typed memory (another player wrote it). */
        fun decode(payload: JsonObject): TypedMemoryPayload =
            runCatching { GamesFile.json.decodeFromJsonElement(TypedMemoryPayload.serializer(), payload) }
                .getOrDefault(TypedMemoryPayload())
    }
}
