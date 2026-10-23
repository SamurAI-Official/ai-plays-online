package me.nathanfallet.aiplayspokemon.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [DecisionNarrator] against a fake voice: the queue never blocks the player, drops the oldest waiting line when
 * behind (the newest is always said), says the same standing error once, and [DecisionNarrator.close] means silent.
 */
class DecisionNarratorTest {

    /** A voice that records its lines, blocking the first one until [gate] opens. */
    private class FakeSpeaker : DecisionNarrator.Speaker {
        val spoken = CopyOnWriteArrayList<String>()
        val firstStarted = CountDownLatch(1)
        @Volatile
        var gate: CountDownLatch? = null

        override fun speak(text: String, voice: String, rate: Int) {
            spoken += text
            firstStarted.countDown()
            gate?.await(5, TimeUnit.SECONDS)
        }

        override fun cut() = Unit
    }

    private fun narrator(speaker: FakeSpeaker) = DecisionNarrator(
        voice = { "Whisper" },
        rate = { 185 },
        scope = CoroutineScope(Dispatchers.IO),
        speaker = speaker,
    )

    @Test
    fun dropsTheOldestWaitingLineWhenBehindInsteadOfBlockingThePlayer() {
        val speaker = FakeSpeaker()
        val narrator = narrator(speaker)
        try {
            speaker.gate = CountDownLatch(1)
            narrator.narrateMilestone("First") // starts speaking and blocks on the gate
            assertTrue(speaker.firstStarted.await(2, TimeUnit.SECONDS), "the first line never started")
            narrator.narrateMilestone("Second")
            narrator.narrateMilestone("Third")
            narrator.narrateMilestone("Fourth") // queue full (2): "Second" dropped, the newest kept
            speaker.gate!!.countDown()
            await { speaker.spoken.size == 3 }
            assertEquals(
                listOf("Milestone: First", "Milestone: Third", "Milestone: Fourth"),
                speaker.spoken,
            )
        } finally {
            speaker.gate?.countDown()
            narrator.close()
        }
    }

    @Test
    fun saysTheSameStandingErrorOnceUntilAnotherLineComes() {
        val speaker = FakeSpeaker()
        val narrator = narrator(speaker)
        try {
            narrator.narrateError("Read timed out")
            narrator.narrateError("Read timed out") // the standing error: not said twice
            await { speaker.spoken.size == 1 } // spoken on the narrator's own coroutine: wait for it
            narrator.narrateMilestone("Left the house") // a decision's story moves on: the error is history
            await { speaker.spoken.size == 2 }
            narrator.narrateError("Read timed out") // and comes back only with a new occurrence
            await { speaker.spoken.size == 3 }
            assertEquals(
                listOf("Warning: Read timed out", "Milestone: Left the house", "Warning: Read timed out"),
                speaker.spoken,
            )
        } finally {
            narrator.close()
        }
    }

    @Test
    fun aLineThatCannotBeSpokenIsReportedInsteadOfSwallowed() {
        val reported = CopyOnWriteArrayList<String>()
        val narrator = DecisionNarrator(
            voice = { "Nope" },
            rate = { 185 },
            scope = CoroutineScope(Dispatchers.IO),
            speaker = object : DecisionNarrator.Speaker {
                override fun speak(text: String, voice: String, rate: Int) =
                    throw IllegalStateException("say exited with 1 for voice \"Nope\"")

                override fun cut() = Unit
            },
            report = { line -> reported += line },
        )
        try {
            narrator.narrateMilestone("Invisible")
            await { reported.isNotEmpty() }
            assertTrue(reported.first().startsWith("narration failed:"), reported.first())
            assertTrue("Nope" in reported.first(), reported.first())
        } finally {
            narrator.close()
        }
    }

    @Test
    fun closedMeansSilent() {
        val speaker = FakeSpeaker()
        val narrator = narrator(speaker)
        narrator.close()
        narrator.narrateMilestone("Too late")
        narrator.narrateError("Too late either")
        Thread.sleep(50) // anything already queued would have been spoken by now
        assertEquals(emptyList(), speaker.spoken)
    }

    /** Waits (briefly) for [condition]: lines are spoken on the narrator's own coroutine. */
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 2_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < until, "condition not met within 2 s")
            Thread.sleep(10)
        }
    }
}