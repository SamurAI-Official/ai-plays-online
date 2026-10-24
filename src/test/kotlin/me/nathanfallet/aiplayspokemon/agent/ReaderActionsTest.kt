package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderActionsTest {

    @Test
    fun everyOptionHasAPlanAndEveryIdIsUnique() {
        val options = ReaderActions.options()
        assertEquals(options.keys.size, options.keys.toSet().size, "no duplicate option id")
        for (id in options.keys) {
            assertTrue(ReaderActions.plan(id) != null, "'$id' is offered but has no plan")
        }
        assertTrue(options.containsKey("press_a"))
        assertTrue(options.containsKey("hold_down"))
        assertTrue(options.containsKey("wait"))
        assertTrue(options.containsKey("touch_128_96"))
    }

    @Test
    fun aPressIsShortAndSettled() {
        val plan = ReaderActions.plan("press_a")!!
        assertEquals(setOf(Button.A), plan.buttons)
        assertEquals(ReaderActions.PRESS_FRAMES, plan.heldFrames)
        assertEquals(ReaderActions.SETTLE_FRAMES, plan.settleFrames)
        assertEquals(setOf(Button.A), plan.input().buttons)
    }

    @Test
    fun aHoldHoldsALongerAndStillSettles() {
        val plan = ReaderActions.plan("hold_up")!!
        assertEquals(setOf(Button.UP), plan.buttons)
        assertEquals(ReaderActions.HOLD_FRAMES, plan.heldFrames)
        assertEquals(ReaderActions.SETTLE_FRAMES, plan.settleFrames)
    }

    @Test
    fun waitingHoldsNothing() {
        val plan = ReaderActions.plan("wait")!!
        assertTrue(plan.buttons.isEmpty())
        assertEquals(0, plan.heldFrames)
        assertEquals(ReaderActions.WAIT_FRAMES, plan.settleFrames)
        assertEquals(ReaderActions.WAIT_FRAMES * 2, ReaderActions.plan("wait_long")!!.settleFrames)
    }

    @Test
    fun aTouchIsAPointOnTheTouchScreen() {
        val plan = ReaderActions.plan("touch_64_160")!!
        assertEquals(TouchPoint(64, 160), plan.touch)
        assertEquals(TouchPoint(64, 160), plan.input().touch)
    }

    @Test
    fun anInventedOptionHasNoPlan() {
        assertNull(ReaderActions.plan("teleport"))
        assertNull(ReaderActions.plan("touch_999"))
        assertNull(ReaderActions.plan("hold_"))
    }

    @Test
    fun whatChangedIsDescribedForTheHistory() {
        assertEquals(
            "screen: 19 → 18",
            ReaderActions.describeChange(mapOf("screen" to "19"), mapOf("screen" to "18")),
        )
        assertEquals(
            "screen: 19 → 18, menu: 70 → 0",
            ReaderActions.describeChange(mapOf("screen" to "19", "menu" to "70"), mapOf("screen" to "18", "menu" to "0")),
        )
        assertEquals(
            "nothing changed",
            ReaderActions.describeChange(mapOf("screen" to "19"), mapOf("screen" to "19")),
        )
        assertEquals("wood: ? → 3", ReaderActions.describeChange(emptyMap(), mapOf("wood" to "3")))
    }

    @Test
    fun theSummaryIsOneLine() {
        assertEquals("screen 84 (in mission) · menu 0", ReaderActions.summary(mapOf("screen" to "84 (in mission)", "menu" to "0")))
    }
}
