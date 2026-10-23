package me.nathanfallet.aiplayspokemon.emulator.audio

import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * One spoken line of the narration, played through the app's own audio output.
 *
 * The macOS `say` command only *renders* the line to a file; playing it is done here, never by `say` itself:
 * what captures the app's sound for a stream hears the app's own output, so a voice coming from another process
 * would be missing from it. This line and the game's ([AudioPlayer]) are separate device lines the device mixes,
 * so narration and game sound can overlap without either write waiting for the other.
 *
 * [play] blocks until the line has been said (the narrator's queue counts on it); [cut] stops it mid-line.
 */
interface SpeechAudio {
    /** Plays [file] (one rendered line) to its end, blocking until it has been heard. */
    fun play(file: Path)

    /** Stops the line being played, if any, mid-line. */
    fun cut()

    companion object {
        /** Through the app's own audio device — the default: what a stream capturing the app's sound hears. */
        fun app(): SpeechAudio = AppSpeechAudio()
    }
}

/** [SpeechAudio] over a Java Sound line: reads the rendered file and writes it out chunk by chunk. */
private class AppSpeechAudio : SpeechAudio {

    @Volatile
    private var cut = false

    override fun play(file: Path) {
        cut = false
        AudioSystem.getAudioInputStream(file.toFile()).use { input ->
            // The file's own format when the device takes it (say usually renders 22 kHz mono), a standard
            // conversion otherwise: either way the line opens with a format it really supports.
            val stream = if (AudioSystem.isConversionSupported(PLAY_FORMAT, input.format)) {
                AudioSystem.getAudioInputStream(PLAY_FORMAT, input)
            } else {
                input
            }
            val format = stream.format
            val line = AudioSystem.getSourceDataLine(format).apply {
                open(format, bufferBytes(format))
                start()
            }
            try {
                val chunk = ByteArray(CHUNK_BYTES)
                while (!cut) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    line.write(chunk, 0, read)
                }
                if (cut) line.stop() else line.drain() // heard to the end, or silenced at once on cut
            } finally {
                line.close()
            }
        }
    }

    override fun cut() {
        cut = true
    }

    private companion object {
        /** What a device that won't take the file's own format gets instead (44.1 kHz, 16-bit, stereo, little-endian). */
        val PLAY_FORMAT = AudioFormat(44100f, 16, 2, true, false)

        /** One write: small enough for `cut` to take effect at once, large enough not to stutter. */
        const val CHUNK_BYTES = 4096

        /** ~100 ms of the format's audio in whole frames (what the line buffers). */
        fun bufferBytes(format: AudioFormat): Int {
            val bytes = (format.frameRate * format.frameSize / 10).toInt()
            return (bytes / format.frameSize * format.frameSize).coerceAtLeast(format.frameSize)
        }
    }
}
