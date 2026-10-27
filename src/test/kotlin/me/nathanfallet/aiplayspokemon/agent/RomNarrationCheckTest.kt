package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import me.nathanfallet.aiplayspokemon.gatedPath
import me.nathanfallet.aiplayspokemon.optionalPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole way a decision becomes speech, on the real game: the emulator boots the ROM, the game's own RAM is
 * read into a real state, a decision is built on it (what the player records), and the macOS voice really says
 * it — the audio it produced is written to a file, so the run can be checked afterwards.
 *
 * Needs a ROM and cores, so it only runs when `NARRATION_ROM` (a HeartGold ROM) and `NARRATION_DATA` (a data
 * directory with `cores/`) are set; `NARRATION_OUT` receives the audio files.
 */
class RomNarrationCheckTest {

    @Test
    fun aDecisionOnTheRealGameIsSaidByTheVoice() {
        val rom = gatedPath("NARRATION_ROM", "a HeartGold ROM to build a real decision on")
        val data = gatedPath("NARRATION_DATA", "a data directory with cores/")
        val out = optionalPath("NARRATION_OUT")?.also { Files.createDirectories(it) }
        val spec = LibretroCoreSpec.forRom(rom.toKotlinxPath(), System.getenv("EMULATOR_CORE"))
        val game = PokemonGames.detect(rom.readBytes())
        checkNotNull(game) { "not a Pokémon ROM" }

        val host = ConsoleHost(spec, rom, data)
        try {
            host.resume()
            // The game starts on its title screen: the intro has to be pressed through (A) until the player is
            // really in the game, the way the app's own agent does it (through the host's lease).
            var state: dev.kotlinds.pokemonclient.state.GameState? = null
            val until = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < until) {
                val current = runBlocking { host.observe { memory -> game.state(memory) } }
                println("screen ${current.screen::class.simpleName}, at ${current.field?.mapName}")
                if (current.field != null) {
                    state = current
                    break
                }
                // The title screen and the messages that follow: A moves the game on.
                runBlocking {
                    host.lease("intro", game.inputProbe) {
                        tap(dev.kotlinds.pokemonclient.console.Button.A)
                        step(30)
                    }
                }
                Thread.sleep(500)
            }
            checkNotNull(state) { "the game never got past its intro" }
            println("game: ${game.name}, at ${state.field?.mapName}, screen ${state.screen::class.simpleName}")

            val decision = Decision(
                number = 1,
                summary = dev.kotlinds.pokemonclient.view.StateView.summary(state),
                actions = listOf("step(north,1)"),
                confidence = 0.75,
                probabilities = listOf("step(north,1)" to 0.75),
                latencyMillis = 42,
                model = "rom-check",
                thought = "I am in ${state.field?.mapName ?: "the field"}, so I step north and look around.",
            )

            val directory = Files.createTempDirectory("rom-narration")
            val file = directory.resolve("decision.aiff")
            val spoken = mutableListOf<String>()
            val narrator = DecisionNarrator(
                voice = { "Whisper" },
                rate = { 185 },
                scope = CoroutineScope(Dispatchers.IO),
                speaker = DecisionNarrator.MacSay { file },
                onSpoken = { line, _ -> spoken += line },
            )
            try {
                narrator.narrateDecision(decision)
                val until = System.currentTimeMillis() + 20_000
                while ((spoken.isEmpty() || !Files.exists(file)) && System.currentTimeMillis() < until) Thread.sleep(20)
                assertEquals(1, spoken.size, "the decision on the real game was not said")
                val line = spoken.first()
                assertTrue("Decision 1." in line, line)
                assertTrue("75 percent confidence" in line, line)
                assertTrue("step north 1" in line, line)
                assertTrue(Files.size(file) > 0, "the audio file is empty")
                out?.let { Files.copy(file, it.resolve("rom-decision.aiff"), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                println("said: $line (${Files.size(file)} bytes)")
            } finally {
                narrator.close()
            }
        } finally {
            host.close()
        }
    }
}
