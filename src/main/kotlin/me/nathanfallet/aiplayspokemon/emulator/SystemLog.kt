package me.nathanfallet.aiplayspokemon.emulator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The recent input and system changes of the console (buttons held, touch down/up, who drives it, pauses, save
 * states), newest first and bounded, as lines for the UI's debug readout: it is how "why is the agent refused /
 * stuck waiting" questions get answered (a key held since a focus loss, a touch that never released, ...).
 *
 * Thread-safe: entries are added from the UI thread (key and touch events), the console thread (driver changes) and
 * the agents' coroutines (pauses).
 */
class SystemLog(
    /** How many entries are kept (the UI shows the newest ones). */
    private val limit: Int = LIMIT,
    /** The clock of the timestamps (tests control it). */
    private val time: () -> String = { LocalTime.now().format(TIMESTAMP) },
) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())

    /** The entries, newest first: `HH:mm:ss.SSS  what happened`. */
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** Remembers one change ([what]), dropping the oldest entries beyond [limit]. */
    fun add(what: String) {
        _entries.update { (listOf("${time()}  $what") + it).take(limit) }
    }

    companion object {
        const val LIMIT = 120
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}