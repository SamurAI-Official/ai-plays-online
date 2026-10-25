package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.runtime.ActionInterruptedException
import dev.kotlinds.pokemonclient.runtime.InputProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.Screens
import me.nathanfallet.aiplayspokemon.games.GameProfile
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.WalkthroughState
import java.nio.file.Path

/**
 * Plays a game whose reader was **calibrated** instead of written: the same observe → decide → act loop as
 * [PokemonPlayer], but its state is the reader's values (`readers/<id>.json`, see `docs/readers.md`) and its actions
 * are the console's own ([ReaderActions]): press, hold, tap, wait.
 *
 * This is what makes a typed agent work for a game nobody wrote code for: the model is told what the reader sees
 * (`screen 84 (in mission)`, `menu 177 (single player menu)`) and what its last action changed, and it plays from
 * that. Nothing here is game-specific: point it at another calibrated reader and it plays that game.
 */
class ReaderPlayer(
    private val emulator: ConsoleHost,
    private val reader: ReaderSpec,
    private val profile: GameProfile,
    override val model: DecisionModel,
    private val scope: CoroutineScope,
    private val settings: () -> PlayerSettings,
    override val walkthrough: WalkthroughState = WalkthroughState(),
    runsDirectory: Path? = null,
    /** The stream's voice for decisions (null: silent). Asked per use, so toggling needs no new player. */
    private val narrator: () -> DecisionNarrator? = { null },
) : AgentPlayer {

    /** Kept for the panel's run log (the walkthrough holds the chapters this run has reached). */
    private val runsDirectory = runsDirectory
    private var job: Job? = null
    private val history = ArrayDeque<String>()
    private val screenState = ScreenState()

    /** The hash of each screen as the previous action left it: what this action's change is measured against. */
    private val lastSeenHashes = mutableMapOf<Screens.Screen, Long>()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override var objective: String = profile.objective

    /** A reader game has no typed actions to probe: what an action did is read from the observations instead. */
    private val inputProbe = InputProbe { _: Memory -> emptySet() }

    override fun play() {
        if (job?.isActive == true) return
        _state.update { it.copy(playing = true, error = null) }
        job = scope.launch { runCatching { loop() }.onFailure(::reportFailure) }
    }

    override fun pause() {
        job?.cancel()
        job = null
        emulator.resume()
        _state.update { it.copy(playing = false, thinking = false) }
    }

    private suspend fun loop() {
        var stagnation = 0
        var lastAction: String? = null
        var lastChange: String? = null
        var reports: Map<Screens.Screen, ActionReport> = emptyMap()

        while (scope.isActive && state.value.playing) {
            val before = observe()
            val chapter = walkthrough.chapter
            // What each screen shows: how it moved while the last action ran, whether it matches a walkthrough
            // landmark, and the model's own reading of each screen — asked per screen, only once that screen settles
            // (that is when a reading is useful and reliable, and it is one more model call per screen).
            val frame = emulator.frames.value
            if (settings().readScreen && model.generative) {
                for (screen in Screens.Screen.entries) {
                    val settled = (reports[screen]?.stableFrames ?: 0) >= SCREEN_READ_STABLE_FRAMES
                    if (settled) {
                        screenState.read(model, frame, screen) { failure ->
                            emulator.systemLog.add("screen read failed: $failure")
                        }
                    }
                }
            }
            val view = screenState.view(frame, chapter, reports)
            _state.update { it.copy(screenRead = view.summary()) }
            val request = ChoiceRequest(
                state = ReaderPrompt.state(
                    profile = profile,
                    objective = objective,
                    chapter = chapter,
                    values = before,
                    screen = view,
                    lastAction = lastAction,
                    lastChange = lastChange,
                    history = history.toList(),
                    stagnation = stagnation,
                ),
                instructions = ReaderPrompt.instructions(profile, chapter, model.generative),
                options = ReaderActions.options(),
                allowSequence = settings().allowSequences,
                reasoning = settings().reasoning,
                allowNote = settings().modelNotes,
            )

            if (settings().pauseWhileThinking) emulator.pause()
            val startedAt = System.nanoTime()
            _state.update { it.copy(thinking = true, lastRequestState = request.state) }
            val result = try {
                model.choose(request)
            } finally {
                emulator.resume()
                _state.update { it.copy(thinking = false) }
            }
            val latencyMillis = (System.nanoTime() - startedAt) / 1_000_000

            val chosen = result.choice.takeIf { ReaderActions.plan(it) != null }
                ?: error("the model chose '${result.choice}', which is not one of the options")
            val sequence = (listOf(chosen) + result.then.takeIf { settings().allowSequences }.orEmpty())
                .filter { ReaderActions.plan(it) != null }

            val performed = mutableListOf<String>()
            var problem: String? = null
            for (option in sequence) {
                val (actionReports, outcome) = perform(option)
                reports = actionReports
                performed += option
                if (outcome != null) {
                    problem = outcome
                    break
                }
            }

            val after = observe()
            val change = ReaderActions.describeChange(before, after)
            val summary = ReaderActions.summary(before) + " | " + view.summary()
            stagnation = if (change == "nothing changed") stagnation + 1 else 0
            lastAction = performed.joinToString(" then ")
            lastChange = change

            val decision = Decision(
                number = state.value.decisions + 1,
                summary = summary,
                actions = performed,
                confidence = result.confidence,
                probabilities = result.probabilities.entries.sortedByDescending { it.value }.map { it.key to it.value },
                latencyMillis = latencyMillis,
                model = result.model,
                thought = result.thought,
                note = result.note,
                problem = problem,
                change = change,
            )
            history += ReaderPrompt.historyLine(decision.number, lastAction, change, summary)
            while (history.size > ReaderPrompt.HISTORY_SIZE) history.removeFirst()
            narrator()?.narrateDecision(decision)

            _state.update {
                it.copy(
                    lastDecision = decision,
                    history = (listOf(decision) + it.history).take(HISTORY_SIZE),
                    decisions = it.decisions + 1,
                    presses = it.presses + performed.size,
                    inputTokens = it.inputTokens + result.inputTokens,
                    outputTokens = it.outputTokens + result.outputTokens,
                    costUsd = it.costUsd + (result.costUsd ?: 0.0),
                    totalLatencyMillis = it.totalLatencyMillis + latencyMillis,
                    note = result.note ?: it.note,
                )
            }
            delay(DECISION_PAUSE_MILLIS)
        }
    }

    /** Reads the reader's observations out of the game, or an empty map when the console can't be read right now. */
    private suspend fun observe(): Map<String, String> =
        runCatching { emulator.observe { memory -> ReaderCatalog.observe(reader, memory) } }.getOrDefault(emptyMap())

    /**
     * Carries [option] out on the console, watching **each screen** frame by frame while it runs: that is how the app
     * knows which screen the game answered on, and which one has settled (the one waiting for input). Returns the
     * report per screen, or the reason it failed.
     *
     * A screen is compared within [STABLE_BITS], the same tolerance the landmark matcher uses, because menus blink and
     * scroll: comparing frames exactly would call every animated screen "moving" and nothing would ever be read.
     */
    private suspend fun perform(option: String): Pair<Map<Screens.Screen, ActionReport>, String?> {
        val plan = ReaderActions.plan(option) ?: return emptyMap<Screens.Screen, ActionReport>() to "unknown action '$option'"
        val startHashes = lastSeenHashes.toMap()
        val previous = mutableMapOf<Screens.Screen, Long>()
        val stableRun = mutableMapOf<Screens.Screen, Int>()
        val changed = mutableMapOf<Screens.Screen, Boolean>()
        val failure = try {
            emulator.lease(option, inputProbe) {
                // One frame at a time: both screens are looked at after each of them, so "which one answered?" and
                // "how long has each been still?" are measured, not guessed.
                repeat(plan.heldFrames) {
                    step(1, plan.input())
                    observeFrame { frame -> for (screen in Screens.Screen.entries) previous[screen] = Screens.hashOf(frame, screen) }
                }
                repeat(plan.settleFrames) {
                    step(1)
                    observeFrame { frame ->
                        for (screen in Screens.Screen.entries) {
                            val hash = Screens.hashOf(frame, screen)
                            val before = previous[screen]
                            if (before != null && FrameHash.distance(hash, before) <= STABLE_BITS) {
                                stableRun[screen] = (stableRun[screen] ?: 0) + 1
                            } else {
                                stableRun[screen] = 0
                            }
                            startHashes[screen]?.let { start ->
                                if (FrameHash.distance(hash, start) > STABLE_BITS) changed[screen] = true
                            }
                            previous[screen] = hash
                        }
                    }
                }
            }
            null
        } catch (interrupted: ActionInterruptedException) {
            // The human took the controller: stop playing, as the typed player does.
            pause()
            "interrupted by the human"
        }
        previous.forEach { (screen, hash) -> lastSeenHashes[screen] = hash }
        val reports = Screens.Screen.entries.associateWith { screen ->
            ActionReport(changed = changed[screen] == true, stableFrames = stableRun[screen] ?: 0)
        }
        return reports to failure
    }

    /** Feeds the frame the console just produced to [block]. */
    private inline fun observeFrame(block: (dev.kotlinds.pokemonclient.console.Frame) -> Unit) {
        emulator.frames.value?.let(block)
    }

    override fun toggle() = if (state.value.playing) pause() else play()

    private fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) return
        val message = (failure as? DecisionException)?.message ?: failure.message ?: failure::class.java.simpleName
        _state.update { it.copy(playing = false, thinking = false, error = message) }
    }

    private companion object {
        /** Past decisions the prompt carries (the history line per decision). */
        const val HISTORY_SIZE = 50

        /** A breather between two decisions, so the panel and the screen can keep up. */
        const val DECISION_PAUSE_MILLIS = 50L

        /**
         * Only ask the model to read the screen once the picture has settled this long: a game waiting for input is
         * worth reading (and easy to read), while an animated transition is not.
         */
        const val SCREEN_READ_STABLE_FRAMES = 20

        /** How many bits two frames may differ by and still count as the same picture (as the landmark matcher). */
        const val STABLE_BITS = 6
    }
}
