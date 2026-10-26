package me.nathanfallet.aiplayspokemon.ui

import me.nathanfallet.aiplayspokemon.mcp.AgentActivity
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.agent.Decision
import me.nathanfallet.aiplayspokemon.agent.DecisionNarrator
import me.nathanfallet.aiplayspokemon.agent.ControlMode
import me.nathanfallet.aiplayspokemon.agent.PlayerSettings
import me.nathanfallet.aiplayspokemon.agent.PlayerState
import me.nathanfallet.aiplayspokemon.agent.RunStats
import me.nathanfallet.aiplayspokemon.agent.AgentPlayer
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.games.AgentKind
import me.nathanfallet.aiplayspokemon.games.DetectedRom
import java.nio.file.Path
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import dev.kotlinds.pokemonclient.view.StateView

/** The right-hand panel: AI controls, what the AI sees, and what it decided. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlPanel(
    controller: AppController,
    currentRom: Path,
    games: List<DetectedRom>,
    onSelectGame: (Path) -> Unit,
    onBrowse: () -> Unit,
    onChangeGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val player by controller.player.collectAsState()
    val backend by controller.backend.collectAsState()
    val status by controller.emulator.status.collectAsState()
    val panel by controller.panel.collectAsState()

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI plays Online", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        GameSelector(controller, currentRom, games, onSelectGame, onBrowse, onChangeGame)
        WalkthroughSection(controller)
        ReaderSection(controller)
        Text(
            "${controller.profile.shortTitle} · ${controller.emulator.info.name} · ${"%.0f".format(status.measuredFps)} fps",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Emulator controls (wrapping: they don't all fit on one line in a narrow panel)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::togglePause) { Text(if (status.running) "Pause game" else "Resume game") }
            // Online the pace is the peer's: fast-forward is refused by the console, so the chip is hidden.
            if (!controller.emulator.isOnline) {
                FilterChip(
                    selected = status.fastForward,
                    onClick = { controller.emulator.setFastForward(!status.fastForward) },
                    label = { Text("Fast forward") },
                )
            }
            FilterChip(
                selected = !status.muted,
                onClick = { controller.emulator.setMuted(!status.muted) },
                label = { Text("Sound") },
            )
            MusicDuringPausesChip(controller)
            WaitForSongChangeChip(controller)
            NarrationChip(controller)
        }

        NetplaySection(controller)

        InputDebugSection(controller)
        NarrationReadout(controller)

        // Which AI plays: Jev, or an LLM (provider + model)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecisionBackend.entries.forEach { entry ->
                FilterChip(
                    selected = backend == entry,
                    onClick = { controller.selectBackend(entry) },
                    label = { Text(entry.label) },
                )
            }
        }
        if (backend == DecisionBackend.LLM) LlmSelector(controller)
        ExperimentSettings(controller, backend)

        val currentPlayer = player
        val reader by controller.reader.collectAsState()
        when {
            controller.emulator.isOnline -> NetplayPlayerNote()
            controller.game == null && reader != null && currentPlayer == null -> Text(
                "This game has a reader (see Reader above): the AI plays it through it, so a model (or its API key) " +
                    "is what is missing.",
            )
            backend == DecisionBackend.MCP -> McpSection(controller)
            currentPlayer == null -> ApiKeyForm(controller.missingApiKey ?: "the model", controller::setApiKey)
            else -> PlayerSection(currentPlayer)
        }

        HorizontalDivider()
        val playerState = player?.state?.collectAsState()?.value
        AgentViewSection(panel, playerState?.lastRequestState)
        HorizontalDivider()
        KeyboardHelp()
    }
}

/**
 * The online session: who plays, where the peer is, and what the engine reports (frame, rollbacks,
 * predicted frames, snapshot memory, last event). Shown only when the game was opened for netplay.
 */
@Composable
private fun NetplaySection(controller: AppController) {
    val status by controller.emulator.netplayStatus.collectAsState()
    val current = status ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Playing online", fontWeight = FontWeight.Bold)
        Text(
            current.summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (current.stopped != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            "frame ${current.frame} · peer ${current.remoteFrame} · rollbacks ${current.rollbacks} " +
                "(max ${current.maxRollbackFrames}) · predicted ${current.predictedFrames} · " +
                "state ${"%.1f".format(current.stateMiB)} MiB",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        current.lastEvent?.let { Text("Last event: $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            "Save states, fast-forward and music during pauses are off online (they would desync the session).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the AI panel says online: the agent has no way into a session-driven console yet. */
@Composable
private fun NetplayPlayerNote() {
    Text(
        "Online, the session owns the frame clock: the AI cannot take the controller yet, you play " +
            "both sides (your input is sent, the peer's comes back). See the plan: the agent plays " +
            "through the session's input queue next.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * "Music during pauses": the music goes on while the game is paused, and the game resumes in sync with it. Disabled
 * (with the reason as its label) when the ROM / core / platform doesn't support it.
 */
@Composable
private fun MusicDuringPausesChip(controller: AppController) {
    val settings by controller.settings.collectAsState()
    val unavailable = controller.emulator.musicDuringPausesUnavailable
    FilterChip(
        selected = settings.musicDuringPauses && unavailable == null,
        enabled = unavailable == null,
        onClick = { controller.updateSettings { it.copy(musicDuringPauses = !it.musicDuringPauses) } },
        label = { Text(if (unavailable == null) "Music during pauses" else "Music during pauses (unavailable)") },
    )
}

/**
 * "Wait for the song change": with music during pauses, a pause asked while the game changes its song starts once the
 * new song plays, so the music doesn't jump back at resume. Only meaningful (enabled) with music during pauses.
 */
@Composable
private fun WaitForSongChangeChip(controller: AppController) {
    val settings by controller.settings.collectAsState()
    val available = settings.musicDuringPauses && controller.emulator.musicDuringPausesUnavailable == null
    FilterChip(
        selected = settings.waitForSongChange && available,
        enabled = available,
        onClick = { controller.updateSettings { it.copy(waitForSongChange = !it.waitForSongChange) } },
        label = { Text("Wait for the song change") },
    )
}

/**
 * "Narrate decisions": the app speaks every decision aloud — the stream's voice for the agent, saying its number,
 * confidence, what it does and why (see [DecisionNarrator]).
 *
 * [NarrationControl] owns the switch: the explicit on/off toggle (the chip and the Switch) turns speech on and off
 * independently of the voice, while the dropdown below picks the voice that the narrator uses from the next line on.
 * "Off" stops the speech, and turning the toggle off cuts the current utterance mid-sentence.
 */
@Composable
private fun NarrationChip(controller: AppController) {
    val settings by controller.settings.collectAsState()
    val voice by controller.narrationVoice.collectAsState()
    val voices = remember { DecisionNarrator.installedVoices() }
        .sortedWith(compareByDescending<String> { it == DecisionNarrator.DEFAULT_VOICE }.thenBy { it })
    var expanded by remember { mutableStateOf(false) }
    Box {
        var toggledOn by remember { mutableStateOf(settings.narration) }
        FilterChip(
            selected = toggledOn,
            onClick = { controller.setNarrationEnabled(!toggledOn); toggledOn = !toggledOn },
            label = { Text(if (toggledOn) "Narrating: $voice" else "Narrate decisions: off") },
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = toggledOn,
            onCheckedChange = { controller.setNarrationEnabled(it); toggledOn = it },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Off") },
                onClick = {
                    controller.setNarrationVoice(null)
                    expanded = false
                },
            )
            voices.forEach { installed ->
                DropdownMenuItem(
                    text = { Text(installed) },
                    onClick = {
                        controller.setNarrationVoice(installed)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Provider chips and model id for the LLM backend. */
@Composable
private fun LlmSelector(controller: AppController) {
    val llm by controller.llm.collectAsState()
    var model by remember(llm) { mutableStateOf(llm.model) }
    var baseUrl by remember(llm) { mutableStateOf(controller.openAiBaseUrl.value.orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LlmProvider.entries.forEach { provider ->
            FilterChip(
                selected = llm.provider == provider,
                onClick = { controller.selectLlmProvider(provider) },
                label = { Text(provider.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model id") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { controller.setLlmModel(model) }, enabled = model.isNotBlank() && model != llm.model) {
            Text("Use")
        }
    }
    if (llm.provider == LlmProvider.OPENAI) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL (OpenAI-compatible, e.g. LM Studio)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { controller.setLlmBaseUrl(baseUrl.takeIf { it.isNotBlank() }) }, enabled = baseUrl.isNotBlank()) {
                Text("Use")
            }
        }
    }
    if (llm.provider == LlmProvider.OLLAMA) {
        val models by controller.ollamaModels.collectAsState()
        val loading by controller.ollamaModelsLoading.collectAsState()
        val error by controller.ollamaModelsError.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { controller.refreshOllamaModels() },
                enabled = !loading,
            ) {
                Text(if (loading) "Refreshing…" else "Refresh models")
            }
            Text(
                text = error ?: when {
                    models.isEmpty() && !loading -> "No Ollama models found (is Ollama running?)"
                    else -> "${models.size} model${if (models.size != 1) "s" else ""} available"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (models.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Installed Ollama models", style = MaterialTheme.typography.labelSmall)
                    LazyColumn(
                        // A LazyColumn scrolls itself: adding verticalScroll here would measure it with an
                        // infinite maximum height, which Compose refuses (it crashed the panel with an LLM).
                        modifier = Modifier.height(150.dp),
                    ) {
                        items(items = models, key = { it }) { m ->
                            TextButton(
                                onClick = {
                                    model = m
                                    controller.setLlmModel(m)
                                },
                            ) {
                                Text(m, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (llm.provider == LlmProvider.OLLAMA || llm.provider == LlmProvider.CLAUDE_CODE) FilterChip(
        selected = llm.thinking,
        onClick = { controller.setLlmThinking(!llm.thinking) },
        label = { Text(if (llm.provider == LlmProvider.OLLAMA) "Think before answering (slower)" else "High effort (slower)") },
    )
    if (llm.provider == LlmProvider.CLAUDE_CODE) Text(
        "Uses the Claude account Claude Code is logged in with (no API key); counts towards its usage limits.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The experiment knobs: control mode (pure / assisted / hybrid) and the options to compare.
 * In hybrid mode, the model selected above decides and the LLM settings define the planner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperimentSettings(controller: AppController, backend: DecisionBackend) {
    val settings by controller.settings.collectAsState()
    Text("Mode", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Hybrid needs our own loop (fast model + planner), so it isn't offered to an external agent.
        ControlMode.entries.filter { backend != DecisionBackend.MCP || it != ControlMode.HYBRID }.forEach { mode ->
            FilterChip(
                selected = settings.mode == mode,
                onClick = { controller.updateSettings { it.copy(mode = mode) } },
                label = { Text(mode.label) },
            )
        }
    }
    Text(settings.mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Knowledge", fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KnowledgeLevel.entries.forEach { level ->
            FilterChip(
                selected = settings.knowledge == level,
                onClick = { controller.updateSettings { it.copy(knowledge = level) } },
                label = { Text(KNOWLEDGE_LABELS.getValue(level)) },
            )
        }
    }
    Text(settings.knowledge.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (settings.mode == ControlMode.HYBRID) Text(
        "Planner: the LLM configured in the LLM tab.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Options that only make sense for some backends are shown only there.
    val llmInLoop = backend == DecisionBackend.LLM || settings.mode == ControlMode.HYBRID
    val toggles = listOfNotNull(
        Triple("Pause while thinking", settings.pauseWhileThinking) { s: PlayerSettings, v: Boolean -> s.copy(pauseWhileThinking = v) },
        Triple("Sequences (LLM)", settings.allowSequences) { s: PlayerSettings, v: Boolean -> s.copy(allowSequences = v) }.takeIf { llmInLoop },
        // Our LLM reasons before answering; an MCP agent must give `reasoning` with each act.
        Triple(if (backend == DecisionBackend.MCP) "Reasoning required (MCP)" else "Reasoning (LLM)", settings.reasoning) { s: PlayerSettings, v: Boolean -> s.copy(reasoning = v) }
            .takeIf { llmInLoop || backend == DecisionBackend.MCP },
        Triple("Notes (LLM)", settings.modelNotes) { s: PlayerSettings, v: Boolean -> s.copy(modelNotes = v) }.takeIf { llmInLoop },
        // Reader games: the model is asked what the picture shows (a model without vision answers `unknown`).
        Triple("Read the screen", settings.readScreen) { s: PlayerSettings, v: Boolean -> s.copy(readScreen = v) }.takeIf { llmInLoop },
        Triple("Explored map", settings.exploredMap) { s: PlayerSettings, v: Boolean -> s.copy(exploredMap = v) },
        Triple("Solve movement puzzles", settings.solvePuzzles) { s: PlayerSettings, v: Boolean -> s.copy(solvePuzzles = v) }
            .takeIf { settings.mode != ControlMode.PURE },
        // Every mode: the map view of the state lists exits too (without destinations when on).
        Triple("Hide where exits lead", settings.hideDestinations) { s: PlayerSettings, v: Boolean -> s.copy(hideDestinations = v) },
        Triple("Sample probabilities", settings.sampleProbabilities) { s: PlayerSettings, v: Boolean -> s.copy(sampleProbabilities = v) }
            .takeIf { backend == DecisionBackend.JEV },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        toggles.forEach { (label, value, update) ->
            FilterChip(
                selected = value,
                onClick = { controller.updateSettings { update(it, !value) } },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun ApiKeyForm(service: String, onSubmit: (String) -> Unit) {
    var key by remember(service) { mutableStateOf("") }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API key needed", fontWeight = FontWeight.Bold)
            Text(
                "Paste your $service API key. It is saved in ~/.ai-plays-pokemon/config.properties.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSubmit(key) }, enabled = key.isNotBlank()) { Text("Save") }
        }
    }
}

@Composable
private fun PlayerSection(player: AgentPlayer) {
    val state by player.state.collectAsState()
    var objective by remember(player) { mutableStateOf(player.objective) }

    Button(
        onClick = player::toggle,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = if (state.playing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors(),
    ) {
        Text(if (state.playing) "⏸  Take back control" else "▶  Let ${player.model.name} play", fontSize = 16.sp)
    }
    if (state.thinking) Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    state.error?.let { Text("Stopped: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    OutlinedTextField(
        value = objective,
        onValueChange = {
            objective = it
            player.objective = it
        },
        label = { Text("Objective") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall,
    )

    Stats(state)
    state.screenRead?.let { screen ->
        Text("Screen: $screen", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    state.note?.let { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
    state.plannerGoal?.let { Text("Planner goal: $it", style = MaterialTheme.typography.bodySmall) }
    Milestones(state.milestones)
    state.lastDecision?.let { DecisionCard(it) }
    DecisionHistory(state.history, Modifier.height(180.dp))
}

@Composable
private fun Stats(state: PlayerState) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("Decisions", state.decisions.toString())
        Stat("Presses", state.presses.toString())
        Stat("Avg latency", "${state.averageLatencyMillis} ms")
        Stat("Tokens in/out", "${state.inputTokens}/${state.outputTokens}")
        if (state.costUsd > 0) Stat("Cost", "$" + "%.2f".format(state.costUsd))
    }
}

/**
 * Debug: what the human holds right now, where they touch, and the last input / system changes (keys, touch, who
 * drives the console, pauses, save states) — how a stuck input ("why is the agent refused / waiting?") becomes
 * visible: a key held since a focus loss, a touch that never released, and what the game was doing meanwhile.
 */
@Composable
private fun InputDebugSection(controller: AppController) {
    val held by controller.emulator.heldButtons.collectAsState()
    val touching by controller.emulator.touching.collectAsState()
    val log by controller.emulator.systemLog.entries.collectAsState()
    Text("Input debug", fontWeight = FontWeight.Bold)
    Text(
        "Held: " + held.joinToString { it.name.lowercase() }.ifEmpty { "none" } +
            " · Touch: " + (touching?.let { "${it.x},${it.y}" } ?: "up"),
        style = MaterialTheme.typography.bodySmall,
    )
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(INPUT_LOG_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(log) { entry ->
            Text(
                entry,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How much of the panel the input log takes (its own scroll, newest first). */
private val INPUT_LOG_HEIGHT = 120.dp

/**
 * What the narrator said, newest first: the lines the voice actually got, with the voice that read them. It is the
 * check that a decision really was read out (the log alone only says a line was handed over).
 */
@Composable
private fun NarrationReadout(controller: AppController) {
    val spoken by controller.spoken.collectAsState()
    if (spoken.isEmpty()) return
    Text("Spoken", fontWeight = FontWeight.Bold)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(SPOKEN_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(spoken) { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How much of the panel the spoken lines take (their own scroll, newest first). */
private val SPOKEN_HEIGHT = 100.dp

/** Lets an external agent (Claude Code...) play through MCP instead of our own loop. */
@Composable
private fun McpSection(controller: AppController) {
    val server by controller.mcp.collectAsState()
    Text(
        if (server != null) "MCP server running: an external agent plays through it." else "Starting the MCP server…",
        fontWeight = FontWeight.Bold,
    )
    val driver by controller.emulator.driver.collectAsState()
    Text("Driving the game: ${driver.description}", style = MaterialTheme.typography.bodySmall)
    var port by remember { mutableStateOf(controller.mcpPort.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("MCP port") }, singleLine = true, modifier = Modifier.width(120.dp))
        OutlinedButton(onClick = { port.toIntOrNull()?.let(controller::setMcpPort) }, enabled = port.toIntOrNull() != controller.mcpPort) {
            Text("Apply")
        }
    }
    server?.let { running ->
        val activity by running.activity.collectAsState()
        val blind by running.blindUses.collectAsState()
        SelectionContainer {
            Text(
                "Connect an agent, e.g.: claude mcp add --transport http pokemon ${running.url}\n" +
                    "Then ask it to play. Calls: ${activity.calls}" +
                    (activity.shown?.let { shown ->
                        val head = if (shown.phase == AgentActivity.Phase.IN_PROGRESS) "In progress" else "Last"
                        "\n$head: ${shown.action}" + (shown.result?.let { " → $it" } ?: "") + (shown.reasoning?.let { "\n“$it”" } ?: "")
                    } ?: "") +
                    (if (blind.isEmpty()) "" else "\nUndecoded screens used blindly: " +
                        blind.entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" }),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Milestones reached during this run (also saved in ~/.ai-plays-pokemon/runs/ to compare configurations). */
@Composable
private fun Milestones(milestones: List<RunStats.Milestone>) {
    if (milestones.isEmpty()) return
    Column {
        Text("Milestones", fontWeight = FontWeight.Bold)
        milestones.forEach {
            Text(
                "${it.name} · ${it.decisions} decisions · ${it.presses} presses · ${it.seconds}s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The last decision: the model's probability for every button when it gives them (Jev does, which
 * makes its decisions "explainable"), or its short thought (LLMs).
 */
@Composable
private fun DecisionCard(decision: Decision) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "#${decision.number} → ${decision.actions.joinToString(" → ")}${if (decision.byPlanner) "  (planner)" else ""}",
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "confidence ${decision.confidence?.let(::percent) ?: "n/a"} · ${decision.latencyMillis} ms · ${decision.model}",
                style = MaterialTheme.typography.bodySmall,
            )
            decision.thought?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
            decision.problem?.let { Text("Failed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            // A sequence ending early by its own rules is normal: said, not shown as an error.
            decision.stop?.let { Text("Sequence ended: $it", style = MaterialTheme.typography.bodySmall) }
            if (decision.confidence != null) decision.probabilities.forEach { (option, probability) ->
                ProbabilityBar(option, probability)
            }
        }
    }
}

@Composable
private fun ProbabilityBar(option: String, probability: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(option, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier.fillMaxWidth(probability.toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(percent(probability), Modifier.width(44.dp).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DecisionHistory(history: List<Decision>, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(history, key = { it.number }) { decision ->
            Text(
                "#${decision.number}  ${decision.actions.joinToString(",").padEnd(10)} ${(decision.confidence?.let(::percent) ?: "").padStart(4)}  ${decision.summary}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

/**
 * What the AI receives: the full state of the last request (screen + memory) while our own loop plays, otherwise
 * exactly what an agent would get now ([AppController.panel]: `GameSession.describe()` with the current options).
 */
@Composable
private fun AgentViewSection(panel: PanelView?, lastRequestState: JsonObject?) {
    var showJson by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("What the AI sees", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = { showJson = !showJson }) { Text(if (showJson) "Hide JSON" else "Show JSON") }
    }
    Text(panel?.summary ?: "Nothing yet", style = MaterialTheme.typography.bodySmall)
    val json = lastRequestState ?: panel?.view
    if (showJson && json != null) {
        SelectionContainer {
            Text(
                prettyJson.encodeToString(JsonObject.serializer(), json),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.height(260.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun KeyboardHelp() {
    Column {
        KeyboardMapping.help.chunked(2).forEach { row ->
            Row {
                row.forEach { (key, action) ->
                    Text(
                        "$key: $action",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun percent(value: Double) = "${(value * 100).toInt()}%"


/** Short names of the knowledge levels, for the chips. */
private val KNOWLEDGE_LABELS = mapOf(
    KnowledgeLevel.NONE to "Screen only",
    KnowledgeLevel.POKEDEX to "Pokédex",
    KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH to "+ Walkthrough",
)

/**
 * Which game is loaded, and a way to pick another one.
 *
 * The list comes from the ROMs directory (`~/.ai-plays-online/roms` by default,
 * `AI_PLAYS_ONLINE_ROMS_DIR` to change it): each ROM is identified by its header, so the picker shows
 * the game's name, whether the AI gets the game's state or only the screen, and what we verified for
 * it. Switching closes the running console (saving the game) and opens the chosen ROM.
 */
@Composable
private fun GameSelector(
    controller: AppController,
    currentRom: Path,
    games: List<DetectedRom>,
    onSelectGame: (Path) -> Unit,
    onBrowse: () -> Unit,
    onChangeGame: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val profile = controller.profile
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text("Game: ${profile.title}", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (games.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No ROMs in the ROMs folder") },
                            onClick = { expanded = false },
                        )
                    }
                    games.forEach { game ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(game.profile.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        agentLabel(game.profile.agent) +
                                            game.profile.tested.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                if (game.path != currentRom) onSelectGame(game.path)
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Browse…") }, onClick = { expanded = false; onBrowse() })
                    DropdownMenuItem(
                        text = { Text("Choose another game…") },
                        onClick = { expanded = false; onChangeGame() },
                    )
                }
            }
            Text(
                agentLabel(profile.agent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Objective: ${profile.objective}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How the picker describes what an AI can see of a game. */
private fun agentLabel(agent: AgentKind): String = when (agent) {
    AgentKind.TYPED -> "typed agent (reads the game)"
    AgentKind.SCREEN -> "screen only"
}



/**
 * The walkthrough of the game being played: which chapter the run is on, what it asks for, and its hints.
 *
 * The chapter is advanced by the game's own progress when it has a typed reader, by a screen landmark otherwise
 * (see [ScreenWatcher]), and can always be pinned here — a human steering the run, or correcting a guess. The text
 * is data (see `docs/walkthroughs.md`): shipped with the app, overridable in `<data>/walkthroughs/`.
 */
/**
 * What the app can read from this game, live.
 *
 * A game with a calibrated reader (`readers/<id>.json`, see `docs/readers.md`) is a typed game even though nobody
 * wrote a reader for it in code: the panel shows every value the reader reads, so it is visible what the app knows
 * (and what it does not) — a value that never changes is a value that is not working yet.
 */
@Composable
private fun ReaderSection(controller: AppController) {
    val reader by controller.reader.collectAsState()
    val values by controller.readerValues.collectAsState()
    val learning by controller.learning.collectAsState()
    learning?.let { progress ->
        // No reader yet: the app is learning one while the game is played (see emulator/ReaderCalibrator.kt).
        HorizontalDivider()
        Text("Reader", fontWeight = FontWeight.Bold)
        Text(
            if (progress.ready) "Learning this game: writing a reader…"
            else "Learning this game while you play: ${progress.samples} looks, ${progress.screens} screens, part " +
                "${progress.chunk + 1}/${progress.chunks} of memory, ${progress.candidates} signals so far, best one " +
                "separates ${progress.bestValues} screens and agrees " +
                "${"%.0f".format(progress.bestConsistency * 100)}% of the time.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Play on (or let the AI play): the app writes readers/<id>.json itself once the game has shown it a few " +
                "screens. See docs/readers.md.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val current = reader ?: return
    HorizontalDivider()
    Text("Reader", fontWeight = FontWeight.Bold)
    Text(
        "This game's reader (${current.provenance}): ${current.observations.size} values, " +
            "${current.progress.size} progress signals.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (observation in current.observations) {
        Text(
            "${observation.name}: ${values[observation.name] ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
    current.notes.takeIf { it.isNotBlank() }?.let { notes ->
        Text(notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WalkthroughSection(controller: AppController) {
    val walkthrough by controller.walkthrough.walkthrough.collectAsState()
    val chapterId by controller.walkthrough.chapterId.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    var autoAdvance by remember { mutableStateOf(controller.walkthrough.autoAdvance) }

    val current = chapterId?.let { id -> walkthrough?.chapter(id) }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Walkthrough", fontWeight = FontWeight.Bold)
            if (walkthrough != null) {
                Text(
                    controller.walkthroughProvenance().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val data = walkthrough
        if (data == null) {
            Text(
                "No walkthrough for this game yet. Add one in <data>/walkthroughs/ (see docs/walkthroughs.md): " +
                    "chapters with a goal, hints, and optionally the screen each one happens on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (current == null) {
            Text(
                "No chapter chosen yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val index = data.chapters.indexOfFirst { it.id == current.id } + 1
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(
                            "Chapter $index/${data.chapters.size}: ${current.title}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        data.chapters.forEachIndexed { position, item ->
                            DropdownMenuItem(
                                text = { Text("${position + 1}. ${item.title}", style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    expanded = false
                                    controller.selectChapter(item.id)
                                },
                            )
                        }
                    }
                }
                if (controller.walkthrough.reason.isNotBlank()) {
                    Text(
                        controller.walkthrough.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(current.goal, style = MaterialTheme.typography.bodySmall)
            current.hints.forEach { hint ->
                Text(
                    "• $hint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.milestone != null) {
                Text(
                    "The app watches for this chapter's screen and moves on when it sees it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = autoAdvance,
                onClick = {
                    autoAdvance = !autoAdvance
                    controller.setWalkthroughAutoAdvance(autoAdvance)
                },
                label = { Text("Follow progress") },
            )
            OutlinedButton(onClick = { exported = controller.exportWalkthrough()?.toString() }) {
                Text("Copy for editing")
            }
        }
        exported?.let { path ->
            Text(
                "Written to $path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
