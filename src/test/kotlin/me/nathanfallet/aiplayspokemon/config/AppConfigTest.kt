package me.nathanfallet.aiplayspokemon.config

import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.libretrorollback.SnapshotStrategy
import me.nathanfallet.aiplayspokemon.emulator.NetplayRole
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun netplayIsOffWithoutARole() {
        assertNull(AppConfig(Files.createTempDirectory("ai-plays-online-config"), Properties(), null).netplay)
    }

    @Test
    fun netplayReadsTheRoleThePeerAndTheEngineSettings() {
        val config = AppConfig(
            dataDirectory = Files.createTempDirectory("ai-plays-online-config"),
            properties = Properties().apply {
                setProperty("netplay.role", "host")
                setProperty("netplay.peer", "192.168.1.20:7001")
                setProperty("netplay.mode", "rollback")
                setProperty("netplay.prediction", "4")
                setProperty("netplay.inputDelay", "3")
                setProperty("netplay.snapshots", "full")
            },
            romPath = null,
        )
        val netplay = assertNotNull(config.netplay)
        assertEquals(NetplayRole.HOST, netplay.role)
        assertEquals(7000, netplay.bindPort, "the host binds 7000 by default")
        assertEquals(0, netplay.localPlayer, "the host is player 1")
        assertEquals("192.168.1.20", netplay.peerHost)
        assertEquals(7001, netplay.peerPort)
        assertEquals(Mode.ROLLBACK, netplay.mode)
        assertEquals(4, netplay.maxPredictionFrames)
        assertEquals(3, netplay.inputDelay)
        assertEquals(SnapshotStrategy.FULL, netplay.snapshotStrategy)
    }

    @Test
    fun aJoinerBindsItsOwnPortAndPlaysPlayerTwo() {
        val config = AppConfig(
            dataDirectory = Files.createTempDirectory("ai-plays-online-config"),
            properties = Properties().apply {
                setProperty("netplay.role", "join")
                setProperty("netplay.peer", "10.0.0.5:7000")
            },
            romPath = null,
        )
        val netplay = assertNotNull(config.netplay)
        assertEquals(NetplayRole.JOIN, netplay.role)
        assertEquals(7001, netplay.bindPort)
        assertEquals(1, netplay.localPlayer)
        assertEquals(Mode.LOCKSTEP, netplay.mode, "lockstep is the safe default (no re-simulation)")
    }

    @Test
    fun aNetplaySessionWithoutAUsablePeerFailsLoudly() {
        // Asking for a session and not being able to build it must not silently play offline.
        val missing = AppConfig(
            dataDirectory = Files.createTempDirectory("ai-plays-online-config"),
            properties = Properties().apply { setProperty("netplay.role", "join") },
            romPath = null,
        )
        assertFailsWith<IllegalArgumentException> { missing.netplay }

        val malformed = AppConfig(
            dataDirectory = Files.createTempDirectory("ai-plays-online-config"),
            properties = Properties().apply {
                setProperty("netplay.role", "join")
                setProperty("netplay.peer", "no-port")
            },
            romPath = null,
        )
        assertFailsWith<IllegalArgumentException> { malformed.netplay }
    }
}
