package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.ValueType

/**
 * The analysis that turns a game's behaviour into a reader: which address says which screen is up, which one counts
 * up on its own, which one reacts to an input.
 *
 * It works on [Sample]s — RAM snapshots paired with the screen that was on screen ([FrameHash.dHash]) — so it is
 * plain, testable code. `dev/RamProbeTool` collects the samples by driving a real game and calls these.
 */
object RamSignals {

    /** One moment of a run: the RAM, and the screen that was on screen then. */
    class Sample(val ram: ByteArray, val screen: Long)

    /** An address whose value follows the screen: the same screen always reads the same value. */
    class Signal(
        val address: Long,
        val byScreen: Map<Long, Long>,
        val consistency: Double,
        val distinct: Int,
    ) {
        /** The value the address holds on [screen], or null when that screen was never seen. */
        fun valueOn(screen: Long): Long? = byScreen[screen]
    }

    /**
     * Groups the samples' screens into the screens a human would name: two frames within [threshold] bits of a
     * representative are the same screen.
     *
     * It matters because most games animate their menus: without this, every frame of a menu is a "different" screen,
     * and any address that moves would look like it follows the screen. Returns one label per sample (the
     * representative hash), which is what the other functions compare against.
     */
    fun cluster(samples: List<Sample>, threshold: Int = 6): List<Long> {
        val representatives = mutableListOf<Long>()
        return samples.map { sample ->
            val known = representatives.firstOrNull { FrameHash.distance(it, sample.screen) <= threshold }
            if (known != null) known else sample.screen.also { representatives += it }
        }
    }

    /**
     * Finds the addresses whose value is a function of the screen — a menu, a mode, an area. That is how a game with
     * no reader gets one: the calibration walks the game through its screens and looks for the address that says which
     * screen is up.
     *
     * [Signal.consistency] is the share of samples whose value matches the majority value of their screen, so 1.0
     * means the address never lies about the screen; [Signal.distinct] is how many different values it takes, so an
     * address that is merely constant is not a signal. Results are ranked by consistency, then by distinctness.
     */
    fun screenSignals(
        samples: List<Sample>,
        type: ValueType = ValueType.U8,
        minDistinct: Int = 2,
        threshold: Int = 6,
        limit: Int = 32,
    ): List<Signal> {
        if (samples.size < 2) return emptyList()
        val labels = cluster(samples, threshold)
        val size = samples.minOf { it.ram.size }
        val stride = RamProbe.step(type)
        val found = mutableListOf<Signal>()
        var offset = 0
        while (offset + stride <= size) {
            val address = RamProbe.MAIN_RAM_START + offset
            val values = samples.map { RamProbe.read(it.ram, address, type) }
            if (values.toSet().size >= minDistinct) {
                val byScreen = mutableMapOf<Long, MutableList<Long>>()
                samples.forEachIndexed { index, _ -> byScreen.getOrPut(labels[index]) { mutableListOf() } += values[index] }
                val majority = byScreen.mapValues { (_, seen) ->
                    seen.groupingBy { it }.eachCount().maxBy { it.value }.key
                }
                val agreeing = byScreen.entries.sumOf { (screen, seen) -> seen.count { it == majority.getValue(screen) } }
                found += Signal(address, majority, agreeing.toDouble() / samples.size, majority.values.toSet().size)
            }
            offset += stride
        }
        return found
            .filter { it.distinct >= minDistinct }
            .sortedWith(compareByDescending<Signal> { it.consistency }.thenByDescending { it.distinct })
            .take(limit)
    }

    /**
     * The addresses that only ever go up (or only ever go down) across [samples], with how much they moved: the game's
     * clock, a frame counter, an accumulating score. Values that jump around are not counters.
     */
    fun counters(
        samples: List<ByteArray>,
        type: ValueType = ValueType.U32,
        minMovement: Long = 1,
        limit: Int = 32,
    ): List<RamProbe.Change> {
        if (samples.size < 2) return emptyList()
        val size = samples.minOf { it.size }
        val stride = RamProbe.step(type)
        val found = mutableListOf<RamProbe.Change>()
        var offset = 0
        while (offset + stride <= size) {
            val address = RamProbe.MAIN_RAM_START + offset
            val values = samples.map { RamProbe.read(it, address, type) }
            val rising = values.zipWithNext().all { (before, after) -> after >= before }
            val falling = values.zipWithNext().all { (before, after) -> after <= before }
            val movement = values.max() - values.min()
            if ((rising || falling) && movement >= minMovement && values.toSet().size > 1) {
                found += RamProbe.Change(address, values.first(), values.last())
            }
            offset += stride
        }
        return found.sortedByDescending { kotlin.math.abs(it.after - it.before) }.take(limit)
    }

    /**
     * The addresses that moved around every [moments] entry (the snapshots either side of an input) and stayed put in
     * [others] (the snapshots either side of nothing happening): what the game keeps about that input, and only that
     * input.
     */
    fun inputSignals(
        moments: List<Pair<ByteArray, ByteArray>>,
        others: List<Pair<ByteArray, ByteArray>> = emptyList(),
        type: ValueType = ValueType.U8,
        limit: Int = 32,
    ): List<RamProbe.Change> {
        if (moments.isEmpty()) return emptyList()
        val size = moments.minOf { it.first.size }
        val stride = RamProbe.step(type)
        val found = mutableListOf<RamProbe.Change>()
        var offset = 0
        while (offset + stride <= size) {
            val address = RamProbe.MAIN_RAM_START + offset
            val movedEveryTime = moments.all { (before, after) ->
                RamProbe.read(before, address, type) != RamProbe.read(after, address, type)
            }
            val quietElsewhere = others.none { (before, after) ->
                RamProbe.read(before, address, type) != RamProbe.read(after, address, type)
            }
            if (movedEveryTime && quietElsewhere) {
                found += RamProbe.Change(
                    address,
                    RamProbe.read(moments.first().first, address, type),
                    RamProbe.read(moments.first().second, address, type),
                )
            }
            offset += stride
        }
        return found.take(limit)
    }
}

