package me.nathanfallet.aiplayspokemon.agent

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
import kotlinx.serialization.json.JsonObject
import dev.kotlinds.pokemonclient.runtime.Recorder
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionException
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.decision.ThenStep
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.view.StateView
import dev.kotlinds.pokemonclient.world.Area
import me.nathanfallet.aiplayspokemon.agent.VisionVerifier
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.GameProfile
import me.nathanfallet.aiplayspokemon.games.WalkthroughState
import java.nio.file.Path
import kotlin.random.Random

/**
 * The autonomous player: an observe → decide → act → remember loop over an [AgentSession].
 *
 * 1. **Observe**: wait until the game expects input, read it, list the options of the current mode.
 * 2. **Decide**: ask the decision [model] which option to take (and, for generative models, maybe a
 *    short sequence and an updated note). In hybrid mode, the [planner] is asked instead when the
 *    model hesitates, when nothing has progressed for a while, or periodically; its note becomes the
 *    goal the fast model follows.
 * 3. **Act / remember**: the session carries the choice out and records what changed.
 *
 * The game is frozen while the AI thinks (when enabled), and the human keeps the keyboard at all times.
 */
class PokemonPlayer(
    emulator: ConsoleHost,
    private val game: PokemonGame,
    recorder: Recorder,
    override val model: DecisionModel,
    private val planner: DecisionModel?,
    private val scope: CoroutineScope,
    settings: () -> PlayerSettings,
    runsDirectory: Path?,
    /** The game's profile: the per-game objective and conventions the prompts carry. */
    private val profile: GameProfile = GameCatalog.generic,
    /** The walkthrough in play, shared with the panel (chapters, hints, which one we are on). */
    override val walkthrough: WalkthroughState = WalkthroughState(),
    /**
     * What a previous session learned of this game ([SessionStore], [AgentMemory.of]): the places, the dialogues, the map
     * it explored, the tiles it was refused at and where the story stood. A fresh [AgentMemory] when this game has not
     * been played before.
     */
    memory: AgentMemory = AgentMemory(),
    /** Called after each decision, so the app can write down what this session learned ([SessionStore]). */
    onMemory: (() -> Unit)? = null,
    /** The stream's voice for decisions (null: silent). Asked per use, so toggling needs no new player. */
    private val narrator: () -> DecisionNarrator? = { null },
) : AgentPlayer {

    /** Everything the AI sees and acts with, including the memory it keeps between sessions. */
    val session = AgentSession(emulator, game, recorder, settings, profile, walkthrough, memory, onMemory)

    /** What this player remembers of the game, in one line for the panel (null when it remembers nothing). */
    override val memorySummary: String? get() = session.memory.summary
    private var lastChapter: String? = null
    private val settings get() = session.settings()
    private val emulator = emulator
    private val stats = RunStats(runsDirectory, "${model.name}${planner?.let { " + planner ${it.name}" } ?: ""}")
    private var job: Job? = null
    private var decisionsSincePlanner = 0

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    override var objective: String
        get() = session.objective
        set(value) {
            session.objective = value
        }

    override fun play() {
        if (job?.isActive == true) return
        _state.update { it.copy(playing = true, error = null) }
        job = scope.launch {
            try {
                while (isActive) {
                    try {
                        step()
                        _state.update { it.copy(error = null) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        // Network hiccup, rate limit, an answer that couldn't be parsed...: show it, wait, retry.
                        // Errors that won't fix themselves (bad API key, invalid request) stop the loop.
                        if (error is DecisionException && !error.retryable) throw error
                        _state.update { it.copy(error = error.message ?: error.toString()) }
                        narrator()?.narrateError(error.message ?: error.toString())
                        delay(2_000)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: error.toString()) }
                narrator()?.narrateError(error.message ?: error.toString())
            } finally {
                _state.update { it.copy(playing = false, thinking = false) }
            }
        }
    }

    override fun pause() {
        job?.cancel()
        job = null
    }

    override fun toggle() = if (state.value.playing) pause() else play()

    /** One full observe → decide → act → remember iteration. */
    /**
     * Records the walkthrough chapter the run is on, once per chapter: typed games advance from their own verified
     * progress, screen-only games from a screen landmark or the panel. This is what makes runs of different games
     * comparable at the chapter level.
     */
    private fun chapterMilestones(decisions: Int, presses: Int, inputTokens: Long, cost: Double): List<RunStats.Milestone> {
        val chapter = walkthrough.chapter ?: return emptyList()
        if (chapter.id == lastChapter) return emptyList()
        lastChapter = chapter.id
        return listOfNotNull(stats.chapterReached(chapter, decisions, presses, inputTokens, cost))
    }

    private suspend fun step() {
        // Time only passes while the emulator runs: don't spend API calls on a game paused by the user.
        if (!emulator.status.value.running) {
            delay(100)
            return
        }
        val turn = session.prepare()
        if (state.value.decisions == 0) stats.start(turn.gameState)
        val area = areaOf(game, turn.gameState)
        // One thinking (game frozen) for the whole decide → verify loop: a refused movement is fed back as
        // `previous_movement_failure` and re-asked within the same thinking, not on the next turn.
        val startedAt = System.nanoTime()
        val gated = session.thinking {
            _state.update { it.copy(thinking = true) }
            try {
                decideGated(turn, area)
            } finally {
                _state.update { it.copy(thinking = false) }
            }
        }
        val latencyMillis = (System.nanoTime() - startedAt) / 1_000_000
        val inputTokens = state.value.inputTokens + gated.inputTokens
        val outputTokens = state.value.outputTokens + gated.outputTokens
        val cost = state.value.costUsd + gated.costUsd

        val committed = when (gated) {
            // Pre-commit vision: a refused movement never reaches the console. Its decision is recorded with the
            // stop reason (and, for a wall or a person, the tile remembered as blocked), so the model picks another
            // action instead.
            is Gated.Refused -> {
                val decision = Decision(
                    number = state.value.decisions + 1,
                    summary = StateView.summary(turn.gameState),
                    actions = listOf(gated.blocked.action),
                    confidence = gated.result.confidence,
                    probabilities = gated.result.probabilities.entries.sortedByDescending { it.value }.map { it.key to it.value },
                    latencyMillis = latencyMillis,
                    model = gated.result.model,
                    thought = gated.result.thought,
                    note = gated.result.note,
                    byPlanner = gated.byPlanner,
                    problem = null,
                    stop = gated.blocked.describe(),
                )
                _state.update {
                    it.copy(
                        lastDecision = decision,
                        history = (listOf(decision) + it.history).take(HISTORY_SIZE),
                        decisions = it.decisions + 1,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        costUsd = cost,
                        totalLatencyMillis = it.totalLatencyMillis + latencyMillis,
                        note = session.memory.note,
                        plannerGoal = session.plannerGoal,
                    )
                }
                return
            }
            is Gated.Committed -> gated
        }

        val report = session.act(
            turn,
            committed.choice,
            // A typed game has its own chain stop rules, so a step's own condition (a reader-game thing) is not used
            // here: only the option ids of the steps are taken.
            committed.then.map { it.action },
            committed.result.thought,
            committed.result.note,
        )

        val decision = Decision(
            number = state.value.decisions + 1,
            summary = StateView.summary(turn.gameState),
            actions = report.performed.ifEmpty { listOf(committed.choice.key) },
            confidence = committed.result.confidence,
            probabilities = committed.result.probabilities.entries.sortedByDescending { it.value }.map { it.key to it.value },
            latencyMillis = latencyMillis,
            model = committed.result.model,
            thought = committed.result.thought,
            note = committed.result.note,
            byPlanner = committed.byPlanner,
            problem = report.problem,
            stop = report.stop,
        )
        val presses = session.actionsDone
        // Counted before the update (this loop is their only writer): the run's milestones, also said aloud as
        // they are reached — and checked once, where inside the update a retry of the copy could have run twice.
        val decisions = state.value.decisions + 1
        val reached = stats.check(report.after, decisions, presses, inputTokens, cost) +
            chapterMilestones(decisions, presses, inputTokens, cost)
        _state.update {
            it.copy(
                lastDecision = decision,
                history = (listOf(decision) + it.history).take(HISTORY_SIZE),
                decisions = it.decisions + 1,
                presses = presses,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = cost,
                totalLatencyMillis = it.totalLatencyMillis + latencyMillis,
                milestones = it.milestones + reached,
                note = session.memory.note,
                plannerGoal = session.plannerGoal,
            )
        }
        narrator()?.narrateDecision(decision)
        reached.forEach { milestone -> narrator()?.narrateMilestone(milestone.name) }
    }

    /**
     * The decide → verify loop's outcome: [Committed]'s choice passed the vision gate and is safe to carry out,
     * [Refused]'s was stopped before the console (its reason is recorded, never pressed).
     */
    private sealed interface Gated {
        val inputTokens: Int
        val outputTokens: Int
        val costUsd: Double

        data class Committed(
            val choice: AgentSession.Option,
            val then: List<ThenStep>,
            val result: ChoiceResult,
            val byPlanner: Boolean,
            override val inputTokens: Int,
            override val outputTokens: Int,
            override val costUsd: Double,
        ) : Gated

        data class Refused(
            val blocked: VisionVerifier.BlockedAction,
            val result: ChoiceResult,
            val byPlanner: Boolean,
            override val inputTokens: Int,
            override val outputTokens: Int,
            override val costUsd: Double,
        ) : Gated
    }

    /**
     * Asks the model until its choice passes the vision gate (at most [MAX_BLOCKED_RETRIES] extra asks, so at most
     * 1 + [MAX_BLOCKED_RETRIES] model calls per turn): a refused movement is set as `previous_movement_failure` —
     * its tile remembered as blocked too, when the refusal names one (a message box refuses the press with no tile,
     * so the wall map is never told a lie) — so the model corrects itself within this same thinking (the game stays
     * frozen) instead of next turn. Tokens and cost accumulate across every ask of the turn.
     */
    private suspend fun decideGated(turn: AgentSession.Turn, area: Area?): Gated {
        var attempts = 0
        var inputTokens = 0
        var outputTokens = 0
        var costUsd = 0.0
        while (true) {
            val (result, byPlanner) = decide(turn)
            inputTokens += result.inputTokens
            outputTokens += result.outputTokens
            costUsd += result.costUsd ?: 0.0
            val chosenId = pick(result)
            val choice = turn.resolve(chosenId)
                ?: throw DecisionException("${model.name} chose '$chosenId', which isn't an option now", retryable = true)
            if (byPlanner) result.note?.let { session.plannerGoal = it }
            val verdict = VisionVerifier.check(choice.action, turn.gameState.field, area, turn.gameState.screen)
            // A refusal is told and retried either way; only a wall/person verdict names a tile to remember as
            // blocked (a message box refuses the press with no tile: the map must not learn a false wall).
            val blocked = when (verdict) {
                VisionVerifier.Verdict.Allowed -> null
                is VisionVerifier.Verdict.Blocked -> {
                    turn.gameState.field?.let { session.memory.noteBlocked(it.mapId, verdict.destination, verdict.reason) }
                    VisionVerifier.BlockedAction(choice.key, verdict.destination, verdict.reason)
                }
                is VisionVerifier.Verdict.Refused -> VisionVerifier.BlockedAction(choice.key, null, verdict.reason)
            }
            if (blocked != null) {
                // Read once by the next request as `previous_movement_failure`: the retry below sees it; on the
                // last attempt it stays set, so the next turn is told about the refusal too.
                session.movementBlocked(blocked.describe())
                attempts++
                if (attempts > MAX_BLOCKED_RETRIES) {
                    return Gated.Refused(blocked, result, byPlanner, inputTokens, outputTokens, costUsd)
                }
                continue
            }
            return Gated.Committed(
                choice,
                if (chosenId == result.choice) result.then else emptyList(),
                result,
                byPlanner,
                inputTokens,
                outputTokens,
                costUsd,
            )
        }
    }

    /** Asks the model, or in hybrid mode the planner when needed. Returns the result and whether the planner decided. */
    private suspend fun decide(turn: AgentSession.Turn): Pair<ChoiceResult, Boolean> {
        val planner = planner.takeIf { settings.mode == ControlMode.HYBRID }
        val periodic = planner != null && (session.plannerGoal == null || decisionsSincePlanner >= settings.hybridPlannerEvery ||
            session.memory.decisionsWithoutProgress >= STAGNATION_DECISIONS)
        if (planner != null && periodic) return askPlanner(planner, turn)

        val request = session.request(turn, generative = model.generative)
        _state.update { it.copy(lastRequestState = request.state) }
        val result = model.choose(request)
        decisionsSincePlanner++
        val hesitant = (result.confidence ?: 1.0) < settings.hybridConfidenceThreshold
        if (planner != null && hesitant) return askPlanner(planner, turn)
        return result to false
    }

    private suspend fun askPlanner(planner: DecisionModel, turn: AgentSession.Turn): Pair<ChoiceResult, Boolean> {
        val request = session.request(turn, generative = true, planner = true)
        _state.update { it.copy(lastRequestState = request.state) }
        decisionsSincePlanner = 0
        return planner.choose(request) to true
    }

    /** The chosen option: the model's answer, or a sample from its probabilities when enabled. */
    private fun pick(result: ChoiceResult): String {
        if (!settings.sampleProbabilities || result.confidence == null) return result.choice
        var remaining = Random.nextDouble()
        for ((option, probability) in result.probabilities) {
            remaining -= probability
            if (remaining <= 0) return option
        }
        return result.choice
    }

    private companion object {
        const val HISTORY_SIZE = 50
        const val STAGNATION_DECISIONS = 10

        /** Extra model asks allowed within one thinking after the vision gate refuses a chosen movement. */
        const val MAX_BLOCKED_RETRIES = 2
    }
}

/** Everything the UI shows about the player. */
data class PlayerState(
    val playing: Boolean = false,
    /** True while the model is thinking (the game is frozen if "pause while thinking" is on). */
    val thinking: Boolean = false,
    /** The full state sent with the last request (screen + memory), exactly as the model received it. */
    val lastRequestState: JsonObject? = null,
    val lastDecision: Decision? = null,
    /** Most recent decisions first. */
    val history: List<Decision> = emptyList(),
    val decisions: Int = 0,
    val presses: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val totalLatencyMillis: Long = 0,
    val milestones: List<RunStats.Milestone> = emptyList(),
    /** What the model can see of the picture right now (a reader game: the screen reading and its stability). */
    val screenRead: String? = null,
    val note: String? = null,
    val plannerGoal: String? = null,
    val error: String? = null,
) {
    val averageLatencyMillis: Long get() = if (decisions == 0) 0 else totalLatencyMillis / decisions
}

/** One decision, with the model's probability for every option. */
data class Decision(
    val number: Int,
    /** The state the decision was made on, in one line (what the history shows). */
    val summary: String,
    /** Actions carried out (the choice, then the sequence if any). */
    val actions: List<String>,
    /** How certain the model was (0..1), or null when it can't tell. */
    val confidence: Double?,
    /** Options sorted by probability, most likely first. */
    val probabilities: List<Pair<String, Double>>,
    val latencyMillis: Long,
    val model: String,
    /** The model's own explanation, when it gives one (LLMs). */
    val thought: String? = null,
    val note: String? = null,
    /** Hybrid mode: true when the planner made this decision. */
    val byPlanner: Boolean = false,
    /** Why a step failed, if one did. */
    val problem: String? = null,
    /**
     * Why the sequence stopped before its end although nothing failed (a step's own condition no longer holds, nothing
     * changed: see [me.nathanfallet.aiplayspokemon.agent.ReaderChain]).
     */
    val stopped: String? = null,
    /** Why the sequence ended early by one of its rules, nothing failing (a step not offered, nothing happening...). */
    val stop: String? = null,
    /**
     * What the action changed in the game's own state, when the player can tell (a reader-based player compares the
     * reader's values before and after): `screen: 19 → 18`. Null for a typed player, which knows what it did.
     */
    val change: String? = null,
)
