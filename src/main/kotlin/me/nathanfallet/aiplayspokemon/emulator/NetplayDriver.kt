package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.libretrorollback.Endpoint
import dev.kotlinds.libretrorollback.RollbackEvent
import dev.kotlinds.libretrorollback.RollbackSession
import dev.kotlinds.libretrorollback.UdpTransport
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.rollback.ConsoleRollbackCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Drives one console through a [RollbackSession]: the online counterpart of the free run in
 * [ConsoleHost]. Created and used on the console thread only (libretro cores are not thread-safe),
 * except for [status], which the UI reads from anywhere.
 *
 * [tick] is one frame: the local input goes into the session, the peer's (confirmed or predicted)
 * comes out of it, and the console runs once. In lockstep it waits for the peer's input, so it may
 * block for a few milliseconds — never for a network round trip that has not started.
 *
 * The session never throws at us: a handshake mismatch, a desync or a disconnect arrives as an
 * event, is logged, and stops the session ([NetplayStatus.stopped]).
 */
class NetplayDriver(
    console: LibretroConsole,
    val settings: NetplaySettings,
    romSha256: String,
    private val log: (String) -> Unit,
) : AutoCloseable {

    private val transport = UdpTransport(settings.bindPort)
    private val core = ConsoleRollbackCore(console, romSha256)
    private val session = RollbackSession(core, transport, settings.toRollbackConfig()) { event -> onEvent(event) }

    private val _status = MutableStateFlow(
        NetplayStatus(
            role = settings.role,
            peer = settings.peer,
            bindPort = settings.bindPort,
            mode = settings.mode,
        ),
    )

    /** Live state of the session: role, peer, synchronization, counters, last event. */
    val status: StateFlow<NetplayStatus> = _status.asStateFlow()

    /** Reused buffer for the local player's 16-byte blob (one per frame, no allocation). */
    private val local = ByteArray(ConsoleRollbackCore.INPUT_SIZE)

    private var closed = false

    init {
        session.start(settings.localPlayer, Endpoint(settings.peerHost, settings.peerPort))
        log(
            "netplay: ${settings.role.name.lowercase()} on port ${settings.bindPort}, " +
                "peer ${settings.peer}, ${settings.mode.name.lowercase()}, " +
                "state ${core.stateSize} bytes",
        )
    }

    /**
     * Runs one frame with [input] for the local player. Returns false when nothing ran (still
     * handshaking, stopped, or the peer is too far ahead): the caller paces itself instead.
     */
    fun tick(input: InputFrame, render: Boolean = true): Boolean {
        if (closed) return false
        ConsoleRollbackCore.encode(input, into = local)
        val ran = session.tick(local, render)
        publish()
        return ran
    }

    /** Pumps the transport without running a frame (the game is paused: the session must stay alive). */
    fun idle() {
        if (closed) return
        session.idle(1)
        publish()
    }

    /** True while frames can run: the handshake finished and nothing has stopped the session. */
    val isSynchronized: Boolean get() = session.isSynchronized

    private fun publish() {
        val stats = session.stats
        _status.update {
            it.copy(
                synchronized = session.isSynchronized,
                frame = stats.frame,
                remoteFrame = stats.remoteFrame,
                rollbacks = stats.rollbacks,
                maxRollbackFrames = stats.maxRollbackFrames,
                predictedFrames = stats.predictedFrames,
                stateMiB = stats.stateBytes / (1024.0 * 1024.0),
            )
        }
    }

    private fun onEvent(event: RollbackEvent) {
        val text = when (event) {
            RollbackEvent.Synchronized -> "synchronized"
            is RollbackEvent.HandshakeFailed -> "handshake failed: ${event.reason}"
            is RollbackEvent.Desync -> "desync at frame ${event.frame}"
            is RollbackEvent.Disconnected -> "peer disconnected (${event.reason})"
            is RollbackEvent.PredictionThreshold -> "the peer is behind (frame ${event.frame})"
            is RollbackEvent.LinkRollbackRefused -> "linked rollback refused at frame ${event.frame}"
            is RollbackEvent.Timesync -> return
            // A rollback is normal: logging every one of them would drown the log.
            is RollbackEvent.Rollback -> return
        }
        log("netplay: $text")
        val fatal = event is RollbackEvent.Desync ||
            event is RollbackEvent.Disconnected ||
            event is RollbackEvent.HandshakeFailed ||
            event is RollbackEvent.LinkRollbackRefused
        _status.update { it.copy(lastEvent = text, stopped = if (fatal) text else it.stopped) }
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
        transport.close()
    }
}
