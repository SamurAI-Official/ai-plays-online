package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import me.nathanfallet.aiplayspokemon.decision.ChoiceResult
import me.nathanfallet.aiplayspokemon.decision.DecisionModel
import me.nathanfallet.aiplayspokemon.emulator.ConsoleHost
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import me.nathanfallet.aiplayspokemon.games.GameCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderCatalog
import me.nathanfallet.aiplayspokemon.games.ReaderSpec
import me.nathanfallet.aiplayspokemon.games.WalkthroughState
import me.nathanfallet.aiplayspokemon.gatedPath
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The map a **screen-only** game builds, on the game itself.
 *
 * A ROM whose reader reads nothing (Phantasy Star 0, Final Fantasy, anything with no calibrated reader yet) has no
 * `screen` value, so the *picture* names its screens — and that is what makes the memory learn, instead of telling the
 * model it had already tried an action here when it had tried it on another screen. That lie is the loop reported on
 * Phantasy Star 0: the agent opening the same menu forever, because the prompt said every action here did nothing.
 *
 * Gated like the other ROM tests, and **run it alone** (a libretro core can only be started once per process):
 *
 * ```
 * SCREEN_ONLY_ROM=/path/game.nds SCREEN_ONLY_DATA=<a data directory with cores/> \
 *   SCREEN_ONLY_STATE=<a savestate taken where the agent was stuck> \
 *   ./gradlew test --tests '*ScreenOnlyLearningTest*'
 * ```
 *
 * Take the state with `dev/SaveStateProbe` from wherever the agent loops, or press F1 in the app at that moment. The
 * model here presses A whatever it is told: the loop the fix has to make breakable, because the information the model
 * needs — where this action leads from *this* screen — is now in the prompt.
 */
class ScreenOnlyLearningTest {

    /** The model the report describes: it presses A, whatever it is told. */
    private class AlwaysPressA : DecisionModel {
        override val name = "always-press-a"
        override val generative = false

        override suspend fun choose(request: ChoiceRequest): ChoiceResult =
            ChoiceResult("press_a", mapOf("press_a" to 1.0), 1.0, name, inputTokens = 0)
    }

    @Test
    fun aScreenOnlyGameBuildsAMapOfItsScreens() {
        val rom = gatedPath("SCREEN_ONLY_ROM", "a ROM whose reader reads nothing (Phantasy Star 0, Final Fantasy…)")
        val data = gatedPath("SCREEN_ONLY_DATA", "a data directory with cores/")
        val state = gatedPath("SCREEN_ONLY_STATE", "a savestate taken where the agent was stuck")
        val slot = 7
        Files.createDirectories(data.resolve("states"))
        Files.copy(
            state,
            data.resolve("states").resolve("${rom.fileName}.state$slot"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val profile = ReaderCatalog.apply(GameCatalog.detect(GameCatalog.headerOf(rom), data), data)
        // The app's own path for a game with no reader: an empty one, so ReaderPlayer runs (see AppController).
        val reader = ReaderCatalog.of(profile, data) ?: ReaderSpec(id = profile.id, provenance = "screen")
        val emulator = ConsoleHost(
            LibretroCoreSpec.forRom(rom.toKotlinxPath(), System.getenv("EMULATOR_CORE")),
            rom,
            data,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val memory = ReaderMemory()
        val player = ReaderPlayer(
            emulator = emulator,
            reader = reader,
            profile = profile,
            model = AlwaysPressA(),
            scope = scope,
            settings = { PlayerSettings() },
            walkthrough = WalkthroughState(),
            memory = memory,
        )
        try {
            check(runBlocking { emulator.loadState(slot) }) { "could not load the state $state" }
            emulator.start()
            player.play()
            runBlocking {
                val until = System.currentTimeMillis() + DECISION_TIMEOUT_MILLIS
                while (player.state.value.decisions < DECISIONS &&
                    player.state.value.error == null &&
                    System.currentTimeMillis() < until
                ) {
                    delay(200)
                }
            }
            player.pause()
        } finally {
            scope.cancel()
            emulator.close()
        }

        assertNull(player.state.value.error, "the player stopped with an error")
        assertTrue(
            player.state.value.decisions >= 2,
            "not enough decisions ran (${player.state.value.decisions}) to build a map",
        )
        val learned = ReaderMemoryPayload.decode(memory.toSession(profile.id, "").payload)
        assertTrue(
            learned.screens.any { it.screen.startsWith(PICTURE_PREFIX) },
            "no screen was named by its picture — the reader reads nothing here, so nothing named them: ${learned.screens}",
        )
        assertTrue(
            learned.screens.size >= 2,
            "every screen of a screen-only game was one screen: ${learned.screens}",
        )
        assertTrue(
            learned.outcomes.any { it.destinations.isNotEmpty() },
            "no action was recorded as leading anywhere, so the map stayed empty: ${learned.outcomes}",
        )
    }

    private companion object {
        /** Enough decisions to move between screens a few times, few enough to stay a short test. */
        const val DECISIONS = 8

        /** A run that has not reached [DECISIONS] by then is stuck for another reason: fail rather than hang. */
        const val DECISION_TIMEOUT_MILLIS = 120_000L

        /**
         * How [ScreenState.identity] names a screen. The first decision of a run has none: the console has not produced
         * a frame yet, so the memory records the one screen nothing could name — and everything after it is named.
         */
        const val PICTURE_PREFIX = "picture:"
    }
}
