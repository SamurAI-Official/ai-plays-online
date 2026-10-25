package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Frame
import me.nathanfallet.aiplayspokemon.emulator.Screens.crop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreensTest {

    /** A screen-sized picture of [kind]: 0 flat, 1 darkening left to right, 2 stripes (dHash is a gradient hash). */
    private fun pattern(kind: Int) = IntArray(256 * 192) { index ->
        val x = index % 256
        val level = when (kind) {
            1 -> 255 - x
            2 -> if (x / 32 % 2 == 0) 0 else 255
            else -> 0
        }
        (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }

    private fun dsFrame(top: Int, bottom: Int) = Frame(256, 384, pattern(top) + pattern(bottom))

    @Test
    fun theTwoScreensAreTheTwoHalvesOfTheFrame() {
        val frame = dsFrame(FLAT, FLAT)
        assertEquals(listOf(0, 0, 256, 192), Screens.regionOf(frame, Screens.Screen.TOP))
        assertEquals(listOf(0, 192, 256, 192), Screens.regionOf(frame, Screens.Screen.BOTTOM))
        assertTrue(Screens.isTwoScreens(frame))
    }

    @Test
    fun aScreenIsCroppedOutOfTheFrame() {
        val frame = dsFrame(RAMP, STRIPES)
        val top = frame.crop(Screens.regionOf(frame, Screens.Screen.TOP))
        val bottom = frame.crop(Screens.regionOf(frame, Screens.Screen.BOTTOM))
        assertEquals(256, top.width)
        assertEquals(192, top.height)
        assertEquals(frame.pixels[0], top.pixels[0])
        assertEquals(frame.pixels[256 * 192], bottom.pixels[0])
        assertTrue(top.pixels[0] != top.pixels[255], "the crop kept the picture, not a flat colour")
    }

    @Test
    fun eachScreenIsHashedOnItsOwn() {
        // The top is the same in both frames and the bottom is not: only the bottom's hash moves.
        val frame = dsFrame(RAMP, STRIPES)
        val other = dsFrame(RAMP, FLAT)
        assertEquals(Screens.hashOf(frame, Screens.Screen.TOP), Screens.hashOf(other, Screens.Screen.TOP))
        assertTrue(Screens.hashOf(frame, Screens.Screen.BOTTOM) != Screens.hashOf(other, Screens.Screen.BOTTOM))
    }

    @Test
    fun bothScreensAreSentAsTwoLabelledPictures() {
        val frame = dsFrame(RAMP, STRIPES)
        val pictures = Screens.picturesOf(frame)
        assertEquals(listOf("top screen", "bottom screen"), pictures.map { it.first })
        assertEquals(192, pictures.first().second.height)
        assertEquals(frame.pixels[256 * 192], pictures.last().second.pixels[0])
    }

    @Test
    fun aFrameThatIsNotTwoScreensIsSentWhole() {
        val single = Frame(256, 192, pattern(RAMP))
        val pictures = Screens.picturesOf(single)
        assertEquals(listOf("screen"), pictures.map { it.first })
        assertEquals(single, pictures.single().second)
        assertEquals(listOf(0, 0, 256, 192), Screens.regionOf(single, Screens.Screen.BOTTOM))
    }

    private companion object {
        const val FLAT = 0
        const val RAMP = 1
        const val STRIPES = 2
    }
}
