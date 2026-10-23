package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import me.nathanfallet.aiplayspokemon.emulator.audio.SpeechAudio

/**
 * The stream's voice for the agent: speaks every decision the player makes — its number, confidence, what it
 * does and why (the reasoning it wrote) — rendered by the macOS `say` command and played through the app's own
 * audio (see [SpeechAudio]), so a viewer hears what the AI is thinking while watching the game and a stream
 * capturing the app's sound carries the voice with it.
 *
 * Lines never block the player's loop ([narrateDecision] only offers them) and are spoken one at a time: at
 * most [QUEUE] wait, the oldest waiting one dropped when a new arrives (a narrator running behind says the
 * decision on screen, not the ones it missed). [close] stops the queue and cuts the voice mid-sentence.
 */
class DecisionNarrator(
    /** The `say` voice (read per line: changing the voice applies to the next line, no restart). */
    private val voice: () -> String,
    /** Words per minute. */
    private val rate: () -> Int,
    private val scope: CoroutineScope,
    /** Speaks one line to its end — injectable so tests never touch a real voice. */
    private val speaker: Speaker = MacSay(),
    /** What the narrator did when it couldn't (e.g. the UI's system log): failures were silent before. */
    private val report: (String) -> Unit = {},
    /** Every line the voice really said, with the voice that said it (the UI's readout, the stdout log). */
    private val onSpoken: (line: String, voice: String) -> Unit = { _, _ -> },
) {

    /** A text-to-speech voice: [speak] blocks until the line is said, [cut] stops it mid-line. */
    interface Speaker {
        fun speak(text: String, voice: String, rate: Int)
        fun cut()
    }

    private val queue = Channel<String>(capacity = QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile private var closed = false

    /** The last error said, so the same standing error isn't repeated on every turn. */
    @Volatile private var lastError: String? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (line in queue) {
                if (closed) break
                val spokenVoice = voice()
                // The readout and the log show a line only once it was really said: a line the voice refused
                // (an unknown voice, a broken audio stack) is reported as a failure, not as something spoken.
                runCatching { speaker.speak(line, spokenVoice, rate()) }
                    .onSuccess { onSpoken(line, spokenVoice) }
                    .onFailure { report("narration failed: ${it.message ?: it::class.simpleName}") }
            }
        }
    }

    /** Says the decision just made (and clears the error remembered: a decision starts a new story). */
    fun narrateDecision(decision: Decision) = offer(NarrationScript.of(decision))

    /** Says a milestone the run reached (a new place, a story step...). */
    fun narrateMilestone(name: String) = offer(NarrationScript.forSpeech("Milestone: $name"))

    /**
     * Says a problem (a timed-out request, an invalid answer...) — but the same error in a row only once while
     * it stands: repeating it every 2 s retry would drown the run's own story. Any other line clears it.
     */
    fun narrateError(message: String) {
        val line = NarrationScript.forSpeech(message)
        if (line.isBlank() || line == lastError || closed) return
        lastError = line
        narrate("Warning: $line")
    }

    /** Offers [line] to the queue (blank lines and closed narrators are ignored). */
    private fun narrate(line: String) {
        if (line.isBlank() || closed) return
        queue.trySend(line)
    }

    /** Non-error lines also mean the remembered error is history. */
    private fun offer(line: String) {
        if (line.isBlank()) return
        lastError = null
        narrate(line)
    }

    /** Stops speaking: no further line is said, and the one being said is cut mid-sentence. */
    fun close() {
        closed = true
        queue.close()
        speaker.cut()
    }

    /**
     * The macOS `say` command: one process per line, its text on stdin (no shell quoting anywhere).
     *
     * The command only *renders* the line (to a temp WAV — `say`'s default AIFC output is not readable by Java
     * Sound, `--data-format=LEI16@22050` is); [audio] plays it through the app, so a stream capturing the app's
     * sound carries the voice — `say`'s own playback comes from another process and would be missing from it.
     * [outputFile] keeps the rendered file instead (one file per line, numbered): the way to check afterwards
     * what the stream really said, and to replay it; nothing is played then.
     */
    class MacSay(
        /** Plays a rendered line through the app (injectable: tests record instead of playing). */
        private val audio: SpeechAudio = SpeechAudio.app(),
        private val outputFile: (lineNumber: Int) -> java.nio.file.Path? = { null },
    ) : Speaker {
        @Volatile private var current: Process? = null
        @Volatile private var cut = false
        private val lineNumber = java.util.concurrent.atomic.AtomicInteger()

        override fun speak(text: String, voice: String, rate: Int) {
            // `say` does not fail on an unknown voice: it falls back to a default one and says the line in it, so
            // the run would claim a voice it never used. The voice is checked here instead, where it can be
            // reported (the voices the picker offers are all installed, so this only catches a stale setting).
            require(voice in installed) { "voice \"$voice\" is not installed" }
            val file = outputFile(lineNumber.incrementAndGet())
            if (file != null) {
                render(text, voice, rate, file)
                return
            }
            // The app's path: render the line to a temp WAV, play it through the app's own audio, then drop it.
            val temp = java.nio.file.Files.createTempFile("narration-", ".wav")
            try {
                render(text, voice, rate, temp)
                if (!cut) play(temp)
            } finally {
                java.nio.file.Files.deleteIfExists(temp)
            }
        }

        /** Runs `say` for one line (its text on stdin), blocking until it has rendered [text] into [file]. */
        private fun render(text: String, voice: String, rate: Int, file: java.nio.file.Path?) {
            val command = mutableListOf("/usr/bin/say", "-v", voice, "-r", rate.toString())
            // A .wav is for the app: --data-format=LEI16@22050, the 16-bit little-endian PCM Java Sound reads
            // (`say`'s AIFC default is unreadable to it, and LEI16 is refused in an .aiff). Any other extension
            // keeps `say`'s own defaults — the numbered .aiff files of the checks and replays.
            if (file != null && file.toString().endsWith(".wav", ignoreCase = true)) {
                command += "--data-format=LEI16@22050"
            }
            if (file != null) command += listOf("-o", file.toString())
            val process = try {
                ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .start()
            } catch (error: Exception) {
                throw IllegalStateException("can't start /usr/bin/say: ${error.message}", error)
            }
            cut = false
            current = process
            try {
                process.outputStream.bufferedWriter().use { it.write(text) } // closing stdin starts the speech
                process.waitFor()
            } catch (_: InterruptedException) {
                process.destroyForcibly()
            } finally {
                if (current === process) current = null
            }
            // An unknown voice (or a broken audio stack) makes `say` exit at once with an error: reported to
            // [DecisionNarrator.report] rather than swallowed — a narrator that can't speak must be visible.
            if (!cut && !process.isAlive && process.exitValue() != 0) {
                throw IllegalStateException("say exited with ${process.exitValue()} for voice \"$voice\"")
            }
        }

        /** Plays a rendered line through the app; a cut during it is silent, any other failure stays visible. */
        private fun play(file: java.nio.file.Path) {
            try {
                audio.play(file)
            } catch (error: Exception) {
                if (!cut) throw error
            }
        }

        override fun cut() {
            cut = true
            current?.destroyForcibly()
            audio.cut()
        }
    }

    companion object {
        /** How many lines may wait behind the one being spoken (the oldest waiting one is dropped). */
        const val QUEUE = 2

        /** The narration voice when nothing else is configured. */
        const val DEFAULT_VOICE = "Whisper"

        /** The voice name is the first word on the line. */
        private val VOICE_NAME = Regex("""^(\S+)""")

        /** An English locale tag anywhere on the line (macOS `say -v '?'` puts it in varying columns). */
        private val ENGLISH_LOCALE = Regex("""\ben_[A-Z]{2}\b""")

        /**
         * The English voices installed for macOS `say` (`say -v '?'`), unique by name — what the voice picker
         * lists. [fallback] when the command can't be read (tests, non-macOS): the default voice alone.
         *
         * The list is read once and kept: the voices don't change while the app runs, and reading them again
         * for every line would start a process per line.
         *
         * Locale-aware: the voice's locale may appear in any column of `say -v '?'` output, so we look for an
         * `en_XX` tag anywhere on the line rather than assuming a fixed column.
         */
        fun installedVoices(fallback: List<String> = listOf(DEFAULT_VOICE)): List<String> = readVoices(fallback)

        /** The voices actually installed, read once and kept (see [installedVoices]). */
        val installed: List<String> by lazy { readVoices(listOf(DEFAULT_VOICE)) }

        /** Reads the voices from `say` itself ([fallback] when it can't be read). */
        private fun readVoices(fallback: List<String>): List<String> = runCatching {
            val process = ProcessBuilder("/usr/bin/say", "-v", "?").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)
            output.lineSequence()
                .filter { ENGLISH_LOCALE.containsMatchIn(it) }
                .mapNotNull { line -> VOICE_NAME.find(line)?.groupValues[1] }
                .distinct()
                .toList()
                .ifEmpty { fallback }
        }.getOrDefault(fallback)
    }
}

/**
 * The line spoken for one decision: what it did, how sure it was, why (its reasoning), and any problem it hit —
 * short enough to be said before the next decision arrives.
 */
object NarrationScript {

    /** The spoken line of a decision as the player records it. */
    fun of(decision: Decision): String = of(
        number = decision.number,
        confidence = decision.confidence,
        actions = decision.actions,
        thought = decision.thought,
        problem = decision.problem,
        stop = decision.stop,
    )

    /** The same from plain fields (tests build one without a game state). */
    fun of(
        number: Int,
        confidence: Double?,
        actions: List<String>,
        thought: String?,
        problem: String? = null,
        stop: String? = null,
    ): String = buildString {
        append("Decision $number.")
        confidence?.let { append(" ${(it * 100).roundToInt()} percent confidence.") }
        val first = actions.firstOrNull()?.let(::speakable) ?: "waited"
        append(" ").append(first)
        if (actions.size > 1) append(", then ${actions.size - 1} more step${if (actions.size == 2) "" else "s"}")
        append(".")
        thought?.let { append(" ").append(forSpeech(it)) }
        problem?.let { append(" But it didn't work: ").append(forSpeech(it, MAX_TAIL)).append(".") }
        stop?.let { append(" Then it stopped: ").append(forSpeech(it, MAX_TAIL)).append(".") }
    }

    /**
     * A piece of text as spoken: markdown (code fences, bold, backticks) removed, whitespace collapsed, cut to
     * [maxChars] at a word boundary — one line must fit between two decisions.
     */
    fun forSpeech(text: String, maxChars: Int = MAX_CHARS): String {
        val clean = text.replace(Regex("""```[A-Za-z]*"""), " ")
            .replace(Regex("""[*_#`]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex(""" ([.,;:!?)\]])"""), "$1") // markdown stripped between a word and its punctuation
            .trim()
        if (clean.length <= maxChars) return clean
        val head = clean.take(maxChars)
        return head.substringBeforeLast(' ', head).trimEnd(',', ';', ':', '-')
    }

    /** An action key as spoken: `step(north,3)` → "step north 3", `go_to(Cianwood)` → "go to Cianwood". */
    private fun speakable(key: String): String =
        key.replace(Regex("""[()_,]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /** Longest spoken reasoning line (a decision comes every few seconds). */
    private const val MAX_CHARS = 160

    /** Longest tail (a problem / stop reason), shorter than the reasoning itself. */
    private const val MAX_TAIL = 70
}
