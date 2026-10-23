package me.nathanfallet.aiplayspokemon.games

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GameCatalogTest {

    /** A minimal DS header: 12-byte title, 4-byte game code, then whatever the game declares. */
    private fun rom(title: String, code: String): ByteArray = ByteArray(0x200).also { bytes ->
        title.padEnd(12, '\u0000').forEachIndexed { index, char -> bytes[index] = char.code.toByte() }
        code.padEnd(4, '\u0000').forEachIndexed { index, char -> bytes[0x0C + index] = char.code.toByte() }
    }

    @Test
    fun readsTheHeaderOfARom() {
        val header = GameCatalog.header(rom("POKEMON HG", "IPKE"))
        assertEquals("POKEMON HG", header.title)
        assertEquals("IPKE", header.gameCode)
    }

    @Test
    fun everyBuiltInIsFoundByItsOwnCode() {
        for (profile in GameCatalog.builtIns) {
            val detected = GameCatalog.detect(rom(profile.romTitle, profile.gameCode))
            assertEquals(profile.id, detected.id, "${profile.title} was not detected by its code")
        }
    }

    @Test
    fun anUnknownRomFallsBackToTheGenericProfile() {
        val detected = GameCatalog.detect(rom("SOME GAME", "ZZZE"))
        assertEquals(GameCatalog.generic.id, detected.id)
        assertEquals(AgentKind.SCREEN, detected.agent)
    }

    @Test
    fun eachGameCarriesItsOwnPrompt() {
        val objectives = GameCatalog.builtIns.associate { it.id to it.objective }
        // Every game gets a real objective and conventions, and they are not all the same text.
        GameCatalog.builtIns.forEach { profile ->
            assertTrue(profile.objective.isNotBlank(), "${profile.id} has no objective")
            assertTrue(profile.conventions.isNotBlank(), "${profile.id} has no conventions")
        }
        assertEquals(GameCatalog.builtIns.size, objectives.values.toSet().size, "objectives are not per-game")
        assertTrue(objectives.getValue("mario-kart-ds").contains("race"), "Mario Kart should be about racing")
        assertTrue(objectives.getValue("final-fantasy-iii").contains("story"), "Final Fantasy should be about the story")
        assertTrue(
            GameCatalog.builtIns.first { it.id == "mario-kart-ds" }.conventions.contains("accelerates"),
            "Mario Kart's conventions should describe its controls",
        )
    }

    @Test
    fun pokemonGamesUseTheTypedReaderAndItsConventions() {
        val heartGold = GameCatalog.builtIns.first { it.id == "pokemon-heartgold" }
        assertEquals(AgentKind.TYPED, heartGold.agent)
        assertEquals(GameCatalog.POKEMON_CONVENTIONS, heartGold.conventions)
        assertNotEquals(GameCatalog.DS_CONVENTIONS, heartGold.conventions)
    }

    @Test
    fun scanFindsTheRomsOfThePickerAndIgnoresTheRest() {
        val directory = Files.createTempDirectory("ai-plays-online-roms")
        Files.write(directory.resolve("mario.nds"), rom("MARIOKARTDS", "AMCE"))
        Files.write(directory.resolve("notes.txt"), "not a rom".encodeToByteArray())
        Files.createDirectories(directory.resolve("subdir.nds"))

        val found = GameCatalog.scan(directory)
        assertEquals(1, found.size, "only .nds files are games")
        assertEquals("mario-kart-ds", found.single().profile.id)
        assertEquals(directory.resolve("mario.nds"), found.single().path)
    }

    @Test
    fun scanOfAMissingFolderIsEmptyNotAnError() {
        assertEquals(emptyList(), GameCatalog.scan(java.nio.file.Path.of("/nowhere/roms")))
    }

    @Test
    fun userProfilesOverrideTheBuiltIns() {
        val dataDirectory = Files.createTempDirectory("ai-plays-online-games")
        Files.writeString(
            dataDirectory.resolve("games.json"),
            """
            {"games": [{"id": "mario-kart-ds", "title": "Mario Kart DS (mine)", "gameCode": "AMCE",
              "agent": "SCREEN", "objective": "Win the 150cc cups", "conventions": "A accelerates."}]}
            """.trimIndent(),
        )
        val detected = GameCatalog.detect(rom("MARIOKARTDS", "AMCE"), dataDirectory)
        assertEquals("Mario Kart DS (mine)", detected.title)
        assertEquals("Win the 150cc cups", detected.objective)
        // The built-ins that were not overridden are still there.
        assertEquals("pokemon-heartgold", GameCatalog.detect(rom("POKEMON HG", "IPKE"), dataDirectory).id)
    }

    @Test
    fun exportDefaultsWritesAFileTheCatalogCanReadBack() {
        val dataDirectory = Files.createTempDirectory("ai-plays-online-games")
        val file = GameCatalog.exportDefaults(dataDirectory)
        assertTrue(Files.readString(file).contains("pokemon-heartgold"))
        assertEquals(GameCatalog.builtIns.size + 1, GameCatalog.userProfiles(dataDirectory).size)
    }
}
