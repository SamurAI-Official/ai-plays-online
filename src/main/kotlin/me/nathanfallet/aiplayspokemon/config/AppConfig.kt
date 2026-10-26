package me.nathanfallet.aiplayspokemon.config

import kotlinx.serialization.json.Json
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.jev.JevClient
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import me.nathanfallet.aiplayspokemon.emulator.NetplayRole
import me.nathanfallet.aiplayspokemon.emulator.NetplaySettings
import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.libretrorollback.SnapshotStrategy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * App settings. Each one is read from an environment variable first, then from
 * `config.properties` in [dataDirectory] (where the settings changed in the UI are saved):
 *
 * | Setting               | Environment variable                    | Config key              |
 * |-----------------------|-----------------------------------------|-------------------------|
 * | ROM                   | `AI_PLAYS_ONLINE_ROM` (or `POKEMON_ROM`, or first argument) | `rom`                   |
 * | Decision model        | `DECISION_BACKEND` (`mcp` / `llm` / `jev`) | `backend` (mcp)      |
 * | Jev API key           | `TYPESAFE_API_KEY`                      | `typesafe.apiKey`       |
 * | Jev model             | `JEV_MODEL`                             | `typesafe.model`        |
 * | Jev endpoint          | `JEV_ENDPOINT`                          | `typesafe.endpoint`     |
 * | LLM provider          | `LLM_PROVIDER` (`openai`, `anthropic`, `openrouter`, `ollama`) | `llm.provider` |
 * | LLM model             | `LLM_MODEL`                             | `llm.<provider>.model`  |
 * | LLM thinks first      | `LLM_THINKING` (`true` / `false`)       | `llm.thinking`          |
 * | MCP server port       | `MCP_PORT`                              | `mcp.port` (3333)       |
 * | Emulator core         | `EMULATOR_CORE` (`desmume` / `melonds`) | `emulator.core`         |
 * | Start muted           | `START_MUTED` (`true` / `false`)        | `start.muted`           |
 * | Save state at launch  | `START_STATE` (slot number)             | `start.state`           |
 * | LLM API key           | `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY` | `llm.<provider>.apiKey` |
 * | LLM base URL          | `OPENAI_BASE_URL`                                           | `llm.openai.baseUrl`    |
 * | Narration voice / rate| `NARRATION_VOICE` / `NARRATION_RATE`                         | `narration.voice` / `narration.rate` |
 *
 * Everything the app writes (config, downloaded cores, in-game saves, save states) lives in
 * [dataDirectory], `~/.ai-plays-online` by default (`AI_PLAYS_ONLINE_DATA_DIR` to change it; the old
 * `AI_PLAYS_POKEMON_DATA_DIR` and `~/.ai-plays-pokemon` still work).
 */
class AppConfig(
    val dataDirectory: Path,
    private val properties: Properties,
    /**
     * The ROM asked for on the command line or through the ROM environment variable: the only one the app starts
     * by itself. Null means "show the game picker".
     */
    val romPath: Path?,
) {
    private val configFile get() = dataDirectory.resolve(CONFIG_FILE)

    // region Decision model

    var backend: DecisionBackend
        get() = enumSetting("DECISION_BACKEND", "backend") ?: DecisionBackend.MCP
        set(value) = save("backend", value.name.lowercase())

    // endregion

    // region Jev

    var jevApiKey: String?
        get() = setting("TYPESAFE_API_KEY", "typesafe.apiKey")
        set(value) = save("typesafe.apiKey", value)

    val jevModel: String get() = setting("JEV_MODEL", "typesafe.model") ?: JevClient.DEFAULT_MODEL

    val jevEndpoint: URI get() = setting("JEV_ENDPOINT", "typesafe.endpoint")?.let(URI::create) ?: JevClient.DEFAULT_ENDPOINT

    // endregion

    // region LLMs

    var llmProvider: LlmProvider
        get() = enumSetting("LLM_PROVIDER", "llm.provider") ?: LlmProvider.OLLAMA
        set(value) = save("llm.provider", value.name.lowercase())

    fun llmModel(provider: LlmProvider): String =
        setting("LLM_MODEL".takeIf { provider == llmProvider }, "llm.${provider.key}.model") ?: provider.defaultModel

    fun saveLlmModel(provider: LlmProvider, model: String) = save("llm.${provider.key}.model", model)

    var llmThinking: Boolean
        get() = setting("LLM_THINKING", "llm.thinking")?.toBooleanStrictOrNull() ?: false
        set(value) = save("llm.thinking", value.toString())

    fun llmApiKey(provider: LlmProvider): String? = setting(provider.apiKeyVariable, "llm.${provider.key}.apiKey")

    /** Base URL for OpenAI-compatible endpoints (e.g. LM Studio). Null = Koog default (api.openai.com). */
    val openAiBaseUrl: String? get() = setting("OPENAI_BASE_URL", "llm.openai.baseUrl")

    /** Base URL of Ollama's OpenAI-compatible API: decisions reach it through our client like any other endpoint. */
    val ollamaBaseUrl: String get() = setting("OLLAMA_BASE_URL", "llm.ollama.baseUrl") ?: "http://localhost:11434/v1"

    fun saveLlmApiKey(provider: LlmProvider, key: String) = save("llm.${provider.key}.apiKey", key)

    fun saveLlmBaseUrl(url: String?) = save("llm.openai.baseUrl", url)

    // endregion

    /** Experiment options of the player (mode, timing, memory...), edited in the UI. */
    var playerSettings: PlayerSettings
        get() = properties.getProperty("player.settings")
            ?.let { runCatching { settingsJson.decodeFromString(PlayerSettings.serializer(), it) }.getOrNull() }
            ?: PlayerSettings()
        set(value) = save("player.settings", settingsJson.encodeToString(PlayerSettings.serializer(), value))

    /** The macOS `say` voice speaking decisions (see `DecisionNarrator.installedVoices`). */
    var narrationVoice: String
        get() = setting("NARRATION_VOICE", "narration.voice") ?: "Whisper"
        set(value) = save("narration.voice", value)

    /** Words per minute of the narration voice (macOS `say` defaults to 175). */
    var narrationRate: Int
        get() = setting("NARRATION_RATE", "narration.rate")?.toIntOrNull() ?: 185
        set(value) = save("narration.rate", value.toString())

    /** Port of the MCP server (external agents play through it). */
    var mcpPort: Int
        get() = setting("MCP_PORT", "mcp.port")?.toIntOrNull() ?: 3333
        set(value) = save("mcp.port", value.toString())

    /** Preferred emulator core (see LibretroCoreSpec); DeSmuME by default. */
    val emulatorCore: String? get() = setting("EMULATOR_CORE", "emulator.core")

    /** Starts with the sound off (tests, or several instances side by side). */
    val startMuted: Boolean get() = setting("START_MUTED", "start.muted")?.toBooleanStrictOrNull() ?: false

    /** Save state slot loaded right after launch, if any (e.g. to resume a test scenario). */
    val startState: Int? get() = setting("START_STATE", "start.state")?.toIntOrNull()

    fun saveRomPath(path: Path) = save("rom", path.toAbsolutePath().toString())

    /**
     * The ROM played last time, offered by the game picker (never loaded by itself): null when there is none, or
     * when the file has moved away.
     */
    val rememberedRomPath: Path?
        get() = properties.getProperty("rom")?.let(Path::of)?.takeIf { it.exists() }

    /**
     * Where the game picker looks for ROMs. Defaults to `<data>/roms`; set `AI_PLAYS_ONLINE_ROMS_DIR`
     * (or the `roms.dir` config key) to point at a folder you already keep them in.
     */
    val romsDirectory: Path
        get() = setting("AI_PLAYS_ONLINE_ROMS_DIR", "roms.dir")?.let(Path::of) ?: dataDirectory.resolve("roms")

    /**
     * Base URL for online walkthroughs (optional). Defaults to the built-in endpoint when unset.
     */
    val walkthroughUrl: String?
        get() = setting("AI_PLAYS_ONLINE_WALKTHROUGH_URL", "walkthrough.url")

    /**
     * Online play, or null when playing offline.
     *
     * | Setting        | Environment variable                        | Config key                | Default |
     * |----------------|---------------------------------------------|---------------------------|---------|
     * | Role           | `AI_PLAYS_ONLINE_NETPLAY` (`host` / `join`) | `netplay.role`            | off     |
     * | Peer           | `AI_PLAYS_ONLINE_NETPLAY_PEER` (`host:port`) | `netplay.peer`           | —       |
     * | Local port     | `AI_PLAYS_ONLINE_NETPLAY_BIND`              | `netplay.bindPort`        | 7000 / 7001 |
     * | Mode           | `AI_PLAYS_ONLINE_NETPLAY_MODE` (`rollback` / `lockstep`) | `netplay.mode` | lockstep |
     * | Input delay    | `AI_PLAYS_ONLINE_NETPLAY_INPUT_DELAY`       | `netplay.inputDelay`      | 2       |
     * | Prediction     | `AI_PLAYS_ONLINE_NETPLAY_PREDICTION`        | `netplay.prediction`      | 8       |
     * | Snapshots      | `AI_PLAYS_ONLINE_NETPLAY_SNAPSHOTS` (`full` / `delta`) | `netplay.snapshots` | delta |
     * | Keyframes      | `AI_PLAYS_ONLINE_NETPLAY_KEYFRAME`          | `netplay.keyframe`        | 30      |
     *
     * Both peers must use the same mode, input delay, prediction and snapshots: the handshake compares
     * them, so a mismatch is refused instead of silently desyncing. A missing or malformed peer is an
     * error (not a silent fallback to offline), because it means the session was asked for and cannot
     * be built.
     */
    val netplay: NetplaySettings?
        get() {
            val role = enumSetting<NetplayRole>("AI_PLAYS_ONLINE_NETPLAY", "netplay.role") ?: return null
            val peer = requireNotNull(setting("AI_PLAYS_ONLINE_NETPLAY_PEER", "netplay.peer")) {
                "netplay: the peer is missing; set AI_PLAYS_ONLINE_NETPLAY_PEER (or netplay.peer) to host:port"
            }
            val host = peer.substringBeforeLast(':', "").trim()
            val port = peer.substringAfterLast(':', "").trim().toIntOrNull()
            require(host.isNotEmpty() && port in 1..65535) {
                "netplay: the peer must be host:port, got '$peer'"
            }
            return NetplaySettings(
                role = role,
                bindPort = setting("AI_PLAYS_ONLINE_NETPLAY_BIND", "netplay.bindPort")?.toIntOrNull()
                    ?: if (role == NetplayRole.HOST) 7000 else 7001,
                peerHost = host,
                peerPort = port!!,
                mode = enumSetting<Mode>("AI_PLAYS_ONLINE_NETPLAY_MODE", "netplay.mode") ?: Mode.LOCKSTEP,
                inputDelay = setting("AI_PLAYS_ONLINE_NETPLAY_INPUT_DELAY", "netplay.inputDelay")?.toIntOrNull() ?: 2,
                maxPredictionFrames = setting("AI_PLAYS_ONLINE_NETPLAY_PREDICTION", "netplay.prediction")?.toIntOrNull() ?: 8,
                snapshotStrategy = enumSetting<SnapshotStrategy>("AI_PLAYS_ONLINE_NETPLAY_SNAPSHOTS", "netplay.snapshots")
                    ?: SnapshotStrategy.DELTA,
                deltaKeyframeInterval = setting("AI_PLAYS_ONLINE_NETPLAY_KEYFRAME", "netplay.keyframe")?.toIntOrNull() ?: 30,
            )
        }

    private val LlmProvider.key get() = name.lowercase()

    private fun setting(variable: String?, key: String): String? =
        variable?.let(System::getenv)?.takeIf { it.isNotBlank() } ?: properties.getProperty(key)?.takeIf { it.isNotBlank() }

    private inline fun <reified T : Enum<T>> enumSetting(variable: String, key: String): T? =
        setting(variable, key)?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }

    private fun save(key: String, value: String?) {
        if (value == null) properties.remove(key) else properties.setProperty(key, value)
        Files.createDirectories(dataDirectory)
        configFile.outputStream().use { properties.store(it, "AI plays Online") }
    }

    companion object {
        private const val CONFIG_FILE = "config.properties"
        private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(args: Array<String>): AppConfig {
            // The app used to be Pokémon-only: keep reading the old data directory when the new one
            // does not exist yet, so existing cores, saves and settings are not lost on upgrade.
            val dataDirectory = System.getenv("AI_PLAYS_ONLINE_DATA_DIR")?.let(Path::of)
                ?: System.getenv("AI_PLAYS_POKEMON_DATA_DIR")?.let(Path::of)
                ?: run {
                    val home = Path.of(System.getProperty("user.home"))
                    val current = home.resolve(".ai-plays-online")
                    val legacy = home.resolve(".ai-plays-pokemon")
                    if (current.exists() || !legacy.exists()) current else legacy
                }
            val properties = Properties()
            dataDirectory.resolve(CONFIG_FILE).takeIf { it.exists() }?.inputStream()?.use(properties::load)

            // Only an explicit request starts a game by itself (the command line, or the ROM environment
            // variable): the remembered one is offered by the game picker, never loaded behind the user's back.
            val rom = args.firstOrNull()
                ?: System.getenv("AI_PLAYS_ONLINE_ROM")
                ?: System.getenv("POKEMON_ROM")
            return AppConfig(dataDirectory, properties, rom?.let(Path::of)?.takeIf { it.exists() })
        }
    }
}
