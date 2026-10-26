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

    /** A list of values (a party, a menu, a bag): one entry per [Observation.length], from [Observation.index]. */
    @Test
    fun arraysAreReadAsAList() {
        val ram = memory(0x700 to byteArrayOf(4, 5, 6, 0))
        val party = Observation("party", addressOf(0x700), ValueType.U8_ARRAY, length = 3)
        assertEquals(listOf(4L, 5L, 6L), party.list(ram))
        assertEquals("4, 5, 6", party.format(ram))

        // From an index, and as halfwords.
        val fromSecond = Observation("party", addressOf(0x700), ValueType.U8_ARRAY, length = 2, index = 1)
        assertEquals("5, 6", fromSecond.format(ram))
        assertEquals("1284", Observation("pair", addressOf(0x700), ValueType.U16_ARRAY, length = 1).format(ram))
    }

    /** Two addresses read as one value: a position, a cursor. */
    @Test
    fun pairsAndExpressionsReadTwoAddresses() {
        val ram = memory(
            0x710 to byteArrayOf(0x10, 0x00, 0x20, 0x00), // x = 16 (s16), y = 32 (s16)
            0x720 to byteArrayOf(0x34, 0x12, 0x02, 0x00), // low = 0x1234, high = 2
        )
        val position = Observation("position", addressOf(0x710), ValueType.PAIR, address2 = addressOf(0x712), type2 = ValueType.S16)
        assertEquals("(16, 32)", position.format(ram))

        // A 16-bit value composed of a byte and another byte × 256.
        val composed = Observation(
            "gold", addressOf(0x720), ValueType.EXPR,
            address2 = addressOf(0x722), type1 = ValueType.U16, type2 = ValueType.U8, op = ValueOp.ADD, factor = 256.0,
        )
        assertEquals((0x1234 + 2 * 256).toString(), composed.format(ram))
        assertEquals((0x1234 + 512).toLong(), composed.read(ram))
    }

    /** A pointer is only followed when it lands in main RAM. */
    @Test
    fun pointersAreFollowedOnlyInsideMainRam() {
        val inside = memory(
            0x730 to byteArrayOf(0x40, 0x07, 0x00, 0x02), // 0x02000740
            0x740 to byteArrayOf(0x2A),
        )
        val pointer = Observation("held", addressOf(0x730), ValueType.POINTER, type2 = ValueType.U8)
        assertEquals("42", pointer.format(inside))

        val outside = memory(0x730 to byteArrayOf(0x00, 0x00, 0x00, 0x00)) // null: not in main RAM
        assertEquals("out of range", Observation("held", addressOf(0x730), ValueType.POINTER).format(outside))
    }

    /** A value that is only meaningful while another one holds: a menu cursor on the field is not a value. */
    @Test
    fun aGuardedValueSaysWhenItDoesNotApply() {
        val field = memory(0x750 to byteArrayOf(0x00, 0x03))
        val cursor = Observation("cursor", addressOf(0x751), ValueType.U8, guardAddress = addressOf(0x750), guardEquals = 1)
        assertEquals("not now", cursor.format(field))
        assertTrue(!cursor.applies(field))

        // The guard holds (the menu is up): the value is read.
        val menu = memory(0x750 to byteArrayOf(0x01, 0x03))
        assertEquals("3", cursor.format(menu))
        assertTrue(cursor.applies(menu))
    }

    /** Named bits: a byte of flags is worth more than a number when the names are known. */
    @Test
    fun namedBitsAreShownByTheirNames() {
        val ram = memory(0x760 to byteArrayOf(0b0000_1001, 0x00, 0x00, 0x00))
        val flags = Observation(
            "status", addressOf(0x760), ValueType.BITS, mask = 0xFF, shift = 0,
            bitNames = mapOf("0" to "paused", "3" to "in battle", "5" to "poisoned"),
        )
        assertEquals("paused, in battle", flags.format(ram))
        // Nothing set, and no names: the number is what is left.
        assertEquals("0", Observation("status", addressOf(0x761), ValueType.BITS, mask = 0xFF).format(ram))
    }

    /**
     * The learner's evidence sits next to the readers it produces (`<id>.learning.json`): it must never be read as a
     * reader, or a game would become "typed" from an unfinished calibration.
     */
    @Test
    fun theLearnersEvidenceIsNotAReader() {
        val dataDirectory = Files.createTempDirectory("readers")
        Files.createDirectories(dataDirectory.resolve("readers"))
        Files.writeString(
            dataDirectory.resolve("readers").resolve("mario-kart-ds.learning.json"),
            """{"id":"mario-kart-ds","samples":12,"screens":[],"found":[]}""",
        )
        assertNull(ReaderCatalog.of("mario-kart-ds", dataDirectory), "evidence is not a reader")
        assertEquals(
            AgentKind.SCREEN,
            ReaderCatalog.apply(GameCatalog.builtIns.first { it.id == "mario-kart-ds" }, dataDirectory).agent,
        )
    }
}
