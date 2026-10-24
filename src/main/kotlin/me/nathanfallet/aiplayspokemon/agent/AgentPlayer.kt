package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.flow.StateFlow
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.games.WalkthroughState

/**
 * What the app and the panel need from whoever is playing: the autonomous loop.
 *
 * There are two of them, and they differ only in what they can see: [PokemonPlayer] plays a game whose reader
 * knows its structures (pokemon-client), [ReaderPlayer] plays one whose reader was calibrated
 * (`readers/<id>.json`, see `docs/readers.md`) — same loop, same options-and-decisions protocol, same panel.
 */
interface AgentPlayer {

    /** The decision model in use (its name is shown on the play button). */
    val model: DecisionModel

    /** Everything the UI shows about the run. */
    val state: StateFlow<PlayerState>

    /** The walkthrough in play, shared with the panel. */
    val walkthrough: WalkthroughState

    /** What the player is trying to achieve; editable at any time from the panel. */
    var objective: String

    /** Starts (or restarts) playing. */
    fun play()

    /** Stops playing; the human keeps the console. */
    fun pause()

    /** [play] when stopped, [pause] when playing. */
    fun toggle()
}
