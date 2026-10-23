package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InputScriptTest {

    @Test
    fun anEmptyScriptHoldsNothing() {
        assertEquals(InputFrame.NONE, InputScript.parse(null).inputAt(0))
        assertEquals(InputFrame.NONE, InputScript.parse("   ").inputAt(120))
    }

    @Test
    fun aButtonIsHeldOverItsWholeRangeOnly() {
        val script = InputScript.parse("A@10-12")
        assertEquals(InputFrame.NONE, script.inputAt(9))
        assertEquals(InputFrame(setOf(Button.A)), script.inputAt(10))
        assertEquals(InputFrame(setOf(Button.A)), script.inputAt(12))
        assertEquals(InputFrame.NONE, script.inputAt(13))
    }

    @Test
    fun oneFrameStepsAndSeveralButtons() {
        assertEquals(InputFrame(setOf(Button.START)), InputScript.parse("START@7").inputAt(7))
        assertEquals(InputFrame(setOf(Button.A, Button.DOWN)), InputScript.parse("A+DOWN@1-2").inputAt(1))
    }

    @Test
    fun aTouchPointIsHeldLikeAButton() {
        val script = InputScript.parse("TOUCH:128,96@300-302")
        assertEquals(InputFrame.NONE, script.inputAt(299))
        assertEquals(InputFrame(emptySet(), TouchPoint(128, 96)), script.inputAt(300))
    }

    @Test
    fun aTouchCanBeCombinedWithAButton() {
        assertEquals(
            InputFrame(setOf(Button.A), TouchPoint(12, 34)),
            InputScript.parse("A+TOUCH:12,34@5-6").inputAt(5),
        )
    }

    @Test
    fun aLaterStepWinsWhenItOverlaps() {
        val script = InputScript.parse("DOWN@0-100;A@40-45")
        assertEquals(InputFrame(setOf(Button.DOWN)), script.inputAt(39))
        assertEquals(InputFrame(setOf(Button.A)), script.inputAt(40))
        assertEquals(InputFrame(setOf(Button.DOWN)), script.inputAt(46))
    }

    @Test
    fun stepsAreReportedBackAsTheyWereWritten() {
        assertEquals("A@10-12;TOUCH:128,96@300", InputScript.parse("A@10-12; TOUCH:128,96@300").toString())
    }

    @Test
    fun unreadableScriptsAreRefused() {
        for (bad in listOf("A@", "A@5-3", "FOO@1", "TOUCH:1@2", "5", "A@x-2")) {
            assertFailsWith<IllegalArgumentException>("'$bad' should be refused") { InputScript.parse(bad) }
        }
    }

    @Test
    fun touchedFramesCoverEveryStep() {
        val script = InputScript.parse("A@10-12;TOUCH:1,2@300")
        assertTrue(script.touchedFrames!!.contains(300))
        assertEquals(10, script.touchedFrames!!.first)
    }
}
