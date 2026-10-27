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

    /**
     * One value that moves on its own, within the part of memory being examined: how it started, how it ended, how
     * many times it moved, and whether it only ever went one way. A score, money or a timer counts; a menu cursor goes
     * both ways and a frame counter moves on every look, so neither is kept (see [foldCounters]).
     */
    private class Counter(
        var first: Long,
        var last: Long,
        var changes: Int,
        val type: ValueType,
        var monotonic: Boolean = true,
    )

    /**
     * One printable run of text in the part of memory being examined: the longest it ever was, how many times its
     * content changed, and the last content seen (what a reader would read right now).
     */
    private class TextRun(
        var longest: Int,
        var changes: Int = 0,
        var last: String = "",
        var looks: Int = 0,
    )


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

    /**
     * The part of memory to start examining at the next look, set by [restore]: a session that ended mid-sweep picks
     * up where it stopped instead of going back to the first byte.
     */
    private var resumeChunk: Int? = null

    /** What each address of the current part held on each screen, and the screens this part has seen. */
    private var tallies = mutableMapOf<Int, MutableMap<Int, Tally>>()
    private val chunkScreens = mutableSetOf<Int>()

    /**
     * Values that count up (or down) on their own, tracked within the current part of memory: a score, money, a
     * timer. Only addresses whose bytes actually moved are tracked (see [trackCounters]), so a part with nothing
     * counting costs almost nothing.
     */
    private var counters = mutableMapOf<Long, Counter>()

    /** Printable text found in the current part of memory, tracked within it (see [findText]). */
    private var textRuns = mutableMapOf<Int, TextRun>()

    /** The counters found in the parts already examined, best per address. */
    private val foundCounters = mutableMapOf<Long, Counter>()

    /** The text runs found in the parts already examined, best per address. */
    private val foundText = mutableMapOf<Long, TextRun>()

    /** Addresses that proved volatile (many values on one screen): not screen signals, and not worth remembering. */
    private val dropped = mutableSetOf<Long>()

    /** The signals found in the parts already examined, best per address. */
    private val found = mutableMapOf<Long, Signal>()

    /** Which part of memory each found signal came from: the best of *each* part is kept (see [pruneFound]). */
    private val foundChunk = mutableMapOf<Long, Int>()

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
        resumeChunk = null
        tallies = mutableMapOf()
        chunkScreens.clear()
        counters = mutableMapOf()
        textRuns = mutableMapOf()
        foundCounters.clear()
        foundText.clear()
        dropped.clear()
        found.clear()
        watched.clear()
        verified.clear()
        revisits.clear()
        refuted.clear()
    }

    /**
     * What has been learned so far, as data a later session can resume from ([ReaderLearning], written to
     * `<data>/readers/<id>.learning.json`). [id] is the game's profile id, and [romSha256] pins the evidence to one ROM.
     *
     * Everything that survives a restart is kept: the screens seen, the signals found with their evidence, how often
     * the watched addresses were checked, and the part of memory the sweep had reached. The part being examined is
     * **not** kept (its tallies belong to a RAM baseline from this session); [restore] re-baselines it.
     */
    fun toEvidence(id: String, romSha256: String = ""): ReaderLearning = ReaderLearning(
        id = id,
        romSha256 = romSha256,
        updatedAt = System.currentTimeMillis(),
        samples = samples,
        chunk = chunkStart / chunkSize,
        screens = screens.entries
            .sortedBy { it.key }
            .map { (index, looks) -> ReaderLearning.ScreenEvidence(index, screenPictures.getOrElse(index) { 0L }, looks) },
        found = found.values.map { signal ->
            ReaderLearning.SignalEvidence(
                signal.address, signal.byScreen, signal.distinct, signal.looks, signal.consistency,
                chunk = foundChunk[signal.address] ?: 0,
            )
        },
        watched = watched.entries.map { (address, byScreen) -> ReaderLearning.WatchedEvidence(address, byScreen) },
        verified = verified.toMap(),
        revisits = revisits.toMap(),
        dropped = dropped.toList(),
        refuted = refuted.toList(),
    )

    /**
     * Resumes from [learning] (see [toEvidence]): the screens, the signals, the checks and the sweep position are put
     * back, and the next look re-baselines the part of memory the previous session was examining.
     *
     * A resumed run therefore keeps everything from the parts of memory already finished, and re-examines the one in
     * progress — the only part whose evidence was tied to a baseline that no longer exists.
     */
    fun restore(learning: ReaderLearning) {
        reset()
        samples = learning.samples
        // Screens are indexed by position, so the pictures have to land at their own index (a gap is kept as a gap).
        val pictures = MutableList((learning.screens.maxOfOrNull { it.index } ?: -1) + 1) { 0L }
        learning.screens.forEach { screen ->
            screens[screen.index] = screen.looks
            pictures[screen.index] = screen.picture
        }
        screenPictures.addAll(pictures)
        learning.found.forEach { signal ->
            found[signal.address] = Signal(signal.address, signal.byScreen, signal.distinct, signal.looks, signal.consistency)
            foundChunk[signal.address] = signal.chunk
        }
        learning.watched.forEach { entry -> watched[entry.address] = entry.byScreen.toMutableMap() }
        verified.putAll(learning.verified)
        revisits.putAll(learning.revisits)
        dropped += learning.dropped
        // `refuted` is **not** resumed: a refutation comes from one session's screen clustering, and a screen whose
        // picture the new session reads slightly differently would refute a good address for good. Every address is
        // re-checked here anyway (the restored [watched] ones on the first look of each screen), so a counter is
        // refuted again within a few looks — while a signal that was wrongly refuted gets its chance back.
        resumeChunk = learning.chunk
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
            // [restore] says which part of memory the previous session had reached: the sweep continues there, with a
            // baseline taken now (the old one described a session that is over).
            startChunk(resumeChunk ?: 0, ram, screen)
            resumeChunk = null
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
            refuted.boundTo(MAX_FORGOTTEN)
            // A refuted address is not a signal: it contradicted a screen. Dropping it here keeps [found] (and the
            // evidence written from it) to what the learner still believes, instead of carrying dead weight.
            found.remove(it)
            foundChunk.remove(it)
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
        // The counters and the text of a part are judged on that part alone (a value that counts up across two parts is
        // two different addresses): they start again with it.
        counters = mutableMapOf()
        textRuns = mutableMapOf()
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
        val moved = mutableListOf<Int>()
        var offset = 0
        while (offset < end) {
            if (ram[chunkStart + offset] != baseline[offset]) {
                if (RamProbe.MAIN_RAM_START + chunkStart + offset !in dropped) {
                    record(offset, screen, ram[chunkStart + offset].toLong() and 0xFF, baseline[offset].toLong() and 0xFF)
                    moved += offset
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
        // The other two kinds of value a reader wants: something that counts, and something the game says in words.
        // Both are looked for only where memory moved, so a quiet part of memory costs nothing.
        trackCounters(ram, moved, end)
        findText(ram, end)
    }

    /**
     * Follows the values that move on their own, at the addresses whose bytes just moved: an address that never moves
     * is not a counter, and a part with nothing counting is not scanned at all.
     *
     * Both a 16- and a 32-bit reading of each moved address are followed (a score is often a halfword, money a word),
     * up to [MAX_COUNTER_TRACKS] addresses per part. What counts is decided when the part is left ([foldCounters]).
     */
    private fun trackCounters(ram: ByteArray, moved: List<Int>, end: Int) {
        if (moved.isEmpty()) return
        for (offset in moved) {
            if (counters.size >= MAX_COUNTER_TRACKS) return
            for (type in COUNTER_TYPES) {
                val at = offset - (offset % RamProbe.step(type))
                if (at < 0 || at + RamProbe.step(type) > end) continue
                val address = RamProbe.MAIN_RAM_START + chunkStart + at
                val value = readAt(ram, chunkStart + at, type)
                // Two (or four) printable characters are text, not a number: a dialogue buffer's tail would otherwise
                // be proposed as a counter that happens to rise (its characters change as the text does).
                if ((0 until RamProbe.step(type)).all { index -> isPrintable(ram[chunkStart + at + index]) }) continue
                val baseline = chunkBaseline ?: return
                val track = counters.getOrPut(address) {
                    // A track is created the moment one of its bytes moves: what it held at the part's baseline is
                    // where it started, and the move that brought us here is the first change.
                    val started = readAt(baseline, at, type)
                    Counter(started, value, if (started != value) 1 else 0, type)
                }
                if (track.type != type) continue
                if (value != track.last) {
                    // A value that goes back down is a cursor, a health bar, a scrolling offset: not a counter.
                    if (value < track.last) track.monotonic = false
                    track.changes++
                    track.last = value
                }
            }
        }
    }

    /**
     * Finds the printable text of the part: runs of at least [MIN_TEXT_LENGTH] printable characters, followed by a
     * terminator (a zero byte, or the end of the part). A run is only kept when its content actually changed while the
     * game was played ([MIN_TEXT_CHANGES]): a string nobody ever rewrites is not something a reader can show the model.
     */
    private fun findText(ram: ByteArray, end: Int) {
        var offset = 0
        while (offset < end) {
            if (!isPrintable(ram[chunkStart + offset])) {
                offset++
                continue
            }
            val start = offset
            while (offset < end && offset - start < MAX_TEXT_LENGTH && isPrintable(ram[chunkStart + offset])) offset++
            val length = offset - start
            // A run that ended because the part did (no terminator) is not a string: the rest may be in the next part.
            val terminated = offset < end && ram[chunkStart + offset].toInt() == 0
            if (length >= MIN_TEXT_LENGTH && terminated) recordText(start, length, ram)
            if (offset == start) offset++
        }
    }

    /** Notes what the run of text at [offset] says this look, and how long it is. */
    private fun recordText(offset: Int, length: Int, ram: ByteArray) {
        val content = String(ram, chunkStart + offset, length, Charsets.ISO_8859_1)
        val run = textRuns.getOrPut(offset) { TextRun(longest = length) }
        run.looks++
        if (length > run.longest) run.longest = length
        if (run.looks > 1 && content != run.last) run.changes++
        run.last = content
    }

    private fun isPrintable(byte: Byte): Boolean = byte.toInt() in 32..126

    private fun readAt(ram: ByteArray, offset: Int, type: ValueType): Long = when (type) {
        ValueType.U16 -> ((ram[offset].toLong() and 0xFF) or ((ram[offset + 1].toLong() and 0xFF) shl 8))
        ValueType.U32 -> (ram[offset].toLong() and 0xFF) or ((ram[offset + 1].toLong() and 0xFF) shl 8) or
            ((ram[offset + 2].toLong() and 0xFF) shl 16) or ((ram[offset + 3].toLong() and 0xFF) shl 24)
        else -> ram[offset].toLong() and 0xFF
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
            dropped.boundTo(MAX_FORGOTTEN)
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
                    foundChunk[signal.address] = chunkStart / chunkSize
                }
            }
            pruneFound()
            foldCounters(looks)
            foldText(looks)
            startChunk((chunkStart / chunkSize + 1) % chunkCount(), ram, screen)
        }
    }

    /**
     * Keeps the counters the part just showed: a value that only ever went one way, moved at least
     * [MIN_COUNTER_CHANGES] times, and did **not** move on most looks.
     *
     * The last rule is what tells a score from a clock: a frame counter rises every look (and would drown the model in
     * noise), while money or a score moves every so often. A value that goes back down (a cursor, a health bar, a
     * scrolling offset) was marked when it did and is not kept.
     */
    private fun foldCounters(looks: Int) {
        for ((address, track) in counters) {
            if (!track.monotonic || track.changes < MIN_COUNTER_CHANGES) continue
            if (track.changes * MAX_COUNTER_CHANGE_RATE > looks) continue
            val previous = foundCounters[address]
            // Same address read as two widths (a halfword inside a word): the narrowest reading wins, so the reader
            // shows the value the game most likely keeps there rather than the same number padded with zeros.
            val narrower = previous != null && RamProbe.step(track.type) < RamProbe.step(previous.type)
            if (previous == null || track.changes > previous.changes ||
                (track.changes == previous.changes && narrower)
            ) {
                foundCounters[address] = track
            }
        }
        if (foundCounters.size > MAX_COUNTERS) {
            val keep = foundCounters.entries.sortedByDescending { it.value.changes }.take(MAX_COUNTERS)
            foundCounters.clear()
            keep.forEach { (address, track) -> foundCounters[address] = track }
        }
    }

    /**
     * Keeps the text the part just showed: a run of printable characters whose content changed while the game was
     * played. The longest run of a part wins, and the parts of memory where text lives are few, so [MAX_TEXT_RUNS]
     * keeps what is worth reading.
     */
    private fun foldText(looks: Int) {
        for ((offset, run) in textRuns) {
            if (run.changes < MIN_TEXT_CHANGES || run.longest < MIN_TEXT_LENGTH) continue
            val address = RamProbe.MAIN_RAM_START + chunkStart + offset
            val previous = foundText[address]
            if (previous == null || run.changes > previous.changes || run.longest > previous.longest) {
                foundText[address] = run
            }
        }
        if (foundText.size > MAX_TEXT_RUNS) {
            val keep = foundText.entries
                .sortedWith(compareByDescending<Map.Entry<Long, TextRun>> { it.value.changes }.thenByDescending { it.value.longest })
                .take(MAX_TEXT_RUNS)
            foundText.clear()
            keep.forEach { (address, run) -> foundText[address] = run }
        }
    }

    /**
     * Keeps [found] to the signals worth keeping.
     *
     * A sweep of 4 MiB folds **every** address whose value moved with a screen — hundreds of thousands of them, and the
     * evidence file then weighed 222 MB in a real run (Phantasy Star 0, 71 screens). Only the best handful are ever
     * read, but *which* handful matters: the screen id of a game lives in one part of memory, so the learner keeps the
     * best [MAX_FOUND_PER_CHUNK] signals of **each** part it examined, up to [MAX_FOUND] in all. A global top-N would
     * instead fill up with the addresses that happen to separate the most screens once (a part examined near the end of
     * a session) and drop the checked ones a resumed session depends on.
     */
    private fun pruneFound() {
        if (found.size <= MAX_FOUND) return
        val keep = found.values
            .groupBy { foundChunk[it.address] ?: 0 }
            .values
            .flatMap { signals ->
                signals.sortedWith(compareByDescending<Signal> { it.consistency }.thenByDescending { it.distinct })
                    .take(MAX_FOUND_PER_CHUNK)
            }
            .sortedWith(
                // The signals that have been **checked** first: what a resumed session must not lose.
                compareByDescending<Signal> { verified[it.address] ?: 0 }
                    .thenByDescending { revisits[it.address] ?: 0 }
                    .thenByDescending { it.consistency }
                    .thenByDescending { it.distinct },
            )
            .take(MAX_FOUND)
        found.clear()
        keep.forEach { signal -> found[signal.address] = signal }
        foundChunk.keys.retainAll(found.keys)
    }

    /**
     * Keeps a "do not look at this again" set bounded: [dropped] and [refuted] are an optimization (they stop the
     * learner from re-examining addresses it already judged), not a record worth keeping forever. The oldest entries
     * go first; an address that turns out to matter is found again on the next sweep.
     */
    private fun <T> MutableSet<T>.boundTo(max: Int) {
        while (size > max) remove(first())
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
     *
     * The filter runs **before** the ranking, over every candidate, not over [candidates]' top handful: a run can end
     * with dozens of unverified addresses that separate more screens than the verified ones (a part of memory examined
     * near the end of a session), and asking the top of *those* would come back empty even though the run did prove a
     * signal. A resumed session hits this as soon as it reaches parts a first session never examined.
     */
    fun proven(): List<Signal> = (found.values + signals(tallies, minConsistency = 0.0))
        .filter { it.address !in refuted }
        .distinctBy { it.address }
        .map { it.copy(verified = verified[it.address] ?: 0, revisits = revisits[it.address] ?: 0) }
        .filter {
            it.consistency >= minConsistency && it.distinct >= minDistinct &&
                it.verified >= minVerified && it.revisits >= minRevisits
        }
        .sortedWith(
            compareByDescending<Signal> { it.consistency }
                .thenByDescending { it.distinct }
                .thenByDescending { it.revisits },
        )
        .take(MAX_CANDIDATES)

    /**
     * A draft reader for [id], or null when there is nothing worth writing yet. [names] names a screen **picture**
     * when something else already knows it (a walkthrough landmark, for instance): the closest name within
     * [screenTolerance] is used, so a screen captured at a slightly different moment still gets its name.
     */
    fun draft(id: String, names: Map<Long, String> = emptyMap()): ReaderSpec? {
        val best = proven().take(MAX_SCREEN_OBSERVATIONS)
        if (best.isEmpty()) return null
        val observations = buildList {
            addAll(best.mapIndexed { index, signal -> screenObservation(index, signal, names) })
            addAll(bestCounters().take(MAX_COUNTER_OBSERVATIONS).mapIndexed { index, (address, counter) ->
                counterObservation(index, address, counter)
            })
            bestText().take(MAX_TEXT_OBSERVATIONS).forEach { (address, run) -> add(textObservation(address, run)) }
        }
        return ReaderSpec(
            id = id,
            provenance = ReaderSpec.PROVENANCE_LEARNED,
            observations = observations.take(MAX_OBSERVATIONS),
            notes = "Written automatically by ReaderCalibrator while the game was played (${samples} looks at " +
                "${screens.size} screens). The values are the game's own; the names are placeholders, and a value that " +
                "never appears in a run is a screen nobody has reached yet.",
        )
    }

    /** One screen signal: the value it holds on each screen, named when something already knows the picture. */
    private fun screenObservation(index: Int, signal: Signal, names: Map<Long, String>) = Observation(
        name = if (index == 0) "screen" else "signal_${index + 1}",
        address = signal.address,
        type = ValueType.U8,
        values = signal.byScreen.entries.associate { (screen, value) -> value.toString() to nameOf(screen, names) },
        confidence = signal.consistency,
        note = "learned while the game was played: it held these values on ${signal.byScreen.size} " +
            "screens out of ${screens.size}, and was then checked on ${signal.verified} further looks " +
            "without ever contradicting a screen (${signal.revisits} of them on a screen that came back). " +
            "Rename the values once you know what they are.",
    )

    /** One counter: a value that only went one way while the part of memory it lives in was examined. */
    private fun counterObservation(index: Int, address: Long, counter: Counter) = Observation(
        name = if (index == 0) "counter" else "counter_${index + 1}",
        address = address,
        type = counter.type,
        confidence = 0.7,
        note = "learned while the game was played: it only ever went one way, from ${counter.first} to " +
            "${counter.last} in ${counter.changes} steps (a score, money, a timer, a level...). Rename it once you " +
            "know what it counts.",
    )

    /** One text run: what the game says in words, read straight out of memory. */
    private fun textObservation(address: Long, run: TextRun) = Observation(
        name = "text",
        address = address,
        type = ValueType.ASCII,
        length = run.longest,
        confidence = 0.6,
        note = "learned while the game was played: printable text that changed ${run.changes} times " +
            "(its longest form was ${run.longest} characters). It is what the game writes there now; a reader " +
            "cannot say what it means.",
    )

    /** The counters to write, the one that moved most first (a score beats a rarely used counter). */
    private fun bestCounters(): List<Pair<Long, Counter>> =
        foundCounters.entries.sortedByDescending { it.value.changes }.map { it.key to it.value }

    /** The text runs to write, the one that changed most first. */
    private fun bestText(): List<Pair<Long, TextRun>> =
        foundText.entries.sortedByDescending { it.value.changes }.map { it.key to it.value }

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
        /** Values a draft keeps: a handful, not a memory dump (screen signals first, then counters, then text). */
        const val MAX_OBSERVATIONS = 6

        /** Screen signals a draft keeps: the screen id and a few more. */
        const val MAX_SCREEN_OBSERVATIONS = 4

        /** Counters a draft keeps: a score and, at most, one more. */
        const val MAX_COUNTER_OBSERVATIONS = 2

        /** Text runs a draft keeps: the one the game talks through. */
        const val MAX_TEXT_OBSERVATIONS = 1

        /** The readings of a moved address that are followed as possible counters. */
        val COUNTER_TYPES = listOf(ValueType.U16, ValueType.U32)

        /** Addresses followed as counters in one part of memory (a part that moves everywhere is not a counter). */
        const val MAX_COUNTER_TRACKS = 512

        /** Times a value has to have moved before it is worth calling a counter. */
        const val MIN_COUNTER_CHANGES = 2

        /**
         * A counter may move at most once every this many looks. A frame counter rises on every look, so it is
         * excluded; a score or money moves every so often.
         */
        const val MAX_COUNTER_CHANGE_RATE = 4

        /** Counters kept across the whole sweep (the best few are written). */
        const val MAX_COUNTERS = 16

        /** Shortest printable run worth reading as text. */
        const val MIN_TEXT_LENGTH = 6

        /** Longest run a reader shows (a reader is a value, not a screenful). */
        const val MAX_TEXT_LENGTH = 64

        /** Times a text run has to have changed: a string nobody rewrites says nothing about the game. */
        const val MIN_TEXT_CHANGES = 1

        /** Text runs kept across the whole sweep (the best few are written). */
        const val MAX_TEXT_RUNS = 4

        /** Signals kept in the report: enough to choose from, few enough to stay cheap. */
        const val MAX_CANDIDATES = 32

        /**
         * Signals the learner remembers across the whole sweep. Without a cap the evidence file reached hundreds of
         * megabytes: a real 71-screen run folded 705,824 addresses, of which 32 are ever reported. The cap is per part
         * of memory ([MAX_FOUND_PER_CHUNK]) and then overall, so a part's own screen id is not pushed out by another
         * part's noisier addresses.
         */
        const val MAX_FOUND = 256

        /** Best signals kept per part of memory examined (the app's default sweep has 32 parts of 128 KiB). */
        const val MAX_FOUND_PER_CHUNK = 8

        /** Addresses kept in the "do not look at this again" sets ([dropped], [refuted]): an optimization, not a record. */
        const val MAX_FORGOTTEN = 4096

        /** Addresses checked against every later look: each one costs a byte read per look. */
        const val MAX_WATCHED = 64

        /** Values one address may take on one screen before it counts as volatile rather than as a screen signal. */
        const val MAX_VALUES_PER_SCREEN = 4
    }
}
