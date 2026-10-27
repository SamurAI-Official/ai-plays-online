package me.nathanfallet.aiplayspokemon.agent

import me.nathanfallet.aiplayspokemon.decision.StepCondition
import me.nathanfallet.aiplayspokemon.decision.ThenStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a reader player's plan stops: the model's own condition on a step (`when`), and the plain fact that the game
 * ignored the previous one. Both are read from the reader, and both are reported the way the typed chains report their
 * stops (`not_done` and why), so a model reads them the same way in either game.
 */
class ReaderChainTest {

    private val mainMenu = mapOf("screen" to "main menu (19)", "menu" to "main menu (70)")
    private val subMenu = mapOf("screen" to "single player menu (18)", "menu" to "single player menu (177)")

    private fun plan(choice: String = "press_a", vararg then: ThenStep) =
        ReaderChain.plan(choice, then.toList(), allowSequences = true) { it in setOf("press_a", "press_b", "wait") }

    @Test
    fun thePlanIsTheChoiceThenTheStepsTheModelAdded() {
        val plan = plan(then = arrayOf(ThenStep("press_a"), ThenStep("wait", StepCondition(isText = "main menu (19)"))))
        assertEquals(listOf("press_a", "press_a", "wait"), plan.map { it.action })
        assertNull(plan[0].expects, "the choice is what the model decided to do, with no condition of its own")
        assertEquals("wait (if `screen` is \"main menu (19)\")", plan[2].described)
        // No sequences allowed: the choice alone, whatever the model added.
        assertEquals(listOf("press_a"), ReaderChain.plan("press_a", listOf(ThenStep("wait")), false) { true }.map { it.action })
        // A step that is not an option is not a step (the same rule the choice follows).
        assertEquals(listOf("press_a"), plan(then = arrayOf(ThenStep("teleport"))).map { it.action })
        // And the limit is the one every chain uses.
        val many = Array(ReaderChain.MAX_THEN + 4) { ThenStep("press_a") }
        assertEquals(1 + ReaderChain.MAX_THEN, plan(then = many).size)
    }

    @Test
    fun aStepWithAConditionRunsOnlyWhileTheGameShowsIt() {
        val step = ReaderChain.Step("press_a", StepCondition(isText = "single player menu (18)"))
        assertNull(ReaderChain.verdict(step, subMenu, moved = true), "the game is where the step expected it")
        val stop = ReaderChain.verdict(step, mainMenu, moved = true)
        assertTrue(stop != null)
        assertEquals(ReaderChain.CONDITION_FAILED, stop.code)
        assertTrue(stop.message.contains("single player menu (18)"), stop.message)
        assertTrue(stop.message.contains("main menu (19)"), "the message says what the game shows instead: ${stop.message}")
        assertEquals(listOf("press_a (if `screen` is \"single player menu (18)\")"), stop.notDone)
    }

    @Test
    fun aStepWithoutAConditionNeedsTheGameToHaveMoved() {
        val step = ReaderChain.Step("press_a")
        assertNull(ReaderChain.verdict(step, subMenu, moved = true))
        val stop = ReaderChain.verdict(step, mainMenu, moved = false)
        assertTrue(stop != null)
        assertEquals(ReaderChain.NOTHING_CHANGED, stop.code)
        assertTrue(stop.message.contains("nothing changed"), stop.message)
        assertTrue(stop.message.contains("press_a"), stop.message)
    }

    @Test
    fun anExplicitConditionWinsOverTheImplicitRule() {
        // "if we are still on the main menu, press B" is a plan, not a mistake: the model knows what it wants.
        val step = ReaderChain.Step("press_b", StepCondition(isText = "main menu (19)"))
        assertNull(ReaderChain.verdict(step, mainMenu, moved = false))
    }

    @Test
    fun aConditionReadsTheValueTheWayTheModelWritesIt() {
        val screen = mapOf("screen" to "single player menu (18)")
        // Any of the three readable forms: the whole text, the name, or the number.
        assertTrue(StepCondition(isText = "single player menu (18)").holds(screen))
        assertTrue(StepCondition(isText = "Single Player Menu").holds(screen))
        assertTrue(StepCondition(isText = "18").holds(screen))
        // Never a substring: `1` is not `18`, and `menu` is not `single player menu`.
        assertTrue(!StepCondition(isText = "1").holds(screen))
        assertTrue(!StepCondition(isText = "menu").holds(screen))
        // `is_not` is the same matching, negated.
        assertTrue(StepCondition(isNotText = "main menu (19)").holds(screen))
        assertTrue(!StepCondition(isNotText = "18").holds(screen))
        // A value the reader does not have cannot satisfy a condition, and one with no constraint is not one.
        assertTrue(!StepCondition(value = "menu", isText = "anything").holds(screen))
        assertTrue(StepCondition().holds(screen))
    }

    @Test
    fun aConditionCanBeAboutAnyValueOfTheReader() {
        val values = mapOf("screen" to "in mission (84)", "gold" to "gold 120", "menu" to "not now")
        assertTrue(StepCondition(value = "gold", isText = "120").holds(values))
        assertTrue(StepCondition(value = "gold", isText = "gold").holds(values))
        assertTrue(StepCondition(value = "menu", isText = "not now").holds(values), "a guard that is off is readable")
        assertEquals("`menu` is \"not now\" and not \"anything\"", StepCondition(value = "menu", isText = "not now", isNotText = "anything").describe())
    }
}
