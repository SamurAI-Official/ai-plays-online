package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import java.nio.file.Files
import java.nio.file.Path

/**
 * Prints the stable screens a ROM shows, with the `dhash:` references a walkthrough chapter's milestone needs.
 *
 * Runs the game headless for [frames] frames, hashes every frame and groups the runs of identical hashes: the long
 * runs are the screens worth using as landmarks (title screen, menus, a save screen...). An author picks a run and
 * copies its hash into `walkthroughs/<game>.json`.
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.FrameHashToolKt \
 *   -PdevArgs="/path/to/game.nds|900|melonds"
 * ```
 *
 * Arguments: `rom | frames (default 900) | core (default melonds)`. The game is driven with no input, so it stays on
 * whatever it shows by itself (usually the title screen and its attract loop); use a savestate and inputs for
 * anything deeper.
 */
fun main(args: Array<String>) {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [frames] [core]"))
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 900
    val core = args.getOrNull(2) ?: "melonds"
    val data = Files.createTempDirectory("frame-hash-tool")

    val hashes = mutableListOf<Long>()
    val console = LibretroConsole(
        spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), core),
        rom = rom.toKotlinxPath(),
        dataDirectory = data.toKotlinxPath(),
        onVideo = { frame -> hashes += FrameHash.dHash(frame) },
        onAudio = { _, _ -> },
    )
    try {
        console.step(frames, InputFrame.NONE)
    } finally {
        console.close()
    }

    println("${rom.fileName}: ${hashes.size} frames hashed")
    println("stable screens (hash, frames it lasted):")
    var start = 0
    for (index in 1..hashes.size) {
        if (index < hashes.size && hashes[index] == hashes[start]) continue
        val length = index - start
        if (length >= MIN_RUN) {
            println("  ${FrameHash.format(hashes[start])}  ${length} frames  (from frame $start)")
        }
        start = index
    }
}

/** Only runs at least this long are worth looking at: shorter ones are transitions and animations. */
private const val MIN_RUN = 20
