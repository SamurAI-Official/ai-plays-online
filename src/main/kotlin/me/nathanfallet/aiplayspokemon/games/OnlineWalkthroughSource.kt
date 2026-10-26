package me.nathanfallet.aiplayspokemon.games

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.decodeFromString

/** A source that can produce a [Walkthrough] for a game, e.g. an online endpoint. */
interface WalkthroughSource {
    /** Returns a walkthrough for [id]/[title], or null when none is available. */
    fun fetch(id: String, title: String): Walkthrough?

    /** Returns a walkthrough for [id], or null when none is available. */
    fun fetch(id: String): Walkthrough? = fetch(id, id)

    /** Alias of [fetch]. */
    fun get(id: String, title: String): Walkthrough? = fetch(id, title)

    /** Alias of [fetch]. */
    fun get(id: String): Walkthrough? = fetch(id)

    /** Alias of [fetch]. */
    fun load(id: String, title: String): Walkthrough? = fetch(id, title)

    /** Alias of [fetch]. */
    fun load(id: String): Walkthrough? = fetch(id)
}

/**
 * Fetches walkthrough JSON from a remote HTTP endpoint.
 *
 * The URL is `{baseUrl}/{id}.json` (trailing slash added if missing). Any failure (network, HTTP status,
 * malformed JSON) returns null instead of throwing, so the app can fall back to its profile prompt.
 */
class OnlineWalkthroughSource(
    baseUrl: URI = defaultBaseUrl(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : WalkthroughSource {

    constructor(url: String) : this(URI.create(url))

    private val base: URI = if (baseUrl.toString().endsWith("/")) baseUrl else URI.create(baseUrl.toString() + "/")

    override fun fetch(id: String, title: String): Walkthrough? = runCatching {
        val uri = base.resolve("$id.json")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val walkthrough = GamesFile.json.decodeFromString<Walkthrough>(response.body())
        if (walkthrough.id.isBlank()) return null
        // Keep the requested id authoritative, and record where the text came from.
        walkthrough.copy(id = id, provenance = Walkthrough.PROVENANCE_ONLINE)
    }.getOrNull()

    companion object {
        /** Base URL for online walkthroughs, overridable through env/config. */
        fun defaultBaseUrl(): URI {
            val fromEnv = System.getenv("AI_PLAYS_ONLINE_WALKTHROUGH_URL")
                ?: System.getenv("AI_PLAYS_ONLINE_WALKTHROUGHS_URL")
            val url = fromEnv?.takeIf { it.isNotBlank() }
                ?: "https://raw.githubusercontent.com/nathanfallet/ai-plays-online-walkthroughs/main/"
            return URI.create(url)
        }
    }
}
