package me.nathanfallet.aiplayspokemon.decision.llm

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A minimal client for OpenAI-compatible chat completion APIs (e.g. LM Studio).
 *
 * Used when [OPENAI_BASE_URL] is configured: instead of Koog's OpenAILLMClient (which points at
 * api.openai.com and can't easily be repointed), we call `{baseUrl}/v1/chat/completions` directly.
 * All errors are wrapped in [IllegalStateException] so the caller can surface them.
 */
class OpenAiCompatibleClient private constructor(
    private val baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient,
) {
    companion object {
        private val defaultHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        /** Normalizes the base URL (strips trailing `/v1` if present) and creates the client. */
        fun create(baseUrl: String, apiKey: String) = OpenAiCompatibleClient(
            baseUrl.removeSuffix("/v1"),
            apiKey,
            defaultHttpClient,
        )
    }

    /** The OpenAI JSON of one message: a plain string, or text + labelled pictures when images are attached. */
    private fun ChatMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        if (images.isEmpty()) {
            put("content", JsonPrimitive(text))
        } else {
            put("content", JsonArray(buildList {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                })
                // Each picture is introduced by its own line, so the model knows which one it is looking at.
                for ((label, base64) in images) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("Image: $label"))
                    })
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put("image_url", buildJsonObject {
                            put("url", JsonPrimitive("data:image/png;base64,$base64"))
                        })
                    })
                }
            }))
        }
    }

    /** Result of a chat completion: the assistant's text and token usage. */
    data class ChatResult(
        val text: String,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    /**
     * One message of the conversation. [images] are the pictures a vision model is shown, each with the label the
     * text refers to ("top screen", "bottom screen"): the OpenAI format carries them as extra parts of the message's
     * content (a plain string when there are none), which is what makes a multimodal endpoint able to *see* the game.
     */
    data class ChatMessage(
        val role: String,
        val text: String,
        val images: List<Pair<String, String>> = emptyList(), // (label, base64 PNG)
    )

    /** Sends a chat completion request and returns the assistant's text and token usage. */
    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        // Headroom for hybrid-reasoning models (the qwen3 family): they may think before answering (see the
        // `chat_template_kwargs` below), and an answer cut off mid-generation (finish_reason=length, empty
        // content) fails the whole turn — the error this budget exists to prevent.
        maxTokens: Int = 2048,
        /** The app's "LLM thinks first" setting: off asks the model's template to answer directly. */
        thinking: Boolean = false,
    ): ChatResult {
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", JsonArray(messages.map { message -> message.toJson() }))
            put("temperature", JsonPrimitive(temperature))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("stream", JsonPrimitive(false))
            // Hybrid-reasoning models (qwen3 family...) think in `reasoning_content` before answering, and their
            // hidden thinking can burn the whole budget, leaving `content` empty (finish_reason=length). With the
            // app's "thinks first" off, `reasoning_effort: none` is what LM Studio honored for qwen3.5 (the
            // template kwarg is ignored there but read by llama.cpp-style templates: both are sent; endpoints
            // without either recognize none of these fields).
            if (!thinking) {
                put("reasoning_effort", JsonPrimitive("none"))
                put("chat_template_kwargs", buildJsonObject { put("enable_thinking", JsonPrimitive(false)) })
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            // Generous: a cold model load on the endpoint (LM Studio, Ollama) takes minutes, and timing out before
            // it finished turned that into a false "timeout" error in the panel. 300 s covers it; real failures
            // (unreachable host) surface at connect time anyway.
            .timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return withContext(Dispatchers.Default) {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("LM Studio returned HTTP ${response.statusCode()}: ${response.body()}")
            }
            val parsed = Json.parseToJsonElement(response.body()) as JsonObject
            val choices = parsed["choices"] as? JsonArray
                ?: throw IllegalStateException("LM Studio response missing 'choices'")
            val first = choices.firstOrNull()
                ?: throw IllegalStateException("LM Studio response has no choices")
            val choice = first as JsonObject
            val message = choice["message"] as? JsonObject
                ?: throw IllegalStateException("LM Studio choice missing 'message'")
            val content = message["content"]
            val contentStr = (if (content is JsonPrimitive) content.content?.toString() else null)
                ?: throw IllegalStateException("LM Studio message missing 'content'")
            if (contentStr.isBlank()) {
                // The typical hybrid-reasoning failure, said so it can be recognized in the panel: the model's
                // hidden thinking used every token of the budget and the answer itself never came.
                val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull ?: "?"
                throw IllegalStateException(
                    "the model answered nothing (finish_reason=$finish, max_tokens=$maxTokens): " +
                        if (finish == "length") "its hidden thinking used the whole budget before answering"
                        else "the endpoint returned an empty message",
                )
            }

            // LM Studio returns usage: { prompt_tokens, completion_tokens, total_tokens }
            val usage = parsed["usage"] as? JsonObject
            val inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0

            ChatResult(contentStr, inputTokens, outputTokens)
        }
    }
}
