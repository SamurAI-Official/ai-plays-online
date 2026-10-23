package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameHashTest {

    /** A frame built from a function of (x, y), so a test can describe a pattern instead of pixels. */
    private fun frame(width: Int = 64, height: Int = 48, pattern: (Int, Int) -> Int): Frame =
        Frame(width, height, IntArray(width * height) { index -> pattern(index % width, index / width) })

    private fun gray(value: Int) = (value shl 16) or (value shl 8) or value

    @Test
    fun identicalFramesHashTheSame() {
        val frame = frame { x, y -> gray((x + y) % 256) }
        assertEquals(0, FrameHash.distance(FrameHash.dHash(frame), FrameHash.dHash(frame)))
    }

    @Test
    fun aSmallChangeStaysClose() {
        val before = frame { x, y -> gray((x + y) % 256) }
        val after = frame { x, y -> if (x == 1 && y == 1) gray(255) else gray((x + y) % 256) }
        val distance = FrameHash.distance(FrameHash.dHash(before), FrameHash.dHash(after))
        assertTrue(distance <= 6, "a one-pixel change moved the hash by $distance bits")
    }

    @Test
    fun differentScreensAreFarApart() {
        val stripes = frame { x, _ -> gray(if (x % 2 == 0) 0 else 255) }
        val inverted = frame { x, _ -> gray(if (x % 2 == 0) 255 else 0) }
        assertTrue(FrameHash.distance(FrameHash.dHash(stripes), FrameHash.dHash(inverted)) > 20)
    }

    @Test
    fun aRegionOnlyLooksAtThatRegion() {
        val base = frame { x, y -> gray((x + y) % 256) }
        val changedOutside = frame { x, y -> if (x < 32) gray(255) else gray((x + y) % 256) }
        val region = listOf(32, 0, 32, 48)
        assertEquals(
            0,
            FrameHash.distance(FrameHash.dHash(base, region), FrameHash.dHash(changedOutside, region)),
            "a change outside the region must not move the hash",
        )
        assertTrue(FrameHash.distance(FrameHash.dHash(base), FrameHash.dHash(changedOutside)) > 0)
    }

    @Test
    fun hashesRoundTripThroughTheirTextForm() {
        val hash = FrameHash.dHash(frame { x, y -> gray((x * 7 + y * 13) % 256) })
        assertEquals(hash, FrameHash.parse(FrameHash.format(hash)))
        assertTrue(FrameHash.matches(hash, listOf(FrameHash.format(hash)), threshold = 0))
        assertTrue(FrameHash.matches(hash, listOf(FrameHash.format(hash xor 0b11)), threshold = 2))
        assertTrue(!FrameHash.matches(hash, listOf(FrameHash.format(hash xor 0b11)), threshold = 1))
    }

    @Test
    fun unreadableReferencesAreIgnored() {
        assertEquals(null, FrameHash.parse("not a hash"))
        assertTrue(!FrameHash.matches(0L, listOf("nonsense"), threshold = 64))
    }
}
