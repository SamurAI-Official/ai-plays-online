package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.agent.ReaderPlayer
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import java.nio.file.Path

/**
 * Plays a game with the reader player, headlessly, with a **scripted** model: the point is to prove the loop
 * (observe → decide → act → see what changed) on a real ROM without an LLM, and to show what a reader-based player
 * can do.
 *
 * The scripted model is a route: it reads the reader's `screen` value out of the request and presses A while a menu
 * is up. It is not clever — it is exactly the kind of policy a model can now write, because the reader says which
 * screen the game is on.
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderPlayProbeKt \
 *   -PdevArgs="/path/to/game.nds|14|desmume"
 * ```
 *
 * Arguments: `rom | decisions (default 12) | core (default desmume)`, then `--model=route|llm`: `route` (the default)
 * plays with a scripted policy, `llm` asks the app's configured model (the environment the launcher sets:
 * `LLM_PROVIDER`, `LLM_MODEL`, `OPENAI_BASE_URL`, `OPENAI_API_KEY`), which is the real thing: a model that only ever
 * sees what the reader reads, playing the game.
 */
fun main(args: Array<String>) = runBlocking {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [decisions] [core] [--model=route|llm]"))
    val decisions = args.getOrNull(1)?.toIntOrNull() ?: 12
    val core = args.getOrNull(2) ?: "desmume"
    val config = AppConfig.load(emptyArray())
    val profile = ReaderCatalog.apply(
        GameCatalog.detect(GameCatalog.headerOf(rom), config.dataDirectory),
        config.dataDirectory,
    )
    val reader = ReaderCatalog.of(profile, config.dataDirectory)
        ?: error("${profile.title} has no reader: calibrate one first (docs/readers.md)")
    val emulator = ConsoleHost(
        LibretroCoreSpec.forRom(rom.toKotlinxPath(), config.emulatorCore ?: core),
        rom,
        config.dataDirectory,
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model: DecisionModel = if (args.any { it == "--model=llm" }) {
        val provider = config.llmProvider
        val modelId = config.llmModel(provider)
        println("model: $provider · $modelId (base URL ${config.openAiBaseUrl ?: "provider default"})")
        LlmDecisionModel(
            provider = provider,
            modelId = modelId,
            apiKey = config.llmApiKey(provider),
            thinking = config.llmThinking,
            baseUrl = config.openAiBaseUrl,
        )
    } else {
        RouteModel()
    }
    val player = ReaderPlayer(
        emulator = emulator,
        reader = reader,
        profile = profile,
        model = model,
        scope = scope,
        settings = { PlayerSettings() },
        walkthrough = me.nathanfallet.aiplayspokemon.games.WalkthroughState(),
    )

    println("== reader play probe: ${profile.title}, $decisions decisions on $core ==")
    println("reader: ${reader.observations.size} values, ${reader.progress.size} signals (${reader.provenance})")
    emulator.start()
    player.play()
    var lastSeen = 0
    while (player.state.value.decisions < decisions && player.state.value.error == null) {
        delay(200)
        val state = player.state.value
        if (state.decisions != lastSeen) {
            lastSeen = state.decisions
            val decision = state.lastDecision
            val summary = decision?.summary.orEmpty()
            val actions = decision?.actions?.joinToString("+").orEmpty()
            println("#${decision?.number}  $summary  →  $actions  →  ${decision?.change.orEmpty()}")
        }
    }
    player.pause()
    val inMission = reader.progress.firstOrNull { it.name == "in-mission" }
    val reached = inMission?.let { signal -> emulator.observe { memory -> signal.reached(memory) } }
    println("decisions: ${player.state.value.decisions}, error: ${player.state.value.error ?: "none"}")
    println("progress signal 'in-mission': $reached")
    emulator.systemLog.entries.value.filter { it.contains("screen read") }.takeLast(3).forEach { println("log: $it") }
    emulator.close()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    println("== done ==")
}

/**
 * A model that plays by the reader: `screen` says which screen is up, so press A while a menu is up, and otherwise
 * wait for the game to get on with it. This is the scripted stand-in for an LLM, and it is deliberately dumb.
 */
private class RouteModel : DecisionModel {

    override val name: String = "route (scripted)"
    override val generative: Boolean = false

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        val screen = request.state["reader"]?.jsonObject?.get("screen")?.jsonPrimitive?.content.orEmpty()
        val option = when {
            screen.contains("main menu") -> "press_a"
            screen.contains("single player") -> "press_a"
            screen.contains("select campaign") -> "press_a"
            screen.contains("select mission") -> "press_a"
            screen.contains("briefing") -> "press_a"
            screen.contains("in mission") -> "hold_right"
            else -> "wait"
        }
        return ChoiceResult(
            choice = option,
            probabilities = mapOf(option to 1.0),
            confidence = 1.0,
            model = name,
            inputTokens = 0,
        )
    }
}
