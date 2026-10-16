package com.nikosm.voiceassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four dialects, both directions — against **fabricated bodies**, because the whole point of
 * keeping the protocol pure is that the wire shapes can be tested without a server, a network or a
 * model. The bodies below are written to each vendor's documented shape rather than captured from a
 * live call: a captured body would pin what a server did once, and these pin what the code must
 * accept.
 *
 * What each test is really guarding is drift. Four dialects in one file is exactly the arrangement
 * where a change to one is applied to three, and the round trip is asserted per provider for that
 * reason — including the two asymmetries that are easy to get wrong: Ollama hands arguments over as
 * an **object** where OpenAI hands over a **string**, and Anthropic and Gemini take all results in
 * **one** user message where the other two take one message per call.
 */
class FsToolProtocolTest {

    private fun resultsFor(vararg calls: FsToolCall) =
        calls.map { FsToolResult(it.id, it.name, "result for ${it.name}", ok = true) }

    // ------------------------------------------------------------------ declarations

    @Test
    fun `the write tool is offered only when writes are allowed, and nothing is offered when off`() {
        assertEquals(listOf(TOOL_LIST, TOOL_READ), fsToolNames(FsToolMode.READ_ONLY))
        assertEquals(listOf(TOOL_LIST, TOOL_READ, TOOL_WRITE), fsToolNames(FsToolMode.READ_WRITE))
        assertTrue(fsToolNames(FsToolMode.OFF).isEmpty())

        assertNull("off means the request carries no tools field at all", fsToolsJsonFor(FsToolProvider.OLLAMA, FsToolMode.OFF))
        val readOnly = fsToolsJsonFor(FsToolProvider.OPENAI, FsToolMode.READ_ONLY)!!
        assertEquals(2, readOnly.length())
        assertFalse("a read-only persona must not even be told a write tool exists", readOnly.toString().contains(TOOL_WRITE))
    }

    @Test
    fun `each provider gets its own declaration shape`() {
        // Ollama and OpenAI share the `type:function` wrapper; Anthropic wants the schema at the top
        // level under `input_schema`; Gemini nests everything under `function_declarations`.
        for (provider in listOf(FsToolProvider.OLLAMA, FsToolProvider.OPENAI)) {
            val first = fsToolsJsonFor(provider, FsToolMode.READ_WRITE)!!.getJSONObject(0)
            assertEquals("function", first.getString("type"))
            assertNotNull("${provider}: the schema rides inside the function", first.getJSONObject("function").optJSONObject("parameters"))
        }

        val anthropic = fsToolsJsonFor(FsToolProvider.ANTHROPIC, FsToolMode.READ_WRITE)!!.getJSONObject(0)
        assertEquals(TOOL_LIST, anthropic.getString("name"))
        assertNotNull("Anthropic calls it input_schema", anthropic.optJSONObject("input_schema"))
        assertNull("and there is no function wrapper", anthropic.optJSONObject("function"))

        val gemini = fsToolsJsonFor(FsToolProvider.GEMINI, FsToolMode.READ_WRITE)!!.getJSONObject(0)
        val declarations = gemini.getJSONArray("function_declarations")
        assertEquals(3, declarations.length())
        assertEquals("object", declarations.getJSONObject(0).getJSONObject("parameters").getString("type"))
    }

    @Test
    fun `every declaration tells the model how a path is spelled, because the root never leaves the machine`() {
        // The absolute workspace root is deliberately not sent to any provider — a cloud model has no
        // business learning the user's local paths or username — so the description is the only place
        // the convention can be stated.
        for (provider in FsToolProvider.entries) {
            val json = fsToolsJsonFor(provider, FsToolMode.READ_WRITE)!!.toString()
            assertTrue("$provider must say where paths start", json.contains("relative to the workspace root"))
            assertFalse("$provider must never see an absolute home path", json.contains("/home/"))
        }
    }

    // ------------------------------------------------------------------ reading a call

    private val ollamaBody = JSONObject(
        """
        {"message":{"role":"assistant","content":"","tool_calls":[
          {"function":{"name":"read_file","arguments":{"path":"notes/todo.md"}}}]}}
        """.trimIndent(),
    )

    private val openAiBody = JSONObject(
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_abc","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"notes/todo.md\"}"}}]}}]}
        """.trimIndent(),
    )

    private val anthropicBody = JSONObject(
        """
        {"content":[{"type":"text","text":"Let me look."},
          {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"notes/todo.md"}}]}
        """.trimIndent(),
    )

    private val geminiBody = JSONObject(
        """
        {"candidates":[{"content":{"role":"model","parts":[
          {"functionCall":{"name":"read_file","args":{"path":"notes/todo.md"}}}]}}]}
        """.trimIndent(),
    )

    @Test
    fun `each provider's call is read from its own place in the body`() {
        val expectedArguments = "notes/todo.md"

        val ollama = fsToolCallsFrom(FsToolProvider.OLLAMA, ollamaBody)!!.single()
        assertEquals(TOOL_READ, ollama.name)
        assertTrue("Ollama sends arguments as an object, which becomes text here", ollama.arguments.contains(expectedArguments))

        val openAi = fsToolCallsFrom(FsToolProvider.OPENAI, openAiBody)!!.single()
        assertEquals(TOOL_READ, openAi.name)
        assertEquals("the server's own id is kept when it sends one", "call_abc", openAi.id)
        assertTrue("OpenAI sends arguments as a string, which stays a string", openAi.arguments.contains(expectedArguments))

        val anthropic = fsToolCallsFrom(FsToolProvider.ANTHROPIC, anthropicBody)!!.single()
        assertEquals(TOOL_READ, anthropic.name)
        assertEquals("toolu_1", anthropic.id)

        val gemini = fsToolCallsFrom(FsToolProvider.GEMINI, geminiBody)!!.single()
        assertEquals(TOOL_READ, gemini.name)
        assertTrue(gemini.arguments.contains(expectedArguments))
    }

    @Test
    fun `an ordinary reply is null, not an empty list`() {
        // The distinction the whole loop hangs on: null means "parse this as a normal answer".
        assertNull(fsToolCallsFrom(FsToolProvider.OLLAMA, JSONObject("""{"message":{"role":"assistant","content":"hello"}}""")))
        assertNull(fsToolCallsFrom(FsToolProvider.OPENAI, JSONObject("""{"choices":[{"message":{"content":"hello"}}]}""")))
        assertNull(fsToolCallsFrom(FsToolProvider.ANTHROPIC, JSONObject("""{"content":[{"type":"text","text":"hello"}]}""")))
        assertNull(fsToolCallsFrom(FsToolProvider.GEMINI, JSONObject("""{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}""")))
        assertNull("a body with nothing recognisable in it is not a call either", fsToolCallsFrom(FsToolProvider.OPENAI, JSONObject("{}")))
    }

    @Test
    fun `a call that names no function is dropped rather than executed`() {
        val nameless = JSONObject("""{"message":{"tool_calls":[{"function":{"arguments":{}}}]}}""")

        assertNull(fsToolCallsFrom(FsToolProvider.OLLAMA, nameless))
    }

    // ------------------------------------------------------------------ sending it back

    @Test
    fun `OpenAI gets its calls echoed and one result message per call`() {
        val calls = fsToolCallsFrom(FsToolProvider.OPENAI, openAiBody)!!
        val turn = fsToolTurnFor(FsToolProvider.OPENAI, openAiBody, calls, resultsFor(*calls.toTypedArray()))

        assertEquals(2, turn.messages.size)
        val assistant = turn.messages[0]
        assertEquals("assistant", assistant.getString("role"))
        assertTrue("content is explicitly null alongside tool_calls", assistant.isNull("content"))
        assertEquals("call_abc", assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))

        val result = turn.messages[1]
        assertEquals("tool", result.getString("role"))
        assertEquals("the id is what pairs a result with its call", "call_abc", result.getString("tool_call_id"))
        assertTrue(result.getString("content").isNotBlank())
    }

    @Test
    fun `Ollama replays its own message and names the tool on the result`() {
        val calls = fsToolCallsFrom(FsToolProvider.OLLAMA, ollamaBody)!!
        val turn = fsToolTurnFor(FsToolProvider.OLLAMA, ollamaBody, calls, resultsFor(*calls.toTypedArray()))

        assertEquals(2, turn.messages.size)
        assertEquals(
            "the received message, verbatim — it already carries the calls in the form this server accepts",
            ollamaBody.getJSONObject("message").toString(),
            turn.messages[0].toString(),
        )
        assertEquals("tool", turn.messages[1].getString("role"))
        assertEquals(TOOL_READ, turn.messages[1].getString("tool_name"))
    }

    @Test
    fun `Anthropic and Gemini carry every result in one user message`() {
        // The asymmetry against the two above: these APIs pair results positionally inside a single
        // message, so sending one message per call would be a different (and rejected) request.
        val twoCalls = listOf(
            FsToolCall("a", TOOL_READ, """{"path":"one.txt"}"""),
            FsToolCall("b", TOOL_LIST, """{"path":"."}"""),
        )
        val results = resultsFor(*twoCalls.toTypedArray())

        val anthropic = fsToolTurnFor(FsToolProvider.ANTHROPIC, anthropicBody, twoCalls, results)
        assertEquals(2, anthropic.messages.size)
        val anthropicBlocks = anthropic.messages[1].getJSONArray("content")
        assertEquals(2, anthropicBlocks.length())
        assertEquals("tool_result", anthropicBlocks.getJSONObject(0).getString("type"))
        assertEquals("a", anthropicBlocks.getJSONObject(0).getString("tool_use_id"))
        assertEquals("b", anthropicBlocks.getJSONObject(1).getString("tool_use_id"))

        val gemini = fsToolTurnFor(FsToolProvider.GEMINI, geminiBody, twoCalls, results)
        assertEquals(2, gemini.messages.size)
        assertEquals("model", gemini.messages[0].getString("role"))
        val parts = gemini.messages[1].getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals(TOOL_READ, parts.getJSONObject(0).getJSONObject("functionResponse").getString("name"))
        assertNotNull(parts.getJSONObject(1).getJSONObject("functionResponse").getJSONObject("response").optString("result"))
    }

    @Test
    fun `a call whose result went missing is answered rather than left dangling`() {
        // Every provider rejects a conversation where a call has no result, so a gap in the results
        // list must still produce a message that says so.
        val calls = listOf(FsToolCall("a", TOOL_READ, "{}"))

        val turn = fsToolTurnFor(FsToolProvider.OPENAI, openAiBody, calls, emptyList())

        val content = turn.messages[1].getString("content")
        assertTrue("the model is told the call produced nothing: $content", content.isNotBlank())
    }

    @Test
    fun `arguments that are not JSON do not throw while being replayed`() {
        // A model can emit `arguments` as plain text; Anthropic and Gemini need an object back, so
        // the fallback is an empty one rather than an exception inside the request builder.
        val calls = listOf(FsToolCall("a", TOOL_READ, "not json at all"))

        val anthropic = fsToolTurnFor(FsToolProvider.ANTHROPIC, anthropicBody, calls, emptyList())
        assertNotNull(anthropic.messages[0].getJSONArray("content").getJSONObject(0).getJSONObject("input"))

        val gemini = fsToolTurnFor(FsToolProvider.GEMINI, geminiBody, calls, emptyList())
        assertNotNull(gemini.messages[0].getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall").getJSONObject("args"))
    }

    @Test
    fun `the declared schema is valid JSON in every dialect`() {
        // Cheap, and it catches the class of mistake that only shows up as a provider 400.
        for (provider in FsToolProvider.entries) {
            val json = fsToolsJsonFor(provider, FsToolMode.READ_WRITE)!!
            val roundTripped = JSONArray(json.toString())
            assertEquals(json.length(), roundTripped.length())
        }
    }
}
