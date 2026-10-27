package me.nathanfallet.aiplayspokemon.decision.llm

import kotlinx.serialization.json.JsonObject
import me.nathanfallet.aiplayspokemon.decision.ChoiceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmAnswerFormatTest {

    private val request = ChoiceRequest(
        state = JsonObject(emptyMap()),
        instructions = "Choose.",
        options = mapOf("a" to "A", "b" to "B", "up" to "Up", "exit_0" to "Stairs"),
        allowSequence = true,
        allowNote = true,
    )

    @Test
    fun `reads choice, reasoning, note and sequence`() {
        val answer = LlmAnswerFormat.parse(
            """Sure! ```json
            {"reasoning": "I see a door north.", "note": "Leave the house", "choice": "up", "then": ["up", "exit_0", "nope"]}
            ```""",
            request,
        )
        assertEquals("up", answer.choice)
        assertEquals("I see a door north.", answer.reasoning)
        assertEquals("Leave the house", answer.note)
        assertEquals(listOf("up", "exit_0"), answer.then.map { it.action })
        assertEquals(listOf(null, null), answer.then.map { it.expects }, "a plain option id carries no condition")
    }

    @Test
    fun `reads the state a step expects`() {
        val conditions = request.copy(allowConditions = true)
        val answer = LlmAnswerFormat.parse(
            """{"choice": "a", "then": [
                {"action": "up", "when": {"value": "screen", "is": "single player menu (18)"}},
                "b",
                {"action": "b", "when": {"is_not": "main menu (19)"}}
            ]}""",
            conditions,
        )
        assertEquals(listOf("up", "b", "b"), answer.then.map { it.action }, "both forms are accepted, in order")
        assertEquals("single player menu (18)", answer.then[0].expects?.isText)
        assertEquals("screen", answer.then[0].expects?.value, "the value defaults to `screen`")
        assertEquals(null, answer.then[1].expects)
        assertEquals("main menu (19)", answer.then[2].expects?.isNotText)
    }

    /** The typed chains have their own stop rules: a condition is only kept where the request allowed one. */
    @Test
    fun `drops conditions the request did not allow`() {
        val answer = LlmAnswerFormat.parse(
            """{"choice": "a", "then": [{"action": "up", "when": {"is": "single player menu (18)"}}]}""",
            request,
        )
        assertEquals(listOf("up"), answer.then.map { it.action })
        assertEquals(listOf(null), answer.then.map { it.expects })
    }

    @Test
    fun `drops steps and conditions it cannot read`() {
        val conditions = request.copy(allowConditions = true)
        val answer = LlmAnswerFormat.parse(
            """{"choice": "a", "then": [
                {"action": "walk", "when": {"is": "somewhere"}},
                {"when": {"is": "somewhere"}},
                "nope",
                {"action": "up", "when": {"value": "screen"}},
                {"action": "b", "when": "main menu"}
            ]}""",
            conditions,
        )
        // An action that is not an option, a step with no action, and an id nobody knows: all dropped, never guessed.
        // A `when` that constrains nothing (or is not an object) is not a condition, but the step itself stands.
        assertEquals(listOf("up", "b"), answer.then.map { it.action })
        assertEquals(listOf(null, null), answer.then.map { it.expects })
    }

    @Test
    fun `normalizes harmless variations of option ids`() {
        assertEquals("a", LlmAnswerFormat.parse("""{"reasoning": "", "choice": "A button"}""", request).choice)
        assertEquals("up", LlmAnswerFormat.parse("""{"reasoning": "", "choice": "D-pad Up"}""", request).choice)
    }

    /** Free text is never scanned for option names: "a door" must not become the A button. */
    @Test
    fun `rejects answers without a valid choice`() {
        assertFailsWith<LlmAnswerFormat.InvalidAnswer> { LlmAnswerFormat.parse("I will press a button to open a door.", request) }
        assertFailsWith<LlmAnswerFormat.InvalidAnswer> { LlmAnswerFormat.parse("""{"reasoning": "a door", "choice": "walk"}""", request) }
    }
}
