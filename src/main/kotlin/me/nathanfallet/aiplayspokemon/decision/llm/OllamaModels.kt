package me.nathanfallet.aiplayspokemon.decision.llm

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches the list of models installed in a local Ollama instance (`GET /api/tags`).
 *
 * Used by the control panel so the user can pick from the models actually available on the
 * device instead of typing a model id blindly. All errors are swallowed: an empty list means
 * Ollama isn't running or isn't reachable.
 */
class OllamaModels private constructor(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
    companion object {
        private val defaultHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()

        fun create(baseUrl: String = "http://localhost:11434") = OllamaModels(baseUrl, defaultHttpClient)
    }

    /** Names of the models installed in Ollama, sorted alphabetically. Empty list when unreachable. */
    suspend fun listModels(): List<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/tags"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        return withContext(Dispatchers.Default) {
            runCatching {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) emptyList()
                else parseModelNames(response.body())
            }.getOrDefault(emptyList())
        }
    }

    private fun parseModelNames(body: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(body) as JsonObject
            val models = root["models"] as JsonArray
            models.mapNotNull {
                val name = (it as JsonObject)["name"]
                if (name is JsonPrimitive) name.content?.toString() else null
            }
        }.getOrDefault(emptyList())
            .sorted()
    }
}
