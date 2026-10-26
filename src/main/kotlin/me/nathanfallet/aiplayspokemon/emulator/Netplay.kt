package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.libretrorollback.Endpoint
import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.libretrorollback.RollbackConfig
import dev.kotlinds.libretrorollback.RollbackEvent
import dev.kotlinds.libretrorollback.RollbackSession
import dev.kotlinds.libretrorollback.SnapshotStrategy
import dev.kotlinds.libretrorollback.UdpTransport
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.rollback.ConsoleRollbackCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which side of an online session this app is: the host is player 1, the joiner player 2. */
enum class NetplayRole { HOST, JOIN }

/**
 * How to play online: which side this app is, where to reach the peer, and the engine settings.
 *
 * Both peers must use the same [mode], [inputDelay], [maxPredictionFrames] and snapshot strategy:
 * the handshake compares them, so a mismatch is refused instead of silently desyncing.
 *
 * This is the **two-controller** topology: both players' inputs go into one emulated console, which
 * is what the engine's `host`/`join` demo does. The DS local-wireless topology (two consoles, one per
 * peer) needs the link ABI of the patched melonDS core and the `libretro-rollback-libretro` adapter,
 * which is not published for the app yet; see `engine/libretro-rollback/docs/two-console.md`.
 */
data class NetplaySettings(
    val role: NetplayRole,
    /** Local UDP port. The default is 7000 for the host and 7001 for the joiner, as in the demo. */
    val bindPort: Int = if (role == NetplayRole.HOST) 7000 else 7001,
    val peerHost: String,
    val peerPort: Int,
    val mode: Mode = Mode.LOCKSTEP,
    val inputDelay: Int = 2,
    val maxPredictionFrames: Int = 8,
    val snapshotStrategy: SnapshotStrategy = SnapshotStrategy.DELTA,
    val deltaKeyframeInterval: Int = 30,
) {
    /** Player index in the session's input frame: the host is 0, the joiner 1. */
    val localPlayer: Int get() = if (role == NetplayRole.HOST) 0 else 1

    val peer: String get() = "$peerHost:$peerPort"

    fun toRollbackConfig(): RollbackConfig = RollbackConfig(
        inputSize = ConsoleRollbackCore.INPUT_SIZE,
        inputDelay = inputDelay,
        maxPredictionFrames = maxPredictionFrames,
        mode = mode,
        snapshotStrategy = snapshotStrategy,
        deltaKeyframeInterval = deltaKeyframeInterval,
        // Linked play is not wired here (see the class comment): a linked session must not compare
        // the two consoles' checksums, which the engine handles with linkChecksums when it is used.
        linkChecksums = false,
    )
}

/** What the panel shows about the online session. */
data class NetplayStatus(
    val role: NetplayRole,
    val peer: String,
    val bindPort: Int,
    val mode: Mode,
    val synchronized: Boolean = false,
    val frame: Int = 0,
    val remoteFrame: Int = -1,
    val rollbacks: Int = 0,
    val maxRollbackFrames: Int = 0,
    val predictedFrames: Long = 0,
    val stateMiB: Double = 0.0,
    val lastEvent: String? = null,
    /** Set when the session ended by itself (desync, disconnect, refused handshake). */
    val stopped: String? = null,
) {
    /** A one-line summary for the panel. */
    val summary: String
        get() = buildString {
            append(role.name.lowercase())
            append(" · ").append(peer)
            append(" · ").append(mode.name.lowercase())
            append(" · ").append(if (synchronized) "synchronized" else "connecting")
            if (stopped != null) append(" · stopped: ").append(stopped)
        }
}
