package me.nathanfallet.aiplayspokemon

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path

/**
 * A test that needs something only a machine with a ROM has (a core, a save state): the environment names it, and
 * without it the test is **skipped**, not passed.
 *
 * The difference matters: a test that returns early when its environment is missing reports green while testing
 * nothing, so a CI run (or a local `./gradlew test`) looks like it covered the netplay session, the narration or the
 * music-during-pauses paths when it did not — the trap this exists to close. A skip says what it wanted, so
 * `./gradlew test` shows the tests that did not run and why.
 *
 * **Run them one at a time** (`--tests '*TheTest*'`), not with the whole suite: a libretro core can only be started
 * once per process, so two of these in the same JVM interfere (the second one's game "never gets past its intro").
 * Their variables are their own on purpose — `POKEMON_ROM` is what the *app* opens a game with, so using it here
 * would break `AppConfigTest` for whoever exports it to run these.
 */
fun gatedEnv(name: String, wanted: String): String {
    val value = System.getenv(name)
    assumeTrue(!value.isNullOrBlank()) { "$name is not set (this test needs $wanted)" }
    return value!!
}

/** [gatedEnv] as a path. */
fun gatedPath(name: String, wanted: String): Path = Path.of(gatedEnv(name, wanted))

/** [gatedEnv] as an optional path: a test that writes somewhere only when asked to. */
fun optionalPath(name: String): Path? = System.getenv(name)?.takeIf { it.isNotBlank() }?.let(Path::of)
