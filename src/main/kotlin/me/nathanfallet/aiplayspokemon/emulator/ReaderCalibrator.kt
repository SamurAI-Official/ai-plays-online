package me.nathanfallet.aiplayspokemon.emulator

import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.ValueType

/**
 * Learns a reader for a game **while it is played**, so nobody has to run a calibration by hand for each ROM.
 *
 * Every time the app looks at the game (a few times a second) it hands over the two things it already has: the picture
 * ([FrameHash] of the frame) and the memory ([ConsoleHost.readMainRam]). For each address in the part of memory being
 * examined it notes which value it held on each screen it has seen. That is what `dev/RamProbeTool`'s recipes look for,
 * except the game drives itself: the human or the agent plays, and the reader falls out of it.
 *
 * **One part of memory at a time.** Main RAM is far too big to judge every address on every look, and the addresses
 * that move while a game boots (tens of thousands of them) would swamp anything worth keeping. So the learner examines
 * [chunkSize] bytes, taking its baseline from the moment it started that part, then moves on to the next: over a
 * session it covers the whole of main RAM, and each part is judged on what changed *within* it — which is exactly
 * where a game keeps "which screen am I on". A part is only left behind once the game has shown it at least two
 * screens, or after [maxChunkLooks] looks.
 *
 * **Evidence, not guesswork.** An address is only a signal when it **mostly agrees with the screen**: one value per
 * screen, held on the overwhelming majority of the looks at that screen ([Signal.consistency]). A frame counter, a
 * timer or an animation step changes constantly, so it never agrees and is dropped; an address that is constant within
 * a screen and different between screens is exactly what a reader wants.
 *
 * Once there is enough evidence it hands back a draft [ReaderSpec] the app writes to `<data>/readers/<id>.json` (see
 * [me.nathanfallet.aiplayspokemon.games.ReaderCatalog]). Nothing is invented: the values are the ones the game itself
 * held, and the draft says how it was learned.
 */
class ReaderCalibrator(
    /** Bytes of main RAM examined at a time (128 KiB: a session covers all of a DS's 4 MiB in 32 parts). */
    private val chunkSize: Int = 1 shl 17,
    /** Looks spent on a part of memory before moving on (as long as it has shown at least two screens). */
    private val chunkLooks: Int = 24,
    /** Looks after which a part is left behind even if it never showed a second screen. */
    private val maxChunkLooks: Int = 96,
    /** Most distinct screens to remember: beyond this the game is showing something new every look. */
    private val maxScreens: Int = 256,
    /** Looks at the game needed before a draft is worth writing. */
    private val minSamples: Int = 40,
    /** Distinct screens needed: one screen says nothing about which value means what. */
    private val minScreens: Int = 4,
    /** Screens the best signal has to separate before a draft is worth writing. */
    private val minDistinct: Int = 3,
    /** How often an address has to agree with a screen to count as a signal (0.9: 9 looks out of 10). */
    private val minConsistency: Double = 0.9,
    /** Looks a signal has to survive being checked against the screen after it was found. */
    private val minVerified: Int = 8,
    /** Times a screen has to come back and find the same value: the evidence a clock cannot fake. */
    private val minRevisits: Int = 1,
    /**
     * Two pictures within this many bits are the same screen. Tight on purpose: two menus whose pictures differ by
     * only a few bits are still two screens, and merging them makes their addresses look inconsistent.
     */
    private val screenTolerance: Int = 2,
) {

    /** What the app can show while learning, and what [isReady] needs. */
    data class Progress(
        val samples: Int,
        val screens: Int,
        /** The part of memory being examined now, and how many there are. */
        val chunk: Int,
        val chunks: Int,
        /** Addresses that look like screen signals so far. */
        val candidates: Int,
        /** Distinct values of the best signal found so far. */
        val bestValues: Int,
        /** How often that signal agreed with the screens it was seen on (1.0: never contradicted them). */
        val bestConsistency: Double,
        val ready: Boolean,
    )

    /** One address that behaves like a screen identifier. */
    data class Signal(
        val address: Long,
        /** The value it held on each screen seen, by screen index (the value it held most often there). */
        val byScreen: Map<Int, Long>,
        /** How many different values it took across the screens. */
        val distinct: Int,
        /** How many looks at the game went into it. */
        val looks: Int,
        /** How often it held its screen's value (1.0: it never once contradicted the screen). */
        val consistency: Double,
        /** Looks taken after it was found, on which it was checked against the screen again. */
        val verified: Int = 0,
        /** How often a screen it had already been seen on came back and it held the same value. */
        val revisits: Int = 0,
    )

    /** The values one address held on one screen, and how often. */
    private class Tally(val values: MutableMap<Long, Int> = mutableMapOf()) {
        fun record(value: Long) {
            values[value] = (values[value] ?: 0) + 1
        }
    }


    /** How many looks at the game each screen was seen in, by screen index. */
    private val screens = mutableMapOf<Int, Int>()

    /** One picture per screen, so a screen can be recognised again (a menu that animates is still one screen). */
    private val screenPictures = mutableListOf<Long>()
    private var samples = 0

    /** The part of memory being examined: where it starts, and the RAM it held when the part started. */
    private var chunkStart = 0
    private var chunkBaseline: ByteArray? = null
    private var chunkBaselineScreen: Int? = null
    private var chunkStartSample = 0
    private var ramSize = 0

    /** What each address of the current part held on each screen, and the screens this part has seen. */
    private var tallies = mutableMapOf<Int, MutableMap<Int, Tally>>()
    private val chunkScreens = mutableSetOf<Int>()

    /** Addresses that proved volatile (many values on one screen): not screen signals, and not worth remembering. */
    private val dropped = mutableSetOf<Long>()

    /** The signals found in the parts already examined, best per address. */
    private val found = mutableMapOf<Long, Signal>()

    /**
     * The addresses worth believing: their value is checked against the screen on **every** later look, for the whole
     * run. A screen that comes back must give the same value — that is what tells a screen id from a clock, since a
     * counter that happened to move once per screen would otherwise look perfect.
     */
    private val watched = mutableMapOf<Long, MutableMap<Int, Long>>()

    /** Looks each watched address has been checked on without contradicting the screen. */
    private val verified = mutableMapOf<Long, Int>()

    /** How often a screen a watched address had already been seen on came back and it held the same value. */
    private val revisits = mutableMapOf<Long, Int>()

    /** Addresses that held two values on one screen: never a screen signal, and not worth watching again. */
    private val refuted = mutableSetOf<Long>()

    /** Forgets everything: a new game. */
    fun reset() {
        screens.clear()
        screenPictures.clear()
        samples = 0
        chunkStart = 0
        chunkBaseline = null
        chunkBaselineScreen = null
        chunkStartSample = 0
        ramSize = 0
        tallies = mutableMapOf()
        chunkScreens.clear()
        dropped.clear()
        found.clear()
        watched.clear()
        verified.clear()
        revisits.clear()
        refuted.clear()
    }

    /**
     * Takes one look at the game: [picture] (the frame's [FrameHash]) and [ram] (the whole main RAM). Returns what is
     * known so far, ready or not.
     *
     * Screens are told apart by **similarity**, not equality ([screenTolerance]): a menu that animates is still the
     * same screen, and a game that redraws every frame would otherwise look like a new screen every look.
     */
    fun observe(picture: Long, ram: ByteArray): Progress {
        val screen = screenOf(picture)
        if (ramSize == 0) {
            ramSize = ram.size
            startChunk(0, ram, screen)
        }
        samples++
        if (screen == null) {
            // The game has shown more screens than we can tell apart (a menu that animates every frame, say): there is
            // nothing to learn from a picture we cannot name, but the machinery has to keep moving, or the parts of
            // memory would never rotate and the learner would stall here for the rest of the run.
            advanceIfDone(ram, null)
            return progressFrom(candidates())
        }
        screens[screen] = (screens[screen] ?: 0) + 1
        chunkScreens += screen
        examine(ram, screen)
        // The credible signals start being checked against every later look straight away, so the checking is not
        // held up until the part of memory being examined is finished with.
        watch(candidates())
        advanceIfDone(ram, screen)
        verify(ram, screen)
        return progressFrom(candidates())
    }

    /** Starts checking the signals that look credible against every later look, for the rest of the run. */
    private fun watch(candidates: List<Signal>) {
        for (signal in candidates) {
            if (watched.size >= MAX_WATCHED) return
            if (signal.consistency >= minConsistency && signal.distinct >= 2 &&
                signal.address !in refuted && signal.address !in watched
            ) {
                watched[signal.address] = signal.byScreen.toMutableMap()
            }
        }
    }

    /**
     * Checks every address worth believing against the screen of this look: the value it holds has to match what it
     * held the last time this screen was up. Two values on one screen means it is not a screen signal at all.
     */
    private fun verify(ram: ByteArray, screen: Int) {
        if (watched.isEmpty()) return
        val contradicted = mutableListOf<Long>()
        for ((address, byScreen) in watched) {
            val offset = (address - RamProbe.MAIN_RAM_START).toInt()
            if (offset !in ram.indices) continue
            val value = ram[offset].toLong() and 0xFF
            val known = byScreen[screen]
            when {
                known == null -> byScreen[screen] = value
                known == value -> revisits[address] = (revisits[address] ?: 0) + 1
                else -> contradicted += address
            }
            verified[address] = (verified[address] ?: 0) + 1
        }
        contradicted.forEach {
            watched.remove(it)
            refuted += it
        }
    }

    /** Starts examining the part of memory at [index]. */
    private fun startChunk(index: Int, ram: ByteArray, screen: Int?) {
        chunkStart = index * chunkSize
        val end = minOf(chunkStart + chunkSize, ram.size)
        chunkBaseline = ram.copyOfRange(chunkStart, end)
        chunkBaselineScreen = screen
        chunkStartSample = samples
        tallies = mutableMapOf()
        chunkScreens.clear()
    }

    /** The parts of memory there are, given how big main RAM turned out to be. */
    private fun chunkCount(): Int = if (ramSize == 0) 1 else (ramSize + chunkSize - 1) / chunkSize

    /**
     * Notes what the current part of memory holds now, for each address: the addresses that differ from the part's
     * baseline (they moved), and the ones that still match it (they did not, which is evidence too — without it an
     * address that is right almost always would look perfect, because only its misses are ever recorded).
     */
    private fun examine(ram: ByteArray, screen: Int) {
        val baseline = chunkBaseline ?: return
        val end = minOf(baseline.size, ram.size - chunkStart)
        var offset = 0
        while (offset < end) {
            if (ram[chunkStart + offset] != baseline[offset]) {
                if (RamProbe.MAIN_RAM_START + chunkStart + offset !in dropped) {
                    record(offset, screen, ram[chunkStart + offset].toLong() and 0xFF, baseline[offset].toLong() and 0xFF)
                }
            }
            offset++
        }
        for (tracked in tallies.keys.toList()) {
            if (tracked >= end) continue
            if (ram[chunkStart + tracked] == baseline[tracked]) {
                val tally = tallies[tracked]?.getOrPut(screen) { Tally() } ?: continue
                tally.record(baseline[tracked].toLong() and 0xFF)
            }
        }
    }

    /**
     * Notes that the address at [offset] of the current part held [value] on [screen]. An address is discovered the
     * first time it differs from the part's baseline, so its value on the baseline's screen is the baseline's own:
     * that is where it started, and a reader needs to know it.
     */
    private fun record(offset: Int, screen: Int, value: Long, baselineValue: Long) {
        var byScreen = tallies[offset]
        if (byScreen == null) {
            byScreen = mutableMapOf()
            chunkBaselineScreen?.let { base -> byScreen[base] = Tally(mutableMapOf(baselineValue to 1)) }
            tallies[offset] = byScreen
        }
        val tally = byScreen.getOrPut(screen) { Tally() }
        tally.record(value)
        // Too many values on one screen: this address moves on its own, it is not telling us which screen it is.
        if (tally.values.size > MAX_VALUES_PER_SCREEN) {
            tallies.remove(offset)
            dropped += RamProbe.MAIN_RAM_START + chunkStart + offset
        }
    }

    /** Leaves the current part of memory behind once it has told us what it can, and starts the next one. */
    private fun advanceIfDone(ram: ByteArray, screen: Int?) {
        val looks = samples - chunkStartSample
        val seenEnough = chunkScreens.size >= 2
        if ((looks >= chunkLooks && seenEnough) || looks >= maxChunkLooks) {
            // A part is visited more than once, so a signal may be seen again: keep the better of the two readings
            // (the one that agrees with the screens more often, and then the one with more looks behind it).
            for (signal in signals(tallies, minConsistency = 0.0)) {
                val previous = found[signal.address]
                if (previous == null || signal.consistency > previous.consistency ||
                    (signal.consistency == previous.consistency && signal.looks > previous.looks)
                ) {
                    found[signal.address] = signal
                }
            }
            startChunk((chunkStart / chunkSize + 1) % chunkCount(), ram, screen)
        }
    }

    /**
     * The index of the screen [picture] belongs to, or null when the game has already shown [maxScreens] of them (the
     * game is showing something new every look: there is nothing to learn from it).
     */
    private fun screenOf(picture: Long): Int? {
        val known = screenPictures.indexOfFirst { FrameHash.distance(it, picture) <= screenTolerance }
        if (known >= 0) return known
        if (screenPictures.size >= maxScreens) return null
        screenPictures += picture
        return screenPictures.lastIndex
    }


    /**
     * The addresses that behave like a screen signal: they took at least two different values across the screens seen,
     * and they held one value per screen on at least [minConsistency] of the looks at it.
     *
     * They are ranked by consistency first (an address that never contradicts the screen beats one that mostly does),
     * then by how many screens they separate. [minConsistency] can be lowered by the tools to see what the data
     * contains before the rule is applied (a run whose best address agrees only 0.6 of the time says something).
     */
    fun candidates(minConsistency: Double = this.minConsistency): List<Signal> =
        (found.values + signals(tallies, minConsistency = 0.0))
            .filter { it.consistency >= minConsistency && it.address !in refuted }
            .distinctBy { it.address }
            .map { it.copy(verified = verified[it.address] ?: 0, revisits = revisits[it.address] ?: 0) }
            .sortedWith(
                compareByDescending<Signal> { it.consistency }
                    .thenByDescending { it.distinct }
                    .thenByDescending { it.revisits },
            )
            .take(MAX_CANDIDATES)

    /** The signals hidden in one part of memory's tallies, best first. */
    private fun signals(tallies: Map<Int, Map<Int, Tally>>, minConsistency: Double): List<Signal> =
        tallies.mapNotNull { (offset, byScreen) ->
            var looks = 0
            var agreed = 0
            val majority = mutableMapOf<Int, Long>()
            for ((screen, tally) in byScreen) {
                val best = tally.values.maxByOrNull { it.value } ?: continue
                majority[screen] = best.key
                looks += tally.values.values.sum()
                agreed += best.value
            }
            val distinct = majority.values.toSet().size
            if (distinct < 2) return@mapNotNull null
            val consistency = if (looks == 0) 0.0 else agreed.toDouble() / looks
            if (consistency < minConsistency) return@mapNotNull null
            Signal(RamProbe.MAIN_RAM_START + chunkStart + offset, majority, distinct, looks, consistency)
        }.sortedWith(compareByDescending<Signal> { it.consistency }.thenByDescending { it.distinct })

    /** True when there is enough evidence to write a reader for this game. */
    fun isReady(): Boolean = samples >= minSamples && screens.size >= minScreens && proven().isNotEmpty()

    /**
     * The signals that have been **checked since they were found** and never once contradicted a screen, and that were
     * seen again on a screen that came back ([Signal.revisits]) — the evidence that tells a screen id from a clock.
     */
    fun proven(): List<Signal> = candidates().filter {
        it.distinct >= minDistinct && it.verified >= minVerified && it.revisits >= minRevisits
    }

    /**
     * A draft reader for [id], or null when there is nothing worth writing yet. [names] names a screen **picture**
     * when something else already knows it (a walkthrough landmark, for instance): the closest name within
     * [screenTolerance] is used, so a screen captured at a slightly different moment still gets its name.
     */
    fun draft(id: String, names: Map<Long, String> = emptyMap()): ReaderSpec? {
        val best = proven().take(MAX_OBSERVATIONS)
        if (best.isEmpty()) return null
        return ReaderSpec(
            id = id,
            provenance = "learned while playing",
            observations = best.mapIndexed { index, signal ->
                Observation(
                    name = if (index == 0) "screen" else "signal_${index + 1}",
                    address = signal.address,
                    type = ValueType.U8,
                    values = signal.byScreen.entries.associate { (screen, value) ->
                        value.toString() to nameOf(screen, names)
                    },
                    confidence = signal.consistency,
                    note = "learned while the game was played: it held these values on ${signal.byScreen.size} " +
                        "screens out of ${screens.size}, and was then checked on ${signal.verified} further looks " +
                        "without ever contradicting a screen (${signal.revisits} of them on a screen that came back). " +
                        "Rename the values once you know what they are.",
                )
            },
            notes = "Written automatically by ReaderCalibrator while the game was played (${samples} looks at " +
                "${screens.size} screens). The values are the game's own; the names are placeholders, and a value that " +
                "never appears in a run is a screen nobody has reached yet.",
        )
    }

    /** What to call screen [index]: the closest name known for its picture, or its picture as a fallback. */
    private fun nameOf(index: Int, names: Map<Long, String>): String {
        val picture = screenPictures.getOrNull(index) ?: return "screen $index"
        val closest = names.minByOrNull { (hash, _) -> FrameHash.distance(hash, picture) }
        return if (closest != null && FrameHash.distance(closest.key, picture) <= screenTolerance) {
            closest.value
        } else {
            "screen " + picture.toULong().toString(16).padStart(16, '0')
        }
    }

    /** What is known so far: how many looks, how many screens, which part of memory is being examined. */
    fun progress(): Progress = progressFrom(candidates())

    private fun progressFrom(candidates: List<Signal>): Progress {
        val best = candidates.firstOrNull()
        return Progress(
            samples = samples,
            screens = screens.size,
            chunk = chunkStart / chunkSize,
            chunks = chunkCount(),
            candidates = candidates.size,
            bestValues = best?.distinct ?: 0,
            bestConsistency = best?.consistency ?: 0.0,
            ready = samples >= minSamples && screens.size >= minScreens && candidates.any {
                it.distinct >= minDistinct && it.verified >= minVerified && it.revisits >= minRevisits
            },
        )
    }

    private companion object {
        /** Observations a draft keeps: a reader is a handful of values, not a memory dump. */
        const val MAX_OBSERVATIONS = 4

        /** Signals kept in the report: enough to choose from, few enough to stay cheap. */
        const val MAX_CANDIDATES = 32

        /** Addresses checked against every later look: each one costs a byte read per look. */
        const val MAX_WATCHED = 64

        /** Values one address may take on one screen before it counts as volatile rather than as a screen signal. */
        const val MAX_VALUES_PER_SCREEN = 4
    }
}
