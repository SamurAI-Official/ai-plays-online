package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.nathanfallet.aiplayspokemon.agent.ReaderAffordances
import me.nathanfallet.aiplayspokemon.agent.ReaderMemory
import me.nathanfallet.aiplayspokemon.agent.ReaderMemoryPayload
import me.nathanfallet.aiplayspokemon.agent.SessionStore
import me.nathanfallet.aiplayspokemon.agent.ReaderPlayer
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.StepCondition
import me.nathanfallet.aiplayspokemon.decision.ThenStep
import me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.sha256OfRom
import java.nio.file.Files
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
 *
 * `--memory` uses the player's own memory of the game ([ReaderMemory]): what a previous run learned is resumed, and
 * what this one learns is written to `<data>/sessions/<id>.json` — run it twice to see the difference (the second
 * run starts knowing the screens, what it tried on them, and the note the model wrote for itself).
 */
fun main(args: Array<String>) = runBlocking {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [decisions] [core] [--model=route|llm] [--memory]"))
    val decisions = args.getOrNull(1)?.toIntOrNull() ?: 12
    val core = args.getOrNull(2) ?: "desmume"
    val config = AppConfig.load(emptyArray())
    val profile = ReaderCatalog.apply(
        GameCatalog.detect(GameCatalog.headerOf(rom), config.dataDirectory),
        config.dataDirectory,
    )
    // A game with no reader is playable too, and this is the path the app takes for it: an empty reader, so the same
    // loop runs and the screen viewer (with the picture naming the screens, see ScreenState.identity) does the seeing.
    // Without this the screen-only path — the one a ROM with no calibrated reader takes — could not be driven headlessly
    // at all, which is how a loop in it went unnoticed.
    val reader = ReaderCatalog.of(profile, config.dataDirectory)
        ?: ReaderSpec(id = profile.id, provenance = "screen")
    val emulator = ConsoleHost(
        LibretroCoreSpec.forRom(rom.toKotlinxPath(), config.emulatorCore ?: core),
        rom,
        config.dataDirectory,
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // --memory: pick up what a previous run learned about this game, and write down what this one learns.
    val useMemory = args.any { it == "--memory" }
    // --goal=<screen>: ask the map for a way to this screen instead of the one the reader's own signals aim at. It is
    // how the route through the menus can be shown for a game whose reader aims at a screen this run never reaches.
    val goalOverride = args.firstOrNull { it.startsWith("--goal=") }?.substringAfter("--goal=")
    val romSha256 = if (useMemory) sha256OfRom(Files.readAllBytes(rom)) else ""
    val known = if (useMemory) SessionStore.of(profile.id, config.dataDirectory, romSha256) else null
    known?.let { session ->
        val stored = ReaderMemoryPayload.decode(session.payload)
        println(
            "memory: resuming ${session.decisions} decisions over ${stored.screens.size} screens" +
                (if (session.note != null) ", with its own note: \"${session.note}\"" else ""),
        )
    }
    val memory = known?.let(ReaderMemory::of) ?: ReaderMemory()
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
        memory = memory,
    )
    println("== reader play probe: ${profile.title}, $decisions decisions on $core ==")
    println("reader: ${reader.observations.size} values, ${reader.progress.size} signals (${reader.provenance})")
    emulator.start()
    player.play()
    var lastSeen = 0
    var firstRequest: kotlinx.serialization.json.JsonObject? = null
    while (player.state.value.decisions < decisions && player.state.value.error == null) {
        delay(200)
        val state = player.state.value
        if (firstRequest == null) firstRequest = state.lastRequestState
        if (state.decisions != lastSeen) {
            lastSeen = state.decisions
            val decision = state.lastDecision
            val summary = decision?.summary.orEmpty()
            val actions = decision?.actions?.joinToString("+").orEmpty()
            val stopped = decision?.stopped?.let { reason -> "  [plan stopped: $reason]" }.orEmpty()
            println("#${decision?.number}  $summary  →  $actions  →  ${decision?.change.orEmpty()}$stopped")
        }
    }
    player.pause()
    val inMission = reader.progress.firstOrNull { it.name == "in-mission" }
    val reached = inMission?.let { signal -> emulator.observe { memory -> signal.reached(memory) } }
    println("decisions: ${player.state.value.decisions}, error: ${player.state.value.error ?: "none"}")
    println("progress signal 'in-mission': $reached")
    // The `memory` block of the last request is what the model was told about its own past: proof that what an earlier
    // run learned is in the prompt, and not only on disk. `affordances` is the same for the map of the game — printed
    // for the first request (where the map matters most) and for the last one.
    player.state.value.lastRequestState?.get("memory")?.let { block -> println("memory in the last request: $block") }
    firstRequest?.get("affordances")?.let { block -> println("affordances at the start of the run: $block") }
    player.state.value.lastRequestState?.get("affordances")?.let { block ->
        println("affordances in the last request: $block")
    }
    if (useMemory) {
        val file = SessionStore.write(memory.toSession(profile.id, romSha256), config.dataDirectory)
        println("memory: wrote $file (${memory.totalDecisions} decisions over ${memory.screensVisited.size} screens)")
        memory.screensVisited.take(6).forEach { visit -> println("memory:   ${visit.screen}: ${visit.decisions}") }
        println("memory: note now: ${memory.note ?: "none"}")
    }
    // What the map of the game knows now, and (with --goal) the route it would give from where the run started: the
    // shortest known way to a screen, which is what a model gets to plan with.
    val knownMap = memory.known()
    println("affordances: ${knownMap.moves.size} known transitions over ${knownMap.screens.size} screens")
    knownMap.moves.take(10).forEach { move -> println("affordances:   ${move.from} -${move.action}-> ${move.to} (${move.times})") }
    goalOverride?.let { goal ->
        val from = firstRequest?.get("reader")?.jsonObject?.get("screen")?.jsonPrimitive?.content.orEmpty()
        println("affordances: from '$from' to '$goal': ${ReaderAffordances.describe(knownMap, from, goal)}")
    }
    emulator.systemLog.entries.value.filter { it.contains("screen read") }.takeLast(3).forEach { println("log: $it") }
    emulator.close()
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    println("== done ==")
}

/**
 * A model that plays by the reader: `screen` says which screen is up, so press A while a menu is up, and otherwise
 * wait for the game to get on with it. This is the scripted stand-in for an LLM, and it is deliberately dumb.
 *
 * It also writes itself a note on its first decision, like a model with `modelNotes` on does: that is what proves the
 * note survives a session (the next run's memory block carries it back). On a screen it does not know, it presses A
 * (the universal way on): that is also what makes it explore — and what fills the known map of the game.
 */
private class RouteModel : DecisionModel {

    override val name: String = "route (scripted)"
    override val generative: Boolean = false
    private var decisions = 0

    override suspend fun choose(request: ChoiceRequest): ChoiceResult {
        val screen = request.state["reader"]?.jsonObject?.get("screen")?.jsonPrimitive?.content.orEmpty()
        val option = when {
            screen.contains("main menu") -> "press_a"
            screen.contains("single player") -> "press_a"
            screen.contains("select campaign") -> "press_a"
            screen.contains("select mission") -> "press_a"
            screen.contains("briefing") -> "press_a"
            screen.contains("in mission") -> "hold_right"
            // Nothing known about this screen: A is the way most games move on, and trying it is what fills the map.
            else -> "press_a"
        }
        val note = if (decisions++ == 0) "my note: press A through the menus, then hold right in a mission" else null
        // A plan, not just a sequence: press A, then press A again **if** the game did what this screen promised. The
        // second entry is on purpose a condition the game does not satisfy (the campaign screen leads elsewhere), so a
        // run of the probe shows both a step that runs and a plan that stops.
        val then = when {
            screen.contains("main menu (19)") -> listOf(
                ThenStep("press_a", StepCondition(isText = "single player menu (18)")),
            )
            screen.contains("select campaign (64)") -> listOf(
                ThenStep("press_a", StepCondition(isText = "in mission (84)")),
            )
            else -> emptyList()
        }
        return ChoiceResult(
            choice = option,
            probabilities = mapOf(option to 1.0),
            confidence = 1.0,
            model = name,
            note = note,
            then = then,
            inputTokens = 0,
        )
    }
}
