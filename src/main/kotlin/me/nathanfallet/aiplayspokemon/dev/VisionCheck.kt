package me.nathanfallet.aiplayspokemon.dev

import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import me.nathanfallet.aiplayspokemon.decision.llm.OpenAiCompatibleClient
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Checks that the configured model really receives (and reads) the screenshot the app attaches to every request.
 *
 * It sends a picture whose answer is known — a solid red square — and asks the model what colour it is. That isolates
 * the whole chain the agent depends on: the app's client, the endpoint, and the model's vision. If it answers
 * "red", a vision model is being fed images; if it answers anything else, it is not, and no amount of prompt work will
 * make the agent see the screen.
 *
 * ```bash
 * LLM_PROVIDER=openai LLM_MODEL=<a vision model> OPENAI_BASE_URL=http://host:1234 OPENAI_API_KEY=lm-studio \
 *   ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.VisionCheckKt
 * ```
 */
fun main(args: Array<String>) = runBlocking {
    val config = AppConfig.load(emptyArray())
    val provider = config.llmProvider
    val modelId = args.firstOrNull { !it.startsWith("--") } ?: config.llmModel(provider)
    val baseUrl = config.openAiBaseUrl ?: config.ollamaBaseUrl
    val apiKey = config.llmApiKey(provider) ?: ""
    println("== vision check: $provider · $modelId (base URL ${baseUrl ?: "provider default"}) ==")
    if (provider != LlmProvider.OPENAI && provider != LlmProvider.OLLAMA) {
        println("this check only speaks to OpenAI-compatible endpoints (LM Studio, Ollama); $provider uses another client")
        return@runBlocking
    }

    val image = redSquare()
    val client = OpenAiCompatibleClient.create(baseUrl ?: error("no base URL configured"), apiKey)
    val answer = runCatching {
        client.chat(
            model = modelId,
            messages = listOf(
                OpenAiCompatibleClient.ChatMessage(
                    role = "system",
                    text = "Answer with one word, nothing else.",
                ),
                OpenAiCompatibleClient.ChatMessage(
                    role = "user",
                    text = "What colour is the square in this image?",
                    imagePngBase64 = image,
                ),
            ),
            thinking = false,
        )
    }.onFailure { failure -> println("the request failed: ${failure.message}") }.getOrNull()

    if (answer == null) {
        println("== no answer: the endpoint or the model refused the request ==")
        return@runBlocking
    }
    println("answer: ${answer.text.trim()}")
    println("tokens: ${answer.inputTokens} in / ${answer.outputTokens} out")
    println(
        if (answer.text.contains("red", ignoreCase = true)) {
            "== the model sees images: the screenshot reaches it =="
        } else {
            "== the model did NOT read the image (it should have said red): check that the loaded model has vision =="
        },
    )
}

/** A 64x64 solid red PNG, base64 (no file, no resource: the picture is made here). */
private fun redSquare(): String {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until 64) for (y in 0 until 64) image.setRGB(x, y, Color.RED.rgb)
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    return Base64.getEncoder().encodeToString(bytes)
}
