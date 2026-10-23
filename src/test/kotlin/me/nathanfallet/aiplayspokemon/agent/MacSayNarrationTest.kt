package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import me.nathanfallet.aiplayspokemon.emulator.audio.SpeechAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real macOS voice: a decision's line is really said by `say`, and the audio it produced is written to a
 * file (one per line, numbered) so the run can be checked afterwards.
 *
 * Only on macOS with `say` installed: the test skips itself elsewhere.
 */
class MacSayNarrationTest {

    @Test
    fun theVoiceReallySpeaksTheLineAndWritesItsAudio() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        val directory = Files.createTempDirectory("narration")
        val file = directory.resolve("line.aiff")
        val speaker = DecisionNarrator.MacSay { file }
        val spoken = mutableListOf<String>()
        val narrator = DecisionNarrator(
            voice = { "Whisper" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            speaker = speaker,
            onSpoken = { line, _ -> spoken += line },
        )
        try {
            narrator.narrateMilestone("Left the house")
            val until = System.currentTimeMillis() + 10_000
            while (spoken.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(listOf("Milestone: Left the house"), spoken, "the line was not really said")
            assertTrue(Files.exists(file), "no audio file was written")
            assertTrue(Files.size(file) > 0, "the audio file is empty")
            println("spoken: ${spoken.single()} -> ${Files.size(file)} bytes")
        } finally {
            narrator.close()
        }
    }

    /**
     * The stream's path (no [outputFile]: how the app speaks in production): `say` only renders the line to a
     * file, and the app itself plays that file — the voice goes out with the app's own audio, what a stream
     * capturing the app hears, never through `say`'s own playback, which comes from another process.
     */
    @Test
    fun theRenderedLineIsPlayedThroughTheAppAndCleanedUp() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        val audio = object : SpeechAudio {
            val played = mutableListOf<Path>()
            val bytes = mutableListOf<Long>()
            override fun play(file: Path) {
                played.add(file)
                // What the app would play must really be audio Java Sound can read, not just some bytes.
                AudioSystem.getAudioInputStream(file.toFile()).use { input ->
                    assertTrue(input.format.frameRate > 0f, "the rendered line is not readable audio")
                }
                bytes.add(Files.size(file))
            }

            override fun cut() {}
        }
        val speaker = DecisionNarrator.MacSay(audio)
        speaker.speak("The app speaks on the stream", "Whisper", 185)
        assertEquals(1, audio.played.size, "the line was not played through the app's audio")
        assertTrue(audio.bytes.single() > 0, "the rendered line is empty")
        assertTrue(!Files.exists(audio.played.single()), "the temporary line file was not cleaned up")
    }

    @Test
    fun anUnknownVoiceIsReportedInsteadOfSilentlySayingNothing() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        val reported = mutableListOf<String>()
        val narrator = DecisionNarrator(
            voice = { "NoSuchVoiceHere" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            report = { line -> reported += line },
        )
        try {
            narrator.narrateMilestone("Never said")
            val until = System.currentTimeMillis() + 10_000
            while (reported.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertTrue(reported.isNotEmpty(), "an unknown voice was not reported")
            assertTrue("narration failed:" in reported.first(), reported.first())
        } finally {
            narrator.close()
        }
    }

    @Test
    fun eachLineGetsItsOwnNumberedFile() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        val directory = Files.createTempDirectory("narration")
        val files = mutableListOf<Path>()
        val speaker = DecisionNarrator.MacSay { number ->
            val file = directory.resolve("line-$number.aiff")
            files.add(file)
            file
        }
        val narrator = DecisionNarrator(
            voice = { "Whisper" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            speaker = speaker,
        )
        try {
            narrator.narrateMilestone("First")
            narrator.narrateMilestone("Second")
            val until = System.currentTimeMillis() + 20_000
            // The file is written by `say` itself, at the end of the line: waiting for it to exist, not for
            // the path to be handed out.
            while (files.size < 2 || files.any { !Files.exists(it) }) {
                assertTrue(System.currentTimeMillis() < until, "the audio files were never written: $files")
                Thread.sleep(20)
            }
            assertEquals(2, files.size, "each line should get its own file")
            files.forEach { assertTrue(Files.size(it) > 0, "$it is empty") }
        } finally {
            narrator.close()
        }
    }
}
