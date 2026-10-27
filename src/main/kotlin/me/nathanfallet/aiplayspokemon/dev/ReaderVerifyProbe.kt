package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.ReaderVerifier
import me.nathanfallet.aiplayspokemon.emulator.ReaderVerdict
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Checks a reader against the game it claims to read: for every value that names screens, does it still read **one
 * value per screen** and tell screens apart? (See [ReaderVerifier] and `docs/readers.md`.)
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderVerifyProbeKt \
 *   -PdevArgs="/path/to/game.nds|3000|desmume"
 * ```
 *
 * Arguments: `rom | frames (default 3000) | core (default desmume)`, plus `--route=…` to walk a different route,
 * `--write` to write the verdicts back into the reader (its `confidence` and one notes line), and `--force` to allow
 * that for a reader that ships with the app — without it, writing would shadow the shipped file with a user copy, so
 * the check is only reported.
 */
fun main(args: Array<String>) {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [frames] [core] [--write] [--force] [--route=…]"))
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 3000
    val core = args.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: "desmume"
    val write = args.any { it == "--write" }
    val force = args.any { it == "--force" }
    val config = AppConfig.load(emptyArray())
    val profile = GameCatalog.detect(GameCatalog.headerOf(rom), config.dataDirectory)
    val reader = ReaderCatalog.of(profile, config.dataDirectory)
        ?: error("${profile.title} has no reader to check: calibrate one first (docs/readers.md)")
    val mine = ReaderCatalog.user(profile.id, config.dataDirectory) != null

    val route = args.firstOrNull { it.startsWith("--route=") }?.substringAfter("--route=") ?: ProbeRoutes.defaultRoute(frames)
    val script = InputScript.parse(route)
    println("== checking the reader of ${profile.title} (${profile.id}) on $core ==")
    println(
        "reader: ${reader.observations.size} values, provenance '${reader.provenance}'" +
            (if (mine) ", yours" else ""),
    )

    val verifier = ReaderVerifier(reader)
    val console = LibretroConsole(
        spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), core),
        rom = rom.toKotlinxPath(),
        dataDirectory = Files.createTempDirectory("reader-verify-probe").toKotlinxPath(),
        onVideo = { },
        onAudio = { _, _ -> },
    )
    var report = verifier.report()
    try {
        var frame = 0
        while (frame < frames) {
            console.step(1, script.inputAt(frame))
            val picture = FrameHash.dHash(console.framebuffer() ?: continue)
            // The app checks the game a few times a second; so does this.
            if (frame % 15 == 0) report = verifier.observe(picture, RamMemory(ProbeRoutes.mainRam(console)))
            frame++
        }
    } finally {
        console.close()
    }

    println(report.summary)
    if (!report.isConclusive) {
        println("(too little to judge the reader: run longer, or with a --route that opens more screens)")
    }
    report.observations.forEach { verdict ->
        val mark = when (verdict.verdict) {
            ReaderVerdict.OK -> "ok          "
            ReaderVerdict.INCONSISTENT -> "INCONSISTENT"
            ReaderVerdict.CONSTANT -> "constant    "
            ReaderVerdict.UNREADABLE -> "UNREADABLE  "
        }
        println(
            "  $mark ${verdict.name} at 0x${verdict.address.toString(16)}: " +
                "${verdict.screensHeld}/${verdict.screensSeen} screens held, ${verdict.distinct} values, " +
                "agreement ${"%.2f".format(verdict.consistency)} — ${verdict.detail}",
        )
    }

    if (!write) {
        println("(dry run: add --write to write this into the reader)")
        return
    }
    if (!report.isConclusive) {
        println("not written: the run did not show enough screens to say anything about the reader")
        return
    }
    if (!mine && !reader.isLearned && !force) {
        println("not written: this reader ships with the app, and a user copy would shadow it (--force to do it anyway)")
        return
    }
    val file = ReaderCatalog.write(verifier.verifiedReader(report), config.dataDirectory)
    println("wrote $file (confidence and one notes line updated)")
}
