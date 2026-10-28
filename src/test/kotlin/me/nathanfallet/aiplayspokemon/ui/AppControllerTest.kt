package me.nathanfallet.aiplayspokemon.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.decision.DecisionBackend
import me.nathanfallet.aiplayspokemon.decision.llm.LlmProvider
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.gatedPath
import me.nathanfallet.aiplayspokemon.sha256OfRom
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wiring layer between the window and the console: [AppController] on a **real** console, built the way `Main`
 * builds it — a real ROM, the same profile and game detection — with no GUI. What the window hands it (keys, settings,
 * model choices) must reach the console, the config file and the panel; what it cannot do (a state while online, a key
 * nobody mapped) must be refused rather than silently half-done.
 *
 * Gated like the other ROM tests, and **run it alone** (a libretro core can only be started once per process):
 *
 * ```
 * APP_TEST_ROM=/path/game.nds APP_TEST_CORE=/path/melonds_libretro_rollback.dylib
 * ```
 */
class AppControllerTest {

    /** An app on a throwaway data directory, closed in one go. */
    private class App(
        val controller: AppController,
        val emulator: ConsoleHost,
        val rom: Path,
        val data: Path,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
            emulator.close()
        }
    }

    private fun openApp(): App {
        val rom = gatedPath("APP_TEST_ROM", "a ROM to open the app on")
        val core = gatedPath("APP_TEST_CORE", "a libretro core to run it with")
        val bytes = rom.readBytes()
        val data = Files.createTempDirectory("ai-plays-app")
        val cores = Files.createDirectories(data.resolve("cores"))
        Files.copy(core, cores.resolve("melonds_libretro_rollback.dylib"), StandardCopyOption.REPLACE_EXISTING)
        // The app's own configuration, on that directory. Jev rather than the default (MCP): the default would start an
        // MCP server on a real port, which a test has no business doing (and two tests in one JVM would clash).
        val config = AppConfig(data, Properties(), null).also { it.backend = DecisionBackend.JEV }
        val profile = ReaderCatalog.apply(GameCatalog.detect(bytes, data), data)
        val game = PokemonGames.detect(bytes)
        val emulator = ConsoleHost(LibretroCoreSpec.MELONDS_ROLLBACK, rom, data)
        emulator.start()
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = AppController(emulator, game, config, scope, profile, sha256OfRom(bytes))
        // Nothing plays by itself: the window's play button does that, and a test wants a console nobody drives.
        controller.player.value?.pause()
        return App(controller, emulator, rom, data, scope)
    }

    /**
     * A key event, as the window hands one over. Compose builds its desktop events from a wrapper it keeps internal to
     * the UI modules, so the factory it marks that way is the only way to give the controller a key without a window.
     */
    @OptIn(InternalComposeUiApi::class)
    private fun key(key: Key, down: Boolean = true, shift: Boolean = false) = KeyEvent(
        key = key,
        type = if (down) KeyEventType.KeyDown else KeyEventType.KeyUp,
        isShiftPressed = shift,
    )

    /** Waits until [condition] holds, or fails with [what] after 20 s. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError(what)
    }

    /** The configuration as a fresh launch reads it: from the file the app writes (a new [AppConfig] with empty
     * properties would only show the defaults, not what was saved). */
    private fun reloaded(data: Path): AppConfig {
        val properties = Properties()
        data.resolve("config.properties").takeIf { Files.exists(it) }?.inputStream()?.use(properties::load)
        return AppConfig(data, properties, null)
    }

    @Test
    fun keyboardPressesReachTheConsoleAndLosingFocusReleasesThem() {
        openApp().use { app ->
            // The mapping is the app's own (X = A, arrows = the d-pad): the controller must pass it through, pressed and
            // released, and tell the window it used the key.
            assertTrue(app.controller.onKeyEvent(key(Key.X)), "the A key was not used")
            assertTrue(Button.A in app.emulator.heldButtons.value, "A is not held on the console")
            assertTrue(app.controller.onKeyEvent(key(Key.X, down = false)), "the A key release was not used")
            assertTrue(app.emulator.heldButtons.value.isEmpty(), "A stayed held after its release")

            assertTrue(app.controller.onKeyEvent(key(Key.DirectionUp)), "the d-pad key was not used")
            assertEquals(setOf(Button.UP), app.emulator.heldButtons.value, "the d-pad key did not reach the console")

            // A key nobody mapped is not ours: the window keeps it (its own shortcuts must not be swallowed).
            assertFalse(app.controller.onKeyEvent(key(Key.K)), "an unmapped key was used")
            assertEquals(setOf(Button.UP), app.emulator.heldButtons.value, "an unmapped key changed the buttons")

            // Losing the focus never delivers the releases: without this the game would walk on its own forever.
            app.controller.onFocusLost()
            assertTrue(app.emulator.heldButtons.value.isEmpty(), "the held buttons survived a focus loss")
            assertTrue(
                app.emulator.systemLog.entries.value.any { it.contains("window focus lost") },
                "the focus loss was not logged: ${app.emulator.systemLog.entries.value}",
            )
        }
    }

    @Test
    fun thePauseKeyStopsTheGameAndPutsItBack() {
        openApp().use { app ->
            waitUntil("the console never ran a frame") { app.emulator.status.value.frameCount > 0 }
            assertTrue(app.emulator.status.value.running, "the console did not start running")

            assertTrue(app.controller.onKeyEvent(key(Key.P)), "the pause key was not used")
            assertFalse(app.emulator.status.value.running, "the pause key did not stop the game")

            app.controller.togglePause()
            assertTrue(app.emulator.status.value.running, "the game did not resume")
        }
    }

    @Test
    fun theFastForwardAndMuteKeysReachTheConsole() {
        openApp().use { app ->
            assertTrue(app.controller.onKeyEvent(key(Key.F)), "the fast-forward key was not used")
            assertTrue(app.emulator.status.value.fastForward, "the fast-forward key did not reach the console")
            assertTrue(app.controller.onKeyEvent(key(Key.F)), "the fast-forward key was not used twice")
            assertFalse(app.emulator.status.value.fastForward, "fast forward did not turn off")

            assertTrue(app.controller.onKeyEvent(key(Key.M)), "the mute key was not used")
            assertTrue(app.emulator.status.value.muted, "the mute key did not reach the console")
        }
    }

    @Test
    fun theStateSlotKeysSaveAndLoad() {
        openApp().use { app ->
            // The state file the console writes for slot 1: `<data>/states/<rom file name>.state1`.
            val state = app.data.resolve("states").resolve("${app.rom.fileName}.state1")
            waitUntil("the console never ran a frame") { app.emulator.status.value.frameCount > 0 }

            // Shift+F1 saves into slot 1 (the same file the window's F-keys use), F1 loads it back. The console does
            // both on its own thread, so the test waits for the outcome rather than for the call to return.
            assertTrue(app.controller.onKeyEvent(key(Key.F1, shift = true)), "the save key was not used")
            waitUntil("shift+F1 did not save a state") {
                app.emulator.systemLog.entries.value.any { it.contains("state 1 saved") }
            }
            assertTrue(Files.exists(state), "the state was logged but not written to $state")

            assertTrue(app.controller.onKeyEvent(key(Key.F1)), "the load key was not used")
            waitUntil("F1 did not load the state") {
                app.emulator.systemLog.entries.value.any { it.contains("state 1 loaded") }
            }
        }
    }

    @Test
    fun thePanelShowsTheGame() {
        openApp().use { app ->
            // The panel is what the window shows of the game: a summary and the view an agent would get. It is built
            // off the console thread, so it appears a moment after the app opens.
            waitUntil("the panel never described the game") { app.controller.panel.value != null }
            val panel = assertNotNull(app.controller.panel.value)
            assertTrue(panel.summary.isNotBlank(), "the panel has no summary")
            assertTrue(panel.view.isNotEmpty(), "the panel has no view: ${panel.view}")
        }
    }

    @Test
    fun whatThePlayerKnowsIsWrittenOnClose() {
        openApp().use { app ->
            waitUntil("the console never ran a frame") { app.emulator.status.value.frameCount > 0 }
            // The window calls this when it closes. Nothing was played here, and a session with no decision in it is not
            // worth writing: the point is that closing an untouched app writes nothing rather than an empty file that a
            // later launch would read as "what the player knows".
            app.controller.saveMemoryOnClose()
            val sessions = app.data.resolve("sessions")
            assertFalse(
                Files.exists(sessions) && Files.list(sessions).use { it.findAny().isPresent },
                "an untouched app wrote a session to $sessions",
            )
        }
    }

    @Test
    fun changingSettingsReachesTheConsoleAndTheConfig() {
        openApp().use { app ->
            val before = app.controller.settings.value.musicDuringPauses
            app.controller.updateSettings { it.copy(musicDuringPauses = !before) }
            val after = app.controller.settings.value.musicDuringPausesSettings
            assertEquals(!before, after.enabled, "the setting did not change")
            assertEquals(after, app.emulator.musicDuringPauses, "the console was not told about the new setting")
            // …and it survives a restart: the panel's changes are written to the config file, not kept in memory.
            assertEquals(
                after,
                reloaded(app.data).playerSettings.musicDuringPausesSettings,
                "the setting was not written to the config file",
            )
        }
    }

    @Test
    fun choosingAModelUpdatesTheConfigAndTheKeyForm() {
        openApp().use { app ->
            app.controller.selectBackend(DecisionBackend.LLM)
            assertEquals(DecisionBackend.LLM, app.controller.backend.value, "the backend did not change")
            assertEquals(DecisionBackend.LLM, reloaded(app.data).backend, "the backend was not saved")

            // A provider that needs a key, with no key: the panel must ask for one rather than fail at the first call.
            app.controller.selectLlmProvider(LlmProvider.OPENAI)
            assertEquals(LlmProvider.OPENAI, app.controller.llm.value.provider, "the provider did not change")
            assertEquals("OpenAI", app.controller.missingApiKey, "the key form was not asked for")
            assertEquals(LlmProvider.OPENAI, reloaded(app.data).llmProvider, "the provider was not saved")

            app.controller.setApiKey("test-key")
            assertNull(app.controller.missingApiKey, "the key form was still asked for after the key was given")
        }
    }
}
