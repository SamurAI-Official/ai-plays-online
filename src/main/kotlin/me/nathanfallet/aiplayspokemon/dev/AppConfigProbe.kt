package me.nathanfallet.aiplayspokemon.dev

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import java.io.File
import me.nathanfallet.aiplayspokemon.config.AppConfig
import me.nathanfallet.aiplayspokemon.emulator.toKotlinxPath
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val romPath = args.find { it.startsWith("--rom=") }?.removePrefix("--rom=")
        ?: args.find { it == "--rom" && args.size > 1 }?.let { args[args.indexOf(it) + 1] }
        ?: System.getenv("POKEMON_ROM")

    val config = AppConfig.load(arrayOf("--rom", romPath))
    val env = System.getenv()

    println("=== APP CONFIG PROBE ===")
    println("romPath      : ${config.romPath}")
    println("dataDirectory: ${config.dataDirectory}")
    println("backend      : ${config.backend}")
    println("llmProvider  : ${config.llmProvider}")
    println("llmModel     : ${config.llmModel(config.llmProvider)}")
    println("emulatorCore : ${config.emulatorCore}")
    println("startMuted   : ${config.startMuted}")
    println("startState   : ${config.startState}")
    println("--- env ---")
    println("POKEMON_ROM              : ${env["POKEMON_ROM"] ?: "(not set)"}")
    println("AI_PLAYS_POKEMON_DATA_DIR: ${env["AI_PLAYS_POKEMON_DATA_DIR"] ?: "(not set)"}")
    println("DECISION_BACKEND         : ${env["DECISION_BACKEND"] ?: "(not set)"}")
    println("LLM_PROVIDER             : ${env["LLM_PROVIDER"] ?: "(not set)"}")
    println("LLM_MODEL                : ${env["LLM_MODEL"] ?: "(not set)"}")
    println("LLM_THINKING             : ${env["LLM_THINKING"] ?: "(not set)"}")
    println("--- resolution ---")
    val romFile = config.romPath?.toUri()?.let { File(it) }
    val gameName = romFile?.takeIf { it.exists() }?.let {
        try {
            val bytes = it.readBytes()
            val g: PokemonGame? = PokemonGames.detect(bytes)
            g?.javaClass?.simpleName ?: "unknown"
        } catch (e: Exception) {
            e.message ?: "error"
        }
    } ?: "(rom not found)"
    println("PokemonGames.detect(rom) => $gameName")
    val coreSpec = config.romPath?.let { p ->
        try { LibretroCoreSpec.forRom(p.toKotlinxPath(), config.emulatorCore) }
        catch (e: Exception) { e::class.java.simpleName + ": " + (e.message ?: "") }
    } ?: "(none)"
    println("LibretroCoreSpec.forRom(rom, emulatorCore) => $coreSpec")
    println("--- game picker (what the app offers at startup) ---")
    println("romsDirectory: ${config.romsDirectory}")
    println("last played  : ${config.rememberedRomPath ?: "(none)"}")
    val games = me.nathanfallet.aiplayspokemon.games.GameCatalog.scan(config.romsDirectory, config.dataDirectory)
    if (games.isEmpty()) {
        println("(no ROM in the folder: the picker would say so and offer Browse…)")
    }
    games.forEach { game ->
        val profile = game.profile
        val walkthrough = me.nathanfallet.aiplayspokemon.games.WalkthroughCatalog.of(profile, config.dataDirectory)
        // A game with a reader is a typed game (what the app does when it opens the ROM, see Main.kt).
        val reader = me.nathanfallet.aiplayspokemon.games.ReaderCatalog.of(profile, config.dataDirectory)
        val typed = me.nathanfallet.aiplayspokemon.games.ReaderCatalog.apply(profile, config.dataDirectory)
        println(
            "- ${profile.title} [${profile.gameCode}] · ${typed.agent} · " +
                "walkthrough: ${walkthrough?.chapters?.size ?: 0} chapters · " +
                "reader: ${reader?.let { "${it.observations.size} values, ${it.progress.size} signals (${it.provenance})" } ?: "none"} · " +
                game.path.fileName,
        )
    }
    println("--- the app would start on the picker (no game loaded) ---")
    println("=== PROBE DONE (no UI started — headless OK) ===")
}