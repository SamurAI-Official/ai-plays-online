package me.nathanfallet.aiplayspokemon

import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import kotlin.io.path.readBytes
import androidx.compose.runtime.DisposableEffect
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
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import dev.kotlinds.pokemonclient.PokemonGames
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.ui.App
import me.nathanfallet.aiplayspokemon.ui.AppController
import me.nathanfallet.aiplayspokemon.ui.GamePickerScreen
import kotlinx.coroutines.CoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
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

    application {
        val scope = rememberCoroutineScope()
        // No game until one is picked: the app opens on the game picker (a ROM on the command line or in the ROM
        // environment variable is the only one it starts by itself).
        var romPath by remember { mutableStateOf(config.romPath) }
        // A core instance belongs to one ROM: picking another game closes this one and opens that one.
        val session = remember(romPath) { romPath?.let { OpenGame.open(config, it, scope) } }
        val windowState = rememberWindowState(size = DpSize(1068.dp, 840.dp))

        fun switchTo(path: Path?) {
            session?.close()
            path?.let(config::saveRomPath)
            romPath = path
        }

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
            if (open == null) {
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
                    currentRom = romPath ?: return@Window,
                    games = GameCatalog.scan(config.romsDirectory, config.dataDirectory),
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
        emulator.close() // unloads the game, which writes the in-game save to disk
    }

    companion object {
        fun open(config: AppConfig, rom: Path, scope: CoroutineScope): OpenGame {
            val emulator = ConsoleHost(
                LibretroCoreSpec.forRom(rom.toKotlinxPath(), config.emulatorCore),
                rom,
                config.dataDirectory,
            )
            val bytes = rom.readBytes()
            // What the app knows about this ROM: its own profile, or the generic one (screen only).
            val profile = GameCatalog.detect(bytes, config.dataDirectory)
            val game = PokemonGames.detect(bytes)
            if (config.startMuted) emulator.setMuted(true)
            config.startState?.let { slot -> runBlocking { check(emulator.loadState(slot)) { "No save state in slot $slot" } } }
            emulator.start()
            return OpenGame(AppController(emulator, game, config, scope, profile), emulator)
        }
    }
}

/** Asks for a ROM with the native file picker (the choice is remembered in the config file). */
private fun chooseRom(): Path? {
    val dialog = FileDialog(null as Frame?, "Choose a Nintendo DS ROM (.nds)", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".nds", ignoreCase = true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return Path.of(dialog.directory, file)
}
