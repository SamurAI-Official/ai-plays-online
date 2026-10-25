package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The Nintendo DS has two screens, and games use both: a map on top and a touch menu below, an illustration on top and
 * a dialogue box below, a status screen on one and the game on the other. Everything that looks at the picture — the
 * stability of what the agent sees, the landmarks a walkthrough captured, the reading a vision model gives, and the
 * images it is shown — therefore works per screen, not on the whole frame.
 *
 * The core hands over one frame of `width x height` with both screens stacked, the top one first
 * ([me.nathanfallet.aiplayspokemon.emulator.ConsoleHost]'s frames are 256x384), so a screen is a region of it:
 * `[0, 0, w, h/2]` and `[0, h/2, w, h/2]`.
 */
object Screens {

    /** The two halves of a DS frame. */
    enum class Screen(val label: String) {
        TOP("top screen"),
        BOTTOM("bottom screen"),
    }

    /** The region of [screen] in [frame], or the whole frame when it is not two screens stacked. */
    fun regionOf(frame: Frame, screen: Screen): List<Int> = when {
        !isTwoScreens(frame) -> listOf(0, 0, frame.width, frame.height)
        screen == Screen.TOP -> listOf(0, 0, frame.width, frame.height / 2)
        else -> listOf(0, frame.height / 2, frame.width, frame.height / 2)
    }

    /** True when [frame] is two screens stacked (what the DS cores hand over). */
    fun isTwoScreens(frame: Frame): Boolean = frame.height >= 2 && frame.height % 2 == 0 && frame.height / 2 >= MIN_SCREEN_HEIGHT

    /** The hash of [screen] in [frame]. */
    fun hashOf(frame: Frame, screen: Screen): Long = FrameHash.dHash(frame, regionOf(frame, screen))

    /**
     * The pictures to attach to a request: each screen on its own, labelled, so a vision model sees both at full
     * size (one combined picture would give each of them half the pixels, which is what small dialogue text suffers
     * from). A frame that is not two screens is sent as one picture.
     */
    fun picturesOf(frame: Frame): List<Pair<String, Frame>> =
        if (isTwoScreens(frame)) {
            listOf(
                Screen.TOP.label to frame.crop(regionOf(frame, Screen.TOP)),
                Screen.BOTTOM.label to frame.crop(regionOf(frame, Screen.BOTTOM)),
            )
        } else {
            listOf("screen" to frame)
        }

    /** The part of [frame] a region covers, as its own frame. */
    fun Frame.crop(region: List<Int>): Frame {
        val x0 = region.getOrNull(0)?.coerceIn(0, maxOf(0, width - 1)) ?: 0
        val y0 = region.getOrNull(1)?.coerceIn(0, maxOf(0, height - 1)) ?: 0
        val cropWidth = (region.getOrNull(2) ?: width).coerceIn(1, width - x0)
        val cropHeight = (region.getOrNull(3) ?: height).coerceIn(1, height - y0)
        val pixels = IntArray(cropWidth * cropHeight)
        for (row in 0 until cropHeight) {
            val source = (y0 + row) * width + x0
            for (column in 0 until cropWidth) pixels[row * cropWidth + column] = this.pixels[source + column]
        }
        return Frame(cropWidth, cropHeight, pixels)
    }

    /** [frame] as a base64 PNG, the form every vision endpoint takes. */
    fun Frame.pngBase64(): String {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "PNG", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** A screen shorter than this is not a DS screen: the frame is something else (a core quirk), sent whole. */
    private const val MIN_SCREEN_HEIGHT = 100
}
