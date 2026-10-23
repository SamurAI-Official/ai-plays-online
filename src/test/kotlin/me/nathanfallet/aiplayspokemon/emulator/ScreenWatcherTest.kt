package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.pokemonclient.console.Frame
import me.nathanfallet.aiplayspokemon.games.Milestone
import me.nathanfallet.aiplayspokemon.games.MilestoneKind
import me.nathanfallet.aiplayspokemon.games.WalkthroughChapter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenWatcherTest {

    private fun frame(gray: Int, width: Int = 32, height: Int = 24) =
        Frame(width, height, IntArray(width * height) { (gray shl 16) or (gray shl 8) or gray })

    /** A chapter whose landmark is [reference], stable over [minStableChecks] checks. */
    private fun chapter(id: String, reference: Frame, minStableChecks: Int = 3) = WalkthroughChapter(
        id = id,
        title = id,
        goal = id,
        milestone = Milestone(
            kind = MilestoneKind.FRAME_HASH,
            hashes = listOf(FrameHash.format(FrameHash.dHash(reference))),
            threshold = 2,
            minStableChecks = minStableChecks,
        ),
    )

    private val landmark = frame(gray = 120)

    @Test
    fun firesOnlyAfterTheLandmarkIsStable() {
        val watcher = ScreenWatcher()
        val chapter = chapter("a", landmark, minStableChecks = 3)
        assertFalse(watcher.check(landmark, chapter), "one matching check is not enough")
        assertFalse(watcher.check(landmark, chapter))
        assertTrue(watcher.check(landmark, chapter), "the third matching check reaches the chapter")
        // Once reached, the same screen does not fire again.
        assertFalse(watcher.check(landmark, chapter))
    }

    @Test
    fun aDifferentScreenResetsTheCount() {
        val watcher = ScreenWatcher()
        val chapter = chapter("a", landmark, minStableChecks = 3)
        watcher.check(landmark, chapter)
        watcher.check(landmark, chapter)
        watcher.check(frame(gray = 10), chapter) // the screen changed: start over
        assertFalse(watcher.check(landmark, chapter))
        assertFalse(watcher.check(landmark, chapter))
        assertTrue(watcher.check(landmark, chapter))
    }

    @Test
    fun chaptersWithoutALandmarkNeverFire() {
        val watcher = ScreenWatcher()
        val chapter = WalkthroughChapter(id = "b", title = "b", goal = "b")
        assertFalse(watcher.check(landmark, chapter))
    }

    @Test
    fun aMissingFrameIsIgnored() {
        val watcher = ScreenWatcher()
        assertFalse(watcher.check(null, chapter("a", landmark)))
    }

    @Test
    fun visionMilestonesAreNotHandledHere() {
        val watcher = ScreenWatcher()
        val chapter = WalkthroughChapter(
            id = "c",
            title = "c",
            goal = "c",
            milestone = Milestone(kind = MilestoneKind.VISION, question = "is this the menu?"),
        )
        assertFalse(watcher.check(landmark, chapter), "the vision kind belongs to the screen agent")
    }

    @Test
    fun movingToAnotherChapterStartsCountingAgain() {
        val watcher = ScreenWatcher()
        val first = chapter("a", landmark, minStableChecks = 2)
        val second = chapter("b", landmark, minStableChecks = 2)
        assertTrue(watcher.check(landmark, first).not() && watcher.check(landmark, first))
        assertFalse(watcher.check(landmark, second), "a new chapter needs its own stable checks")
        assertTrue(watcher.check(landmark, second))
    }
}
