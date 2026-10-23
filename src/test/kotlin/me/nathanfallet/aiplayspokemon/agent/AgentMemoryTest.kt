package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.state.MapName
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentMemoryTest {

    private val overworld = Screen.Overworld(awaiting = Awaiting.INPUT)

    private fun at(x: Int, y: Int, facing: Direction = Direction.SOUTH, screen: Screen = overworld, map: Int = 1) = GameState(
        frame = 0, screen = screen, player = null, party = emptyList(), bag = null, battle = null,
        field = FieldState(map, MapName(map, map = "Town $map"), x, y, height = 0, facing = facing, movement = MovementMode.WALK, moving = false),
    )

    private fun talking(text: String) = Screen.Dialogue(TextSource.FIELD, null, text, Awaiting.INPUT)

    private fun AgentMemory.actions() = describe(false, null)["recent_actions"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `describes what each action changed`() {
        val memory = AgentMemory()
        memory.record("press(up)", at(5, 5), at(5, 4))
        memory.record("press(left)", at(5, 4), at(5, 4, facing = Direction.WEST))
        memory.record("press(left)", at(5, 4, facing = Direction.WEST), at(5, 4, facing = Direction.WEST))
        memory.record("press(up)", at(5, 4), at(3, 9, map = 2))
        memory.record("press(a)", at(3, 9, map = 2), at(3, 9, screen = talking("Hello!"), map = 2))

        val changes = memory.actions().map { it["what_changed"]!!.jsonPrimitive.content }
        assertEquals(
            listOf(
                "you moved from (5, 5) to (5, 4)",
                "you turned to face west without moving",
                "you did not move",
                "you arrived in Town 2 at (3, 9)",
            ),
            changes.dropLast(1),
        )
        assertTrue(changes.last().startsWith("you did not move; screen changed from overworld to "), changes.last())
        assertTrue(changes.last().endsWith("; dialogue: none -> Hello!"), changes.last())
        assertEquals(listOf("Hello!"), describeDialogues(memory))
    }

    private fun describeDialogues(memory: AgentMemory) =
        memory.describe(false, null)["dialogues_read"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `counts repeats in a row and in the same situation`() {
        val memory = AgentMemory()
        repeat(4) { memory.record("press(a)", at(5, 5), at(5, 5)) }
        val action = memory.actions().single()
        assertEquals(4, action["times_in_a_row"]!!.jsonPrimitive.int)
        // The first press "discovers" the tile the player stands on; the next three bring nothing new.
        assertEquals(3, memory.decisionsWithoutProgress)
    }

    @Test
    fun `progress resets the stagnation counter`() {
        val memory = AgentMemory()
        memory.record("press(a)", at(5, 5), at(5, 5))
        memory.record("press(up)", at(5, 5), at(5, 4))
        assertEquals(0, memory.decisionsWithoutProgress)
    }

    @Test
    fun `keeps the model note and renders the explored map from the common map view`() {
        val memory = AgentMemory()
        // A 2x2 room of map 1: a wall and stairs (a warp) on the top row, floor below.
        val wall = TileInfo(blocked = true, kind = TileKind.Wall)
        val floor = TileInfo(blocked = false, kind = TileKind.Floor)
        val room = Area(1, "room", 0, 0, 2, 2, arrayOf(wall, floor, floor, floor), warps = listOf(Warp(zone = 1, id = 0, x = 1, y = 0, targetZone = 2, targetWarp = 0)))
        memory.observeMap(at(0, 1).field, room)
        memory.updateNote("Take the stairs")
        val description = memory.describe(true, at(1, 1, facing = Direction.NORTH).field)
        assertEquals("Take the stairs", description["your_note"]!!.jsonPrimitive.content)
        val explored = description["explored_map"]!!.jsonObject
        val rows = explored["rows"]!!.jsonArray.map { it.jsonPrimitive.content }
        // The player (facing north) and the tile walked on; the wall and the exit as the map view draws them.
        assertEquals(listOf("y  0 #E", "y  1 oA"), rows)
        val howToRead = explored["how_to_read"]!!.jsonPrimitive.content
        assertTrue("A you" in howToRead && "E exit" in howToRead, howToRead)
        assertTrue(memory.describe(false, null)["explored_map"] == null)
    }

    @Test
    fun `blocked tiles show in the explored map`() {
        val memory = AgentMemory()
        val floor = TileInfo(blocked = false, kind = TileKind.Floor)
        val room = Area(1, "room", 0, 0, 3, 3, Array(9) { floor })
        memory.observeMap(at(1, 1).field, room)
        memory.noteBlocked(1, VisionVerifier.Point(1, 0), "blocked by wall (Wall)")
        val explored = memory.describe(true, at(1, 1).field)["explored_map"]!!.jsonObject
        val rows = explored["rows"]!!.jsonArray.map { it.jsonPrimitive.content }
        // The player faces south, so MapView.player draws 'V' there ('A' only when facing north).
        assertEquals(listOf("y  0 .x.", "y  1 .V.", "y  2 ..."), rows)
        val blocked = explored["blocked_tiles"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("(1, 0): blocked by wall (Wall)"), blocked)
        val howToRead = explored["how_to_read"]!!.jsonPrimitive.content
        assertTrue("x blocked" in howToRead, howToRead)
    }

    @Test
    fun `a walked tile stays walked and blocked tiles stay on their own map`() {
        val memory = AgentMemory()
        val floor = TileInfo(blocked = false, kind = TileKind.Floor)
        val room = Area(1, "room", 0, 0, 3, 3, Array(9) { floor })
        memory.observeMap(at(1, 1).field, room)
        memory.observeMap(at(1, 0).field, room) // walked on (1, 0) before it got blocked
        memory.noteBlocked(1, VisionVerifier.Point(1, 0), "blocked by a person")
        memory.noteBlocked(2, VisionVerifier.Point(0, 0), "blocked by wall (Wall)") // another map
        val explored = memory.describe(true, at(1, 1).field)["explored_map"]!!.jsonObject
        val rows = explored["rows"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("y  0 .o.", "y  1 .V.", "y  2 ..."), rows)
        val blocked = explored["blocked_tiles"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("(1, 0): blocked by a person"), blocked)
    }
}
