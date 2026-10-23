package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Frame

/**
 * Difference hashes of a frame: the cheap, offline way to tell "is this the screen a walkthrough chapter is about?"
 *
 * The frame is reduced to a 9x8 grid of luminance samples and each pixel is compared with its right neighbour: 64
 * bits that survive small changes (a blinking cursor, an animation in a corner, a HUD counter) while separating
 * genuinely different screens. Comparing two hashes is a popcount of their XOR.
 */
object FrameHash {

    /** Grid of samples: one more column than the bits, because each bit compares two neighbours. */
    private const val COLUMNS = 9
    private const val ROWS = 8

    /** The hash of [frame], or of the sub-rectangle `[x, y, width, height]` when [region] is given. */
    fun dHash(frame: Frame, region: List<Int>? = null): Long {
        val x0 = region?.getOrNull(0)?.coerceIn(0, maxOf(0, frame.width - 1)) ?: 0
        val y0 = region?.getOrNull(1)?.coerceIn(0, maxOf(0, frame.height - 1)) ?: 0
        val width = (region?.getOrNull(2) ?: frame.width).coerceIn(1, frame.width - x0)
        val height = (region?.getOrNull(3) ?: frame.height).coerceIn(1, frame.height - y0)

        val samples = IntArray(COLUMNS * ROWS)
        for (row in 0 until ROWS) {
            val y = y0 + (row * height / ROWS).coerceAtMost(height - 1)
            for (column in 0 until COLUMNS) {
                val x = x0 + (column * width / COLUMNS).coerceAtMost(width - 1)
                samples[row * COLUMNS + column] = luminance(frame.pixels[y * frame.width + x])
            }
        }

        var hash = 0L
        var bit = 0
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS - 1) {
                if (samples[row * COLUMNS + column] > samples[row * COLUMNS + column + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** How many bits differ between two hashes. */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** `dhash:<16 hex digits>`, the form the walkthrough data uses. */
    fun format(hash: Long): String = "dhash:" + hash.toULong().toString(16).padStart(16, '0')

    /** Reads a `dhash:…` reference (also accepts a bare hex or decimal number). Returns null when unreadable. */
    fun parse(text: String): Long? {
        val value = text.trim().removePrefix("dhash:").removePrefix("0x")
        return value.toULongOrNull(16)?.toLong() ?: value.toLongOrNull()
    }

    /** True when [hash] is within [threshold] bits of any of the [references]. */
    fun matches(hash: Long, references: List<String>, threshold: Int): Boolean =
        references.any { reference -> parse(reference)?.let { distance(hash, it) <= threshold } == true }

    /** The luminance of an ARGB pixel, 0..255. */
    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }
}
