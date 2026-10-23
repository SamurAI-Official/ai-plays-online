package me.nathanfallet.aiplayspokemon.agent

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VisionVerifierTest {

    private val openField = FieldState(
        1, MapName(1, map = "Town"), 1, 1, height = 0, facing = Direction.SOUTH,
        movement = MovementMode.WALK, moving = false,
    )

    /** The screens the gate reads: walking, a message box open over it, a menu keeping the D-pad for its cursor. */
    private val overworld = Screen.Overworld(awaiting = Awaiting.INPUT)
    private val dialogue = Screen.Dialogue(TextSource.FIELD, null, "Hello there!", Awaiting.INPUT)
    private val menu = Screen.ListMenu(MenuKind.OTHER, entries = emptyList(), cursor = Cursor.Hidden, topology = Topology.vertical(0))

    private fun press(button: Button): GameAction = GameAction.Press(button)

    private fun roomWithWallAbove(): Area {
        val floor: TileInfo? = TileInfo(blocked = false, kind = TileKind.Floor)
        val wall: TileInfo? = TileInfo(blocked = true, kind = TileKind.Wall)
        return Area(1, "room", 0, 0, 3, 3, arrayOf(floor, wall, floor, floor, floor, floor, floor, floor, floor))
    }

    private fun roomAllFloor(): Area {
        val floor: TileInfo? = TileInfo(blocked = false, kind = TileKind.Floor)
        return Area(1, "room", 0, 0, 3, 3, arrayOf(floor, floor, floor, floor, floor, floor, floor, floor, floor))
    }

    @Test
    fun wallStepIsBlocked() {
        val verdict = VisionVerifier.check(press(Button.UP), openField, roomWithWallAbove(), overworld)
        assertIs<VisionVerifier.Verdict.Blocked>(verdict)
        assertEquals(VisionVerifier.Point(1, 0), verdict.destination)
        assertTrue("wall" in verdict.reason, verdict.reason)
    }

    @Test
    fun floorStepIsAllowed() {
        val verdict = VisionVerifier.check(press(Button.UP), openField, roomAllFloor(), overworld)
        assertIs<VisionVerifier.Verdict.Allowed>(verdict)
    }

    @Test
    fun noMapIsFailOpen() {
        val verdict = VisionVerifier.check(press(Button.UP), openField, area = null, screen = overworld)
        assertIs<VisionVerifier.Verdict.Allowed>(verdict)
    }

    @Test
    fun menuPressIsAllowed() {
        val verdict = VisionVerifier.check(press(Button.A), openField, roomWithWallAbove(), menu)
        assertIs<VisionVerifier.Verdict.Allowed>(verdict)
    }

    @Test
    fun gotoBlockedTileIsBlocked() {
        val verdict = VisionVerifier.check(GameAction.GoTo(x = 1, y = 0, target = null), openField, roomWithWallAbove(), overworld)
        assertIs<VisionVerifier.Verdict.Blocked>(verdict)
        assertEquals(VisionVerifier.Point(1, 0), verdict.destination)
    }

    @Test
    fun personOnTileIsBlocked() {
        val withPerson = openField.copy(
            objects = listOf(
                FieldObject(
                    id = "nurse", label = "Nurse", kind = FieldObjectKind.PERSON,
                    x = 1, y = 0, facing = Direction.SOUTH, role = PersonRole.NURSE,
                ),
            ),
        )
        val verdict = VisionVerifier.check(press(Button.UP), withPerson, roomAllFloor(), overworld)
        assertIs<VisionVerifier.Verdict.Blocked>(verdict)
        assertTrue("Nurse" in verdict.reason, verdict.reason)
    }

    @Test
    fun blockedActionDescriptionNamesActionDestinationAndReason() {
        val blocked = VisionVerifier.BlockedAction("press(up)", VisionVerifier.Point(1, 0), "blocked by wall (Wall)")
        assertEquals("“press(up)” blocked by wall (Wall) at (1, 0)", blocked.describe())
    }

    @Test
    fun aStepWithAMessageBoxOpenIsRefusedAsTheBoxNotAsAWall() {
        // The wall is really there, but it is not why the press can't happen: the box is. Refused, not Blocked,
        // so the heatmap never learns a tile from a press that never walked.
        val verdict = VisionVerifier.check(press(Button.UP), openField, roomWithWallAbove(), dialogue)
        assertIs<VisionVerifier.Verdict.Refused>(verdict)
        assertTrue("message box" in verdict.reason, verdict.reason)
        assertTrue("advance it with A" in verdict.reason, verdict.reason)
    }

    @Test
    fun gotoWithAMessageBoxOpenIsRefused() {
        val verdict = VisionVerifier.check(GameAction.GoTo(x = 1, y = 0, target = null), openField, roomAllFloor(), dialogue)
        assertIs<VisionVerifier.Verdict.Refused>(verdict)
    }

    @Test
    fun advancingDialogueWhileAMessageBoxIsOpenIsAllowed() {
        val verdict = VisionVerifier.check(press(Button.A), openField, roomWithWallAbove(), dialogue)
        assertIs<VisionVerifier.Verdict.Allowed>(verdict)
    }

    @Test
    fun aMenuPressIsNeverJudgedAgainstTheOverworldGrid() {
        // In a menu (or battle) the D-pad moves the cursor, not the player: the wall over the player's tile on the
        // map behind the menu says nothing about this press.
        val verdict = VisionVerifier.check(press(Button.UP), openField, roomWithWallAbove(), menu)
        assertIs<VisionVerifier.Verdict.Allowed>(verdict)
    }

    @Test
    fun refusedActionDescriptionNamesActionAndReasonWithoutATile() {
        val refused = VisionVerifier.BlockedAction("press(up)", null, "a message box is open: advance it with A")
        assertEquals("“press(up)” a message box is open: advance it with A", refused.describe())
    }
}
