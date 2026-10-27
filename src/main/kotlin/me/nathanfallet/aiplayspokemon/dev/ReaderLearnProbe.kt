package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.sha256OfRom
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.RamProbe
import me.nathanfallet.aiplayspokemon.emulator.ReaderCalibrator
import me.nathanfallet.aiplayspokemon.emulator.ReaderLearningStore
import me.nathanfallet.aiplayspokemon.emulator.ReaderMerge
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.games.WalkthroughCatalog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plays a ROM headlessly and **learns a reader for it the way the app does**: the same [ReaderCalibrator] the panel
 * drives while a game is played, fed here by a scripted route (confirm, wander the menus, back, start) so a ROM nobody
 * has calibrated can be tried without sitting at the app.
 *
 * It prints what it learned and, with `--write`, writes `<data>/readers/<id>.json` — the file the app would write by
 * itself after a few minutes of play. Use it to check a game, or to seed a reader for a game you have not played yet.
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderLearnProbeKt \
 *   -PdevArgs="/path/to/game.nds|3600|desmume|--write"
 * ```
 *
 * Arguments: `rom | frames (default 3600) | core (default desmume)`, plus `--write` to save the reader (without it the
 * draft is only printed), plus `--route=<input script>` to walk a different route (default: confirm, wander, back).
 */
fun main(args: Array<String>) {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [frames] [core] [--write] [--route=A@10-20;...]"))
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 3600
    val core = args.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: "desmume"
    val write = args.any { it == "--write" }
    val config = AppConfig.load(emptyArray())
    val profile = GameCatalog.detect(GameCatalog.headerOf(rom), config.dataDirectory)
    val walkthrough = WalkthroughCatalog.of(profile, config.dataDirectory)
    val data = Files.createTempDirectory("reader-learn-probe")
    // The tools can look at memory in bigger or smaller parts than the app does: the probe usually has a short run
    // and wants to cover all of main RAM, so it takes bigger parts and fewer looks each.
    val chunk = args.firstOrNull { it.startsWith("--chunk=") }?.substringAfter("--chunk=")?.toIntOrNull()
    val looks = args.firstOrNull { it.startsWith("--looks=") }?.substringAfter("--looks=")?.toIntOrNull()
    val calibrator = ReaderCalibrator(
        chunkSize = chunk ?: 0x100000,
        chunkLooks = looks ?: 8,
        maxChunkLooks = (looks ?: 8) * 4,
    )

    // A route that shows the game a few screens (see ProbeRoutes): nothing at first (logos, intros), then a button
    // every five seconds for the whole run — confirm, wander, back, start — so a long run keeps opening screens.
    val script = InputScript.parse(
        args.firstOrNull { it.startsWith("--route=") }?.substringAfter("--route=") ?: ProbeRoutes.defaultRoute(frames),
    )
    // --resume: pick up the evidence a previous run left in <data>/readers/<id>.learning.json (and write it back at
    // the end), which is what makes "the app does not retrain every time" observable headlessly.
    val resume = args.any { it == "--resume" }
    val romHash = sha256OfRom(Files.readAllBytes(rom))

    println("== learning a reader for ${profile.title} (${profile.id}) on $core ==")
    if (resume) {
        val evidence = ReaderLearningStore.of(profile.id, config.dataDirectory)
        if (evidence == null || !evidence.matches(romHash)) {
            println("nothing to resume (no evidence, or it is from another ROM)")
        } else {
            calibrator.restore(evidence)
            println(
                "resumed: ${evidence.samples} looks, ${evidence.screens.size} screens, part ${evidence.chunk + 1}, " +
                    "${evidence.found.size} signals found before",
            )
        }
    }
    val console = LibretroConsole(
        spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), core),
        rom = rom.toKotlinxPath(),
        dataDirectory = data.toKotlinxPath(),
        onVideo = { },
        onAudio = { _, _ -> },
    )
    var progress = calibrator.progress()
    val samplesBefore = progress.samples
    try {
        var frame = 0
        while (frame < frames) {
            console.step(1, script.inputAt(frame))
            val screen = FrameHash.dHash(console.framebuffer() ?: continue)
            // The app looks at the game a few times a second; so does this.
            if (frame % 15 == 0) progress = calibrator.observe(screen, ProbeRoutes.mainRam(console))
            frame++
        }
    } finally {
        console.close()
    }

    if (resume) {
        // Written back before anything else so the next run resumes from here, whatever this run learned (the app
        // does the same: the evidence is saved as it goes, the reader only once there is one).
        val file = ReaderLearningStore.write(calibrator.toEvidence(profile.id, romHash), config.dataDirectory)
        println("wrote $file (${progress.samples} looks, ${progress.screens} screens, part ${progress.chunk + 1})")
    }
    println("looks: ${progress.samples}, screens: ${progress.screens}, addresses kept: ${progress.candidates}")
    if (samplesBefore > 0) println("this run added ${progress.samples - samplesBefore} looks to the $samplesBefore resumed")

    println(
        "ready: ${progress.ready}, best signal separates ${progress.bestValues} screens and agrees " +
            "${"%.0f".format(progress.bestConsistency * 100)}% of the time",
    )
    // The best few signals, ready or not: this is what says whether the game (or the route) is the problem.
    println("-- signals agreeing with the screens at least 90% of the time:")
    calibrator.candidates().take(5).forEach { signal -> println("   " + describe(signal)) }
    println("-- the same, ignoring how well they agree (what the data contains):")
    calibrator.candidates(minConsistency = 0.0).take(5).forEach { signal -> println("   " + describe(signal)) }
    // A game whose reader was calibrated by hand is the learner's ground truth: does it find those addresses again?
    ReaderCatalog.of(profile.id, config.dataDirectory)?.let { known ->
        println("-- the reader this game already has (a hand calibration, for comparison):")
        known.observations.forEach { observation ->
            val found = calibrator.candidates(minConsistency = 0.0).firstOrNull { it.address == observation.address }
            val verdict = found?.let { "found: " + describe(it) } ?: "not found in this run"
            println("   ${observation.name} at ${RamProbe.address(observation.address)}: $verdict")
        }
    }
    val names = walkthrough?.chapters.orEmpty().mapNotNull { chapter ->
        val hash = chapter.milestone?.hashes?.firstNotNullOfOrNull { FrameHash.parse(it) } ?: return@mapNotNull null
        hash to chapter.title
    }.toMap()
    val draft = calibrator.draft(profile.id, names)
    if (draft == null) {
        println("nothing learned: the game showed too few screens (run longer, or with a --route that opens menus)")
        return
    }
    draft.observations.forEach { observation ->
        println("- ${observation.name} at ${RamProbe.address(observation.address)}: ${observation.values}")
    }
    if (write) {
        // --merge: extend the reader the app already has (the same path AppController takes), instead of replacing it.
        val merged = if (args.any { it == "--merge" }) {
            ReaderMerge.merge(ReaderCatalog.of(profile.id, config.dataDirectory), draft, names)
        } else {
            draft
        }
        val file = ReaderCatalog.write(merged, config.dataDirectory)
        println("wrote $file (${merged.observations.size} values, ${merged.progress.size} progress signals)")
    } else {
        println("(dry run: add --write to save it to ${config.dataDirectory.resolve("readers")})")
    }
}

/** One candidate signal, for the report. */
private fun describe(signal: ReaderCalibrator.Signal): String =
    "${RamProbe.address(signal.address)}: ${signal.distinct} values over ${signal.byScreen.size} screens, " +
        "agrees ${"%.2f".format(signal.consistency)} over ${signal.looks} looks, checked ${signal.verified} times " +
        "since (${signal.revisits} on a screen that came back), values ${signal.byScreen}"
