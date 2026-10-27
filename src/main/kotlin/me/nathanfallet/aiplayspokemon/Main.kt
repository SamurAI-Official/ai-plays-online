package me.nathanfallet.aiplayspokemon

import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import kotlin.io.path.readBytes
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import me.nathanfallet.aiplayspokemon.config.AppConfig
import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.NetplaySettings
import dev.kotlinds.pokemonclient.PokemonGames
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.OnlineWalkthroughSource
import me.nathanfallet.aiplayspokemon.games.WalkthroughCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.ui.App
import me.nathanfallet.aiplayspokemon.ui.AppController
import me.nathanfallet.aiplayspokemon.ui.GamePickerScreen
import kotlinx.coroutines.CoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import java.nio.file.Path

/**
 * Entry point. Wires the layers together:
 *
 *   emulator (libretro core)  →  game (reads RAM into the typed GameState)
 *        ↑ buttons                     ↓
 *   agent (PokemonPlayer)  ←  decision model (the AI: Jev, or an LLM through Koog)
 *
 * and shows everything in a Compose window.
 */
fun main(args: Array<String>) {
    val config = AppConfig.load(args)
    // Online play is resolved before the UI starts: a missing peer or a bad port must fail loudly,
    // never silently fall back to an offline game.
    val netplay = config.netplay

    application {
        val scope = rememberCoroutineScope()
        // The open game, created once per choice. It is deliberately *not* built inside a remember() calculation:
        // Compose may re-run those, and a second ConsoleHost means a second pair of emulator cores in this process
        // (which crashed the core natively when it happened).
        var session by remember { mutableStateOf<OpenGame?>(null) }
        var romPath by remember { mutableStateOf<Path?>(null) }
        val windowState = rememberWindowState(size = DpSize(1068.dp, 840.dp))

        fun switchTo(path: Path?) {
            println("ai-plays-online: ${path?.fileName ?: "no game (picker)"}")
            session?.close()
            path?.let(config::saveRomPath)
            romPath = path
            session = path?.let { OpenGame.open(config, it, scope, netplay) }
        }

        // A ROM on the command line (or in the ROM environment variable) is the only one opened without a click.
        LaunchedEffect(Unit) { config.romPath?.let(::switchTo) }

        Window(
            onCloseRequest = {
                session?.close() // unloads the game, which writes the in-game save to disk
                exitApplication()
            },
            title = "AI plays Online",
            state = windowState,
            onKeyEvent = { event -> session?.controller?.onKeyEvent(event) ?: false },
        ) {
            val open = session
            val rom = romPath
            if (open == null || rom == null) {
                GamePickerScreen(config, onPick = ::switchTo, onBrowse = { chooseRom() })
            } else {
                // Focus loss while a key is held: its release never arrives (it goes to the other app), which would
                // keep the button pressed forever — and a held key keeps every agent action refused as "human driving".
                DisposableEffect(window) {
                    val listener = object : WindowAdapter() {
                        override fun windowLostFocus(event: WindowEvent) = open.controller.onFocusLost()
                    }
                    window.addWindowFocusListener(listener)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                App(
                    controller = open.controller,
                    window = windowState,
                    currentRom = rom,
                    games = remember(rom) { GameCatalog.scan(config.romsDirectory, config.dataDirectory) },
                    onSelectGame = { path -> switchTo(path) },
                    onBrowse = { chooseRom()?.let(::switchTo) },
                    onChangeGame = { switchTo(null) },
                )
            }
        }
    }
}

/**
 * One open game: the console, the profile of the ROM loaded in it, and the controller driving both.
 * [close] shuts everything down in order (the player first, so it stops acting on a closed console),
 * which is also how the game picker switches to another ROM.
 */
private class OpenGame(val controller: AppController, private val emulator: ConsoleHost) : AutoCloseable {

    override fun close() {
        controller.player.value?.pause()
        controller.mcp.value?.stop()
        // What the reader learner knows so far is written now: the next session resumes from it instead of starting
        // over (the console below is what closes the core, so this comes first).
        controller.saveLearningOnClose()
        // And what the check of the reader saw: the reader then says how well it is holding up (docs/readers.md).
        controller.saveVerificationOnClose()
        emulator.close() // unloads the game, which writes the in-game save to disk
    }

    companion object {
        fun open(
            config: AppConfig,
            rom: Path,
            scope: CoroutineScope,
            netplay: NetplaySettings? = null,
        ): OpenGame {
            val bytes = rom.readBytes()
            // One hash for the whole open: it pins the netplay handshake and what the reader learner writes down.
            val romHash = sha256OfRom(bytes)
            // Online play runs both peers' inputs into one console, so it needs a core both can run and,
            // in rollback mode, a deterministic one: prefer the fixed-RTC build (see docs/games.md).
            val spec = if (netplay != null) {
                LibretroCoreSpec.forRomRollback(rom.toKotlinxPath(), config.emulatorCore)
            } else {
                LibretroCoreSpec.forRom(rom.toKotlinxPath(), config.emulatorCore)
            }
            if (netplay != null && netplay.mode == Mode.ROLLBACK && !spec.rollbackSafe) {
                error(
                    "${spec.name} is not rollback-safe (its RTC follows the host clock): " +
                        "use AI_PLAYS_ONLINE_NETPLAY_MODE=lockstep, or a fixed-RTC core build",
                )
            }
            val emulator = ConsoleHost(
                spec,
                rom,
                config.dataDirectory,
                netplay = netplay,
                romSha256 = netplay?.let { romHash },
            )
            // What the app knows about this ROM: its own profile, or the generic one (screen only). A game with a
            // calibrated reader (`readers/<id>.json`) is a typed game: the app can read its state (see docs/readers.md).
            val profile = ReaderCatalog.apply(GameCatalog.detect(bytes, config.dataDirectory), config.dataDirectory)
            // Screen viewer + reader are already active for every ROM. Once the game title is verified, try to
            // generate (and cache) a walkthrough from the online source so screen-only games get guidance too.
            if (GameCatalog.verifyTitle(profile)) {
                val source = config.walkthroughUrl?.takeIf { it.isNotBlank() }?.let { OnlineWalkthroughSource(URI.create(it)) }
                    ?: OnlineWalkthroughSource()
                WalkthroughCatalog.generate(profile, config.dataDirectory, source)
            }
            val game = PokemonGames.detect(bytes)
            println("ai-plays-online: ${profile.title} (${profile.agent}) on ${emulator.info.name}")
            if (config.startMuted) emulator.setMuted(true)
            config.startState?.let { slot -> runBlocking { check(emulator.loadState(slot)) { "No save state in slot $slot" } } }
            emulator.start()
            return OpenGame(AppController(emulator, game, config, scope, profile, romHash), emulator)
        }
    }
}

/** SHA-256 of the ROM, hex: the netplay handshake compares it, so both peers must run the same file. */
internal fun sha256OfRom(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }


/** Asks for a ROM with the native file picker (the choice is remembered in the config file). */
private fun chooseRom(): Path? {
    val dialog = FileDialog(null as Frame?, "Choose a Nintendo DS ROM (.nds)", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".nds", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Path.of(dialog.directory, file)
}
