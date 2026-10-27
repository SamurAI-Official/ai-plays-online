package me.nathanfallet.aiplayspokemon.decision

import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * A decision model: given a situation, picks one option from a closed set, with a probability for
 * each option. This is the "brain" of the player.
 *
 * The agent only depends on this interface, not on a specific model or API. Implementations:
 * - [me.nathanfallet.aiplayspokemon.decision.jev.JevDecisionModel]: Jev, TypeSafe's System One
 *   model, the one this project is about (fast, calibrated probabilities for every option);
 * - [me.nathanfallet.aiplayspokemon.decision.llm.LlmDecisionModel]: any LLM Koog can talk to
 *   (OpenAI, Anthropic, OpenRouter, local Ollama...), to compare how different AIs play.
 */
interface DecisionModel {
    /** Shown in the UI, e.g. "Jev (jev-latest)". */
    val name: String

    /**
     * True for models that generate text (LLMs): they can reason, keep a note and answer with a
     * sequence. False for pure decision models like Jev, which only pick an option.
     */
    val generative: Boolean get() = true

    suspend fun choose(request: ChoiceRequest): ChoiceResult
}

/** "Given [state], following [instructions], which of [options] (id → description)?" */
data class ChoiceRequest(
    val state: JsonObject,
    val instructions: String,
    val options: Map<String, String>,
    /**
     * Generative models only: they may also return a short sequence of further option ids to execute
     * after [ChoiceResult.choice] (like a player holding a direction for a few tiles).
     */
    val allowSequence: Boolean = false,
    /**
     * Generative models only: each step of that sequence may carry the state it expects ([ThenStep.expects]), and the
     * player only runs it while the game still shows that (reader games, see
     * [me.nathanfallet.aiplayspokemon.agent.ReaderChain]). Off for games whose chain has its own stop rules.
     */
    val allowConditions: Boolean = false,
    /** Generative models only: think step by step before answering (slower, usually smarter). */
    val reasoning: Boolean = true,
    /** Generative models only: they may rewrite a persistent note (their goal/plan), sent back next time. */
    val allowNote: Boolean = false,
)

/**
 * A condition a model puts on a step of its sequence: a value of the state it sees has to read (or not read) something
 * for the step to make sense. `{"value": "screen", "is": "single player menu (18)"}` in an answer.
 *
 * The matching is deliberately loose in one direction and strict in the other: what the model writes is compared to the
 * value as it is rendered (`single player menu (18)`), and it may write **any** of its three readable forms — the whole
 * text, the name before the number, or the number itself. It never matches on a substring, so `1` does not match `18`.
 */
data class StepCondition(
    /** The name of the value, as the state shows it (`screen` in a reader game). */
    val value: String = "screen",
    /** What the value has to read, or null to not constrain it. */
    val isText: String? = null,
    /** What the value must not read, or null to not constrain it. */
    val isNotText: String? = null,
) {

    /** True when [values] satisfies every part of this condition (a value that is not there at all does not). */
    fun holds(values: Map<String, String>): Boolean {
        val rendered = values[value] ?: return false
        return (isText == null || matches(rendered, isText)) && (isNotText == null || !matches(rendered, isNotText))
    }

    /** One sentence for the model and the history: `` `screen` is "single player menu (18)" ``. */
    fun describe(): String = buildString {
        append("`$value` is ")
        when {
            isText != null && isNotText != null -> append("\"$isText\" and not \"$isNotText\"")
            isText != null -> append("\"$isText\"")
            isNotText != null -> append("not \"$isNotText\"")
            else -> append("anything")
        }
    }

    /** True when [rendered] reads [want]: the whole text, the name before the number, or the number itself. */
    private fun matches(rendered: String, want: String): Boolean = want.trim().lowercase() in formsOf(rendered)

    /** The readable forms of a rendered value: `single player menu (18)` → itself, `single player menu`, `18`. */
    private fun formsOf(rendered: String): Set<String> {
        val text = rendered.trim().lowercase()
        val number = NUMBER.find(text)?.value ?: return setOf(text)
        return setOf(text, number, text.replace(number, "").trim().trimEnd('(', ')', ':', '=', '-').trim())
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private companion object {
        /** The number a value carries, in `single player menu (18)` or `gold 120`. */
        val NUMBER = Regex("""\d+""")
    }
}

/**
 * One step after [ChoiceResult.choice]: the option to run, and — in games whose state is a reader
 * ([ChoiceRequest.allowConditions]) — what the game has to show for it to make sense.
 *
 * The model writes it as a plain option id, or as an object with the condition it expects:
 * `{"action": "press_a", "when": {"value": "screen", "is": "single player menu (18)"}}`. Both forms are accepted, and
 * a step without a condition is only run while the game is still moving (see [me.nathanfallet.aiplayspokemon.agent.ReaderChain]).
 */
data class ThenStep(
    /** The option id to run (one of the request's options). */
    val action: String,
    /** What the game has to show for this step to make sense, or null when the model expects anything. */
    val expects: StepCondition? = null,
)

data class ChoiceResult(
    /** The chosen option id (one of the request's options). */
    val choice: String,
    /** Probability of each option (they sum to 1); models that can't estimate them put 1 on the choice. */
    val probabilities: Map<String, Double>,
    /** How certain the model is (0..1), or null when the model can't tell. */
    val confidence: Double?,
    /** The exact model that answered, e.g. "jev-1.13.0". */
    val model: String,
    val inputTokens: Int,
    /** Tokens generated by the model (output), when reported by the backend. */
    val outputTokens: Int = 0,
    /** A short explanation of the choice, for models that give one (LLMs); shown in the UI. */
    val thought: String? = null,
    /** Further options to execute after [choice], when [ChoiceRequest.allowSequence] was set. */
    val then: List<ThenStep> = emptyList(),
    /** The model's updated note, when [ChoiceRequest.allowNote] was set. */
    val note: String? = null,
    /** Cost of the decision in USD when the backend reports it (equivalent API price for subscriptions). */
    val costUsd: Double? = null,
)

/**
 * A failed decision. [retryable] is false when trying again can't help (bad API key, invalid
 * request...), which stops the player instead of retrying forever.
 */
open class DecisionException(message: String, val retryable: Boolean, cause: Throwable? = null) : IOException(message, cause)

/**
 * Who plays, selectable in the UI: our own loop with a [DecisionModel] (Jev or an LLM), or an
 * external agent connected to the app's MCP server.
 */
enum class DecisionBackend(val label: String) {
    /** An external agent (e.g. Claude Code) plays through the MCP server; our loop doesn't run. */
    MCP("MCP"),
    LLM("LLM"),
    JEV("Jev"),
}
