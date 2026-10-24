package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.emulator.FrameHash
import me.nathanfallet.aiplayspokemon.emulator.RamProbe
import me.nathanfallet.aiplayspokemon.emulator.RamSignals
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.ValueType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the game's memory while it runs: the workbench for writing a reader, and the calibration that writes one.
 *
 * A typed reader is data (`readers/<id>.json`, see `docs/readers.md`), and this tool is how that data is found: it
 * drives a game with an [InputScript], snapshots its main RAM along the way, and looks for the addresses that behave
 * like something a reader wants — the address that says which screen is up, one that counts up on its own, one that
 * reacts to a press. `--write` then puts a draft reader in the data directory, which is what makes the game typed in
 * the app.
 *
 * ```bash
 * ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.RamProbeToolKt \
 *   -PdevArgs="game.nds|3000|desmume|--script=A@700-702;A@1000-1002|--calibrate=mode|--write=auto"
 * ```
 *
 * Arguments: `rom | frames (default 1800) | core (default desmume)`, then any of:
 *
 * - `--script=…` — drive the game ([InputScript] format), which is how the calibration sees more than one screen;
 * - `--state=<file>` — start from a savestate;
 * - `--calibrate=mode|counter|input` — find screen signals, self-advancing counters, or input reactions;
 * - `--write=auto|<file>` — write the candidates as a draft `readers/<id>.json` (`auto`: the app's data directory);
 * - `--find=<value>[:type]` — addresses holding a value at the end of the run;
 * - `--watch=<address>[:type]` — the value at that address over the run (how a candidate is confirmed by hand);
 * - `--dump=<address>,<length>` — the bytes at an address, hex and text;
 * - `--diff=<frameA>,<frameB>` — what changed between two frames of the run;
 * - `--every=<frames>` (default 60) and `--samples=<n>` (default 32) — how often, and how often at most, memory is
 *   snapshotted (each snapshot is 4 MB, so a calibration is a few hundred MB at most).
 */
fun main(args: Array<String>) {
    val rom = Path.of(args.firstOrNull() ?: error("usage: <rom.nds> [frames] [core] [--calibrate=mode] [--write=auto]"))
    val frames = args.getOrNull(1)?.toIntOrNull() ?: 1800
    val core = args.getOrNull(2) ?: "desmume"
    val script = InputScript.parse(args.value("--script="))
    val state = args.value("--state=")?.let(Path::of)
    val recipe = args.value("--calibrate=")
    val every = args.value("--every=")?.toIntOrNull() ?: 60
    val maxSamples = args.value("--samples=")?.toIntOrNull() ?: 32
    val type = args.value("--type=")?.let(::valueType) ?: ValueType.U8
    val data = Files.createTempDirectory("ram-probe-tool")

    val snaps = mutableListOf<Snap>()
    val diffs = mutableMapOf<Int, ByteArray>()
    val wanted = listOfNotNull(
        args.value("--diff=")?.substringBefore(',')?.toIntOrNull(),
        args.value("--diff=")?.substringAfter(',')?.toIntOrNull(),
    )
    var screen = 0L

    val console = LibretroConsole(
        spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), core),
        rom = rom.toKotlinxPath(),
        dataDirectory = data.toKotlinxPath(),
        onVideo = { frame -> screen = FrameHash.dHash(frame) },
        onAudio = { _, _ -> },
    )
    try {
        state?.let { console.loadState(Files.readAllBytes(it)) }
        for (frame in 0 until frames) {
            console.step(1, script.inputAt(frame))
            if (frame % every == 0 && snaps.size < maxSamples) snaps += Snap(frame, mainRam(console), screen)
            if (frame in wanted) diffs[frame] = mainRam(console)
        }
        report(rom, core, script, snaps, args, type, console, diffs, recipe)
    } finally {
        console.close()
    }
}


/** The main RAM of the frame the console just finished, as the readers read it. */
private fun mainRam(console: LibretroConsole): ByteArray =
    ByteArray(console.memorySize(MemoryRegion.MAIN_RAM)).also { console.read(MemoryRegion.MAIN_RAM, 0, it.size, it) }

/** Everything the tool has to say about the run: what was asked for, then the calibration's candidates. */
private fun report(
    rom: Path,
    core: String,
    script: InputScript,
    snaps: List<Snap>,
    args: Array<String>,
    type: ValueType,
    console: LibretroConsole,
    diffs: Map<Int, ByteArray>,
    recipe: String?,
) {
    println("${rom.fileName} on $core: ${snaps.size} snapshots")
    if (script !== InputScript.NONE) println("script: $script")
    println("snapshot frames: ${snaps.joinToString(", ") { it.frame.toString() }}")
    println("distinct screens seen: ${snaps.map { it.screen }.toSet().size}")

    args.value("--diff=")?.let { pair ->
        val (first, second) = pair.split(',').mapNotNull { it.trim().toIntOrNull() }
        val before = diffs[first]
        val after = diffs[second]
        if (before == null || after == null) println("--diff: no snapshot at $first or $second") else {
            println("changed between frame $first and $second (as $type):")
            println(RamProbe.summarise(RamProbe.diff(before, after, type)))
        }
    }

    args.value("--find=")?.let { query ->
        val value = query.substringBefore(':').trim().toLongOrNull() ?: error("--find=<value>[:type]")
        val addresses = RamProbe.find(mainRam(console), value, type)
        println("addresses holding $value ($type): ${addresses.joinToString { RamProbe.address(it) }}")
    }

    args.value("--watch=")?.let { query ->
        val address = RamProbe.parseAddress(query.substringBefore(':')) ?: error("--watch=<address>[:type]")
        println("${RamProbe.address(address)} ($type) over the run:")
        for (snap in snaps) println("  frame ${snap.frame}: ${RamProbe.read(snap.ram, address, type)}")
    }

    args.value("--dump=")?.let { query ->
        val address = RamProbe.parseAddress(query.substringBefore(',')) ?: error("--dump=<address>,<length>")
        val length = query.substringAfter(',').trim().toIntOrNull() ?: 64
        val bytes = RamProbe.snapshot(mainRam(console)).readBytes(address, length)
        println("${RamProbe.address(address)}, $length bytes:")
        bytes.toList().chunked(16).forEachIndexed { row, chunk ->
            val offset = RamProbe.address(address + row * 16).removePrefix("0x")
            println("  $offset  " + chunk.joinToString(" ") { it.toInt().and(0xFF).toString(16).padStart(2, '0') })
        }
    }

    when (recipe) {
        null -> Unit
        "mode" -> calibrateMode(rom, snaps, args)
        "counter" -> calibrateCounter(snaps)
        "input" -> calibrateInput(script, snaps)
        else -> error("--calibrate=mode|counter|input (not '$recipe')")
    }
}

/** The addresses that say which screen is up: the reader's "where is the game" signal. */
private fun calibrateMode(rom: Path, snaps: List<Snap>, args: Array<String>) {
    println("\nscreen signals (an address whose value says which screen is up):")
    val samples = snaps.map { it.sample() }
    val labels = RamSignals.cluster(samples)
    println("  screens: ${labels.toSet().size} (${labels.groupingBy { it }.eachCount().values.sortedDescending().joinToString("/")} samples each)")
    val signals = RamSignals.screenSignals(samples, ValueType.U8) +
        RamSignals.screenSignals(samples, ValueType.U16)
    val top = signals
        .sortedWith(compareByDescending<RamSignals.Signal> { it.consistency }.thenByDescending { it.distinct })
        .take(10)
    if (top.isEmpty()) println("  none: the run never showed two different screens")
    for (signal in top) {
        val values = signal.byScreen.entries.joinToString(", ") { (screen, value) ->
            "${FrameHash.format(screen).removePrefix("dhash:")}=$value"
        }
        println(
            "  ${RamProbe.address(signal.address)}  consistency ${"%.2f".format(signal.consistency)}  " +
                "${signal.distinct} values  [$values]",
        )
    }
    writeIfAsked(rom, args, "mode", top.map { it.address to it.consistency })
}

/** The addresses that only ever move one way: the game's clock, a score, a frame counter. */
private fun calibrateCounter(snaps: List<Snap>) {
    println("\ncounters (an address that only ever goes one way):")
    val counters = RamSignals.counters(snaps.map { it.ram }, ValueType.U32) +
        RamSignals.counters(snaps.map { it.ram }, ValueType.U16)
    if (counters.isEmpty()) println("  none")
    for (counter in counters.distinctBy { it.address }.take(10)) {
        println("  ${RamProbe.address(counter.address)}  ${counter.before} -> ${counter.after}")
    }
    println("(confirm one with --watch=<address>:u32 over the same run before believing it)")
}

/** The addresses that moved on every press of the script and never otherwise. */
private fun calibrateInput(script: InputScript, snaps: List<Snap>) {
    val presses = snaps.mapIndexedNotNull { index, snap ->
        if (index > 0 && script.inputAt(snap.frame).buttons.isNotEmpty()) index else null
    }
    val pressed = presses.map { snaps[it].frame }.toSet()
    val moments = presses.map { index -> snaps[index - 1].ram to snaps[index].ram }
    val others = snaps.zipWithNext()
        .filter { (before, after) -> (before.frame until after.frame).none { it in pressed } }
        .map { (before, after) -> before.ram to after.ram }
    println("\ninput reactions (moved on every press, and never otherwise):")
    val signals = RamSignals.inputSignals(moments, others, ValueType.U8)
    if (signals.isEmpty()) println("  none (use --every=5 so a press and its answer are both sampled)")
    for (signal in signals.take(10)) {
        println("  ${RamProbe.address(signal.address)}  ${signal.before} -> ${signal.after} on each of ${moments.size} presses")
    }
}

/** Writes the candidates as a draft reader when `--write` was given. */
private fun writeIfAsked(rom: Path, args: Array<String>, recipe: String, candidates: List<Pair<Long, Double>>) {
    val target = args.value("--write=") ?: return
    if (candidates.isEmpty()) {
        println("nothing to write: no candidate was found")
        return
    }
    val dataDirectory = AppConfig.load(emptyArray()).dataDirectory
    val profile = GameCatalog.detect(GameCatalog.headerOf(rom), dataDirectory)
    val spec = ReaderSpec(
        id = profile.id,
        provenance = "calibrated",
        observations = candidates.mapIndexed { index, (address, confidence) ->
            Observation(
                name = "signal_${index + 1}",
                address = address,
                type = ValueType.U8,
                confidence = confidence,
                note = "$recipe signal found by dev/RamProbeTool; rename it once you know what it means",
            )
        },
        notes = "Draft written by dev/RamProbeTool (--calibrate=$recipe). The names are placeholders: the confidence " +
            "says how well the address followed the screen, so check each one with --watch before trusting it.",
    )
    val file = if (target == "auto") {
        ReaderCatalog.write(spec, dataDirectory)
    } else {
        val path = Path.of(target)
        Files.writeString(path, ReaderCatalog.jsonOf(spec))
        path
    }
    println("\nwrote ${spec.observations.size} candidate observations to $file")
    println("the app reads it as the reader of ${profile.title} (${profile.id}); rename the entries and drop what is wrong")
}

/** `--type=u8` and friends. */
private fun valueType(text: String): ValueType = ValueType.entries.firstOrNull {
    it.name.equals(text, ignoreCase = true) || it.name.equals("U$text", ignoreCase = true)
} ?: error("--type=u8|u16|u32|s8|s16|s32|bool|bits|ascii")

/** One snapshot: the frame it was taken at, the RAM, and the screen that was up then. */
private class Snap(val frame: Int, val ram: ByteArray, val screen: Long) {
    fun sample() = RamSignals.Sample(ram, screen)
}
