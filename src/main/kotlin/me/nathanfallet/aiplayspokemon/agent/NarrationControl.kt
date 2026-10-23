package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The app's narration control: the one dropdown (off, or a voice), the narrator saying the decisions, and the
 * lines it really said.
 *
 * The voice is read per line, so changing it applies to the next line without restarting the narrator. Turning
 * narration off closes the narrator: the line being said is cut mid-sentence and no further line is said.
 */
class NarrationControl(
    /** The voice to use (read per line: changing it applies to the next line, no restart). */
    private val voice: () -> String,
    /** Words per minute. */
    private val rate: () -> Int,
    private val scope: CoroutineScope,
    /** The voice that speaks the lines (injectable: tests never touch a real voice). */
    private val speaker: () -> DecisionNarrator.Speaker = { DecisionNarrator.MacSay() },
    /** Where the narrator's doings are logged (the UI's system log). */
    private val log: (String) -> Unit = {},
    /** How many spoken lines are kept (the newest ones). */
    private val limit: Int = LIMIT,
    /** Whether narration is already on when the app starts (the saved setting). */
    initialEnabled: Boolean = false,
) {

    private val _spoken = MutableStateFlow<List<String>>(emptyList())

    /**
     * The lines the voice really said, newest first, with the voice that said them: the proof that a decision
     * was read out.
     */
    val spoken: StateFlow<List<String>> = _spoken.asStateFlow()

    @Volatile private var enabled = initialEnabled

    /** Whether the decisions are spoken right now. */
    val isEnabled: Boolean get() = enabled

    @Volatile private var narratorInstance: DecisionNarrator? = null

    /**
     * The narrator while narration is on, null when off: the player asks for it per decision, so turning
     * narration on and off needs no new player.
     */
    fun narrator(): DecisionNarrator? {
        if (!enabled) return null
        narratorInstance?.let { return it }
        return synchronized(this) {
            narratorInstance ?: DecisionNarrator(
                voice = voice,
                rate = rate,
                scope = scope,
                speaker = speaker(),
                report = { line -> log(line) }, // a narrator that can't speak shows here
                onSpoken = { line, spokenVoice -> record(line, spokenVoice) },
            ).also {
                narratorInstance = it
                log("narration on (${voice()} @ ${rate()} wpm, local macOS voice)")
            }
        }
    }

    /**
     * Picks the voice: null turns narration off, a voice turns it on (the voice itself is read per line, so it
     * applies to the next line).
     */
    fun setVoice(voice: String?) {
        if (voice == null) {
            disable()
            return
        }
        enabled = true
        log("narration voice: $voice")
    }

    /**
     * Turns narration on or off, following the app's setting: off closes the narrator, on lets the next
     * decision be said.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) this.enabled = true else disable()
    }

    /** Turns narration off: no further line, and the one being said is cut mid-sentence. */
    fun disable() {
        if (!enabled && narratorInstance == null) return
        enabled = false
        if (narratorInstance != null) log("narration off")
        narratorInstance?.close()
        narratorInstance = null
    }

    /** Records a line the voice really said (the readout, the log, stdout). */
    private fun record(line: String, spokenVoice: String) {
        val shown = "[$spokenVoice] $line"
        _spoken.update { (listOf(shown) + it).take(limit) }
        log("said: $line")
        println("narration: $shown")
    }

    companion object {
        /** How many spoken lines the UI keeps (the newest ones). */
        const val LIMIT = 30
    }
}
