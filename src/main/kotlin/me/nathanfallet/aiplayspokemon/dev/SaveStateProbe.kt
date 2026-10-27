package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Boots a ROM, runs it for a while and writes a save state, then prints where it went: the quickest way to produce the
 * fixtures the gated audio tests need (they take a state **with music playing**, `PAUSE_MUSIC_STATE=…`), without sitting
 * at the app and pressing F1.
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.SaveStateProbeKt \
 *   -PdevArgs="/path/to/game.nds|1200|desmume|1"
 * ```
 *
 * Arguments: `rom | frames (default 1200: past the logos, into the title music) | core (default desmume) | slot
 * (default 1)`, plus `--route=…` to press buttons while it runs (a state on the field rather than at the title) and
 * `--out=<file>` to write somewhere else than the data directory's `states/`.
 */
fun main(args: Array<String>) = runBlocking {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [frames] [core] [slot] [--route=…] [--out=…]"))
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 1200
    val core = args.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: "desmume"
    val slot = args.getOrNull(3)?.toIntOrNull() ?: 1
    val route = args.firstOrNull { it.startsWith("--route=") }?.substringAfter("--route=")
    val config = me.nathanfallet.aiplayspokemon.config.AppConfig.load(emptyArray())
    val script = InputScript.parse(route ?: ProbeRoutes.defaultRoute(frames))
    val out = args.firstOrNull { it.startsWith("--out=") }?.substringAfter("--out=")?.let(Path::of)
        ?: config.dataDirectory.resolve("states").resolve("${rom.fileName}.state$slot")

    println("== save state probe: ${rom.fileName}, $frames frames on $core ==")
    val console = LibretroConsole(
        spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), core),
        rom = rom.toKotlinxPath(),
        dataDirectory = Files.createTempDirectory("save-state-probe").toKotlinxPath(),
        onVideo = { },
        onAudio = { _, _ -> },
    )
    val state = try {
        var frame = 0
        while (frame < frames) {
            console.step(1, script.inputAt(frame))
            frame++
        }
        console.saveState()
    } finally {
        console.close()
    }
    out.parent?.createDirectories()
    Files.write(out, state)
    println("state: $out (${state.size} bytes)")
}
