package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.libretro.LibretroConsole

/**
 * What the reader tools share: the blind route they drive a game with, and how they read its memory.
 *
 * The route shows the game a few screens — nothing at first (logos, intros), then a button every five seconds for the
 * whole run (confirm, wander, back, start) — so a tool can learn or check a reader without anyone sitting at the app.
 */
internal object ProbeRoutes {

    /** A scripted route of [frames] frames, as `A@700-708;DOWN@1000-1008;…`. */
    fun defaultRoute(frames: Int): String = buildString {
        val buttons = listOf("A", "DOWN", "A", "UP", "B", "START", "A", "LEFT")
        var frame = 400
        var step = 0
        while (frame < frames) {
            if (isNotEmpty()) append(';')
            append(buttons[step % buttons.size]).append('@').append(frame).append('-').append(frame + 8)
            frame += 300
            step++
        }
    }

    /** The main RAM of the frame the console just finished. */
    fun mainRam(console: LibretroConsole): ByteArray =
        ByteArray(console.memorySize(MemoryRegion.MAIN_RAM)).also { console.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }
}
