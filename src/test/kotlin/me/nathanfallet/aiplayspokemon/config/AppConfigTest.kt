package me.nathanfallet.aiplayspokemon.config

import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppConfigTest {

    private fun romFile(): java.nio.file.Path {
        val file = Files.createTempFile("ai-plays-online", ".nds")
        Files.write(file, ByteArray(0x200))
        return file
    }

    @Test
    fun theAppNeverStartsAGameByItself() {
        // No argument and no ROM environment variable: the app must open on the game picker, whatever the config
        // file remembers from the last session (that is what the picker offers, not what it loads).
        assertNull(AppConfig.load(emptyArray()).romPath, "a remembered ROM must not be loaded at startup")
    }

    @Test
    fun anExplicitRomIsTheOnlyOneLoaded() {
        val rom = romFile()
        assertEquals(rom, AppConfig.load(arrayOf(rom.toString())).romPath)
    }

    @Test
    fun theRememberedRomIsOfferedSeparately() {
        val rom = romFile()
        val config = AppConfig(
            dataDirectory = Files.createTempDirectory("ai-plays-online-config"),
            properties = Properties().apply { setProperty("rom", rom.toString()) },
            romPath = null,
        )
        assertNull(config.romPath, "the remembered ROM is not the one to load")
        assertNotNull(config.rememberedRomPath, "the picker should offer the ROM played last time")
        assertEquals(rom, config.rememberedRomPath)
    }

    @Test
    fun aRememberedRomThatMovedAwayIsForgotten() {
        val config = AppConfig(
            dataDirectory = Files.createTempDirectory("ai-plays-online-config"),
            properties = Properties().apply { setProperty("rom", "/nowhere/game.nds") },
            romPath = null,
        )
        assertNull(config.rememberedRomPath)
    }

    @Test
    fun theRomsDirectoryDefaultsToTheDataDirectory() {
        val data = Files.createTempDirectory("ai-plays-online-config")
        val config = AppConfig(data, Properties(), null)
        assertEquals(data.resolve("roms"), config.romsDirectory)
    }
}
