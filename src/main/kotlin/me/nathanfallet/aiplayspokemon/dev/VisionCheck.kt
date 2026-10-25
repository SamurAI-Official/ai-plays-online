package me.nathanfallet.aiplayspokemon.dev

import kotlinx.coroutines.runBlocking
import dev.kotlinds.pokemonclient.console.Frame
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import me.nathanfallet.aiplayspokemon.decision.llm.OpenAiCompatibleClient
import me.nathanfallet.aiplayspokemon.emulator.Screens
import me.nathanfallet.aiplayspokemon.emulator.Screens.pngBase64
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Checks that the configured model really receives (and reads) the pictures the app attaches to every request.
 *
 * Two checks, both with an answer that is known in advance:
 *
 * 1. one picture — a solid red square, "what colour is it?";
 * 2. **both screens** — a DS-shaped frame whose top half is red and bottom half is blue, sent the way the app sends
 *    it (two labelled crops, see [Screens.picturesOf]), asking about each screen.
 *
 * That isolates the whole chain the agent depends on: the app's client, the endpoint, the model's vision, and the fact
 * that the two screens travel as two pictures the model can tell apart. If the first check fails, no prompt work will
 * make the agent see; if the second fails, the agent sees one screen and guesses the other.
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
    val client = OpenAiCompatibleClient.create(baseUrl ?: error("no base URL configured"), apiKey)

    val one = ask(client, modelId, listOf("image" to redSquare()), "What colour is the square in this image?")
    println("\n1. one picture: ${one ?: "no answer"}")
    println(
        if (one?.contains("red", ignoreCase = true) == true) "   → the model sees images: the screenshot reaches it"
        else "   → the model did NOT read the image: check that the loaded model has vision",
    )

    val twoScreens = ask(
        client,
        modelId,
        Screens.picturesOf(twoColourFrame()).map { (label, picture) -> label to picture.pngBase64() },
        "These are the two screens of a Nintendo DS, sent as two labelled pictures. What colour is the top screen, " +
            "and what colour is the bottom screen? Answer as: top=<colour> bottom=<colour>",
    )
    println("\n2. both screens: ${twoScreens ?: "no answer"}")
    val sawTop = twoScreens?.contains(Regex("top\\s*[=:]?\\s*red", RegexOption.IGNORE_CASE)) == true
    val sawBottom = twoScreens?.contains(Regex("bottom\\s*[=:]?\\s*blue", RegexOption.IGNORE_CASE)) == true
    println(
        if (sawTop && sawBottom) "   → both screens are seen, and told apart"
        else "   → the model did not distinguish the two screens (top should be red, bottom blue)",
    )
    println("\n== done ==")
}

/** Asks the model about [pictures], returning its text or null (with the failure printed). */
private suspend fun ask(
    client: OpenAiCompatibleClient,
    modelId: String,
    pictures: List<Pair<String, String>>,
    question: String,
): String? {
    val answer = runCatching {
        client.chat(
            model = modelId,
            messages = listOf(
                OpenAiCompatibleClient.ChatMessage(role = "system", text = "Answer briefly, nothing else."),
                OpenAiCompatibleClient.ChatMessage(role = "user", text = question, images = pictures),
            ),
            thinking = false,
        )
    }.onFailure { failure -> println("the request failed: ${failure.message}") }.getOrNull() ?: return null
    println("tokens: ${answer.inputTokens} in / ${answer.outputTokens} out")
    return answer.text.trim()
}

/** A 64x64 solid red PNG, base64 (no file, no resource: the picture is made here). */
private fun redSquare(): String = pngBase64(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB).also { image ->
    for (x in 0 until 64) for (y in 0 until 64) image.setRGB(x, y, Color.RED.rgb)
})

/** A DS-shaped frame (256x384) whose top screen is red and bottom screen is blue. */
private fun twoColourFrame(): Frame = Frame(256, 384, IntArray(256 * 384) { index ->
    if (index < 256 * 192) Color.RED.rgb else Color.BLUE.rgb
})

/** [image] as a base64 PNG. */
private fun pngBase64(image: BufferedImage): String {
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    return Base64.getEncoder().encodeToString(bytes)
}
