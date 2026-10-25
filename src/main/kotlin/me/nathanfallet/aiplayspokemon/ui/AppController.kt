package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.agent.DecisionNarrator
import me.nathanfallet.aiplayspokemon.agent.NarrationControl
import me.nathanfallet.aiplayspokemon.agent.AgentPlayer
import me.nathanfallet.aiplayspokemon.agent.ReaderPlayer
import me.nathanfallet.aiplayspokemon.agent.PokemonPlayer
import me.nathanfallet.aiplayspokemon.config.AppConfig
import dev.kotlinds.pokemonclient.console.Button
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.view.StateView
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.jev.JevClient
import me.nathanfallet.aiplayspokemon.decision.jev.JevDecisionModel
import me.nathanfallet.aiplayspokemon.agent.ControlMode
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.mcp.DeliveryLog
import me.nathanfallet.aiplayspokemon.mcp.GameMcpServer
import me.nathanfallet.aiplayspokemon.agent.GameSession
import me.nathanfallet.aiplayspokemon.decision.claudecode.ClaudeCodeDecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import me.nathanfallet.aiplayspokemon.decision.llm.OllamaModels
import me.nathanfallet.aiplayspokemon.emulator.ScreenWatcher
import me.nathanfallet.aiplayspokemon.emulator.Screens
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import java.nio.file.Path
import me.nathanfallet.aiplayspokemon.games.GameProfile
import me.nathanfallet.aiplayspokemon.games.MilestoneKind
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.AgentKind
import me.nathanfallet.aiplayspokemon.games.WalkthroughState
import me.nathanfallet.aiplayspokemon.games.WalkthroughCatalog

/**
 * Glue between the UI and the rest of the app: owns the [PokemonPlayer], turns keyboard events into
 * emulator input, and keeps a live [panel] of what the agents see, for display (even while a human plays).
 */
class AppController(
    val emulator: ConsoleHost,
    val game: PokemonGame?,
    private val config: AppConfig,
    private val scope: CoroutineScope,
    /** The profile of the ROM being played: names the game and carries its per-game prompt. */
    val profile: GameProfile = GameCatalog.generic,
) {
    private val humanButtons = mutableSetOf<Button>()

    private val _backend = MutableStateFlow(config.backend)

    /** Which kind of AI drives the player: Jev, or an LLM. */
    val backend: StateFlow<DecisionBackend> = _backend.asStateFlow()

    private val _llm = MutableStateFlow(LlmSettings(config.llmProvider, config.llmModel(config.llmProvider), config.llmThinking))

    /** The LLM used when [backend] is [DecisionBackend.LLM]. */
    val llm: StateFlow<LlmSettings> = _llm.asStateFlow()

    /** Base URL for OpenAI-compatible endpoints (e.g. LM Studio). */
    private val _openAiBaseUrl = MutableStateFlow(config.openAiBaseUrl)

    /** Base URL for OpenAI-compatible endpoints (e.g. LM Studio). */
    val openAiBaseUrl: StateFlow<String?> = _openAiBaseUrl.asStateFlow()

    /** Sets the OpenAI base URL and recreates the player. */
    fun setLlmBaseUrl(url: String?) {
        val trimmed = url?.trim()?.takeIf { it.isNotBlank() }
        config.saveLlmBaseUrl(trimmed)
        _openAiBaseUrl.value = trimmed
        if (_llm.value.provider == LlmProvider.OPENAI) createPlayer()
    }

    /** Client that talks to the local Ollama instance to list installed models. */
    private val ollamaService = OllamaModels.create()

    private val _ollamaModels = MutableStateFlow<List<String>>(emptyList())

    /** Models installed in the local Ollama instance (fetched via `GET /api/tags`). */
    val ollamaModels: StateFlow<List<String>> = _ollamaModels.asStateFlow()

    private val _ollamaModelsLoading = MutableStateFlow(false)

    /** Whether an Ollama model list refresh is currently in flight. */
    val ollamaModelsLoading: StateFlow<Boolean> = _ollamaModelsLoading.asStateFlow()

    private val _ollamaModelsError = MutableStateFlow<String?>(null)

    /** Error message from the last Ollama model list refresh, or null when there was none. */
    val ollamaModelsError: StateFlow<String?> = _ollamaModelsError.asStateFlow()

    private val _settings = MutableStateFlow(config.playerSettings)

    /** The experiment options of the player; changes apply from the next decision. */
    val settings: StateFlow<PlayerSettings> = _settings.asStateFlow()

    /** The voice saying decisions (state for the picker; the narrator reads it per line, no restart). */
    private val _narrationVoice = MutableStateFlow(config.narrationVoice)

    val narrationVoice: StateFlow<String> = _narrationVoice.asStateFlow()

    /**
     * What the app can read from this game: its calibrated reader (`readers/<id>.json`, see `docs/readers.md`), or
     * null when the game has none. A game with a reader is a typed game ([profile] was switched to
     * [AgentKind.TYPED] when it was opened), and its values are shown live in the panel and used to verify a
     * chapter whose milestone is `kind: memory`.
     */
    val reader: ReaderSpec? = ReaderCatalog.of(profile, config.dataDirectory)

    private val _readerValues = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The reader's observations as they read right now (name → value), for the panel. */
    val readerValues: StateFlow<Map<String, String>> = _readerValues.asStateFlow()

    /**
     * The narration control: the voice saying the decisions, and the lines it really said. It owns the
     * narrator, so the toggle, the voice and the readout all live in one testable place.
     */
    private val narration = NarrationControl(
        voice = { _narrationVoice.value },
        rate = { config.narrationRate },
        scope = scope,
        log = { line -> emulator.systemLog.add(line) },
        initialEnabled = config.playerSettings.narration,
    )

    /**
     * The lines the voice really said, newest first, with the voice that said them: the proof that a decision
     * was read out (what was said, and in which voice).
     */
    val spoken: StateFlow<List<String>> = narration.spoken

    private val _player = MutableStateFlow<AgentPlayer?>(null)

    /**
     * The autonomous player, or null when it can't be created: unsupported game, or the selected
     * model needs an API key that isn't configured yet.
     */
    val player: StateFlow<AgentPlayer?> = _player.asStateFlow()

    private val _mcp = MutableStateFlow<GameMcpServer?>(null)

    /** The MCP server when enabled: an external agent plays through it instead of our own loop. */
    val mcp: StateFlow<GameMcpServer?> = _mcp.asStateFlow()

    /**
     * Watches every frame and logs what happens (texts, battle messages, captures...), so agents get everything
     * shown since their last call. Null for unsupported games.
     */
    val recorder: Recorder? = game?.let { Recorder(it) }

    /**
     * The session the "What the AI sees" panel reads, with the same options as the agents' sessions (MCP, our loop) and
     * its own event cursor: what the panel shows is exactly what an agent would get, and it never takes the events
     * meant for the playing agent. Null for unsupported games.
     */
    private val panelSession: GameSession? = game?.let { session(it, confirmDelivery = false) }

    /** One reading of [panelSession] at a time (the panel's refresh and the F12 snapshot), off the UI thread. */
    private val panelLock = Mutex()

    private suspend fun <T> withPanelSession(block: suspend (GameSession) -> T): T? {
        val session = panelSession ?: return null
        return withContext(Dispatchers.Default) { panelLock.withLock { block(session) } }
    }

    private val _panel = MutableStateFlow<PanelView?>(null)

    /** What an agent would get from the game right now ([GameSession.describe]), refreshed a few times per second. */
    val panel: StateFlow<PanelView?> = _panel.asStateFlow()

    /**
     * The walkthrough of this game and the chapter being played. Typed games advance it from their own verified
     * progress; for the others the screen watcher or the panel does.
     */
    val walkthrough = WalkthroughState()

    /** Watches the frame for the current chapter's landmark (screen-only games). */
    val screenWatcher = ScreenWatcher()

    init {
        walkthrough.load(WalkthroughCatalog.of(profile, config.dataDirectory))
        emulator.musicDuringPauses = _settings.value.musicDuringPausesSettings
        recorder?.let { r ->
            emulator.frameListener = r::onFrame
            emulator.humanInputListener = { r.humanInput(emulator.status.value.frameCount) }
            // A state loaded (F-keys) is another game than a moment ago: the recorder starts learning it again. The
            // command line's start state is loaded before this recorder exists: it starts from it anyway.
            emulator.stateLoadedListener = r::saveLoaded
        }
        createPlayer()
        updateMcpServer()
        scope.launch { refreshPanel() }
        refreshOllamaModels()
    }

    fun updateSettings(transform: (PlayerSettings) -> PlayerSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        config.playerSettings = updated
        emulator.musicDuringPauses = updated.musicDuringPausesSettings
        // The setting and the narrator are one thing: turning the setting off closes the narrator, turning it
        // on lets the next decision be said (the voice itself is read per line).
        narration.setEnabled(updated.narration)
    }

    /**
     * The decision narrator while narration is on, created on first use (stopped when switched off).
     * The player asks for it per decision, so the toggle needs no new player.
     */
    private fun narrator(): DecisionNarrator? = narration.narrator()

    /**
     * The voice narrating decisions, or null when narration is off: the dropdown's single choice (off, or a voice).
     * Picking a voice turns narration on (and says the choice aloud, so it is heard at once).
     */
    fun setNarrationVoice(voice: String?) {
        if (voice == null) {
            updateSettings { it.copy(narration = false) }
            return
        }
        config.narrationVoice = voice
        _narrationVoice.value = voice
        narration.setVoice(voice)
        if (!_settings.value.narration) updateSettings { it.copy(narration = true) }
    }

    /**
     * Explicit on/off toggle for the Whisper speech control: turns narration on or off regardless of the voice
     * dropdown, following the app's setting (the saved [config.playerSettings.narration] flag). Off closes the
     * narrator, on lets the next decision be said (the voice itself is read per line).
     */
    fun setNarrationEnabled(enabled: Boolean) {
        updateSettings { it.copy(narration = enabled) }
    }

    /** Runs the MCP server only while the MCP tab is selected (one player at a time). */
    private fun updateMcpServer() {
        val wanted = _backend.value == DecisionBackend.MCP && game != null
        val running = _mcp.value
        if (wanted && running == null) {
            val session = session(game, confirmDelivery = true)
            val server = GameMcpServer(session, emulator, config.mcpPort, pauseWhileThinking = { _settings.value.pauseWhileThinking },
                reasoningRequired = { _settings.value.reasoning },
                deliveryLog = DeliveryLog.startingNow(config.dataDirectory.resolve("runs")))
            server.start()
            _mcp.value = server
        } else if (!wanted && running != null) {
            running.stop()
            _mcp.value = null
        }
    }

    /** A session of [game] with the app's current options (read at each call, so changes apply at once). */
    private fun session(game: PokemonGame, confirmDelivery: Boolean) =
        GameSession(emulator, game, recorder!!, options = { _settings.value.agentOptions }, confirmDelivery = confirmDelivery)

    /** Switches the decision model: the current player is stopped and replaced. */
    fun selectBackend(backend: DecisionBackend) {
        if (backend == _backend.value) return
        _backend.value = backend
        config.backend = backend
        if (backend == DecisionBackend.MCP && _settings.value.mode == ControlMode.HYBRID) {
            updateSettings { it.copy(mode = ControlMode.ASSISTED) } // hybrid needs our own loop
        }
        createPlayer()
        updateMcpServer()
    }

    /** Switches the LLM provider (and loads the model last used with it). */
    fun selectLlmProvider(provider: LlmProvider) {
        config.llmProvider = provider
        _llm.value = _llm.value.copy(provider = provider, model = config.llmModel(provider))
        createPlayer()
        if (provider == LlmProvider.OLLAMA) refreshOllamaModels()
    }

    fun setLlmModel(model: String) {
        if (model.isBlank()) return
        config.saveLlmModel(_llm.value.provider, model.trim())
        _llm.value = _llm.value.copy(model = model.trim())
        createPlayer()
    }

    /** Lets reasoning models think before each press (smarter, slower). */
    fun setLlmThinking(thinking: Boolean) {
        config.llmThinking = thinking
        _llm.value = _llm.value.copy(thinking = thinking)
        createPlayer()
    }

    /** Fetches the list of models installed in the local Ollama instance. Safe to call repeatedly. */
    fun refreshOllamaModels() {
        scope.launch {
            _ollamaModelsLoading.value = true
            _ollamaModelsError.value = null
            try {
                _ollamaModels.value = ollamaService.listModels()
            } catch (e: Exception) {
                _ollamaModelsError.value = e.message
            } finally {
                _ollamaModelsLoading.value = false
            }
        }
    }

    /** Port of the MCP server: one app per agent, each on its own port, to compare agents side by side. */
    val mcpPort: Int get() = config.mcpPort

    /** Changes the MCP server port and restarts the server on it. */
    fun setMcpPort(port: Int) {
        if (port !in 1024..65535 || port == config.mcpPort) return
        config.mcpPort = port
        _mcp.value?.stop()
        _mcp.value = null
        updateMcpServer()
    }

    /** Stores the API key of the current model (Jev or LLM provider) in the config file. */
    fun setApiKey(apiKey: String) {
        if (apiKey.isBlank()) return
        when (_backend.value) {
            DecisionBackend.JEV -> config.jevApiKey = apiKey.trim()
            DecisionBackend.LLM -> config.saveLlmApiKey(_llm.value.provider, apiKey.trim())
            DecisionBackend.MCP -> return // the external agent brings its own model
        }
        createPlayer()
    }

    /** What the API key form should ask for, or null when the current model is ready. */
    val missingApiKey: String?
        get() = when (_backend.value) {
            DecisionBackend.JEV -> "TypeSafe (console.typesafe.ai/keys)".takeIf { _player.value == null }
            DecisionBackend.LLM -> _llm.value.provider.takeIf { it.needsApiKey && config.llmApiKey(it) == null }?.label
            DecisionBackend.MCP -> null
        }

    /** Pins the chapter the run should work on (the panel's picker), or null to let the app choose. */
    fun selectChapter(id: String?) {
        walkthrough.select(id, "chosen in the panel")
    }

    /** Whether the app may advance the chapter on its own (typed progress or a screen landmark). */
    fun setWalkthroughAutoAdvance(enabled: Boolean) {
        walkthrough.autoAdvance = enabled
    }

    /** Copies the walkthrough of this game into `<data>/walkthroughs/`, to edit or translate it. Returns the file. */
    fun exportWalkthrough(): Path? =
        walkthrough.walkthrough.value?.let { WalkthroughCatalog.export(it.id, config.dataDirectory) }

    /** Where the text of the current walkthrough came from, as a sentence for the panel. */
    fun walkthroughProvenance(): String? = walkthrough.walkthrough.value?.let { WalkthroughCatalog.provenanceNote(it) }

    private fun createPlayer() {
        _player.value?.pause()
        val game = game
        val model = createModel(_backend.value, emulator)
        val reader = reader
        _player.value = when {
            game != null && model != null -> {
                // In hybrid mode, the LLM settings define the planner (it may be the same as the decider).
                PokemonPlayer(
                    emulator, game, recorder!!, model,
                    planner = createModel(DecisionBackend.LLM, emulator),
                    scope = scope,
                    settings = { _settings.value },
                    runsDirectory = config.dataDirectory.resolve("runs"),
                    profile = profile,
                    walkthrough = walkthrough,
                    narrator = { narrator() },
                )
            }
            // A game whose reader was calibrated (readers/<id>.json): the same loop, its state is what the reader
            // reads and its actions are the console's own (see docs/readers.md).
            reader != null && model != null -> ReaderPlayer(
                emulator = emulator,
                reader = reader,
                profile = profile,
                model = model,
                scope = scope,
                settings = { _settings.value },
                walkthrough = walkthrough,
                runsDirectory = config.dataDirectory.resolve("runs"),
                narrator = { narrator() },
            )
            else -> null
        }
    }

    private fun createModel(backend: DecisionBackend, emulator: ConsoleHost): DecisionModel? = when (backend) {
        DecisionBackend.JEV -> {
            // TypeSafe's cloud needs a key; a custom (e.g. local) endpoint may not.
            if (config.jevApiKey == null && config.jevEndpoint == JevClient.DEFAULT_ENDPOINT) null
            else JevDecisionModel(JevClient(config.jevApiKey, config.jevModel, config.jevEndpoint))
        }

        DecisionBackend.LLM -> {
            val (provider, model, thinking) = _llm.value
            val apiKey = config.llmApiKey(provider)
            when {
                provider == LlmProvider.CLAUDE_CODE -> ClaudeCodeDecisionModel(model, effort = if (thinking) "high" else "low")
                provider.needsApiKey && apiKey == null -> null
                // Both OpenAI-compatible endpoints (LM Studio, and Ollama's own /v1 API) go through our client:
                // one request timeout (300 s — a cold model load is not an error) and the same error handling.
                else -> LlmDecisionModel(
                    provider, model, apiKey, thinking,
                    when (provider) {
                        LlmProvider.OPENAI -> config.openAiBaseUrl
                        LlmProvider.OLLAMA -> config.ollamaBaseUrl
                        else -> null
                    },
                    // Both screens, cropped and labelled: a DS game uses them both, and one combined picture would
                    // give each of them half the pixels (see emulator/Screens.kt).
                    screens = { emulator.frames.value?.let(Screens::picturesOf).orEmpty() },
                )
            }
        }

        DecisionBackend.MCP -> null // the external agent decides
    }

    /** Reads the game a few times per second so the panel shows what an agent would get right now. */
    private suspend fun refreshPanel() {
        if (panelSession == null) return
        while (scope.isActive) {
            _panel.value = runCatching { withPanelSession { it.describeWithState() } }.getOrNull()?.let { (state, view) -> PanelView(StateView.summary(state), view) }
            refreshReader()
            watchScreen()
            delay(250)
        }
    }

    /** Reads the game's reader, if it has one: what the panel shows live, and what [watchScreen] checks. */
    private suspend fun refreshReader() {
        val reader = reader ?: return
        if (reader.observations.isEmpty()) return
        _readerValues.value = runCatching {
            emulator.observe { memory -> ReaderCatalog.observe(reader, memory) }
        }.getOrElse { _readerValues.value }
    }

    /**
     * Looks at the frame the console just produced for the current chapter's landmark (a few times a second, see
     * [refreshPanel]). When it matches, the chapter is reached and the walkthrough moves on — this is how a game we
     * only see the screen of advances on its own. A chapter whose milestone is `kind: memory` is checked against the
     * game's reader instead: its progress is the game's own state, not a picture of it. Typed games never get here
     * (their own progress is exact).
     */
    private suspend fun watchScreen() {
        if (!walkthrough.autoAdvance) return
        val chapter = walkthrough.chapter ?: return
        val milestone = chapter.milestone ?: return
        if (milestone.kind == MilestoneKind.MEMORY) {
            val signal = reader?.progress?.firstOrNull { it.name == milestone.signal } ?: return
            if (emulator.observe { memory -> signal.reached(memory) }) {
                walkthrough.advance("the game's own state (${signal.name})")
            }
            return
        }
        if (screenWatcher.check(emulator.frames.value, chapter)) {
            walkthrough.advance("seen on screen")
        }
    }

    /**
     * Saves the current RAM and what the agents see ([GameSession.describe], as the panel shows it) into
     * `<data>/snapshots/`, to debug or extend the game reader on a precise situation (the RAM can be loaded back with
     * `RamMemory`).
     */
    private suspend fun saveSnapshot() {
        val ram = emulator.readMainRam()
        val directory = Files.createDirectories(config.dataDirectory.resolve("snapshots"))
        val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        Files.write(directory.resolve("$name.ram"), ram)
        withPanelSession { it.describe() }?.let { view ->
            Files.writeString(directory.resolve("$name.json"), prettyJson.encodeToString(JsonObject.serializer(), view))
        }
        println("Snapshot saved: ${directory.resolve(name)}")
    }

    /** Handles a window key event. Returns true when the key was used. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        KeyboardMapping.buttons[event.key]?.let { button ->
            if (down) humanButtons += button else humanButtons -= button
            emulator.setButtons(humanButtons.toSet())
            return true
        }
        if (!down) return false
        KeyboardMapping.stateSlots[event.key]?.let { slot ->
            scope.launch { if (event.isShiftPressed) emulator.saveState(slot) else emulator.loadState(slot) }
            return true
        }
        when (event.key) {
            Key.Spacebar -> player.value?.toggle()
            Key.P -> togglePause()
            Key.F -> emulator.setFastForward(!emulator.status.value.fastForward)
            Key.M -> emulator.setMuted(!emulator.status.value.muted)
            Key.F12 -> scope.launch { saveSnapshot() }
            else -> return false
        }
        return true
    }

    fun togglePause() = emulator.setUserPaused(emulator.status.value.running)

    /**
     * The window lost focus: key and touch releases made while unfocused never arrive, which would keep a button
     * held (the game walking on its own) and the screen touched — both make every agent action refused as "human
     * driving", the agent then caught waiting. So release everything the human held, and say why in the log.
     */
    fun onFocusLost() {
        humanButtons.clear()
        emulator.releaseHumanInputs("window focus lost")
    }

    private companion object {
    }
}
/**
 * What the control panel shows of the game at one moment: what an agent gets ([GameSession.describe], the library's
 * [dev.kotlinds.pokemonclient.view.AgentView]) and a one-line [summary] of it.
 */
data class PanelView(val summary: String, val view: JsonObject)

/** Which LLM to use: a provider, one of its model ids, and whether it thinks before answering. */
data class LlmSettings(val provider: LlmProvider, val model: String, val thinking: Boolean)

/** JSON printed for people (the panel, the snapshots): indented. */
internal val prettyJson = Json { prettyPrint = true }
