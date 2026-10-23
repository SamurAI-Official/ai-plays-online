package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.TouchPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [humanTouchPoint]: a press anywhere on the frame (both DS screens stacked) becomes a point of the bottom screen
 * only — the mapping behind [ConsoleHost.touch], where the UI sends fractions of the whole frame.
 */
class HumanTouchPointTest {

    @Test
    fun theTopScreenIsNotTouchable() {
        assertNull(humanTouchPoint(0.5f, 0.0f, 256, 192))
        assertNull(humanTouchPoint(0.5f, 0.25f, 256, 192))
        assertNull(humanTouchPoint(0.9f, 0.49f, 256, 192))
    }

    @Test
    fun theBottomScreenMapsTheLowerHalfOfTheFrame() {
        assertEquals(TouchPoint(0, 0), humanTouchPoint(0f, 0.5f, 256, 192))
        assertEquals(TouchPoint(128, 96), humanTouchPoint(0.5f, 0.75f, 256, 192))
        // The right and bottom edges clamp to the last pixel (a fraction of 1 would be one past it).
        assertEquals(TouchPoint(255, 191), humanTouchPoint(1f, 1f, 256, 192))
    }
}