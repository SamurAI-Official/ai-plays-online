package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.Memory
import kotlinx.serialization.Serializable
import me.nathanfallet.aiplayspokemon.games.Observation
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.ValueType

/** What the check thought of one value of a reader. */
@Serializable
enum class ReaderVerdict {
    /** A value that names screens: it held one value per screen and told screens apart. */
    OK,

    /** It took more than one value on a screen it had already seen: it moves on its own. */
    INCONSISTENT,

    /** It never changed: it cannot say which screen is up (it may still be a constant the reader wanted). */
    CONSTANT,

    /** It was never readable: a guard that never held, a pointer outside main RAM, an address out of range. */
    UNREADABLE,
}

/** What the check saw of one observation, over a run. */
@Serializable
data class ObservationVerdict(
    val name: String,
    val address: Long,
    val verdict: ReaderVerdict,
    /** Looks where the value was read (a guarded value is only read while its guard holds). */
    val reads: Int,
    /** Looks where it was not (guard off, pointer out of range). */
    val unreadable: Int,
    /** Of the looks where it was read: the share that held the value its screen is known for. */
    val consistency: Double,
    /** Different values it held, one per screen: 1 means it cannot tell screens apart. */
    val distinct: Int,
    /** Screens where it held one value, over the screens it was read on. */
    val screensHeld: Int,
    val screensSeen: Int,
    /** How many times its value changed while the game was played (a counter moves, a constant does not). */
    val changes: Int,
    /** What it reads now, or the range it moved over, in one line. */
    val detail: String,
)

/** The check of a whole reader, as the panel and the tools show it. */
@Serializable
data class ReaderVerification(
    val id: String,
    val looks: Int,
    val screens: Int,
    val observations: List<ObservationVerdict>,
) {
    /** Values that name screens and were judged as such. */
    private val screenValues get() = observations.filter { it.screensSeen > 0 }

    /**
     * Whether the run showed enough to say anything: at least [MIN_SCREENS] screens and [MIN_LOOKS] looks. Below that a
     * verdict would be about the route rather than the reader — one screen makes every value look constant — so nothing
     * is written back and the panel says it is still checking.
     */
    val isConclusive: Boolean get() = screens >= MIN_SCREENS && looks >= MIN_LOOKS

    /** The values that did not hold up: what a user has to look at (empty while the check is still thin). */
    val problems: List<ObservationVerdict>
        get() = if (!isConclusive) emptyList() else observations.filter {
            it.verdict == ReaderVerdict.INCONSISTENT || it.verdict == ReaderVerdict.UNREADABLE
        }

    /** One line for the panel. */
    val summary: String
        get() = if (!isConclusive) {
            "checking… $looks looks and $screens screens so far (a verdict needs $MIN_LOOKS looks and $MIN_SCREENS screens)"
        } else {
            buildString {
                append("checked over $looks looks and $screens screens: ")
                append("${screenValues.count { it.verdict == ReaderVerdict.OK }} of ${screenValues.size} screen values hold")
                if (problems.isNotEmpty()) append(" · ${problems.size} to look at")
                if (screenValues.isEmpty()) append(" · no value claims to name screens")
            }
        }

    companion object {
        /** Looks a check needs before it is believed: a few seconds of play. */
        const val MIN_LOOKS = 60

        /** Screens a check needs before it is believed: below that, "it never changed" says nothing. */
        const val MIN_SCREENS = 3
    }
}

/**
 * Checks what a reader claims against the game as it is played: does each value still read **one value per screen**,
 * and does it tell screens apart?
 *
 * A reader is a claim about someone else's program (see `docs/readers.md`): `confidence` and `note` are how the claim
 * is written down, and this is how it is checked later. It applies the same rule the learner writes readers under
 * ([ReaderCalibrator]): a value is a screen signal when it holds one value on 90%+ of the looks at a screen, and tells
 * at least two screens apart. A value that moves on its own within a screen is [ReaderVerdict.INCONSISTENT] — it would
 * tell the agent it is on a screen it has left.
 *
 * Only the values that **claim** to name screens (they carry a `values` map) are judged that way. A counter is expected
 * to move and text is expected to be rewritten: those are reported for what they do (moved / constant / unreadable),
 * never against a rule they did not claim.
 *
 * What it compares is what the agent would see ([Observation.format]), guards and all, so a value that is `not now` in
 * this game is reported as unreadable instead of being compared to something it never was.
 */
class ReaderVerifier(
    reader: ReaderSpec,
    /** Two pictures within this many bits are the same screen: the learner's tolerance ([ReaderCalibrator]). */
    private val screenTolerance: Int = 2,
    /** How often a value has to agree with a screen to count as held there: the learner's rule. */
    private val minConsistency: Double = 0.9,
    /** Most distinct screens to remember (as the learner: beyond this the game shows something new every look). */
    private val maxScreens: Int = 256,
) {

    /** The reader being checked, as it is now: [reread] follows it when the app learns more of it while playing. */
    var reader: ReaderSpec = reader
        private set

    private class Tally(val values: MutableMap<String, Int> = mutableMapOf())

    /** What is tracked of one observation. */
    private class Track(var observation: Observation) {
        val byScreen = mutableMapOf<Int, Tally>()
        var reads = 0
        var notNow = 0
        var outOfRange = 0
        var changes = 0
        var last: String? = null
        var smallest: Long = Long.MAX_VALUE
        var largest: Long = Long.MIN_VALUE
    }

    /** How a value agreed with the screens it was read on. */
    private data class Agreement(val held: Int, val distinct: Int, val consistency: Double)

    /** The screens seen, by index: one picture each, so the same screen is recognised again. */
    private val screenPictures = mutableListOf<Long>()
    private val tracks = reader.observations.map(::Track).toMutableList()
    private var looks = 0

    /** Takes one look at the game: the picture ([FrameHash] of the frame) and the memory. */
    fun observe(picture: Long, memory: Memory): ReaderVerification {
        val screen = screenOf(picture) ?: return report()
        looks++
        for (track in tracks) {
            val observation = track.observation
            val value = observation.format(memory)
            if (value == NOT_NOW || value == OUT_OF_RANGE) {
                if (value == NOT_NOW) track.notNow++ else track.outOfRange++
                continue
            }
            track.reads++
            if (track.last != null && track.last != value) track.changes++
            track.last = value
            runCatching { observation.read(memory) }.getOrNull()?.let { raw ->
                if (raw < track.smallest) track.smallest = raw
                if (raw > track.largest) track.largest = raw
            }
            if (observation.values.isNotEmpty()) {
                track.byScreen.getOrPut(screen) { Tally() }.values.merge(value, 1, Int::plus)
            }
        }
        return report()
    }

    /** The verdicts so far, ready or not. */
    fun report(): ReaderVerification = ReaderVerification(
        id = reader.id,
        looks = looks,
        screens = screenPictures.size,
        observations = tracks.map(::verdictOf),
    )

    /**
     * Follows [spec], the same reader after the app learned more of it (a `learned while playing` reader grows while it
     * is played, see [ReaderMerge]). The looks already taken are **kept** for every value that is still the same reading
     * (`name`, `address`, `type`, guards, names…); a value that changed reading starts over, because the tally is not
     * about it anymore. Without this, a growing reader would restart the check every time it grew, and never be checked
     * over more than a minute of play.
     */
    fun reread(spec: ReaderSpec) {
        if (spec == reader) return
        val previous = tracks.associateBy { it.observation.name }
        reader = spec
        tracks.clear()
        tracks += spec.observations.map { observation ->
            previous[observation.name]
                ?.takeIf { it.observation.sameReadingAs(observation) }
                ?.also { it.observation = observation }
                ?: Track(observation)
        }
    }

    /** True when both are the same reading: everything but what the check itself writes ([confidence], [note]). */
    private fun Observation.sameReadingAs(other: Observation): Boolean =
        copy(confidence = 0.0, note = "") == other.copy(confidence = 0.0, note = "")


    /**
     * [reader] with what the check saw written into it: a screen value caught moving within a screen it had seen is
     * downgraded to how often it really held (the observed agreement), and the notes carry one rewritten line saying how
     * the check went.
     *
     * Deliberately **not** written: a value that never changed, or was never readable, keeps its confidence — the check
     * cannot refute it (a run that stays on one screen makes every value look constant), so it is reported in the notes
     * and the panel instead. Values that claim nothing about screens ([Observation.values] empty: a counter, text) keep
     * their confidence too. A check that has not seen enough ([ReaderVerification.isConclusive]) changes nothing at all.
     */
    fun verifiedReader(verification: ReaderVerification = report()): ReaderSpec {
        if (!verification.isConclusive) return reader
        val byName = verification.observations.associateBy { it.name }
        val observations = reader.observations.map { observation ->
            val verdict = byName[observation.name] ?: return@map observation
            // Only a value that claims to name screens can be judged as one: a counter or text is reported, not graded.
            if (observation.values.isEmpty()) return@map observation
            val confidence = verdict.confidence() ?: return@map observation
            observation.copy(confidence = minOf(observation.confidence, confidence))
        }
        val judged = verification.observations.filter { it.screensSeen > 0 }
        val line = "$VERIFIED_MARKER ${judged.count { it.verdict == ReaderVerdict.OK }} of ${judged.size} screen " +
            "values hold (${verification.looks} looks, ${verification.screens} screens)" +
            (if (verification.problems.isNotEmpty()) ", ${verification.problems.size} to look at" else "") + "."
        val kept = reader.notes.lineSequence().filterNot { it.startsWith(VERIFIED_MARKER) }.joinToString("\n").trimEnd()
        return reader.copy(
            observations = observations,
            notes = if (kept.isEmpty()) line else "$kept\n$line",
        )
    }

    private fun verdictOf(track: Track): ObservationVerdict {
        val observation = track.observation
        val screensSeen = track.byScreen.size
        val agreement = screenAgreement(track)
        val verdict = when {
            track.reads == 0 -> ReaderVerdict.UNREADABLE
            observation.values.isEmpty() -> if (track.changes > 0) ReaderVerdict.OK else ReaderVerdict.CONSTANT
            agreement.distinct <= 1 -> ReaderVerdict.CONSTANT
            agreement.held < screensSeen * minConsistency -> ReaderVerdict.INCONSISTENT
            else -> ReaderVerdict.OK
        }
        return ObservationVerdict(
            name = observation.name,
            address = observation.address,
            verdict = verdict,
            reads = track.reads,
            unreadable = track.notNow + track.outOfRange,
            consistency = agreement.consistency,
            distinct = agreement.distinct,
            screensHeld = agreement.held,
            screensSeen = screensSeen,
            changes = track.changes,
            detail = detailOf(observation, track),
        )
    }

    /** How the value agreed with the screens it was read on: screens held, values seen, overall agreement. */
    private fun screenAgreement(track: Track): Agreement {
        var held = 0
        var reads = 0
        var agreed = 0
        val majorities = mutableSetOf<String>()
        for ((_, tally) in track.byScreen) {
            val total = tally.values.values.sum()
            val best = tally.values.maxByOrNull { it.value } ?: continue
            reads += total
            agreed += best.value
            majorities += best.key
            if (best.value >= total * minConsistency) held++
        }
        return Agreement(held, majorities.size, if (reads == 0) 0.0 else agreed.toDouble() / reads)
    }

    /**
     * The confidence a screen value keeps after the check: how often it really held its screen when it held at all, and
     * **null** when the check cannot say (a constant, or a value it never read): the claim is not refuted, so it is not
     * touched — the verdict is what says it did not do anything in this game.
     */
    private fun ObservationVerdict.confidence(): Double? = when (verdict) {
        ReaderVerdict.OK, ReaderVerdict.INCONSISTENT -> consistency
        ReaderVerdict.CONSTANT, ReaderVerdict.UNREADABLE -> null
    }

    /** What the value reads now, or the range it moved over: the evidence a user judges the claim by. */
    private fun detailOf(observation: Observation, track: Track): String = when {
        track.reads == 0 -> "never read (${neverRead(track)})"
        observation.type == ValueType.ASCII -> "now: \"${track.last.orEmpty().take(48)}\", changed ${track.changes} times"
        observation.values.isNotEmpty() -> "now: ${track.last}, changed ${track.changes} times"
        track.smallest == Long.MAX_VALUE -> "now: ${track.last}"
        else -> "moved ${track.changes} times, from ${track.smallest} to ${track.largest}"
    }

    /** Why a value was never read: a guard that never held, or a pointer that never landed in main RAM. */
    private fun neverRead(track: Track): String = listOfNotNull(
        track.notNow.takeIf { it > 0 }?.let { "guard off $it times" },
        track.outOfRange.takeIf { it > 0 }?.let { "out of range $it times" },
    ).joinToString(", ").ifEmpty { "not one look" }

    /**
     * The index of the screen [picture] belongs to, or null when the game has shown more screens than we can tell
     * apart: nothing can be said about a picture we cannot name, so that look is not counted.
     */
    private fun screenOf(picture: Long): Int? {
        val known = screenPictures.indexOfFirst { FrameHash.distance(it, picture) <= screenTolerance }
        if (known >= 0) return known
        if (screenPictures.size >= maxScreens) return null
        screenPictures += picture
        return screenPictures.lastIndex
    }

    private companion object {
        /** What [Observation.format] answers when a guarded value does not apply right now. */
        const val NOT_NOW = "not now"

        /** What it answers for a pointer that does not land in main RAM. */
        const val OUT_OF_RANGE = "out of range"

        /** The notes line the check rewrites, so a reader does not grow a line per run. */
        const val VERIFIED_MARKER = "Checked while playing:"
    }
}

