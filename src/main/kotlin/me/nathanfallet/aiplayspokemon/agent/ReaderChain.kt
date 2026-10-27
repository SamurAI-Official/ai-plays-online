package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.actions.ChainRunner
import me.nathanfallet.aiplayspokemon.decision.StepCondition
import me.nathanfallet.aiplayspokemon.decision.ThenStep

/**
 * How a reader player's sequence goes: what each step of the model's plan expects of the game, and why a plan stops
 * before its end.
 *
 * A reader game has no typed chain: there is no battle to change under the plan (`ChainStop.FoeChanged`) and no map to
 * leave. What breaks a plan here is that **the game did not do what the step assumed**, and there are two ways to see
 * it, both read from the reader itself:
 *
 * - the model's own expectation for a step (`when`: "press A, then press A again *if* we are in the submenu"), checked
 *   against the reader right after the previous step;
 * - the plain fact that nothing moved at all — in the reader **and** on the screens — so a step taken "because we are
 *   now somewhere else" is taken on a false assumption.
 *
 * A step the model left without a condition is only run while the game is moving; a step with one is run exactly when
 * its condition holds, whatever moved (the model knows what it wants: "if we are *still* on the main menu, press B").
 *
 * The stop is reported with the same vocabulary the typed chains use ([ChainStop] in pokemon-client): a stable `code`,
 * a one-sentence `message`, and the steps left as not done — the next state shows them as
 * `not_done` / `not_done_code` / `not_done_reason`, so a model that learned to read one chain reads the other.
 */
object ReaderChain {

    /** Further steps a model may add after its choice: the same limit as every other chain ([ChainRunner.MAX_THEN]). */
    const val MAX_THEN = ChainRunner.MAX_THEN

    /** One step of a plan: the option to run, and what the game has to show for it to make sense. */
    data class Step(val action: String, val expects: StepCondition? = null) {
        /** The step as the model sees it in the history: `press_a`, or `press_a (if `screen` is "single player menu (18)")`. */
        val described: String get() = expects?.let { "$action (if ${it.describe()})" } ?: action
    }

    /** Why a plan stopped before its end: the steps left, and why they were not done. */
    data class Stop(val code: String, val message: String, val notDone: List<String>)

    /**
     * The plan of one decision: the option the model chose, then the steps it added ([then], at most [MAX_THEN] of them,
     * and only the ones that are real options). [allowSequences] false means no plan at all: the choice, alone.
     */
    fun plan(choice: String, then: List<ThenStep>, allowSequences: Boolean, isOption: (String) -> Boolean): List<Step> =
        listOf(Step(choice)) + then
            .filter { allowSequences && isOption(it.action) }
            .take(MAX_THEN)
            .map { Step(it.action, it.expects) }

    /**
     * Whether the plan may go on with [next], now that the previous step ran and the reader shows [values]: null to
     * continue, or the reason to stop ([Stop]).
     *
     * [moved] is whether anything happened at all (the reader changed, or a screen did): the rule for a step with no
     * condition, because a step taken "because we are now somewhere else" makes no sense when we are not.
     */
    fun verdict(next: Step, values: Map<String, String>, moved: Boolean): Stop? = when {
        next.expects != null && !next.expects.holds(values) -> Stop(
            code = CONDITION_FAILED,
            message = "the next step only runs while ${next.expects.describe()}, and the reader shows " +
                "\"${values[next.expects.value] ?: "(nothing)"}\": it is not done",
            notDone = listOf(next.described),
        )

        next.expects == null && !moved -> Stop(
            code = NOTHING_CHANGED,
            message = "nothing changed after the last step, so `${next.action}` — which assumed it did — is not done",
            notDone = listOf(next.described),
        )

        else -> null
    }

    /** The next step's condition does not hold any more: the game went somewhere the model did not plan for. */
    const val CONDITION_FAILED = "CONDITION_FAILED"

    /** The previous step changed nothing at all: the game ignored it, so the rest of the plan rests on nothing. */
    const val NOTHING_CHANGED = "NOTHING_CHANGED"
}
