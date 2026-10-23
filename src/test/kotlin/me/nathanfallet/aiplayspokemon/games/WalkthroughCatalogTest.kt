package me.nathanfallet.aiplayspokemon.games

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkthroughCatalogTest {

    @Test
    fun everyGameWithAWalkthroughShippedHasOneForItsProfile() {
        val withWalkthrough = GameCatalog.builtIns.filter { WalkthroughCatalog.of(it) != null }
        assertTrue(withWalkthrough.isNotEmpty(), "no walkthrough ships with the app")
        withWalkthrough.forEach { profile ->
            val walkthrough = WalkthroughCatalog.of(profile)
            assertEquals(profile.id, walkthrough?.id, "${profile.id}'s walkthrough has another id")
            assertTrue(walkthrough!!.chapters.isNotEmpty(), "${profile.id} has an empty walkthrough")
            walkthrough.chapters.forEach { chapter ->
                assertTrue(chapter.title.isNotBlank() && chapter.goal.isNotBlank(), "${chapter.id} is incomplete")
            }
        }
    }

    @Test
    fun heartGoldIsGeneratedFromTheStoryTableAndMapsItsSteps() {
        val walkthrough = assertNotNull(WalkthroughCatalog.of(GameCatalog.builtIns.first { it.id == "pokemon-heartgold" }))
        assertTrue(walkthrough.chapters.size >= 10, "expected a chapter per checkpoint")
        assertTrue(walkthrough.chapters.all { it.steps.isNotEmpty() }, "every chapter should cover story steps")
        // The step ids are the story table's, so typed progress can drive the chapter.
        assertNotNull(walkthrough.chapterOfStep("johto:badge_zephyr"))
        assertEquals("hgss:badge_zephyr", walkthrough.chapterOfStep("johto:badge_zephyr")?.id)
    }

    @Test
    fun screenOnlyGamesCarryHintsAndCanCarryLandmarks() {
        val marioKart = assertNotNull(WalkthroughCatalog.of(GameCatalog.builtIns.first { it.id == "mario-kart-ds" }))
        assertTrue(marioKart.chapters.any { it.hints.isNotEmpty() }, "Mario Kart should have hints")
        assertTrue(marioKart.chapters.any { it.milestone != null }, "Mario Kart should have a landmark to watch")
        assertTrue(marioKart.chapters.none { it.steps.isNotEmpty() }, "a screen-only game has no story steps")
    }

    @Test
    fun aUserWalkthroughReplacesTheShippedOne() {
        val data = Files.createTempDirectory("ai-plays-online-walkthroughs")
        val directory = Files.createDirectories(data.resolve("walkthroughs"))
        Files.writeString(
            directory.resolve("mario-kart-ds.json"),
            """{"id": "mario-kart-ds", "provenance": "imported",
                 "chapters": [{"id": "mine", "title": "Mine", "goal": "Do the thing"}]}""",
        )
        val walkthrough = assertNotNull(WalkthroughCatalog.of("mario-kart-ds", data))
        assertEquals("mine", walkthrough.chapters.single().id)
        assertEquals(Walkthrough.PROVENANCE_IMPORTED, walkthrough.provenance)
        assertTrue(WalkthroughCatalog.provenanceNote(walkthrough).contains("imported"))
    }

    @Test
    fun exportCopiesAShippedWalkthroughOutForEditing() {
        val data = Files.createTempDirectory("ai-plays-online-walkthroughs")
        val file = assertNotNull(WalkthroughCatalog.export("pokemon-heartgold", data))
        assertTrue(Files.readString(file).contains("hgss:badge_zephyr"))
        assertEquals(
            WalkthroughCatalog.shipped("pokemon-heartgold")?.chapters?.size,
            WalkthroughCatalog.user("pokemon-heartgold", data)?.chapters?.size,
        )
    }

    @Test
    fun anUnknownGameSimplyHasNoWalkthrough() {
        assertNull(WalkthroughCatalog.of("no-such-game"))
        assertNull(WalkthroughCatalog.of(GameCatalog.generic))
        assertNull(WalkthroughCatalog.export("no-such-game", Files.createTempDirectory("x")))
    }
}
