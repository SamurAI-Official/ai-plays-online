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
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.ReaderCalibrator
import me.nathanfallet.aiplayspokemon.emulator.ReaderLearningStore
import me.nathanfallet.aiplayspokemon.emulator.ReaderMerge
import me.nathanfallet.aiplayspokemon.emulator.ReaderVerification
import me.nathanfallet.aiplayspokemon.emulator.ReaderVerifier
import me.nathanfallet.aiplayspokemon.games.ProgressSignal
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter
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
    /** SHA-256 of the ROM: pins what the app learns (a reader's evidence) to one revision of one game. */
    private val romSha256: String = "",
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
     * What the app can read from this game: its reader (`readers/<id>.json`, see `docs/readers.md`), or null when the
     * game has none. A game with a reader is a typed game ([profile] was switched to [AgentKind.TYPED] when it was
     * opened), and its values are shown live in the panel and used to verify a chapter whose milestone is `kind:
     * memory`. A reader the app learns itself ([calibrator]) appears here as soon as it is written.
     */
    private val _reader = MutableStateFlow(ReaderCatalog.of(profile, config.dataDirectory))

    /** The game's reader, live: the panel, the prompt and the memory milestones read it from here. */
    val reader: StateFlow<ReaderSpec?> = _reader.asStateFlow()

    private val _readerValues = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The reader's observations as they read right now (name → value), for the panel. */
    val readerValues: StateFlow<Map<String, String>> = _readerValues.asStateFlow()

    /**
     * What the reader's values really did while the game was played ([ReaderVerifier]): a value that claims to name
     * screens has to read **one value per screen**, or the agent is told it is on a screen it has left. The panel shows
     * it, and a reader of ours (learned, or one already in the user's own `<data>/readers`) gets the verdicts written
     * back into it — a reader that ships with the app is only checked: writing it would shadow the shipped file with a
     * user copy.
     */
    private val _verification = MutableStateFlow<ReaderVerification?>(null)

    /** The check of this game's reader, live, or null while nothing has been checked yet. */
    val verification: StateFlow<ReaderVerification?> = _verification.asStateFlow()

    /** The check running against the reader in [reader], rebuilt when the reader itself changes (a learned one grows). */
    private var verifier: ReaderVerifier? = null

    /** When the check's verdicts were last written (a write is a file: at most every few seconds, see [verifyReader]). */
    private var lastVerificationWriteMillis = 0L

    /**
     * The learner: a game with no reader teaches the app one while it is played (see [ReaderCalibrator]). It runs when
     * the game has no reader at all, **and** keeps running for a reader the app learned itself (a `learned while
     * playing` one): a later session extends that draft instead of freezing it. A reader somebody wrote (shipped,
     * calibrated by hand) is left alone.
     */
    private val calibrator = ReaderCalibrator().takeIf {
        val reader = _reader.value
        reader == null || reader.isLearned
    }

    private val _learning = MutableStateFlow<ReaderCalibrator.Progress?>(null)

    /** How far the app has got learning this game's reader, or null when it is not learning one. */
    val learning: StateFlow<ReaderCalibrator.Progress?> = _learning.asStateFlow()

    /** The looks at the game the reader was last written at, so a later session extends it as it learns more. */
    private var readerWrittenAtSamples = -1

    /**
     * Which value of the reader's `screen` was being read when a chapter's landmark was seen, and how often in a row:
     * the evidence behind a `kind: memory` progress signal ([learnedProgress]).
     */
    private val landmarkScreens = HashMap<String, Pair<Long, Int>>()

    /** When the learner's evidence was last written (writes are a few seconds apart at most, see [learnReader]). */
    private var lastLearningWriteMillis = 0L

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
        // A session that ended before a reader was ready must not start over: the learner's evidence is picked up where
        // it stopped (everything from the parts of memory already examined; the part in progress is re-baselined).
        calibrator?.let { learner ->
            ReaderLearningStore.of(profile.id, config.dataDirectory)
                ?.takeIf { it.usable && it.matches(romSha256) }
                ?.let { evidence ->
                    learner.restore(evidence)
                    _learning.value = learner.progress()
                    emulator.systemLog.add(
                        "resuming the reader for ${profile.title}: ${evidence.samples} looks, " +
                            "${evidence.screens.size} screens, part ${evidence.chunk + 1}",
                    )
                    println("ai-plays-online: resuming the reader for ${profile.title} (${evidence.samples} looks)")
                }
        }
        emulator.musicDuringPauses = _settings.value.musicDuringPausesSettings
        recorder?.let { r ->
            emulator.frameListener = r::onFrame
            emulator.humanInputListener = { r.humanInput(emulator.status.value.frameCount) }
            // A state loaded (F-keys) is another game than a moment ago: the recorder starts learning it again. The
            // command line's start state is loaded before this recorder exists: it starts from it anyway.
            emulator.stateLoadedListener = r::saveLoaded
        }
        createPlayer()
        scope.launch {
            var first = true
            _reader.collect {
                if (first) { first = false; return@collect }
                createPlayer()
            }
        }
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
        // Online, the session owns the frame clock and the agent has no way in yet (ConsoleHost.lease
        // refuses): no player, and the panel says why. The human plays both sides' inputs meanwhile.
        if (emulator.isOnline) {
            _player.value = null
            return
        }
        val game = game
        val model = createModel(_backend.value, emulator)
        // Screen-only games have no calibrated reader yet, but the AI can still play them through the screen
        // viewer (and a reader is learned while it plays): give them an empty reader so ReaderPlayer runs.
        val reader = _reader.value ?: ReaderSpec(id = profile.id, provenance = "screen")
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
            // A game with a reader (shipped, calibrated by hand, or learned by the app while playing): the same loop,
            // its state is what the reader reads and its actions are the console's own (see docs/readers.md).
            model != null -> ReaderPlayer(
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
                provider.needsApiKey && apiKey == null && !(provider == LlmProvider.OPENAI && config.openAiBaseUrl != null) -> null
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
        val reader = reader.value
        if (reader == null) {
            learnReader()
            return
        }
        if (reader.observations.isEmpty()) return
        // One look at the game serves both: what the reader reads now, and what the check sees of it.
        val picture = emulator.frames.value?.let { frame -> FrameHash.dHash(frame) }
        var checked: ReaderVerification? = null
        _readerValues.value = runCatching {
            emulator.observe { memory ->
                if (picture != null) checked = verifierFor(reader).observe(picture, memory)
                ReaderCatalog.observe(reader, memory)
            }
        }.getOrElse { _readerValues.value }
        checked?.let { verification ->
            _verification.value = verification
            saveVerification(reader, verification)
        }
    }

    /** The check of [reader], following it when the app learns more of it (the reader grows while it is played). */
    private fun verifierFor(reader: ReaderSpec): ReaderVerifier {
        val running = verifier
        if (running == null) return ReaderVerifier(reader).also { verifier = it }
        running.reread(reader)
        return running
    }

    /**
     * Writes what the check saw back into the reader (its `confidence` and one notes line, see
     * [ReaderVerifier.verifiedReader]) — the reader then says how well it is doing, for whoever reads it next.
     *
     * Only a reader of ours is written: one the app learned itself, or one that is already in the user's own
     * `<data>/readers` (a copy the user made, by calibrating or by editing). A reader that ships with the app is
     * **never** written: that would shadow the shipped file with a user copy, and a later release could not fix it.
     * Writes are a few seconds apart ([VERIFICATION_WRITE_INTERVAL_MILLIS]), and once more when the game closes.
     */
    private fun saveVerification(reader: ReaderSpec, verification: ReaderVerification, force: Boolean = false) {
        // A check that has not seen enough says nothing: writing it would be a verdict about the route, not the reader.
        if (!verification.isConclusive) return
        val now = System.currentTimeMillis()
        if (!force && now - lastVerificationWriteMillis < VERIFICATION_WRITE_INTERVAL_MILLIS) return
        lastVerificationWriteMillis = now
        val mine = reader.isLearned || runCatching {
            ReaderCatalog.user(profile.id, config.dataDirectory) != null
        }.getOrDefault(false)
        if (!mine) return
        runCatching { ReaderCatalog.write(verifierFor(reader).verifiedReader(verification), config.dataDirectory) }
            .onFailure { failure -> println("ai-plays-online: could not save the reader check: ${failure.message}") }
    }

    /**
     * Writes the check's verdicts now (the game is closing). Harmless when nothing was checked, and when the reader is
     * one that ships with the app (see [saveVerification]).
     */
    fun saveVerificationOnClose() {
        val reader = reader.value ?: return
        val verification = _verification.value ?: return
        saveVerification(reader, verification, force = true)
    }

    /**
     * A game with no reader teaches the app one while it is played: every look at the game hands the learner the
     * picture and the memory ([ReaderCalibrator]), and once there is enough evidence the app writes the reader to
     * `<data>/readers/<id>.json` — the same file a calibration by hand would produce, so the game becomes typed the
     * moment it is written (and the panel starts showing its values).
     */
    private suspend fun learnReader() {
        val calibrator = calibrator ?: return
        val frame = emulator.frames.value ?: return
        val ram = runCatching { emulator.readMainRam() }.getOrNull() ?: return
        val progress = calibrator.observe(FrameHash.dHash(frame), ram)
        _learning.value = progress
        // What has been learned so far is written as it goes (a few seconds apart at most, and once more when the
        // game closes): a session that ends before a reader is ready resumes instead of starting over.
        saveLearningEvidence(calibrator, force = false)
        if (!progress.ready) return
        // The reader is written once, then extended as the learner sees more (not once per look: a write is a file).
        if (progress.samples - readerWrittenAtSamples < READER_WRITE_EVERY_LOOKS) return
        val draft = calibrator.draft(profile.id, screenNames()) ?: return
        // A reader the app learned itself grows: the draft is merged into it, never replacing what it already knows.
        val merged = ReaderMerge.merge(_reader.value, draft, screenNames(), learnedProgress())
        val file = runCatching { ReaderCatalog.write(merged, config.dataDirectory) }.getOrNull() ?: return
        readerWrittenAtSamples = progress.samples
        _reader.value = merged
        emulator.systemLog.add(
            "reader for ${profile.title}: ${merged.observations.size} values, " +
                "${merged.progress.size} progress signals → $file",
        )
        println(
            "ai-plays-online: reader for ${profile.title} (${merged.observations.size} values, " +
                "${merged.progress.size} signals) → $file",
        )
    }

    /**
     * The progress signals the app can claim from what it saw: a chapter whose **landmark** was seen while the reader's
     * `screen` read one value, [MIN_LANDMARK_SIGHTINGS] times in a row. Named `chapter:<id>`, so a walkthrough (or a
     * user writing one) can refer to it with `kind: memory` — exact, where a picture is a guess. Only a reader the app
     * learned itself gets these: a reader somebody wrote has its own progress, verified by hand.
     */
    private fun learnedProgress(): List<ProgressSignal> {
        val reader = _reader.value?.takeIf { it.isLearned } ?: return emptyList()
        val screen = reader.observations.firstOrNull { it.name == "screen" } ?: return emptyList()
        return landmarkScreens
            .filterValues { (_, sightings) -> sightings >= MIN_LANDMARK_SIGHTINGS }
            .map { (chapter, seen) ->
                ProgressSignal(
                    name = "chapter:$chapter",
                    address = screen.address,
                    type = screen.type,
                    mask = screen.mask,
                    shift = screen.shift,
                    equals = seen.first,
                    note = "learned while playing: the landmark of chapter '$chapter' was seen ${seen.second} times " +
                        "in a row while the reader's screen read ${seen.first}",
                )
            }
    }

    /**
     * Writes what the learner knows so far to `<data>/readers/<id>.learning.json` ([ReaderLearningStore]), at most
     * every [LEARNING_WRITE_INTERVAL_MILLIS] unless [force]. A write failure is printed and ignored: losing evidence is
     * not worth failing a game over, and the app simply learns from scratch next time.
     */
    private fun saveLearningEvidence(calibrator: ReaderCalibrator, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLearningWriteMillis < LEARNING_WRITE_INTERVAL_MILLIS) return
        lastLearningWriteMillis = now
        runCatching { ReaderLearningStore.write(calibrator.toEvidence(profile.id, romSha256), config.dataDirectory) }
            .onFailure { failure -> println("ai-plays-online: could not save the reader evidence: ${failure.message}") }
    }

    /**
     * Writes the learner's evidence now (the game is closing): the next session resumes from it. Harmless when the app
     * is not learning this game (a shipped reader, or nothing to learn).
     */
    fun saveLearningOnClose() {
        calibrator?.let { saveLearningEvidence(it, force = true) }
    }

    /** The screen names a walkthrough already knows, so a learned reader names them instead of "screen 3f". */
    private fun screenNames(): Map<Long, String> = walkthrough.walkthrough.value?.chapters.orEmpty()
        .mapNotNull { chapter ->
            val milestone = chapter.milestone ?: return@mapNotNull null
            val hash = milestone.hashes.firstNotNullOfOrNull { FrameHash.parse(it) } ?: return@mapNotNull null
            hash to chapter.title
        }
        .toMap()

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
            val signal = reader.value?.progress?.firstOrNull { it.name == milestone.signal } ?: return
            if (emulator.observe { memory -> signal.reached(memory) }) {
                walkthrough.advance("the game's own state (${signal.name})")
            }
            return
        }
        if (screenWatcher.check(emulator.frames.value, chapter)) {
            rememberLandmarkScreen(chapter)
            walkthrough.advance("seen on screen")
        }
    }

    /**
     * Remembers which value of the reader's `screen` was read when a chapter's landmark was seen (and how many times in
     * a row): [learnedProgress] turns a few agreeing sightings into a `kind: memory` progress signal, which is exact
     * where a picture is a guess. Does nothing without a learned reader, or when the reader has no `screen` value.
     */
    private suspend fun rememberLandmarkScreen(chapter: WalkthroughChapter) {
        val reader = _reader.value?.takeIf { it.isLearned } ?: return
        val screen = reader.observations.firstOrNull { it.name == "screen" } ?: return
        val value = runCatching { emulator.observe { memory -> screen.read(memory) } }.getOrNull() ?: return
        val previous = landmarkScreens[chapter.id]
        val sightings = if (previous?.first == value) previous.second + 1 else 1
        landmarkScreens[chapter.id] = value to sightings
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
        /**
         * How often the learner's evidence is written while the game is played. A write is a few tens of KB; a few
         * seconds apart keeps a crash or a kill from costing more than a handful of looks, without hammering the disk.
         */
        const val LEARNING_WRITE_INTERVAL_MILLIS = 5_000L

        /** Looks between two writes of a learned reader: enough for it to have grown, few enough to follow a run. */
        const val READER_WRITE_EVERY_LOOKS = 60

        /** How often the check of a reader is written back into it at most (a write is a file, and a file is not free). */
        const val VERIFICATION_WRITE_INTERVAL_MILLIS = 30_000L

        /** Times a chapter's landmark has to be seen on the same `screen` value before it becomes a progress signal. */
        const val MIN_LANDMARK_SIGHTINGS = 3
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
