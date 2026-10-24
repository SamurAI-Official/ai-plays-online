package me.nathanfallet.aiplayspokemon.games

import dev.kotlinds.pokemonclient.RamMemory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderCatalogTest {

    private fun addressOf(offset: Int) = 0x02000000L + offset

    /** A memory holding [values] at their offsets (a fake game's RAM). */
    private fun memory(vararg values: Pair<Int, ByteArray>): RamMemory {
        val ram = ByteArray(0x400000)
        values.forEach { (offset, bytes) -> bytes.copyInto(ram, offset) }
        return RamMemory(ram)
    }

    @Test
    fun everyValueTypeIsReadTheWayTheGameWroteIt() {
        val ram = memory(
            0x100 to byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0x00),
            0x200 to byteArrayOf(0xFE.toByte()),
            0x300 to "Oasis\u0000\u0000".encodeToByteArray(),
        )
        assertEquals(0x34, Observation("wood", addressOf(0x100), ValueType.U8).read(ram))
        assertEquals(0x1234, Observation("wood", addressOf(0x100), ValueType.U16).read(ram))
        assertEquals(0x00FF1234, Observation("wood", addressOf(0x100), ValueType.U32).read(ram))
        assertEquals(-2, Observation("hp", addressOf(0x200), ValueType.S8).read(ram))
        assertEquals(1, Observation("paused", addressOf(0x200), ValueType.BOOL).read(ram))
        assertEquals("Oasis", Observation("map", addressOf(0x300), ValueType.ASCII, length = 8).format(ram))
    }

    @Test
    fun bitsAndNamedValuesAreDecoded() {
        val ram = memory(0x400 to byteArrayOf(0x00, 0x00, 0x00, 0x00).also { })
        // 0x00000028 = bits 3 and 5 set.
        val bits = Observation("mode", addressOf(0x400), ValueType.BITS, mask = 0xFF, shift = 0)
        assertEquals(0, bits.read(ram))
        val named = Observation("mode", addressOf(0x400), ValueType.U8, values = mapOf("0" to "menu"))
        assertEquals("menu (0)", named.format(ram))
    }

    @Test
    fun aProgressSignalSaysWhenTheStepIsReached() {
        val ram = memory(0x500 to byteArrayOf(1))
        assertTrue(ProgressSignal("in-mission", addressOf(0x500), ValueType.U8).reached(ram))
        assertTrue(!ProgressSignal("in-mission", addressOf(0x500), ValueType.U8, equals = 2).reached(ram))
    }

    @Test
    fun aReaderMakesAGameTypedAndIsReadFromTheUsersDirectory() {
        val dataDirectory = Files.createTempDirectory("readers")
        // A game with no reader anywhere (the one that ships has one, see the test below).
        val profile = GameCatalog.builtIns.first { it.id == "final-fantasy-iii" }
        assertNull(ReaderCatalog.of(profile, dataDirectory), "nothing shipped and nothing written yet")
        assertEquals(AgentKind.SCREEN, ReaderCatalog.apply(profile, dataDirectory).agent)

        val spec = ReaderSpec(
            id = profile.id,
            provenance = "calibrated",
            observations = listOf(Observation("mode", addressOf(0x1000), ValueType.U8, confidence = 0.8)),
            progress = listOf(ProgressSignal("in-mission", addressOf(0x2000), ValueType.U8)),
        )
        val file = ReaderCatalog.write(spec, dataDirectory)
        assertTrue(Files.exists(file))
        assertEquals(spec, ReaderCatalog.of(profile, dataDirectory))
        assertEquals(AgentKind.TYPED, ReaderCatalog.apply(profile, dataDirectory).agent)
    }

    @Test
    fun theShippedAgeOfEmpiresReaderIsReadAndMakesItTyped() {
        val profile = GameCatalog.builtIns.first { it.id == "age-of-empires-aok" }
        val reader = ReaderCatalog.of(profile, dataDirectory = null)
        assertNotNull(reader, "the calibrated reader ships with the app")
        assertEquals("calibrated", reader.provenance)
        assertTrue(reader.observations.any { it.name == "screen" })
        assertTrue(reader.progress.any { it.name == "in-mission" })
        assertEquals(AgentKind.TYPED, ReaderCatalog.apply(profile).agent, "a reader makes the game typed")
    }

    @Test
    fun aUserReaderReplacesTheShippedOne() {
        val dataDirectory = Files.createTempDirectory("readers")
        val profile = GameCatalog.builtIns.first { it.id == "age-of-empires-aok" }
        val mine = ReaderSpec(id = profile.id, provenance = "imported", observations = emptyList())
        ReaderCatalog.write(mine, dataDirectory)
        assertEquals(mine, ReaderCatalog.of(profile, dataDirectory), "the user's file wins")
    }

    @Test
    fun observationsAreShownAsThePanelAndThePromptSeeThem() {
        val ram = memory(0x600 to byteArrayOf(3, 0))
        val spec = ReaderSpec(
            id = "test",
            provenance = "hand-written",
            observations = listOf(
                Observation("mode", addressOf(0x600), ValueType.U8, values = mapOf("3" to "in mission")),
                Observation("gold", addressOf(0x601), ValueType.U16),
            ),
        )
        assertEquals(mapOf("mode" to "in mission (3)", "gold" to "0"), ReaderCatalog.observe(spec, ram))
    }

    @Test
    fun anUnreadableFileIsNotAReader() {
        val dataDirectory = Files.createTempDirectory("readers")
        Files.createDirectories(dataDirectory.resolve("readers"))
        Files.writeString(dataDirectory.resolve("readers").resolve("broken.json"), "{ not json")
        assertNull(ReaderCatalog.of("broken", dataDirectory))
        assertNotNull(ReaderCatalog.apply(GameCatalog.generic, dataDirectory), "still a profile")
    }
}
