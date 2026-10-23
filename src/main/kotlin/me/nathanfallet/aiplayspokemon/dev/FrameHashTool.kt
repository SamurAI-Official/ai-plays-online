package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.ScreenWatcher
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.WalkthroughCatalog
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

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
 * Arguments: `rom | frames (default 900) | core (default melonds)`, then any of:
 *
 * - `--script=A@120-130;TOUCH:128,96@300-302` — drive the game ([InputScript] format) instead of leaving it alone;
 * - `--state=<file>` — start from a savestate (the script's frames count from there);
 * - `--png=<dir>` — write one PNG per stable screen (named `<frame>-<hash>.png`), to see what a landmark is;
 * - `--pngAt=100,600` — write those frames too;
 * - `--check=<walkthrough id>` — follow the walkthrough with the app's own [ScreenWatcher] and print the frame each
 *   chapter's milestone fires on, which is how a captured landmark is verified before it is committed.
 *
 * The game is driven with no input unless a script says otherwise, so on its own it stays on whatever it shows by
 * itself (usually the title screen and its attract loop).
 */
fun main(args: Array<String>) {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [frames] [core] [--script=] [--state=] [--png=]"))
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 900
    val core = args.getOrNull(2) ?: "melonds"
    val script = InputScript.parse(args.value("--script="))
    val state = args.value("--state=")?.let(Path::of)
    val pngDirectory = args.value("--png=")?.let(Path::of)
    val pngAt = args.value("--pngAt=")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
    val checkId = args.value("--check=")
    val data = Files.createTempDirectory("frame-hash-tool")

    val hashes = mutableListOf<Long>()
    val runs = mutableListOf<Run>()
    var pending: Frame? = null
    var pendingHash = 0L
    var pendingFrom = 0
    val watcher = ScreenWatcher()
    val chapters = checkId?.let { WalkthroughCatalog.of(it)?.chapters ?: error("no walkthrough '$it'") } ?: emptyList()
    var chapter = 0

    fun onFrame(frame: Frame) {
        val index = hashes.size
        val hash = FrameHash.dHash(frame)
        hashes += hash

        if (chapter < chapters.size && watcher.check(frame, chapters[chapter])) {
            println("chapter ${chapters[chapter].id} (${chapters[chapter].title}) reached at frame $index")
            chapter++
        }
        if (pngDirectory != null && index in pngAt) writePng(pngDirectory, index, hash, frame)

        // A run is a screen that doesn't change: keep its first frame, and report it once it ends.
        if (index == 0 || hash != pendingHash) {
            pending?.let { if (index - pendingFrom >= MIN_RUN) runs += Run(pendingFrom, index, pendingHash, it) }
            pending = frame.copyOf()
            pendingHash = hash
            pendingFrom = index
        }
    }

    val console = LibretroConsole(
        spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), core),
        rom = rom.toKotlinxPath(),
        dataDirectory = data.toKotlinxPath(),
        onVideo = ::onFrame,
        onAudio = { _, _ -> },
    )
    try {
        state?.let { console.loadState(Files.readAllBytes(it)) }
        // One frame at a time: the script decides what is held on each of them (`runFrame` is not in the published
        // pokemon-client artifact yet).
        for (index in 0 until frames) console.step(1, script.inputAt(index))
    } finally {
        console.close()
    }
    pending?.let { if (hashes.size - pendingFrom >= MIN_RUN) runs += Run(pendingFrom, hashes.size, pendingHash, it) }

    println("${rom.fileName}: ${hashes.size} frames hashed on $core")
    if (state != null) println("started from savestate $state")
    if (script !== InputScript.NONE) println("script: $script")
    println("stable screens (hash, frames it lasted):")
    for (run in runs) {
        println("  ${FrameHash.format(run.hash)}  ${run.lastFrame - run.firstFrame} frames  (from frame ${run.firstFrame})")
        if (pngDirectory != null) writePng(pngDirectory, run.firstFrame, run.hash, run.frame)
    }
    if (checkId != null) {
        val missing = chapters.drop(chapter).map { it.id }
        if (missing.isEmpty()) println("walkthrough $checkId: every chapter's milestone fired") else {
            println("walkthrough $checkId: no milestone fired for ${missing.joinToString(", ")}")
        }
    }
    if (pngDirectory != null) println("frames written to $pngDirectory")
}

/** A screen that lasted from [firstFrame] until [lastFrame], with the hash every frame of it shared. */
private class Run(val firstFrame: Int, val lastFrame: Int, val hash: Long, val frame: Frame)

/** Only runs at least this long are worth looking at: shorter ones are transitions and animations. */
private const val MIN_RUN = 20

/** The value of `--flag=value` in [args], or null. */
private fun Array<String>.value(flag: String): String? =
    firstOrNull { it.startsWith(flag) }?.removePrefix(flag)?.takeIf { it.isNotBlank() }

/** Writes one frame as PNG, so an author can see which screen a hash belongs to. */
private fun writePng(directory: Path, frame: Int, hash: Long, source: Frame) {
    Files.createDirectories(directory)
    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, source.width, source.height, source.pixels, 0, source.width)
    ImageIO.write(image, "png", directory.resolve("$frame-${FrameHash.format(hash).removePrefix("dhash:")}.png").toFile())
}

/** A frame that can be kept around: the core reuses its own buffer, so the pixels are copied. */
private fun Frame.copyOf() = Frame(width, height, pixels.copyOf())
