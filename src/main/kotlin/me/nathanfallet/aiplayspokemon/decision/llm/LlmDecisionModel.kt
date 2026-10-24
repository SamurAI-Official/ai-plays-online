package me.nathanfallet.aiplayspokemon.decision.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import dev.kotlinds.pokemonclient.console.Frame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * [DecisionModel] backed by a generative LLM (OpenAI, Anthropic, Gemini through OpenRouter, a local
 * Ollama model...), called through Koog.
 *
 * The LLM gets the same state, instructions and options as any other decision model, and answers in
 * the [LlmAnswerFormat] (reasoning, optional note and sequence, then the choice). An invalid answer
 * is sent back to the model once with the error, never guessed. An LLM gives no calibrated
 * probability: the choice gets 1, confidence is null.
 *
 * The latest emulator frame is attached as a PNG screenshot so the model can see the game screen.
 */
class LlmDecisionModel(
    private val provider: LlmProvider,
    private val modelId: String,
    apiKey: String?,
    private val thinking: Boolean = false,
    private val baseUrl: String? = null,
    /** The latest emulator frame as a screenshot, injected so the model sees the current screen. */
    private val latestFrame: () -> Frame? = { null },
) : DecisionModel {

    override val name = "${provider.label} · $modelId${if (thinking) " (thinking)" else ""}"

    private val executor: PromptExecutor = provider.executor(apiKey)
    private val model = provider.model(modelId)

    /** Custom client for OpenAI-compatible endpoints (e.g. LM Studio). Null = use Koog's client. */
    private val compatibleClient: OpenAiCompatibleClient? = baseUrl?.let {
        OpenAiCompatibleClient.create(it, apiKey ?: "")
    }

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        val state = "State:\n${json.encodeToString(JsonObject.serializer(), request.state)}"
        var inputTokens = 0
        var outputTokens = 0
        var previous: Pair<String, String>? = null // (invalid answer, error) to correct on retry
        // The screenshot is read once per choose() call: the model sees the screen it's deciding on.
        val screenshotBase64 = latestFrame.invoke()?.let { encodeFrameToPngBase64(it) }
        repeat(MAX_ATTEMPTS) {
            val decisionPrompt = prompt("decision", params = provider.params(thinking)) {
                system(LlmAnswerFormat.instructions(request))
                user {
                    text(state)
                    screenshotBase64?.let {
                        image(
                            AttachmentSource.Image(
                                AttachmentContent.Binary.Base64(it),
                                "png",
                                "image/png",
                                "screenshot.png"
                            )
                        )
                    }
                }
                previous?.let { (answer, error) ->
                    assistant(answer)
                    user("Invalid answer: $error Reply again with only the JSON object.")
                }
            }
            val text = try {
                if (compatibleClient != null) {
                    // OpenAI-compatible endpoint (e.g. LM Studio): call it directly. The messages are built here, not
                    // taken from the Koog prompt, because the screenshot has to travel with them: a vision model only
                    // sees the screen if the image is a part of the message (see OpenAiCompatibleClient.ChatMessage).
                    val messages = buildList {
                        add(OpenAiCompatibleClient.ChatMessage("system", LlmAnswerFormat.instructions(request)))
                        add(OpenAiCompatibleClient.ChatMessage("user", state, imagePngBase64 = screenshotBase64))
                        previous?.let { (answer, error) ->
                            add(OpenAiCompatibleClient.ChatMessage("assistant", answer))
                            add(
                                OpenAiCompatibleClient.ChatMessage(
                                    "user",
                                    "Invalid answer: $error Reply again with only the JSON object.",
                                ),
                            )
                        }
                    }
                    val result = compatibleClient.chat(modelId, messages, thinking = thinking)
                    inputTokens += result.inputTokens
                    outputTokens += result.outputTokens
                    result.text
                } else {
                    val response = try {
                        executor.execute(decisionPrompt, model)
                    } catch (error: Exception) {
                        throw classify(error)
                    }
                    val meta = response.metaInfo
                    inputTokens += meta.inputTokensCount ?: 0
                    outputTokens += meta.outputTokensCount ?: 0
                    response.textContent()
                }
            } catch (error: Exception) {
                throw classify(error)
            }
            try {
                val answer = LlmAnswerFormat.parse(text, request)
                return answer.toChoiceResult(request, model = modelId, inputTokens = inputTokens, outputTokens = outputTokens)
            } catch (invalid: LlmAnswerFormat.InvalidAnswer) {
                previous = text to (invalid.message ?: "invalid answer")
            }
        }
        throw DecisionException("$name gave no valid answer: ${previous?.second}", retryable = true)
    }

    /** Encodes a [Frame]'s ARGB pixels as a base64 PNG data URL. */
    private fun encodeFrameToPngBase64(frame: Frame): String {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    internal companion object {
        const val MAX_ATTEMPTS = 2
        private val json = Json { explicitNulls = false }

        /** Authentication and request errors won't fix themselves: stop instead of retrying forever. */
        fun classify(error: Exception): Exception {
            if (error is DecisionException) return error
            val message = error.message.orEmpty()
            val fatal = listOf("401", "403", "invalid x-api-key", "invalid_api_key", "authentication", "404")
                .any { message.contains(it, ignoreCase = true) }
            return DecisionException(message.ifEmpty { error.toString() }, retryable = !fatal, cause = error)
        }
    }
}
