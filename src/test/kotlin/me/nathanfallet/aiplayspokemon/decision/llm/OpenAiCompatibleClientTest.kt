package me.nathanfallet.aiplayspokemon.decision.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [OpenAiCompatibleClient] against a stub endpoint: it asks the chat template for no hidden thinking (the
 * thinking-burns-the-whole-budget failure that sent empty answers), gives the answer enough tokens, and says
 * plainly when a response carried no content at all.
 */
class OpenAiCompatibleClientTest {

    /** A stub `/v1/chat/completions` answering [answer], remembering the last request body it got. */
    private class Stub(val answer: String) {
        lateinit var lastBody: String
        val server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val response = answer.toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val url = "http://localhost:${server.address.port}/v1"

        fun close() = server.stop(0)
    }

    private fun chat(stub: Stub, thinking: Boolean = false) = runBlocking {
        OpenAiCompatibleClient.create(stub.url, "key")
            .chat("some-model", listOf("system" to "Choose.", "user" to "State: {}"), thinking = thinking)
    }

    @Test
    fun asksTheModelForNoThinkingUnlessTheAppWantsIt() {
        val stub = Stub("""{"choices": [{"message": {"content": "{\"choice\": \"up\"}"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 10, "completion_tokens": 5}}""")
        try {
            chat(stub)
            assertTrue(""""reasoning_effort":"none"""" in stub.lastBody, "thinking not disabled in: ${stub.lastBody}")
            assertTrue(
                """"chat_template_kwargs":{"enable_thinking":false}""" in stub.lastBody,
                "template kwarg missing in: ${stub.lastBody}",
            )
            assertTrue(""""max_tokens":2048""" in stub.lastBody, "no answer headroom in: ${stub.lastBody}")
        } finally {
            stub.close()
        }
    }

    @Test
    fun keepsThinkingOnWhenTheAppAskedForIt() {
        val stub = Stub("""{"choices": [{"message": {"content": "{\"choice\": \"up\"}"}, "finish_reason": "stop"}]}""")
        try {
            chat(stub, thinking = true)
            assertFalse("reasoning_effort" in stub.lastBody, "thinking was overridden in: ${stub.lastBody}")
            assertFalse("chat_template_kwargs" in stub.lastBody, "thinking was overridden in: ${stub.lastBody}")
        } finally {
            stub.close()
        }
    }

    @Test
    fun saysWhenTheThinkingAteTheWholeAnswer() {
        val stub = Stub("""{"choices": [{"message": {"content": ""}, "finish_reason": "length"}]}""")
        try {
            val error = assertFailsWith<IllegalStateException> { chat(stub) }
            assertTrue("finish_reason=length" in error.message.orEmpty(), error.message)
            assertTrue("thinking" in error.message.orEmpty(), error.message)
        } finally {
            stub.close()
        }
    }

    @Test
    fun returnsTheTextAndWhatItUsed() {
        val stub = Stub("""{"choices": [{"message": {"content": "{\"choice\": \"up\"}"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 42, "completion_tokens": 7}}""")
        try {
            val result = runBlocking {
                OpenAiCompatibleClient.create(stub.url, "key").chat("some-model", listOf("user" to "State: {}"))
            }
            assertEquals("""{"choice": "up"}""", result.text)
            assertEquals(42, result.inputTokens)
            assertEquals(7, result.outputTokens)
        } finally {
            stub.close()
        }
    }
}